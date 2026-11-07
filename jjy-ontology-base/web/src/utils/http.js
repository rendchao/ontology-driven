import axios from 'axios'
import { getToken, clearToken } from './auth'
import { Message } from 'element-ui'

/**
 * HTTP 封装。
 *
 * <p>统一处理三件事：</p>
 * <ol>
 *   <li>注入 JWT；</li>
 *   <li>拆解后端统一响应 {@code { code, message, data }}，把 data 直接返回给调用方；</li>
 *   <li>业务失败统一弹提示；401 自动跳登录。</li>
 * </ol>
 *
 * <p><b>调用示例</b>：</p>
 * <pre>
 * const rows = await this.$http.get('/meta/dictionaries')
 * await this.$http.post('/behavior/Product_SaveAsDraft', { dto: this.form })
 * </pre>
 */
const http = axios.create({
  baseURL: process.env.VUE_APP_API_BASE || '/api/v1',
  timeout: 30000
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    // 非统一响应格式（如文件流）直接返回
    if (!body || typeof body.code === 'undefined') {
      return body
    }
    if (body.code === 2000) {
      return body.data
    }
    if (body.code === 4001) {
      clearToken()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
      return Promise.reject(new Error(body.message))
    }
    Message.error(body.message || '请求失败')
    return Promise.reject(new Error(body.message || '请求失败'))
  },
  (error) => {
    const msg = error.response && error.response.data && error.response.data.message
    Message.error(msg || error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default http
