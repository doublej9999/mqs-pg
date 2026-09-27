<template>
  <div class="app-wrapper">
    <Sidebar :collapsed="collapsed" />
    <div class="main-container">
      <Navbar :collapsed="collapsed" @toggle="collapsed = !collapsed" />
      <AppMain />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import Sidebar from './components/Sidebar.vue'
import Navbar from './components/Navbar.vue'
import AppMain from './components/AppMain.vue'

const collapsed = ref(false)
</script>
