<template>
  <a-layout style="height: 100%">
    <a-layout-header class="topbar">
      <a-space :size="12">
        <a-select v-model:value="currentSessionId" style="width: 200px" size="small" placeholder="选择/新建会话"
                  :options="sessionOptions" @change="onSessionChange" />
        <a-button size="small" @click="newSession">新建会话</a-button>
        <a-select v-model:value="datasourceId" style="width: 180px" size="small" placeholder="数据源"
                  :options="datasourceOptions" @change="onDatasourceChange" />
        <a-select v-model:value="modelId" style="width: 200px" size="small" placeholder="模型"
                  :options="modelOptions" />
        <a-badge :status="store.connected ? 'processing' : 'default'"
                 :text="store.connected ? '已连接' : '未连接'" />
      </a-space>
    </a-layout-header>
    <a-layout>
      <a-layout-sider width="38%" theme="light" class="left-pane">
        <ChatPanel :datasource-id="datasourceId" :model-id="modelId" />
      </a-layout-sider>
      <a-layout-content class="right-pane">
        <SqlConsole />
        <ResultPanel />
      </a-layout-content>
    </a-layout>
    <DatasourceFormModal v-model:open="dsModalOpen" @saved="onDatasourceSaved" />
  </a-layout>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '../stores/chat'
import { datasourceApi, modelApi, sessionApi } from '../api'
import ChatPanel from '../components/ChatPanel.vue'
import SqlConsole from '../components/SqlConsole.vue'
import ResultPanel from '../components/ResultPanel.vue'
import DatasourceFormModal from '../components/DatasourceFormModal.vue'

const store = useChatStore()
const router = useRouter()
const currentSessionId = ref(undefined)
const datasourceId = ref(undefined)
const modelId = ref(undefined)
const datasourceOptions = ref([])
const modelOptions = ref([])

const sessionOptions = computed(() => store.sessions.map((s) => ({ value: s.id, label: s.title })))

onMounted(async () => {
  await store.loadSessions()
  const [dsList, models] = await Promise.all([datasourceApi.list(), modelApi.list()])
  datasourceOptions.value = dsList.map((d) => ({ value: d.id, label: d.name }))
  modelOptions.value = models.map((m) => ({ value: m.id, label: m.name + (m.enabled ? '（已启用）' : '') }))
  if (dsList.length) datasourceId.value = dsList[0].id
  const enabled = models.find((m) => m.enabled)
  if (enabled) modelId.value = enabled.id
  if (store.sessions.length) {
    currentSessionId.value = store.sessions[0].id
    await store.switchSession(store.sessions[0].id)
  }
})

const onSessionChange = async (id) => { await store.switchSession(id) }
const newSession = async () => {
  const s = await sessionApi.create({ datasourceId: datasourceId.value })
  store.sessions.unshift(s)
  currentSessionId.value = s.id
  await store.switchSession(s.id)
}
const onDatasourceChange = () => {}

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
.topbar { background: #fff; border-bottom: 1px solid #f0f0f0; padding: 0 12px; height: 48px; line-height: 48px; }
.left-pane { border-right: 1px solid #f0f0f0; }
.right-pane { display: flex; flex-direction: column; height: 100%; }
.right-pane > :last-child { flex: 1; overflow: hidden; }
</style>
