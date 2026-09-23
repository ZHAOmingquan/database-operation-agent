// 浏览器指纹：浏览器特征串 + 16 字节随机盐 → SHA-256，缓存于 localStorage['dbagent_fp']。
// 同一浏览器（刷新/重启）指纹稳定；不同浏览器/无头实例因随机盐必然互异；清空站点数据视为新身份。
const STORAGE_KEY = 'dbagent_fp'
let cached = null

function randomSalt() {
  const bytes = new Uint8Array(16)
  if (window.crypto && window.crypto.getRandomValues) window.crypto.getRandomValues(bytes)
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

function fnv1a64Hex(input) {
  let hash = 0xcbf29ce484222325n
  const prime = 0x100000001b3n
  const mask = 0xffffffffffffffffn
  for (let i = 0; i < input.length; i++) {
    hash ^= BigInt(input.charCodeAt(i))
    hash = (hash * prime) & mask
  }
  return hash.toString(16).padStart(16, '0')
}

async function hashHex(input) {
  if (window.crypto && window.crypto.subtle) {
    const digest = await window.crypto.subtle.digest('SHA-256', new TextEncoder().encode(input))
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
  }
  return fnv1a64Hex(input) // 非安全上下文（如局域网 http 访问）回退
}

function features() {
  const s = window.screen || {}
  return [
    navigator.userAgent,
    navigator.language,
    navigator.platform || '',
    `${s.width || 0}x${s.height || 0}x${s.colorDepth || 0}`,
    window.devicePixelRatio || 1,
    String(new Date().getTimezoneOffset()),
    navigator.hardwareConcurrency || 0
  ].join('|')
}

export async function initFingerprint() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) { cached = saved; return cached }
  } catch (e) { /* 存储不可用时退化为内存态指纹 */ }
  cached = await hashHex(`${randomSalt()}|${features()}`)
  try { localStorage.setItem(STORAGE_KEY, cached) } catch (e) { /* 忽略 */ }
  return cached
}

export function getFingerprint() { return cached }
