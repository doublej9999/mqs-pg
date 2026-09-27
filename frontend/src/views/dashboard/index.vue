<template>
  <div v-loading="loading">
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">活跃路由</div>
        <div class="stat-value">{{ overview.activeRoutes ?? '-' }}</div>
        <div class="stat-hint">共 {{ overview.totalRoutes ?? '-' }} 条配置</div>
      </div>

      <div class="stat-card">
        <div class="stat-label">待重试任务</div>
        <div class="stat-value" :style="{ color: retryColor }">
          {{ summary.pending ?? 0 }}
        </div>
        <div class="stat-hint">运行中 {{ summary.running ?? 0 }}</div>
      </div>

      <div class="stat-card">
        <div class="stat-label">DLQ 终态</div>
        <div class="stat-value" :style="{ color: (summary.dlq ?? 0) > 0 ? '#f56c6c' : undefined }">
          {{ summary.dlq ?? 0 }}
        </div>
        <div class="stat-hint">需人工介入</div>
      </div>

      <div class="stat-card">
        <div class="stat-label">已成功重放</div>
        <div class="stat-value" style="color: #67c23a">{{ summary.succeeded ?? 0 }}</div>
        <div class="stat-hint">累计</div>
      </div>

      <div class="stat-card">
        <div class="stat-label">PG 健康</div>
        <div class="stat-value">
          <el-tag :type="pgHealthy ? 'success' : 'danger'" size="large">
            {{ pgHealthy ? '正常' : '异常' }}
          </el-tag>
        </div>
        <div class="stat-hint">{{ pgDetail }}</div>
      </div>
    </div>

    <el-card class="page-card" style="margin-top: 16px">
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between">
          <span>消费路由状态</span>
          <el-button size="small" :icon="Refresh" @click="loadAll">刷新</el-button>
        </div>
      </template>

      <el-table :data="consumerStates" stripe size="small" empty-text="暂无路由状态">
        <el-table-column prop="routeId" label="路由 ID" width="90" />
        <el-table-column prop="state" label="状态" width="140">
          <template #default="{ row }">
            <el-tag :type="stateTagType(row.state)" size="small">{{ row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="receivedTotal" label="已接收" width="110" />
        <el-table-column prop="ackedTotal" label="已 ACK" width="110" />
        <el-table-column prop="failedTotal" label="失败" width="90" />
        <el-table-column prop="lastError" label="最近错误" show-overflow-tooltip />
        <el-table-column prop="updatedAt" label="更新时间" width="200" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getOverview, getPgHealth, listConsumerStates } from '@/api/overview'
import { retrySummary } from '@/api/retry'

const loading = ref(false)
const overview = ref({})
const summary = ref({})
const consumerStates = ref([])
const pg = ref({})

const retryColor = computed(() => ((summary.value.pending ?? 0) > 0 ? '#e6a23c' : undefined))
const pgHealthy = computed(() => pg.value.healthy !== false)
const pgDetail = computed(() => pg.value.detail || '最近探测通过')

function stateTagType(state) {
  if (state === 'RUNNING') return 'success'
  if (state === 'PAUSED' || state === 'BACKOFF') return 'warning'
  if (state === 'STOPPED') return 'info'
  return 'danger'
}

async function loadAll() {
  loading.value = true
  // 各请求互相独立：一个失败不应让整页空白
  const [ov, sum, states, health] = await Promise.allSettled([
    getOverview(),
    retrySummary(),
    listConsumerStates(),
    getPgHealth()
  ])

  if (ov.status === 'fulfilled') overview.value = ov.value || {}
  if (sum.status === 'fulfilled') summary.value = sum.value || {}
  if (states.status === 'fulfilled') consumerStates.value = states.value || []
  if (health.status === 'fulfilled') pg.value = health.value || {}

  loading.value = false
}

onMounted(loadAll)
</script>
