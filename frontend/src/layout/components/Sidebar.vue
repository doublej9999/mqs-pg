<template>
  <aside class="sidebar-container" :class="{ 'is-collapsed': collapsed }">
    <div class="sidebar-logo">
      <span v-if="!collapsed">MQS → PostgreSQL</span>
      <span v-else>MQ</span>
    </div>

    <el-menu
      :default-active="activeMenu"
      :collapse="collapsed"
      :collapse-transition="false"
      background-color="#304156"
      text-color="#bfcbd9"
      active-text-color="#409eff"
      router
    >
      <template v-for="item in menus" :key="item.path">
        <!-- 单个子路由：直接渲染成菜单项 -->
        <el-menu-item v-if="item.single" :index="item.single.fullPath">
          <el-icon><component :is="item.single.meta.icon" /></el-icon>
          <template #title>{{ item.single.meta.title }}</template>
        </el-menu-item>

        <!-- 多个子路由：渲染成分组 -->
        <el-sub-menu v-else :index="item.path">
          <template #title>
            <el-icon><component :is="item.meta?.icon || 'Menu'" /></el-icon>
            <span>{{ item.meta?.title }}</span>
          </template>
          <el-menu-item v-for="child in item.children" :key="child.fullPath" :index="child.fullPath">
            <el-icon><component :is="child.meta.icon" /></el-icon>
            <template #title>{{ child.meta.title }}</template>
          </el-menu-item>
        </el-sub-menu>
      </template>
    </el-menu>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { routes } from '@/router'

defineProps({
  collapsed: { type: Boolean, default: false }
})

const route = useRoute()

const activeMenu = computed(() => route.path)

/** 把路由表整理成侧边栏需要的结构，跳过登录页与 404 */
function joinPath(parent, child) {
  if (child.startsWith('/')) return child
  return `${parent.replace(/\/$/, '')}/${child}`
}

const menus = computed(() =>
  routes
    .filter((r) => !r.meta?.hidden && r.path !== '/login' && r.path !== '/:pathMatch(.*)*')
    .map((r) => {
      const children = (r.children || [])
        .filter((c) => !c.meta?.hidden)
        .map((c) => ({ ...c, fullPath: joinPath(r.path, c.path) }))

      return {
        ...r,
        children,
        single: children.length === 1 ? children[0] : null
      }
    })
    .filter((r) => r.children.length > 0)
)
</script>
