// core-js 需最先引入：为 Chrome 63 等旧内核补齐缺失的 JS API（Promise.finally、Array.flat 等）
import 'core-js/stable'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import App from './App.vue'
import router from './router'
import { initFingerprint } from './utils/fingerprint'
import './styles.css'

// 先初始化浏览器指纹再挂载：axios 请求拦截器与 WS 连接都依赖它
initFingerprint().then(() => {
  const app = createApp(App)
  app.use(createPinia())
  app.use(router)
  app.use(Antd)
  app.mount('#app')
})
