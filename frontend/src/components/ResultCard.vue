<template>
  <a-card size="small" class="result-card" :body-style="{ padding: '10px' }">
    <div class="head">
      <a-tag :color="statusColor">{{ statusText }}</a-tag>
      <span class="ds">{{ item.datasourceName }}</span>
      <span class="meta">
        {{ item.resultType === 'query' ? item.rowCount + ' 行' : '影响 ' + item.affectedRows + ' 行' }}
        · {{ item.elapsedMs }}ms · {{ item.source === 'console' ? '控制台' : 'AI' }}
      </span>
      <a class="copy" @click="copySql">复制SQL</a>
    </div>
    <pre class="sql mono">{{ item.sqlText }}</pre>
    <a-alert v-if="item.status === 'error'" type="error" :message="item.errorMessage" show-icon />
    <template v-else-if="isQuery">
      <a-table :columns="columns" :data-source="rows" size="small" :pagination="false"
               :scroll="{ x: 'max-content', y: 160 }" row-key="__rowKey" />
    </template>
    <div v-if="item.aiComment" class="ai-comment">
      <a-tag color="geekblue">AI 解读</a-tag>
      <span>{{ item.aiComment }}</span>
    </div>
  </a-card>
</template>

<script setup>
import { computed } from 'vue'
import { message } from 'ant-design-vue'

const props = defineProps({ item: { type: Object, required: true } })

const isQuery = computed(() => props.item.resultType === 'query')
const statusText = computed(() => props.item.status === 'success' ? '成功' : '失败')
const statusColor = computed(() => props.item.status === 'success' ? 'green' : 'red')

const columns = computed(() => {
  const cols = JSON.parse(props.item.columnsJson || '[]')
  return cols.map((c) => ({ title: c, dataIndex: c, ellipsis: true }))
})
const rows = computed(() => {
  const cols = JSON.parse(props.item.columnsJson || '[]')
  const data = JSON.parse(props.item.rowsJson || '[]')
  return data.map((row, i) => {
    const obj = { __rowKey: i }
    cols.forEach((c, j) => { obj[c] = row[j] })
    return obj
  })
})

const copySql = async () => {
  await navigator.clipboard.writeText(props.item.sqlText)
  message.success('SQL 已复制')
}
</script>

<style scoped>
.result-card { margin-bottom: 10px; }
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.ds { font-weight: 600; }
.meta { color: #999; font-size: 12px; }
.copy { margin-left: auto; font-size: 12px; }
.sql { background: #fafafa; border-radius: 4px; padding: 6px 8px; white-space: pre-wrap; font-size: 12px; max-height: 90px; overflow: auto; }
.ai-comment { margin-top: 8px; background: #f0f5ff; border-radius: 4px; padding: 6px 8px; font-size: 13px; }
</style>
