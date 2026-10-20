<template>
  <!-- K6：按需引入后不再 app.use(ElementPlus)，中文 locale 由此提供。
       缺了它，日期选择器、分页、空表格等组件的内置文案会退回英文。 -->
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup>
// 根组件只做路由出口，布局与权限都在 router / layout 中处理
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>
