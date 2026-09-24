<template>
  <div class="chat-panel">
    <div class="messages" ref="scroller">
      <MessageItem v-for="(m, i) in store.messages" :key="i" :msg="m" />
      <ConfirmCard v-for="c in store.pendingConfirms" :key="'c' + c.id" :item="c" @resolve="onConfirmResolved" />
      <div v-if="store.thinking" class="thinking"><a-spin size="small" /> 思考中…</div>
    </div>
    <div v-if="showQueueBanner" class="queue-banner">
      <a-spin size="small" />
      <span>当前正在排队处理问题，排队数量：{{ store.queuePosition }}</span>
    </div>
    <a-alert v-if="store.modelError" class="model-error" type="error" show-icon closable
             :message="store.modelError" @close="store.clearModelError()" />
    <div class="input-bar">
      <a-textarea v-model:value="draft" placeholder="用自然语言描述你要对数据库做的事，例如：帮我查询用户列表？"
                  :rows="3" @press-enter="onEnter" />
      <a-button type="primary" :disabled="!draft.trim() || store.thinking || store.queuePosition !== null"
                @click="submit">发送</a-button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useChatStore } from '../stores/chat'
import MessageItem from './MessageItem.vue'
import ConfirmCard from './ConfirmCard.vue'

const props = defineProps({ datasourceId: Number, modelId: Number })
const store = useChatStore()
const draft = ref('')
const scroller = ref(null)

// 排队数量只有超过 5 才显示横幅（≤5 时静默等待，轮到直接处理）
const showQueueBanner = computed(() => store.queuePosition !== null && store.queuePosition > 5)

watch(() => [store.messages.length, store.pendingConfirms.length, store.thinking], async () => {
  await nextTick()
  if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
})

const onEnter = (e) => { if (!e.shiftKey) { e.preventDefault(); submit() } }

const submit = async () => {
  const content = draft.value.trim()
  if (!content) return
  await store.ensureSession(props.datasourceId)
  store.sendMessage(content, props.datasourceId, props.modelId)
  draft.value = ''
}

const onConfirmResolved = () => { /* store 在 confirm_result 事件中自动清理 */ }
</script>

<style scoped>
.chat-panel { display: flex; flex-direction: column; height: 100%; }
.messages { flex: 1; overflow: auto; padding: 12px; }
.input-bar { display: flex; align-items: flex-end; gap: 8px; padding: 10px; border-top: 1px solid #f0f0f0; }
.thinking { color: #999; font-size: 12px; padding: 4px 0; }
.queue-banner {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 12px; font-size: 12px; color: #d46b08;
  background: #fff7e6; border-top: 1px solid #ffe7ba;
}
.model-error { margin: 8px 10px 0; }
</style>
