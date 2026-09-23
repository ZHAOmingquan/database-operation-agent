<template>
  <div class="console">
    <div class="bar">
      <a-select v-model:value="datasourceId" style="width: 180px" placeholder="选择数据源"
                :options="datasourceOptions" size="small" />
      <a-select v-model:value="limit" style="width: 100px" size="small"
                :options="[10, 50, 100, 500].map((n) => ({ value: n, label: '最多 ' + n + ' 行' }))" />
      <a-button type="primary" size="small" :loading="running" @click="execute">执行 (Ctrl+Enter)</a-button>
    </div>
    <a-textarea v-model:value="sql" class="mono" :rows="6" placeholder="输入 SQL，例如：select * from users"
                @keydown.ctrl.enter.prevent="execute" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { datasourceApi, sessionApi } from '../api'
import { useChatStore } from '../stores/chat'

const store = useChatStore()
const sql = ref('')
const limit = ref(10)
const running = ref(false)
const datasourceId = ref(undefined)
const datasourceOptions = ref([])

onMounted(async () => {
  const list = await datasourceApi.list()
  datasourceOptions.value = list.map((d) => ({ value: d.id, label: d.name }))
  if (!datasourceId.value && list.length) datasourceId.value = list[0].id
})

const execute = async () => {
  if (!sql.value.trim()) return
  if (!datasourceId.value) { message.warning('请选择数据源'); return }
  running.value = true
  try {
    const sessionId = await store.ensureSession(datasourceId.value)
    await sessionApi.consoleExecute(sessionId, { sql: sql.value, datasourceId: datasourceId.value, limit: limit.value })
  } catch { /* 失败提示：axios 拦截器 message.error + delete_denied 弹框，此处不重复处理 */ } finally { running.value = false }
}
</script>

<style scoped>
.console { padding: 10px; border-bottom: 1px solid #f0f0f0; }
.bar { display: flex; gap: 8px; margin-bottom: 8px; }
</style>
