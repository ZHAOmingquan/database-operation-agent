import { defineStore } from 'pinia'
import * as socket from '../ws/socket'
import { sessionApi } from '../api'

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [],
    currentSessionId: null,
    messages: [],
    results: [],
    pendingConfirms: [],
    connected: false,
    thinking: false,
    errors: [],
    lastPrompt: null,     // 最近一次提问，用于“无数据源”引导后自动重发
    noDatasource: null,   // 收到 no_datasource 事件时暂存 payload（{message, hasAnyDatasource}）
    deleteDenied: null    // 收到 delete_denied 事件时暂存 payload（{sql, reason}），供工作台弹框提示
  }),
  actions: {
    async loadSessions() { this.sessions = await sessionApi.list() },
    async ensureSession(datasourceId) {
      if (this.currentSessionId) return this.currentSessionId
      const s = await sessionApi.create({ datasourceId })
      this.sessions.unshift(s)
      await this.switchSession(s.id)
      return s.id
    },
    async switchSession(id) {
      socket.disconnect()
      this.currentSessionId = id
      this.pendingConfirms = []
      this.messages = await sessionApi.messages(id)
      this.results = (await sessionApi.results(id)).reverse()
      const self = this
      socket.connect(id, {
        onStatus: (s) => { self.connected = s === 'connected' },
        onEvent: (evt) => self.onEvent(evt)
      })
    },
    sendMessage(content, datasourceId, modelId) {
      this.thinking = true
      this.lastPrompt = { content, modelId }
      // 后端以首条消息为标题；本地同步列表展示（截断规则与后端 abbreviate 一致）
      const cur = this.sessions.find((s) => s.id === this.currentSessionId)
      if (cur && cur.title === '新会话') cur.title = content.length <= 20 ? content : content.slice(0, 20) + '…'
      socket.send({ type: 'user_message', content, datasourceId, modelId })
    },
    clearNoDatasource() { this.noDatasource = null },
    clearDeleteDenied() { this.deleteDenied = null },
    resendLast(datasourceId) {
      this.noDatasource = null
      if (!this.lastPrompt) return
      this.thinking = true
      socket.send({ type: 'user_message', content: this.lastPrompt.content,
        datasourceId, modelId: this.lastPrompt.modelId })
    },
    onEvent(evt) {
      const p = evt.payload || {}
      switch (evt.type) {
        case 'message':
          if (p.role === 'user') this.messages.push({ role: 'user', content: p.content })
          else { this.messages.push({ role: 'assistant', content: p.content, messageId: p.messageId, toolResultIds: p.toolResultIds }); this.thinking = false }
          break
        case 'result':
          this.results.push(p)
          break
        case 'result_update': {
          const r = this.results.find((x) => x.id === p.id)
          if (r) r.aiComment = p.aiComment
          break
        }
        case 'confirm_request':
          this.pendingConfirms.push({ ...p, createdAt: Date.now() })
          this.thinking = false
          break
        case 'confirm_result':
          this.pendingConfirms = this.pendingConfirms.filter((c) => c.id !== p.id)
          this.thinking = true
          break
        case 'delete_denied':
          this.deleteDenied = p
          this.thinking = false
          break
        case 'no_datasource':
          this.noDatasource = p
          this.thinking = false
          break
        case 'error':
          this.errors.push(p.message)
          this.messages.push({ role: 'error', content: p.message })
          this.thinking = false
          break
      }
    },
    async removeSession(id) {
      await sessionApi.remove(id)
      this.sessions = this.sessions.filter((s) => s.id !== id)
      if (this.currentSessionId === id) {
        this.currentSessionId = null; this.messages = []; this.results = []; socket.disconnect()
        if (this.sessions.length) await this.switchSession(this.sessions[0].id)
      }
    }
  }
})
