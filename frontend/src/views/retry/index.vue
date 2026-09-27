<template>
  <div>
    <el-card class="page-card">
      <div class="page-toolbar">
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <el-input-number v-model="query.limit" :min="1" :max="500" :step="50" style="width: 130px" />
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <div class="spacer" />
        <el-tag v-for="(count, status) in summary" :key="status" :type="tagType(status)" effect="plain">
          {{ status }}: {{ count }}
        </el-tag>
      </div>

      <el-table v-loading="loading" :data="tasks" stripe size="small" empty-text="没有重试任务">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="messageId" label="消息 ID" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.messageId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="routeId" label="路由" width="70" />
        <el-table-column label="尝试" width="90">
          <template #default="{ row }">{{ row.attempt }} / {{ row.maxAttempt }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="tagType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorCode" label="错误码" width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.errorCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="220" show-overflow-tooltip />
        <el-table-column prop="nextRetryAt" label="下次重试" width="200" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'DLQ' || row.status === 'CANCELLED'"
              link
              type="warning"
              size="small"
              @click="onReplay(row)"
            >
              重放
            </el-button>
            <el-button
              v-if="row.status === 'PENDING' || row.status === 'RUNNING'"
              link
              type="danger"
              size="small"
              @click="onCancel(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="detailVisible" title="重试任务详情" size="620px">
      <el-descriptions v-if="detail" :column="1" border size="small">
        <el-descriptions-item label="ID">{{ detail.id }}</el-descriptions-item>
        <el-descriptions-item label="消息 ID">
          <span class="mono">{{ detail.messageId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="路由 ID">{{ detail.routeId }}</el-descriptions-item>
        <el-descriptions-item label="配置版本">{{ detail.configVersion }}</el-descriptions-item>
        <el-descriptions-item label="Topic / Tag">
          {{ detail.topic }} / {{ detail.tag }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="tagType(detail.status)" size="small">{{ detail.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="尝试次数">
          {{ detail.attempt }} / {{ detail.maxAttempt }}
        </el-descriptions-item>
        <el-descriptions-item label="错误阶段">{{ detail.errorStage }}</el-descriptions-item>
        <el-descriptions-item label="错误码">
          <span class="mono">{{ detail.errorCode }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="错误信息">
          <pre style="white-space: pre-wrap; margin: 0">{{ detail.errorMessage }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="下次重试">{{ detail.nextRetryAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="租约到期">{{ detail.leaseUntil || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ detail.updatedAt }}</el-descriptions-item>
      </el-descriptions>

      <el-divider>原始消息体</el-divider>
      <pre
        class="mono"
        style="background: #f5f7fa; padding: 10px; border-radius: 4px; white-space: pre-wrap"
        >{{ prettyPayload }}</pre
      >
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { cancelRetryTask, getRetryTask, listRetry, replayRetryTask, retrySummary } from '@/api/retry'

const statuses = ['PENDING', 'RUNNING', 'SUCCEEDED', 'DLQ', 'CANCELLED']

const loading = ref(false)
const tasks = ref([])
const summary = ref({})
const query = ref({ status: '', limit: 100 })

const detailVisible = ref(false)
const detail = ref(null)

const prettyPayload = computed(() => {
  const raw = detail.value?.payload
  if (!raw) return '（无）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    // 非 JSON 原文照display，排障时看到原始内容比报错有用
    return raw
  }
})

function tagType(status) {
  switch (status) {
    case 'SUCCEEDED':
      return 'success'
    case 'RUNNING':
      return 'primary'
    case 'PENDING':
      return 'warning'
    case 'DLQ':
      return 'danger'
    default:
      return 'info'
  }
}

async function load() {
  loading.value = true
  try {
    const [list, sum] = await Promise.all([
      listRetry({ status: query.value.status || undefined, limit: query.value.limit }),
      retrySummary()
    ])
    tasks.value = list || []
    summary.value = sum || {}
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  detail.value = await getRetryTask(row.id)
  detailVisible.value = true
}

async function onReplay(row) {
  await ElMessageBox.confirm(
    `确认重放任务 ${row.id}？会追加一轮重试额度并把任务置为待重试。`,
    '人工重放',
    { type: 'warning' }
  )
  await replayRetryTask(row.id)
  ElMessage.success('已提交重放，调度器将在下一轮领取')
  load()
}

async function onCancel(row) {
  await ElMessageBox.confirm(`确认取消任务 ${row.id}？`, '取消任务', { type: 'warning' })
  await cancelRetryTask(row.id)
  ElMessage.success('已取消')
  load()
}

onMounted(load)
</script>
