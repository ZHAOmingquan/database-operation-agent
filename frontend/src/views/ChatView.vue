<template>
  <a-layout class="chat-view">
    <a-layout-sider width="240" theme="light" class="pane session-pane">
      <SessionList :datasource-id="datasourceId" />
    </a-layout-sider>
    <a-layout-content class="pane chat-pane">
      <div class="chat-toolbar">
        <a-select v-model:value="datasourceId" style="width: 180px" size="small" placeholder="数据源"
                  :options="datasourceOptions" />
        <a-select v-model:value="modelId" style="width: 200px" size="small" placeholder="模型"
                  :options="modelOptions" />
        <a-badge :status="store.connected ? 'processing' : 'default'"
                 :text="store.connected ? '已连接' : '未连接'" />
      </div>
      <div class="chat-body">
        <ChatPanel :datasource-id="datasourceId" :model-id="modelId" />
      </div>
    </a-layout-content>
    <a-layout-sider width="42%" theme="light" class="pane console-pane">
      <SqlConsole />
      <ResultPanel />
    </a-layout-sider>
    <DatasourceFormModal v-model:open="dsModalOpen" @saved="onDatasourceSaved" />
  </a-layout>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '../stores/chat'
import { datasourceApi, modelApi } from '../api'
import SessionList from '../components/SessionList.vue'
import ChatPanel from '../components/ChatPanel.vue'
import SqlConsole from '../components/SqlConsole.vue'
import ResultPanel from '../components/ResultPanel.vue'
import DatasourceFormModal from '../components/DatasourceFormModal.vue'

const store = useChatStore()
const router = useRouter()
const datasourceId = ref(undefined)
const modelId = ref(undefined)
const datasourceOptions = ref([])
const modelOptions = ref([])

onMounted(async () => {
  await store.loadSessions()
  const [dsList, models] = await Promise.all([datasourceApi.list(), modelApi.list()])
  datasourceOptions.value = dsList.map((d) => ({ value: d.id, label: d.name }))
  modelOptions.value = models.map((m) => ({ value: m.id, label: m.name + (m.enabled ? '（已启用）' : '') }))
  if (dsList.length) datasourceId.value = dsList[0].id
  const enabled = models.find((m) => m.enabled)
  if (enabled) modelId.value = enabled.id
  if (store.sessions.length) await store.switchSession(store.sessions[0].id)
})

// 无数据源闭环：后端推送 no_datasource 时弹出新建数据源表单（已有数据源但未选择则提示）
const dsModalOpen = ref(false)
watch(() => store.noDatasource, (v) => {
  if (!v) return
  if (v.hasAnyDatasource) { message.warning('请先在顶部选择数据源'); store.clearNoDatasource(); return }
  dsModalOpen.value = true
})

// 删除拦截提示：DeleteGuard 拒绝删除类操作时弹框（对话与控制台共用），含前往系统配置入口
watch(() => store.deleteDenied, (v) => {
  if (!v) return
  Modal.confirm({
    title: '删除操作被拒绝',
    content: `${v.reason}（SQL：${v.sql}）`,
    okText: '前往系统配置',
    cancelText: '知道了',
    onOk: () => { router.push('/configs') }
  })
  store.clearDeleteDenied()
})

const onDatasourceSaved = async (ds) => {
  datasourceOptions.value.push({ value: ds.id, label: ds.name })
  datasourceId.value = ds.id
  await store.resendLast(ds.id)
}
</script>

<style scoped>
.chat-view { height: 100%; }
.pane { height: 100%; }
.session-pane { border-right: 1px solid #f0f0f0; }
.console-pane { border-left: 1px solid #f0f0f0; display: flex; flex-direction: column; }
.console-pane > :last-child { flex: 1; overflow: hidden; }
.chat-pane { display: flex; flex-direction: column; min-width: 0; }
.chat-toolbar {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 8px 12px; border-bottom: 1px solid #f0f0f0; background: #fff;
}
.chat-body { flex: 1; min-height: 0; }
</style>
