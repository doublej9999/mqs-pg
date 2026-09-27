import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/**
 * 登录态。
 *
 * 说明：后端目前没有鉴权模块（PRD 未要求）。这里保留 token 与角色的位置，
 * 使布局、路由守卫、Axios 拦截器的写法与 vue-admin-template 一致，
 * 将来接入真实登录只需替换 login() 的实现。
 */
export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('mqs-token') || '')
  const name = ref(localStorage.getItem('mqs-user') || '')
  const roles = ref(JSON.parse(localStorage.getItem('mqs-roles') || '["admin"]'))

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
