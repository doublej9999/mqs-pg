import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * Axios 封装（沿用 vue-admin-template 的约定）。
 *
 * 后端统一返回信封 { success, code, message, data }：
 * - 成功：resolve 出 `data` 本身，页面里不用每次解包
 * - 失败：抛错并弹出后端给的 message（那是可直接展示给运维的中文说明）
 */
const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 30000
})

service.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('mqs-token')
    if (token) {
      config.headers['X-Token'] = token
    }
    return config
  },
  (error) => Promise.reject(error)
)

service.interceptors.response.use(
  (response) => {
    const body = response.data

    // 非信封响应（例如 actuator）直接返回
    if (body === null || typeof body !== 'object' || !('success' in body)) {
      return body
    }

    if (body.success) {
      return body.data
    }

    ElMessage({ message: body.message || '请求失败', type: 'error', duration: 5000 })
    return Promise.reject(new Error(body.message || `请求失败（code=${body.code}）`))
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data

    if (status === 401) {
      ElMessageBox.confirm('登录状态已失效，是否重新登录？', '提示', {
        confirmButtonText: '重新登录',
        cancelButtonText: '取消',
        type: 'warning'
      })
        .then(() => {
          localStorage.removeItem('mqs-token')
          location.reload()
        })
        .catch(() => {})
      return Promise.reject(error)
    }

    const message = body?.message || error.message || `请求失败（HTTP ${status ?? '网络异常'}）`
    ElMessage({ message, type: 'error', duration: 5000 })
    return Promise.reject(error)
  }
)

export default service
