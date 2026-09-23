<template>
  <div ref="el" class="chart-view" :style="{ height: height + 'px' }"></div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent, LegendComponent, TitleComponent, TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent,
  TitleComponent, TooltipComponent, CanvasRenderer])

const props = defineProps({
  columns: { type: Array, default: () => [] },
  rows: { type: Array, default: () => [] },
  chartType: { type: String, default: 'bar' },
  title: { type: String, default: '' },
  xField: { type: String, default: '' },
  yField: { type: String, default: '' },
  height: { type: Number, default: 240 }
})

const el = ref(null)
let chart = null
let observer = null

const toNum = (v) => { const n = Number(v); return Number.isFinite(n) ? n : 0 }

/** 解析字段映射：优先采用显式 x/y 字段（须存在于列中），缺省时 x=第一列、y=第一个数值列 */
const resolveIndexes = () => {
  const cols = props.columns
  let xi = cols.indexOf(props.xField)
  if (xi < 0) xi = 0
  let yi = cols.indexOf(props.yField)
  if (yi < 0 || yi === xi) {
    yi = -1
    for (let c = 0; c < cols.length; c++) {
      if (c === xi) continue
      const hasNumber = props.rows.some((r) => r[c] !== null && r[c] !== '' && Number.isFinite(Number(r[c])))
      if (hasNumber) { yi = c; break }
    }
    if (yi < 0) yi = cols.length > 1 ? (xi === 0 ? 1 : 0) : xi
  }
  return [xi, yi]
}

const buildOption = () => {
  const cols = props.columns
  if (!cols.length) return {}
  const [xi, yi] = resolveIndexes()
  const names = props.rows.map((r) => (r[xi] === null || r[xi] === undefined ? '-' : String(r[xi])))
  const values = props.rows.map((r) => toNum(r[yi]))
  const base = {
    title: props.title
      ? { text: props.title, left: 'center', top: 4, textStyle: { fontSize: 13, fontWeight: 600 } }
      : undefined,
    animationDuration: 300
  }
  if (props.chartType === 'pie') {
    return {
      ...base,
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { bottom: 0, type: 'scroll' },
      series: [{
        type: 'pie', radius: ['32%', '62%'], center: ['50%', '50%'],
        data: names.map((n, i) => ({ name: n, value: values[i] })),
        label: { formatter: '{b}: {c}' }
      }]
    }
  }
  return {
    ...base,
    tooltip: { trigger: 'axis' },
    grid: { left: 8, right: 16, top: props.title ? 34 : 18, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category', data: names,
      axisLabel: { interval: 0, rotate: names.length > 6 ? 30 : 0 }
    },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f0f0f0' } } },
    series: [{
      name: cols[yi], type: props.chartType === 'line' ? 'line' : 'bar',
      data: values, barMaxWidth: 40,
      smooth: props.chartType === 'line',
      label: { show: names.length <= 12, position: 'top', fontSize: 11 }
    }]
  }
}

const render = () => {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  chart.setOption(buildOption(), true)
  chart.resize()
}

watch(() => [props.columns, props.rows, props.chartType, props.title, props.xField, props.yField], render, { deep: true })

onMounted(() => {
  render()
  // 跟随容器尺寸变化（分栏拖动/窗口缩放）
  if (window.ResizeObserver) {
    observer = new ResizeObserver(() => chart && chart.resize())
    observer.observe(el.value)
  }
})

onBeforeUnmount(() => {
  if (observer) { observer.disconnect(); observer = null }
  if (chart) { chart.dispose(); chart = null }
})
</script>

<style scoped>
.chart-view { width: 100%; }
</style>
