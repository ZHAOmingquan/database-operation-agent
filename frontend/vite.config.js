import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 兼容目标：Chrome 64+ / Firefox 60+ / Safari 12+（覆盖 360、QQ 等双核浏览器的 Chromium 内核）。
// build.target=chrome64：产物不出现 ?. / ?? 等 ES2020 语法（旧内核支持 ES Module 但不支持新语法）；
// core-js polyfill 在 main.js 引入，补齐旧内核缺失的 API（Promise.finally、Array.flat 等）。
// 动态 import 的运行时助手含 import.meta 语法（Chrome 64 起支持），故 63 及以下内核无法使用；
// 更低的内核（如 IE 兼容模式）由 index.html 的内核检测脚本提示切换极速模式。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true },
      '/mcp': { target: 'http://localhost:8080', changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    target: 'chrome64'
  }
})
