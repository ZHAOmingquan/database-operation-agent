<template>
  <div class="session-list">
    <div class="head">
      <span class="title">会话（{{ store.sessions.length }}）</span>
      <a-button type="primary" size="small" @click="onNew">新建会话</a-button>
    </div>
    <div class="list">
      <div v-for="s in store.sessions" :key="s.id" :class="['item', { active: s.id === store.currentSessionId }]"
           @click="onSelect(s.id)">
        <div class="item-title" :title="s.title">{{ s.title }}</div>
        <a-popconfirm title="删除该会话及其消息/结果？" ok-text="删除" cancel-text="取消"
                      @confirm="store.removeSession(s.id)">
          <a-button type="text" size="small" danger class="del" @click.stop>删除</a-button>
        </a-popconfirm>
      </div>
      <a-empty v-if="!store.sessions.length" description="暂无会话，点击新建" :image-style="{ height: '40px' }" />
    </div>
  </div>
</template>

<script setup>
import { useChatStore } from '../stores/chat'
import { sessionApi } from '../api'

const props = defineProps({ datasourceId: Number })
const store = useChatStore()

const onSelect = async (id) => {
  if (id !== store.currentSessionId) await store.switchSession(id)
}

const onNew = async () => {
  const s = await sessionApi.create({ datasourceId: props.datasourceId })
  store.sessions.unshift(s)
  await store.switchSession(s.id)
}
</script>

<style scoped>
.session-list { display: flex; flex-direction: column; height: 100%; }
.head { display: flex; align-items: center; justify-content: space-between; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
.title { font-weight: 600; font-size: 13px; }
.list { flex: 1; overflow: auto; padding: 6px; }
.item { display: flex; align-items: center; gap: 4px; padding: 6px 8px; border-radius: 6px; cursor: pointer; }
.item:hover { background: #f5f5f5; }
.item.active { background: #e6f4ff; }
.item-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.item.active .item-title { color: #1677ff; font-weight: 600; }
.del { visibility: hidden; flex-shrink: 0; }
.item:hover .del { visibility: visible; }
</style>
