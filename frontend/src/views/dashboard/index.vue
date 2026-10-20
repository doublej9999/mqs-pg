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
          <el-tag :type="pgHealth.type" size="large">{{ pgHealth.label }}</el-tag>
        </div>
        <div class="stat-hint">{{ pgHealth.detail }}</div>
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
        <el-table-column prop="state" label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="stateTagType(row.state)" size="small">{{ row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已接收" width="100">
          <template #default="{ row }">{{ row.receivedTotal ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="已 ACK" width="100">
          <template #default="{ row }">{{ row.ackedTotal ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="未 ACK" width="100">
          <template #default="{ row }">{{ row.failedTotal ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="bindVersion" label="绑定版本" width="100" />
        <el-table-column label="最近成功" width="180">
          <template #default="{ row }">{{ row.lastSuccessAt ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="最近错误" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.pauseReason || row.reason">{{ row.pauseReason || row.reason }}</span>
            <span v-else-if="row.lastErrorAt">{{ row.lastErrorAt }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
      </el-table>

      <div class="page-hint">「已接收 / 已 ACK / 未 ACK」为进程内存计数，语义是「本次启动以来」。</div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getOverview, getPgHealth, listConsumerStates } from '@/api/overview'
import { retrySummaryByState } from '@/api/retry'

const loading = ref(false)
const overview = ref({})
const summary = ref({})
const consumerStates = ref([])
// 三态：null = 还没拿到结果（请求失败或未开始），不能当成「正常」
const pgHealthy = ref(null)
const pgDetail = ref('')

const retryColor = computed(() => ((summary.value.pending ?? 0) > 0 ? '#e6a23c' : undefined))

const pgHealth = computed(() => {
  if (pgHealthy.value === true) {
    return { type: 'success', label: '正常', detail: pgDetail.value || '最近探测通过' }
  }
  if (pgHealthy.value === false) {
    return { type: 'danger', label: '异常', detail: pgDetail.value || '探测失败' }
  }
  return { type: 'info', label: '未知', detail: pgDetail.value || '尚未取得探测结果' }
})

function stateTagType(state) {
  if (state === 'RUNNING') return 'success'
  if (state === 'PAUSED' || state === 'RECOVERING') return 'warning'
  if (state === 'ERROR') return 'danger'
  return 'info'
}

/**
 * 把聚合健康视图压成一句话。
 *
 * 卡片叫「PG 健康」，运维默认理解为**目标库**。改造前它探的其实是配置库，
 * 目标库挂了这张卡仍是「正常」—— 所以这里两个库都要说清楚，
 * 并且明确列出是哪个数据源的闸门关了，否则只看到「异常」没法定位。
 */
function describePgHealth(h) {
  const parts = []
  const cfg = h.configDb
  if (cfg) {
    parts.push(cfg.healthy === true ? '配置库正常' : `配置库不可用`)
  }
  const targets = Array.isArray(h.targets) ? h.targets : []
  if (targets.length === 0) {
    // 闸门只记录「出现过写入尝试」的数据源，样本为空不代表健康，要说清楚
    parts.push('目标库暂无写入样本')
  } else {
    const closed = targets.filter((t) => t.healthy === false)
    if (closed.length === 0) {
      parts.push(`目标库 ${targets.length} 个数据源全部正常`)
    } else {
      parts.push(`目标库异常：数据源 ${closed.map((t) => t.datasourceId).join('、')} 闸门已关闭`)
    }
  }
  return parts.join('；')
}

async function loadAll() {
  loading.value = true
  // 各请求互相独立：一个失败不应让整页空白
  const [ov, sum, states, health] = await Promise.allSettled([
    getOverview(),
    retrySummaryByState(),
    listConsumerStates(),
    getPgHealth()
  ])

  if (ov.status === 'fulfilled') overview.value = ov.value || {}
  if (sum.status === 'fulfilled') summary.value = sum.value || {}
  if (states.status === 'fulfilled') consumerStates.value = states.value || []

  if (health.status === 'fulfilled') {
    const h = health.value || {}
    // H3 之后 /api/pg/health 是聚合视图：configDb（配置库）+ targets（各数据源闸门）
    pgHealthy.value = h.healthy !== false
    pgDetail.value = describePgHealth(h)
  } else {
    // 请求本身失败（后端未起 / 超时）：这是「不知道」，不是「正常」
    pgHealthy.value = null
    pgDetail.value = '健康探测请求失败，详见页面右上角提示'
  }

  loading.value = false
}

onMounted(loadAll)
</script>
