import axios from 'axios'
import { message } from 'ant-design-vue'
import { getFingerprint } from '../utils/fingerprint'

const http = axios.create({ baseURL: '/api', timeout: 60000 })

// 所有请求统一携带浏览器指纹（后端按指纹隔离会话数据）
http.interceptors.request.use((cfg) => {
  const fp = getFingerprint()
  if (fp) cfg.headers['X-Browser-Fingerprint'] = fp
  return cfg
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) {
        message.error(body.message || '请求失败')
        return Promise.reject(new Error(body.message))
      }
      return body.data
    }
    return body
  },
  (err) => {
    message.error(err.response?.data?.message || err.message || '网络错误')
    return Promise.reject(err)
  }
)

export const datasourceApi = {
  list: () => http.get('/datasources'),
  create: (data) => http.post('/datasources', data),
  update: (id, data) => http.put(`/datasources/${id}`, data),
  remove: (id) => http.delete(`/datasources/${id}`),
  test: (data, id) => http.post(`/datasources/test${id ? `?id=${id}` : ''}`, data)
}

export const dictApi = {
  list: (type, enabledOnly) => http.get('/dicts', { params: { type, enabledOnly } }),
  create: (data) => http.post('/dicts', data),
  update: (id, data) => http.put(`/dicts/${id}`, data),
  remove: (id) => http.delete(`/dicts/${id}`),
  modelIds: (provider) => http.get('/dicts/model_ids', { params: { provider } })
}

export const modelApi = {
  list: () => http.get('/models'),
  create: (data) => http.post('/models', data),
  update: (id, data) => http.put(`/models/${id}`, data),
  remove: (id) => http.delete(`/models/${id}`),
  enable: (id) => http.post(`/models/${id}/enable`),
  test: (data, id) => http.post(`/models/test${id ? `?id=${id}` : ''}`, data)
}

export const sessionApi = {
  list: () => http.get('/sessions'),
  create: (data) => http.post('/sessions', data),
  update: (id, data) => http.put(`/sessions/${id}`, data),
  remove: (id) => http.delete(`/sessions/${id}`),
  messages: (id) => http.get(`/sessions/${id}/messages`),
  results: (id) => http.get(`/sessions/${id}/results`),
  consoleExecute: (id, data) => http.post(`/sessions/${id}/console/execute`, data)
}

export const confirmApi = {
  approve: (id) => http.post(`/confirm/${id}/approve`),
  reject: (id) => http.post(`/confirm/${id}/reject`)
}

export const configApi = {
  list: () => http.get('/configs'),
  update: (key, value) => http.put(`/configs/${key}`, { value })
}

export default http
