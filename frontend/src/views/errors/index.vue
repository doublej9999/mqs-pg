<template>
  <div>
    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
      :title="`加载失败：${loadError}`"
      description="下面的表格已被清空，不是「没有数据」。请检查后端是否可达后重试。"
    />
    <el-card class="page-card">
      <div class="page-toolbar">
        <el-select v-model="query.isFinal" placeholder="全部" clearable style="width: 160px" @change="onFilterChange">
          <el-option label="仅终态（DLQ）" :value="true" />
          <el-option label="仅非终态" :value="false" />
        </el-select>
        <el-select
          v-model="query.errorStage"
          placeholder="全部阶段"
          clearable
          style="width: 160px"
          @change="onFilterChange"
        >
          <el-option v-for="s in stages" :key="s" :label="s" :value="s" />
        </el-select>
        <el-input
          v-model="query.messageId"
          placeholder="按消息 ID 精确查询"
          clearable
          style="width: 280px"
          @keyup.enter="onFilterChange"
        />
        <el-button type="primary" :icon="Search" @click="onFilterChange">查询</el-button>
        <div class="spacer" />
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" stripe size="small" empty-text="没有错误记录">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="messageId" label="消息 ID" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.messageId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="routeId" label="路由" width="70" />
        <el-table-column prop="errorStage" label="阶段" width="110" />
        <el-table-column prop="errorCode" label="错误码" width="210" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.errorCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="retryCount" label="重试次数" width="90" />
        <el-table-column prop="finalFlag" label="终态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.finalFlag ? 'danger' : 'warning'" size="small">
              {{ row.finalFlag ? 'DLQ' : '可重试' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="240" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" width="200" />
      </el-table>

      <el-pagination
        style="margin-top: 14px; justify-content: flex-end"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.limit"
        :current-page="query.page"
        @current-change="onPageChange"
      />
    </el-card>
  </div>
</template>

<script setup>
import { onActivated, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { listErrors } from '@/api/errors'
import { getErrorStages } from '@/api/meta'

// 阶段下拉来自后端枚举（`/api/meta/error-stages`），不在这里硬编码：
// 硬编码的值域一旦与后端漂移，表现是「某个选项永远查不到数据」而不是报错，
// 恰恰是最难被发现的那类缺陷。接口失败时退回到空列表并给出提示。
const stages = ref([])

const loading = ref(false)
const loadError = ref('')
const rows = ref([])
const total = ref(0)
const query = ref({ isFinal: undefined, errorStage: '', messageId: '', page: 1, limit: 50 })

async function loadStages() {
  try {
    const data = await getErrorStages()
    stages.value = data?.stages || []
  } catch {
    stages.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const data = await listErrors({
      isFinal: query.value.isFinal,
      errorStage: query.value.errorStage || undefined,
      messageId: query.value.messageId || undefined,
      page: query.value.page,
      limit: query.value.limit
    })
    rows.value = data?.items || []
    total.value = data?.total || 0
    loadError.value = ''
  } catch (e) {
    // K7：没有 catch 时，后端不可达表现为「没有数据」而不是「加载失败」——
    // 与「这条路由确实没有错误流水」长得一模一样，让人误判。
    // 明确清空列表并置错误态：宁可显示「加载失败」，也不展示过期数据。
    rows.value = []
    total.value = 0
    loadError.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function onPageChange(page) {
  query.value.page = page
  load()
}

// 改筛选条件时回到第 1 页：否则在第 3 页切筛选常常得到空表，看起来像「没有数据」
function onFilterChange() {
  query.value.page = 1
  load()
}

// K2：切回本页要看到最新数据，见 retry/index.vue 的说明
onActivated(() => {
  loadStages()
  load()
})
</script>
