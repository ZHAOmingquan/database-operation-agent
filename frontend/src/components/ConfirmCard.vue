<template>
  <div class="confirm-card">
    <div class="head">
      <a-tag color="orange">写操作待确认</a-tag>
      <span class="ds">数据源：{{ item.datasourceName }}</span>
      <span class="countdown">{{ remain }}s</span>
    </div>
    <pre class="sql mono">{{ item.sql }}</pre>
    <a-space>
      <a-button type="primary" size="small" @click="approve">确认执行</a-button>
      <a-button size="small" danger @click="reject">取消</a-button>
    </a-space>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { confirmApi } from '../api'

const props = defineProps({ item: { type: Object, required: true } })
const emit = defineEmits(['resolve'])
const remain = ref(props.item.expiresInSeconds ?? 60)
let timer = null

onMounted(() => {
  timer = setInterval(() => { if (remain.value > 0) remain.value-- }, 1000)
})
onUnmounted(() => clearInterval(timer))

const approve = async () => { await confirmApi.approve(props.item.id); emit('resolve', props.item.id) }
const reject = async () => { await confirmApi.reject(props.item.id); emit('resolve', props.item.id) }
</script>

<style scoped>
.confirm-card { border: 1px solid #faad14; background: #fffbe6; border-radius: 8px; padding: 10px 12px; margin-bottom: 12px; }
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.countdown { margin-left: auto; color: #fa8c16; font-weight: 600; }
.sql { background: #fff; border: 1px solid #f0f0f0; border-radius: 4px; padding: 8px; max-height: 160px; overflow: auto; white-space: pre-wrap; }
</style>
