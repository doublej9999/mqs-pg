<template>
  <div>
    <el-card class="page-card">
      <div class="page-toolbar">
        <span class="text-muted">Topic + Tag 决定写入哪张表；版本用于灰度与回滚。</span>
        <div class="spacer" />
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="routes" stripe size="small" empty-text="暂无路由配置">
        <el-table-column prop="routeId" label="ID" width="70" />
        <el-table-column prop="topic" label="Topic" min-width="160" />
        <el-table-column prop="tag" label="Tag" min-width="140" />
        <el-table-column prop="targetSchema" label="目标 Schema" width="130" />
        <el-table-column prop="targetTable" label="目标表" min-width="150" />
        <el-table-column prop="activeVersion" label="生效版本" width="100" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openVersions(row)">版本</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="versionsVisible" :title="`路由 ${current?.routeId} 的配置版本`" size="680px">
      <el-table v-loading="versionsLoading" :data="versions" stripe size="small">
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="200" />
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status !== 'ACTIVE'"
              link
              type="primary"
              size="small"
              @click="onActivate(row)"
            >
              设为生效
            </el-button>
            <span v-else class="text-muted">当前生效</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { activateVersion, listRoutes, listVersions } from '@/api/routes'

const loading = ref(false)
const routes = ref([])

const versionsVisible = ref(false)
const versionsLoading = ref(false)
const versions = ref([])
const current = ref(null)

async function load() {
  loading.value = true
  try {
    routes.value = (await listRoutes()) || []
  } finally {
    loading.value = false
  }
}

async function openVersions(row) {
  current.value = row
  versionsVisible.value = true
  versionsLoading.value = true
  try {
    versions.value = (await listVersions(row.routeId)) || []
  } finally {
    versionsLoading.value = false
  }
}

async function onActivate(row) {
  await ElMessageBox.confirm(
    `确认把路由 ${current.value.routeId} 的生效版本切到 v${row.version}？` +
      `切换只影响之后接收的消息，已在重试队列里的任务仍按绑定版本重放。`,
    '切换生效版本',
    { type: 'warning' }
  )
  await activateVersion(current.value.routeId, row.version)
  ElMessage.success('已切换')
  await openVersions(current.value)
  load()
}

onMounted(load)
</script>
