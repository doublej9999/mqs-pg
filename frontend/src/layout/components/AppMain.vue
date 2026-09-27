<template>
  <main class="app-main">
    <router-view v-slot="{ Component }">
      <keep-alive :max="10">
        <component :is="Component" :key="$route.path" />
      </keep-alive>
    </router-view>
  </main>
</template>

<script setup>
// 主内容区：只负责承载路由视图
</script>
