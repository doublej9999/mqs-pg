<template>
  <div>
    <el-card class="page-card">
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between">
          <span>本地模拟投递</span>
          <el-tag type="warning" effect="plain" size="small">
            仅用于联调：MockMqsBroker 是内存实现
          </el-tag>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 14px"
        title="这里投递的消息会走完整的消费链路（拉取 → 转换 → 折叠 → 批量 upsert → ACK），
包括重试与 DLQ。平台侧接入真实 MQS 后，这个页面只用于压测和排障。"
      />

      <el-form :model="form" label-width="110px">
        <el-form-item label="Topic">
          <el-input v-model="form.topic" placeholder="order-topic" />
        </el-form-item>

        <el-form-item label="Tag">
          <el-input v-model="form.tag" placeholder="order.updated" />
        </el-form-item>

        <el-form-item label="消息体">
          <el-input
            v-model="form.body"
            type="textarea"
            :rows="12"
            class="mono"
            placeholder='{"id":1,"name":"demo"}'
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="sending" @click="onPublish">投递</el-button>
          <el-button @click="loadSample">填入示例</el-button>
          <el-button @click="formatBody">格式化 JSON</el-button>
        </el-form-item>
      </el-form>

      <el-divider>消费状态</el-divider>

      <div class="page-toolbar">
        <el-input-number v-model="statusRouteId" :min="1" style="width: 150px" />
        <el-button :icon="Refresh" @click="loadStatus">查询</el-button>
      </div>

      <el-descriptions v-if="status" :column="4" border size="small">
        <el-descriptions-item label="已接收">{{ status.received ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="已 ACK">{{ status.acked ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="待处理">{{ status.pending ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="批次数">{{ status.batches ?? '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-divider>投递结果</el-divider>
      <pre
        class="mono"
        style="background: #f5f7fa; padding: 10px; border-radius: 4px; min-height: 60px; margin: 0"
        >{{ lastResult }}</pre
      >
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { mockStatus, publishMock } from '@/api/mock'

const sending = ref(false)
const statusRouteId = ref(1)
const status = ref(null)
const lastResult = ref('（尚未投递）')

const form = ref({
  topic: 'order-topic',
  tag: 'order.updated',
  body: JSON.stringify(
    {
      id: 1001,
      name: 'demo-order',
      amount: 20.0,
      status: 'PAID',
      updatedAt: new Date().toISOString()
    },
    null,
    2
  )
})

function loadSample() {
  form.value.body = JSON.stringify(
    {
      id: Math.floor(Math.random() * 100000),
      name: 'demo-order',
      amount: 20.0,
      status: 'PAID',
      updatedAt: new Date().toISOString()
    },
    null,
    2
  )
}

function formatBody() {
  try {
    form.value.body = JSON.stringify(JSON.parse(form.value.body), null, 2)
    ElMessage.success('已格式化')
  } catch {
    ElMessage.error('不是合法 JSON')
  }
}

async function onPublish() {
  sending.value = true
  try {
    const result = await publishMock(form.value)
    lastResult.value = JSON.stringify(result, null, 2)
    ElMessage.success('已投递')
  } catch (e) {
    lastResult.value = String(e.message || e)
  } finally {
    sending.value = false
  }
}

async function loadStatus() {
  status.value = await mockStatus(statusRouteId.value)
}
</script>
