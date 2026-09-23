import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', component: () => import('../views/Placeholder.vue') },
  { path: '/datasources', component: () => import('../views/DatasourceView.vue') },
  { path: '/models', component: () => import('../views/Placeholder.vue') },
  { path: '/dict', component: () => import('../views/DictView.vue') }
]
export default createRouter({ history: createWebHistory(), routes })
