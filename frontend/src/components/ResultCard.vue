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
      <div class="view-bar">
        <a-radio-group v-model:value="viewMode" size="small" button-style="solid">
          <a-radio-button value="table">表格</a-radio-button>
          <a-radio-button value="chart">图表</a-radio-button>
        </a-radio-group>
        <a-select v-if="viewMode === 'chart'" v-model:value="chartType" size="small"
                  style="width: 92px" :options="chartTypeOptions" />
        <a-tag v-if="chartConfig" color="purple">AI 图表</a-tag>
      </div>
      <a-table v-if="viewMode === 'table'" :columns="columns" :data-source="rows" size="small" :pagination="false"
               :scroll="{ x: 'max-content', y: 160 }" row-key="__rowKey" />
      <ChartView v-else :columns="rawColumns" :rows="rawRows" :chart-type="chartType"
                 :title="chartConfig?.title || ''" :x-field="chartConfig?.xField || ''"
                 :y-field="chartConfig?.yField || ''" :height="240" />
    </template>
    <div v-if="aiComment" class="ai-comment">
      <a-tag color="geekblue">AI 解读</a-tag>
      <div class="markdown-body ai-comment-body" v-html="aiCommentHtml"></div>
    </div>
  </a-card>
</template>

<script setup>
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import ChartView from './ChartView.vue'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({ item: { type: Object, required: true } })

const isQuery = computed(() => props.item.resultType === 'query')
const statusText = computed(() => props.item.status === 'success' ? '成功' : '失败')
const statusColor = computed(() => props.item.status === 'success' ? 'green' : 'red')
// 与 MessageItem 一致：推理模型的 <think>…</think> 思维链不展示，并按 Markdown 渲染
const aiComment = computed(() => (props.item.aiComment || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim())
const aiCommentHtml = computed(() => renderMarkdown(aiComment.value))

const safeParse = (text, fallback) => { try { return text ? JSON.parse(text) : fallback } catch { return fallback } }

const rawColumns = computed(() => safeParse(props.item.columnsJson, []))
const rawRows = computed(() => safeParse(props.item.rowsJson, []))
const columns = computed(() => rawColumns.value.map((c) => ({ title: c, dataIndex: c, ellipsis: true })))
const rows = computed(() => rawRows.value.map((row, i) => {
  const obj = { __rowKey: i }
  rawColumns.value.forEach((c, j) => { obj[c] = row[j] })
  return obj
}))

// AI 通过 render_chart 产出图表配置时默认展示图表；否则默认表格、可手动切换
const chartConfig = computed(() => safeParse(props.item.chartConfig, null))
const viewMode = ref(props.item.chartConfig ? 'chart' : 'table')
const chartType = ref(chartConfig.value?.chartType || 'bar')
const chartTypeOptions = [
  { value: 'bar', label: '柱状' }, { value: 'line', label: '折线' }, { value: 'pie', label: '饼图' }
]

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
.view-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.ai-comment { margin-top: 8px; background: #f0f5ff; border-radius: 4px; padding: 6px 8px; font-size: 13px; }
.ai-comment-body { margin-top: 4px; font-size: 13px; }
</style>
