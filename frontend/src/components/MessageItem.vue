<template>
  <div :class="['msg', msg.role]">
    <div class="bubble">
      <template v-if="msg.role === 'assistant'">
        <div class="answer">{{ answer }}</div>
        <a-tag v-if="msg.toolResultIds?.length" color="blue" style="margin-top:6px">
          已执行 {{ msg.toolResultIds.length }} 个 SQL（见右下结果集）
        </a-tag>
      </template>
      <span v-else>{{ msg.content }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ msg: { type: Object, required: true } })
// 推理模型（如 MiniMax-M3）会把 <think>…</think> 思维链原样写进 content，展示时剥离
const answer = computed(() => (props.msg.content || '').replace(/<think>[\s\S]*?<\/think>/g, '').trim())
</script>

<style scoped>
.msg { display: flex; margin-bottom: 12px; }
.msg.user { justify-content: flex-end; }
.bubble { max-width: 85%; padding: 8px 12px; border-radius: 8px; background: #f5f5f5; white-space: pre-wrap; word-break: break-word; }
.msg.user .bubble { background: #1677ff; color: #fff; }
.msg.error .bubble { background: #fff2f0; color: #cf1322; }
.answer { white-space: pre-wrap; }
</style>
