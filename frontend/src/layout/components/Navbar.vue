<template>
  <header class="navbar">
    <div class="navbar-left">
      <el-icon style="cursor: pointer" :size="20" @click="$emit('toggle')">
        <component :is="collapsed ? 'Expand' : 'Fold'" />
      </el-icon>
      <el-breadcrumb separator="/">
        <el-breadcrumb-item v-for="(item, i) in breadcrumbs" :key="i">
          {{ item }}
        </el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <div class="navbar-right">
      <el-tag type="info" effect="plain" size="small">本地环境</el-tag>
      <el-dropdown @command="onCommand">
        <span style="cursor: pointer; display: flex; align-items: center; gap: 6px">
          <el-icon><UserFilled /></el-icon>
          <span>{{ userStore.name || 'admin' }}</span>
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'

defineProps({
  collapsed: { type: Boolean, default: false }
})
defineEmits(['toggle'])

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 由匹配到的路由链生成面包屑标题 */
const breadcrumbs = computed(() => {
  const titles = route.matched
    .map((m) => m.meta?.title)
    .filter(Boolean)

  return titles.length ? titles : ['运行总览']
})

function onCommand(command) {
  if (command === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}
</script>
