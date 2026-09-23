let ws = null
let sessionId = null
let handlers = {}
let retryDelay = 1000
let closedByUser = false

export function connect(id, onEvent) {
  sessionId = id
  handlers = onEvent
  closedByUser = false
  open()
}

function open() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${proto}://${location.host}/ws/session/${sessionId}`)
  ws.onopen = () => { retryDelay = 1000; handlers.onStatus?.('connected') }
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
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(payload))
}

export function disconnect() {
  closedByUser = true
  ws?.close()
}
