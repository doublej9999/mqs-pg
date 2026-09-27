<template>
  <div>
    <el-card class="page-card">
      <div class="page-toolbar">
        <el-input v-model="query.messageId" placeholder="按消息 ID 查询" clearable style="width: 300px" @keyup.enter="load" />
        <el-input-number v-model="query.routeId" :min="1" placeholder="路由 ID" style="width: 150px" />
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <div class="spacer" />
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 14px"
        title="原始留存按 receive_time 日分区，默认保留 7 天；过期分区由 DETACH + DROP 回收。"
      />

      <el-table v-loading="loading" :data="rows" stripe size="small" empty-text="没有留存记录">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="messageId" label="消息 ID" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.messageId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="routeId" label="路由" width="70" />
        <el-table-column prop="topic" label="Topic" width="150" show-overflow-tooltip />
        <el-table-column prop="tag" label="Tag" width="140" show-overflow-tooltip />
        <el-table-column prop="configVersion" label="版本" width="70" />
        <el-table-column prop="receiveTime" label="接收时间" width="200" />
        <el-table-column label="内容" min-width="120">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
            <el-tag v-if="!row.payload" type="warning" size="small" effect="plain">非 JSON</el-tag>
          </template>
        </el-table-column>
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

    <el-dialog v-model="detailVisible" title="原始消息" width="720px">
      <pre
        class="mono"
        style="background: #f5f7fa; padding: 12px; border-radius: 4px; max-height: 60vh; overflow: auto; white-space: pre-wrap"
        >{{ prettyDetail }}</pre
      >
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { listRawMessages } from '@/api/overview'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ messageId: '', routeId: undefined, page: 1, limit: 50 })

const detailVisible = ref(false)
const detail = ref(null)

const prettyDetail = computed(() => {
  if (!detail.value) return ''
  const source = detail.value.payload ?? detail.value.payloadRaw
  if (!source) return '（空）'
  try {
    return JSON.stringify(typeof source === 'string' ? JSON.parse(source) : source, null, 2)
  } catch {
    return source
  }
})

async function load() {
  loading.value = true
  try {
    const data = await listRawMessages({
      messageId: query.value.messageId || undefined,
      routeId: query.value.routeId || undefined,
      page: query.value.page,
      limit: query.value.limit
    })
    rows.value = data?.items || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

function onPageChange(page) {
  query.value.page = page
  load()
}

function openDetail(row) {
  detail.value = row
  detailVisible.value = true
}

onMounted(load)
</script>
