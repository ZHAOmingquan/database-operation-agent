<template>
  <div class="result-panel">
    <div class="bar">
      <span class="title">结果集（{{ store.results.length }}）</span>
      <a-button size="small" type="link" @click="store.results = []">清空</a-button>
    </div>
    <div class="list">
      <ResultCard v-for="r in [...store.results].reverse()" :key="r.id" :item="r" />
      <a-empty v-if="!store.results.length" description="暂无执行结果" />
    </div>
  </div>
</template>

<script setup>
import { useChatStore } from '../stores/chat'
import ResultCard from './ResultCard.vue'
const store = useChatStore()
</script>

<style scoped>
.result-panel { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.bar { display: flex; align-items: center; padding: 6px 10px; border-bottom: 1px solid #f0f0f0; }
.title { font-weight: 600; flex: 1; }
.list { flex: 1; overflow: auto; min-height: 0; padding: 10px; }
</style>
