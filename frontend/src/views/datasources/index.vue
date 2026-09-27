<template>
  <div>
    <el-card class="page-card">
      <div class="page-toolbar">
        <span class="text-muted">
          目标库连接。「目标表」页签选定的 schema 与表都来自这里配置的数据源。
        </span>
        <div class="spacer" />
        <el-button :icon="Plus" type="primary" @click="openCreate">新建数据源</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        stripe
        size="small"
        empty-text="还没有数据源，先建一个再配置路由"
      >
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="jdbcUrl" label="JDBC URL" min-width="280" show-overflow-tooltip />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column label="密码" width="90">
          <template #default="{ row }">
            <el-tag :type="row.hasPassword ? 'success' : 'info'" size="small">
              {{ row.hasPassword ? '已设置' : '未设置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="引用目标表" width="110">
          <template #default="{ row }">
            <span :class="{ 'text-muted': row.targetCount === 0 }">{{ row.targetCount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="200" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onTest(row)">测试连接</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? `编辑数据源 #${form.id}` : '新建数据源'"
      width="640px"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="如 local-pg（唯一）" />
        </el-form-item>
        <el-form-item label="JDBC URL" prop="jdbcUrl">
          <el-input
            v-model="form.jdbcUrl"
            placeholder="jdbc:postgresql://localhost:5432/postgres"
          />
          <div class="form-hint">
            只支持 PostgreSQL —— 写入用的是 MERGE ... RETURNING 与分区语法，都是 PG 专有。
          </div>
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item label="密码" :prop="editing ? undefined : 'password'">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="editing ? '留空表示不修改' : '必填'"
          />
          <div v-if="editing" class="form-hint">出于安全考虑密码不回显，留空即保留原密码。</div>
        </el-form-item>
        <el-form-item label="连接池配置">
          <el-input
            v-model="form.poolConfig"
            type="textarea"
            :rows="3"
            placeholder='可选，JSON，如 {"maximumPoolSize":4,"minimumIdle":0}'
          />
        </el-form-item>
        <el-form-item v-if="testResult" label="试连结果">
          <el-alert
            :type="testResult.ok ? 'success' : 'error'"
            :title="testResult.ok ? `连接成功：${testResult.databaseProduct} ${testResult.databaseVersion}` : testResult.message"
            :closable="false"
            show-icon
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button :loading="testing" @click="onTestForm">测试连接</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  createDatasource,
  deleteDatasource,
  listDatasources,
  testDatasource,
  testStoredDatasource,
  updateDatasource
} from '@/api/datasources'

const loading = ref(false)
const rows = ref([])

const dialogVisible = ref(false)
const editing = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)
const formRef = ref(null)

const emptyForm = () => ({
  id: null,
  name: '',
  jdbcUrl: 'jdbc:postgresql://localhost:5432/postgres',
  username: 'postgres',
  password: '',
  poolConfig: ''
})
const form = reactive(emptyForm())

const rules = {
  name: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
  jdbcUrl: [{ required: true, message: 'JDBC URL 不能为空', trigger: 'blur' }],
  username: [{ required: true, message: '用户名不能为空', trigger: 'blur' }],
  password: [{ required: true, message: '新建数据源必须填写密码', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    rows.value = (await listDatasources()) || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, emptyForm())
  editing.value = false
  testResult.value = null
  dialogVisible.value = true
}

function openEdit(row) {
  Object.assign(form, emptyForm(), {
    id: row.id,
    name: row.name,
    jdbcUrl: row.jdbcUrl,
    username: row.username,
    password: '',
    poolConfig: row.poolConfig ? JSON.stringify(row.poolConfig) : ''
  })
  editing.value = true
  testResult.value = null
  dialogVisible.value = true
}

function resetForm() {
  Object.assign(form, emptyForm())
  testResult.value = null
  formRef.value?.clearValidate()
}

/** 解析连接池 JSON；返回 undefined 表示要中止提交。 */
function parsePoolConfig() {
  const raw = (form.poolConfig || '').trim()
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw)
  } catch {
    ElMessage.error('连接池配置不是合法 JSON')
    return undefined
  }
}

async function onTestForm() {
  const pool = parsePoolConfig()
  if (pool === undefined) {
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const payload = {
      name: form.name,
      jdbcUrl: form.jdbcUrl,
      username: form.username,
      password: form.password,
      poolConfig: pool
    }
    // 带上 id：编辑态留空的密码会由后端回落到已保存的密码
    testResult.value = await testDatasource(payload, editing.value ? form.id : undefined)
  } catch {
    // 请求层已弹出错误信息
  } finally {
    testing.value = false
  }
}

async function onSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  const pool = parsePoolConfig()
  if (pool === undefined) {
    return
  }
  const payload = {
    name: form.name,
    jdbcUrl: form.jdbcUrl,
    username: form.username,
    password: form.password,
    poolConfig: pool
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateDatasource(form.id, payload)
      ElMessage.success('已保存')
    } else {
      await createDatasource(payload)
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 请求层已弹出错误信息
  } finally {
    saving.value = false
  }
}

async function onTest(row) {
  const result = await testStoredDatasource(row.id)
  if (result?.ok) {
    ElMessage.success(`连接成功：${result.databaseProduct} ${result.databaseVersion}`)
  } else {
    ElMessage.error(result?.message || '连接失败')
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(
    `确认删除数据源「${row.name}」？` +
      (row.targetCount > 0 ? `它被 ${row.targetCount} 个目标表引用，需要先删掉那些目标表。` : ''),
    '删除数据源',
    { type: 'warning' }
  )
  try {
    await deleteDatasource(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 被引用时后端会给出「是谁在引用」，由请求层展示
  }
}

onMounted(load)
</script>
