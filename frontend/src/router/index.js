import { createRouter, createWebHistory } from 'vue-router'
import Layout from '@/layout/index.vue'

/**
 * 路由表。
 *
 * meta 约定与 vue-admin-template 一致：
 * - title：侧边栏与面包屑显示的标题
 * - icon：Element Plus 图标组件名
 * - hidden：不在侧边栏出现
 * - roles：可访问角色（当前后端无鉴权，保留结构）
 */
export const routes = [
  {
    path: '/login',
    component: () => import('@/views/login/index.vue'),
    meta: { hidden: true, title: '登录' }
  },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '运行总览', icon: 'Odometer' }
      }
    ]
  },
  {
    path: '/config',
    component: Layout,
    meta: { title: '配置管理', icon: 'Setting' },
    children: [
      {
        path: 'routes',
        name: 'Routes',
        component: () => import('@/views/routes/index.vue'),
        meta: { title: '路由与版本', icon: 'Guide' }
      },
      {
        path: 'datasources',
        name: 'Datasources',
        component: () => import('@/views/datasources/index.vue'),
        meta: { title: '数据源', icon: 'Coin' }
      }
    ]
  },
  {
    path: '/ops',
    component: Layout,
    meta: { title: '运行运维', icon: 'Tools' },
    children: [
      {
        path: 'retry',
        name: 'Retry',
        component: () => import('@/views/retry/index.vue'),
        meta: { title: '重试队列', icon: 'Refresh' }
      },
      {
        path: 'errors',
        name: 'Errors',
        component: () => import('@/views/errors/index.vue'),
        meta: { title: '错误与 DLQ', icon: 'WarningFilled' }
      },
      {
        path: 'raw',
        name: 'Raw',
        component: () => import('@/views/raw/index.vue'),
        meta: { title: '原始留存', icon: 'Files' }
      }
    ]
  },
  {
    path: '/devtools',
    component: Layout,
    meta: { title: '联调工具', icon: 'MagicStick' },
    children: [
      {
        path: 'mock',
        name: 'Mock',
        component: () => import('@/views/mock/index.vue'),
        meta: { title: '模拟投递', icon: 'Promotion' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    component: () => import('@/views/error/404.vue'),
    meta: { hidden: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  const token = localStorage.getItem('mqs-token')
  if (to.path === '/login') {
    return true
  }
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
