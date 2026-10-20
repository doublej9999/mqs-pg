import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/**
 * 登录态。
 *
 * <p><b>这不是鉴权。</b>后端目前没有鉴权模块（PRD 未要求，见
 * docs/optimization-plan.md §9 Q1）。这里任意非空凭据都能登录、token 是
 * `local-<时间戳>`、后端也不校验 `X-Token`；路由守卫只看 localStorage 里
 * 有没有 token。因此它只是**界面占位**，用来让布局/守卫/拦截器的写法与将来
 * 的真实登录保持一致 —— 绝不能把它当作安全边界。
 *
 * <p>将来接入真实登录只需替换 login() 的实现，并把 §9 Q1 的鉴权专题一起做。
 */
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('mqs-token') || '')
  const name = ref(localStorage.getItem('mqs-user') || '')
  const roles = ref(JSON.parse(localStorage.getItem('mqs-roles') || '["admin"]'))

  /**
   * 预留：当前界面没有基于登录态的分支，守卫直接读 localStorage。
   * 保留是为了将来换成真实登录时不必再补一遍。
   */
  const isLoggedIn = computed(() => !!token.value)

  function login({ username, password }) {
    if (!username || !password) {
      return Promise.reject(new Error('请输入用户名和密码'))
    }
    // 本地控制台：任意非空凭据都放行，后端无鉴权
    token.value = `local-${Date.now()}`
    name.value = username
    roles.value = ['admin']
    localStorage.setItem('mqs-token', token.value)
    localStorage.setItem('mqs-user', name.value)
    localStorage.setItem('mqs-roles', JSON.stringify(roles.value))
    return Promise.resolve()
  }

  function logout() {
    token.value = ''
    name.value = ''
    roles.value = []
    localStorage.removeItem('mqs-token')
    localStorage.removeItem('mqs-user')
    localStorage.removeItem('mqs-roles')
  }

  return { token, name, roles, isLoggedIn, login, logout }
})
