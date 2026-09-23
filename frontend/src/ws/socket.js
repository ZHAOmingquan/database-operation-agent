let ws = null
let sessionId = null
let handlers = {}
let retryDelay = 1000
let closedByUser = false
let outbox = [] // 连接尚未就绪（CONNECTING）时暂存待发消息，onopen 后按序补发

export function connect(id, onEvent) {
  sessionId = id
  handlers = onEvent
  closedByUser = false
  outbox = []
  open()
}

function open() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${proto}://${location.host}/ws/session/${sessionId}`)
  ws.onopen = () => {
    retryDelay = 1000
    handlers.onStatus?.('connected')
    const pending = outbox
    outbox = []
    pending.forEach((d) => ws.send(d))
  }
  ws.onmessage = (e) => {
    try { handlers.onEvent?.(JSON.parse(e.data)) } catch (err) { console.error('bad ws payload', err) }
  }
  ws.onclose = () => {
    handlers.onStatus?.('disconnected')
    if (!closedByUser) {
      setTimeout(open, retryDelay)
      retryDelay = Math.min(retryDelay * 2, 15000)
    }
  }
  ws.onerror = () => ws.close()
}

export function send(payload) {
  const data = JSON.stringify(payload)
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(data)
  else if (ws && ws.readyState === WebSocket.CONNECTING) outbox.push(data)
  else console.warn('ws not connected, message dropped', payload)
}

export function disconnect() {
  closedByUser = true
  ws?.close()
}
