<template>
  <div>
    <el-card class="page-card">
      <div class="page-toolbar">
        <span class="text-muted">
          Topic + Tag 决定消费什么，目标表决定写到哪个库的哪个 schema 的哪张表。
        </span>
        <div class="spacer" />
        <el-button :icon="Plus" type="primary" @click="openCreate">新建路由</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        stripe
        size="small"
        empty-text="还没有路由，点「新建路由」开始配置"
      >
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" min-width="130" />
        <el-table-column prop="topic" label="Topic" min-width="150" show-overflow-tooltip />
        <el-table-column prop="tag" label="Tag" min-width="130" show-overflow-tooltip />
        <el-table-column prop="targetTable" label="目标表" min-width="170" show-overflow-tooltip />
        <el-table-column label="生效版本" width="100">
          <template #default="{ row }">
            <span v-if="row.activeVersion">v{{ row.activeVersion }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="消费" width="110">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.consumerReason"
              :content="row.consumerReason"
              placement="top"
            >
              <el-tag :type="consumerTagType(row.consumerStatus)" size="small">
                {{ row.consumerStatus || '—' }}
              </el-tag>
            </el-tooltip>
            <el-tag v-else :type="consumerTagType(row.consumerStatus)" size="small">
              {{ row.consumerStatus || '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openMappings(row)">配置</el-button>
            <el-button link type="primary" size="small" @click="openVersions(row)">版本</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ============ 新建 / 编辑路由 ============ -->
    <el-dialog
      v-model="routeDialogVisible"
      :title="editingRoute ? `编辑路由 #${form.routeId}` : '新建路由'"
      width="720px"
      @closed="resetRouteForm"
    >
      <el-form ref="routeFormRef" :model="form" :rules="routeRules" label-width="130px">
        <el-form-item label="路由名称" prop="name">
          <el-input v-model="form.name" placeholder="如 order-sync（唯一）" />
        </el-form-item>

        <el-divider content-position="left">消费什么</el-divider>
        <el-form-item label="Topic" prop="topic">
          <el-input v-model="form.topic" placeholder="如 order-topic" />
        </el-form-item>
        <el-form-item label="Tag">
          <el-input v-model="form.tag" placeholder="如 order.updated；留空表示全部 Tag" />
          <div class="form-hint">Topic + Tag 在系统内必须唯一。</div>
        </el-form-item>

        <el-divider content-position="left">写到哪张表</el-divider>
        <el-form-item label="数据源" prop="datasourceId">
          <div style="display: flex; gap: 8px; width: 100%">
            <el-select
              v-model="form.datasourceId"
              placeholder="选择目标库"
              style="flex: 1"
              @change="onDatasourceChange"
            >
              <el-option
                v-for="ds in datasources"
                :key="ds.id"
                :label="`${ds.name}（${ds.jdbcUrl}）`"
                :value="ds.id"
              />
            </el-select>
            <el-button :icon="Coin" @click="goDatasources">管理</el-button>
          </div>
        </el-form-item>
        <el-form-item label="Schema" prop="schemaName">
          <el-select
            v-model="form.schemaName"
            placeholder="选择 schema"
            style="width: 100%"
            :loading="loadingSchemas"
            :disabled="!form.datasourceId"
            filterable
            allow-create
            default-first-option
            @change="onSchemaChange"
          >
            <el-option v-for="s in schemas" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="表" prop="tableName">
          <el-select
            v-model="form.tableName"
            placeholder="选择表"
            style="width: 100%"
            :loading="loadingTables"
            :disabled="!form.schemaName"
            filterable
            @change="onTableChange"
          >
            <el-option v-for="t in tables" :key="t" :label="t" :value="t" />
          </el-select>
          <div class="form-hint">只列出普通表与分区表 —— 视图和外部表无法用 MERGE 稳定写入。</div>
        </el-form-item>
        <el-form-item label="Upsert Key" prop="upsertKeys">
          <el-select
            v-model="form.upsertKeys"
            multiple
            placeholder="默认 id"
            style="width: 100%"
            :disabled="!columns.length"
          >
            <el-option
              v-for="c in columns"
              :key="c.name"
              :label="c.primaryKey ? `${c.name}（主键）` : c.name"
              :value="c.name"
            />
          </el-select>
          <div class="form-hint">
            必须整体命中主键或唯一索引，否则发布校验会拒绝 —— MERGE 的 ON 条件依赖它。
          </div>
        </el-form-item>
        <el-form-item label="单调守卫字段" prop="updateTimeField">
          <el-select
            v-model="form.updateTimeField"
            placeholder="默认 update_time"
            style="width: 100%"
            :disabled="!columns.length"
          >
            <el-option v-for="c in columns" :key="c.name" :label="c.name" :value="c.name" />
          </el-select>
          <div class="form-hint">
            用于丢弃乱序到达的旧版本数据；只有严格更新时才覆盖。
          </div>
        </el-form-item>
        <el-form-item label="目标表名称">
          <el-input v-model="form.targetName" :placeholder="suggestedTargetName" />
          <div class="form-hint">留空则用 {{ suggestedTargetName }}</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="routeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRoute" @click="onSubmitRoute">
          {{ editingRoute ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 版本抽屉 ============ -->
    <el-drawer v-model="versionsVisible" :title="`路由 ${current?.name || ''} 的配置版本`" size="760px">
      <div class="page-toolbar">
        <el-button
          :icon="Plus"
          type="primary"
          size="small"
          :disabled="hasDraft"
          @click="onCreateVersion"
        >
          新建版本
        </el-button>
        <el-button size="small" :disabled="!current?.activeVersion" @click="onRollback">
          回滚到上一版
        </el-button>
        <div class="spacer" />
        <el-button :icon="Refresh" size="small" @click="openVersions(current)">刷新</el-button>
      </div>
      <div v-if="hasDraft" class="form-hint" style="margin-bottom: 8px">
        已有草稿版本。发布或删除它之后才能新建下一个版本。
      </div>

      <el-table v-loading="versionsLoading" :data="versions" stripe size="small">
        <el-table-column prop="version" label="版本" width="70">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="versionTagType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="200" />
        <el-table-column prop="changeNote" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="!row.active && row.status !== 'ACTIVE'"
              link
              type="primary"
              size="small"
              @click="onActivate(row)"
            >
              设为生效
            </el-button>
            <span v-else class="text-muted">当前生效</span>
            <el-button
              link
              type="danger"
              size="small"
              :disabled="row.active || row.status === 'ACTIVE'"
              @click="onDeleteVersion(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- ============ 映射编辑器 ============ -->
    <el-dialog
      v-model="mappingVisible"
      :title="`字段映射：${current?.name || ''}`"
      width="1080px"
      top="6vh"
      @closed="resetMapping"
    >
      <div class="page-toolbar">
        <el-tag v-if="draft.version" size="small">v{{ draft.version }}</el-tag>
        <el-tag v-else size="small" type="warning">未保存</el-tag>
        <el-tag size="small" :type="draft.persisted ? 'success' : 'info'">
          {{ draft.persisted ? '已保存草稿' : '尚未保存' }}
        </el-tag>
        <span class="text-muted">{{ draft.changeNote }}</span>
        <div class="spacer" />
        <el-button size="small" @click="onAutoGenerate">按同名列自动生成</el-button>
        <el-button size="small" @click="addRow">添加一行</el-button>
        <el-button size="small" @click="onValidate">校验</el-button>
        <el-button size="small" type="primary" :loading="savingMapping" @click="onSaveDraft">
          保存草稿
        </el-button>
        <el-button size="small" type="success" :loading="publishing" @click="onPublish">
          保存并发布
        </el-button>
      </div>

      <el-alert
        v-if="issues.length"
        :type="hasBlockingIssue ? 'error' : 'warning'"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      >
        <template #title>
          {{ hasBlockingIssue ? '校验未通过，无法发布' : '校验通过，但有以下提示' }}
        </template>
        <ul class="issue-list">
          <li v-for="(issue, i) in issues" :key="i">
            <span class="issue-field">{{ issue.field }}</span>
            <el-tag :type="issue.severity === 'ERROR' ? 'danger' : 'warning'" size="small">
              {{ issue.severity }}
            </el-tag>
            {{ issue.message }}
          </li>
        </ul>
      </el-alert>

      <el-table :data="mappings" size="small" border>
        <el-table-column label="目标列" width="180">
          <template #default="{ row }">
            <el-select
              v-model="row.target"
              filterable
              allow-create
              default-first-option
              placeholder="列名"
              size="small"
              style="width: 100%"
            >
              <el-option
                v-for="c in columns"
                :key="c.name"
                :label="c.requiresValue ? `${c.name} *` : c.name"
                :value="c.name"
              />
            </el-select>
          </template>
        </el-table-column>

        <el-table-column label="来源" width="120">
          <template #default="{ row }">
            <el-select v-model="row.kind" size="small" style="width: 100%">
              <el-option label="源字段" value="source" />
              <el-option label="常量" value="constant" />
              <el-option label="表达式" value="expression" />
            </el-select>
          </template>
        </el-table-column>

        <el-table-column label="取值" min-width="200">
          <template #default="{ row }">
            <el-input
              v-model="row.value"
              size="small"
              :placeholder="valuePlaceholder(row.kind)"
              class="mono"
            />
          </template>
        </el-table-column>

        <el-table-column label="转换" width="130">
          <template #default="{ row }">
            <el-select v-model="row.transformType" size="small" style="width: 100%">
              <el-option v-for="t in transformTypes" :key="t" :label="t" :value="t" />
            </el-select>
          </template>
        </el-table-column>

        <el-table-column label="转换参数" width="200">
          <template #default="{ row }">
            <template v-if="row.transformType === 'timestamp'">
              <el-input v-model="row.pattern" size="small" placeholder="yyyy-MM-dd'T'HH:mm:ssXXX" />
              <el-input
                v-model="row.zone"
                size="small"
                placeholder="Asia/Shanghai"
                style="margin-top: 4px"
              />
            </template>
            <el-input
              v-else-if="row.transformType === 'enum'"
              v-model="row.enumMapping"
              size="small"
              type="textarea"
              :rows="2"
              placeholder='{"CREATED":1,"PAID":2}'
            />
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="必填" width="70" align="center">
          <template #default="{ row }">
            <el-checkbox v-model="row.required" />
          </template>
        </el-table-column>

        <el-table-column label="默认值" width="120">
          <template #default="{ row }">
            <el-input v-model="row.defaultValue" size="small" placeholder="可选" />
          </template>
        </el-table-column>

        <el-table-column label="" width="60" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="mappings.splice($index, 1)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="form-hint" style="margin-top: 10px">
        标 * 的列是 NOT NULL 且无数据库默认值，必须给出 source / constant / expression / defaultValue，
        否则发布会被拦下。
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Coin, Plus, Refresh } from '@element-plus/icons-vue'
import {
  activateVersion,
  autoGenerateMappings,
  createRoute,
  createVersion,
  deleteRoute,
  deleteVersion,
  getDraft,
  getRoute,
  listRoutes,
  listVersions,
  publishVersion,
  rollbackRoute,
  saveDraft,
  updateRoute,
  validateVersion
} from '@/api/routes'
import { listColumns, listDatasources, listSchemas, listTables } from '@/api/datasources'
import { createTarget, updateTarget } from '@/api/targets'

const router = useRouter()

const transformTypes = [
  'string',
  'long',
  'integer',
  'decimal',
  'double',
  'boolean',
  'timestamp',
  'date',
  'enum'
]

const loading = ref(false)
const rows = ref([])
const datasources = ref([])

// ---- 路由表单 ----
const routeDialogVisible = ref(false)
const editingRoute = ref(false)
const savingRoute = ref(false)
const routeFormRef = ref(null)
const schemas = ref([])
const tables = ref([])
const columns = ref([])
const loadingSchemas = ref(false)
const loadingTables = ref(false)

const emptyForm = () => ({
  routeId: null,
  targetId: null,
  name: '',
  topic: '',
  tag: '',
  datasourceId: null,
  schemaName: '',
  tableName: '',
  upsertKeys: [],
  updateTimeField: '',
  targetName: ''
})
const form = reactive(emptyForm())

const routeRules = {
  name: [{ required: true, message: '路由名称不能为空', trigger: 'blur' }],
  topic: [{ required: true, message: 'Topic 不能为空', trigger: 'blur' }],
  datasourceId: [{ required: true, message: '必须选择数据源', trigger: 'change' }],
  schemaName: [{ required: true, message: '必须选择 schema', trigger: 'change' }],
  tableName: [{ required: true, message: '必须选择表', trigger: 'change' }]
}

const suggestedTargetName = computed(() =>
  form.schemaName && form.tableName ? `${form.schemaName}.${form.tableName}` : ''
)

// ---- 版本 ----
const versionsVisible = ref(false)
const versionsLoading = ref(false)
const versions = ref([])
const current = ref(null)
const hasDraft = computed(() => versions.value.some((v) => v.status === 'DRAFT'))

// ---- 映射 ----
const mappingVisible = ref(false)
const savingMapping = ref(false)
const publishing = ref(false)
const mappings = ref([])
const issues = ref([])
// mappingStrategy / jslt 必须原样带回：页面只编辑 mappings，
// 若保存时把这两个字段重置成默认值，就会悄悄抹掉别人配的策略与 JSLT。
const draft = reactive({
  version: null,
  status: null,
  persisted: false,
  changeNote: '',
  mappingStrategy: 'EXACT',
  jslt: null
})

const hasBlockingIssue = computed(() => issues.value.some((i) => i.severity === 'ERROR'))

// ==================================================================
// 列表
// ==================================================================

async function load() {
  loading.value = true
  try {
    rows.value = (await listRoutes()) || []
  } finally {
    loading.value = false
  }
}

async function loadDatasources() {
  datasources.value = (await listDatasources()) || []
}

function consumerTagType(status) {
  if (status === 'RUNNING') return 'success'
  if (status === 'PAUSED' || status === 'ERROR') return 'danger'
  if (status === 'RECOVERING') return 'warning'
  return 'info'
}

function versionTagType(status) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'PUBLISHED') return 'primary'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

// ==================================================================
// 结构探查（下拉框）
// ==================================================================

async function loadSchemas() {
  if (!form.datasourceId) {
    schemas.value = []
    return
  }
  loadingSchemas.value = true
  try {
    schemas.value = (await listSchemas(form.datasourceId)) || []
  } finally {
    loadingSchemas.value = false
  }
}

async function loadTables() {
  if (!form.datasourceId || !form.schemaName) {
    tables.value = []
    return
  }
  loadingTables.value = true
  try {
    tables.value = (await listTables(form.datasourceId, form.schemaName)) || []
  } finally {
    loadingTables.value = false
  }
}

async function loadColumns() {
  if (!form.datasourceId || !form.schemaName || !form.tableName) {
    columns.value = []
    return
  }
  columns.value =
    (await listColumns(form.datasourceId, form.schemaName, form.tableName)) || []
  // 首次选表时给出合理默认值，省掉两次点击
  if (!form.upsertKeys.length) {
    const pk = columns.value.filter((c) => c.primaryKey).map((c) => c.name)
    form.upsertKeys = pk.length ? pk : columns.value.slice(0, 1).map((c) => c.name)
  }
  if (!form.updateTimeField) {
    form.updateTimeField = columns.value.some((c) => c.name === 'update_time')
      ? 'update_time'
      : ''
  }
}

async function onDatasourceChange() {
  form.schemaName = ''
  form.tableName = ''
  columns.value = []
  await loadSchemas()
}

async function onSchemaChange() {
  form.tableName = ''
  columns.value = []
  await loadTables()
}

async function onTableChange() {
  form.upsertKeys = []
  form.updateTimeField = ''
  await loadColumns()
}

// ==================================================================
// 新建 / 编辑路由
// ==================================================================

function openCreate() {
  Object.assign(form, emptyForm())
  schemas.value = []
  tables.value = []
  columns.value = []
  editingRoute.value = false
  routeDialogVisible.value = true
  loadDatasources()
}

async function openEdit(row) {
  const detail = await getRoute(row.id)
  const t = detail.target || {}
  Object.assign(form, emptyForm(), {
    routeId: row.id,
    targetId: row.targetId,
    name: row.name,
    topic: row.topic,
    tag: row.tag,
    datasourceId: t.datasourceId ?? null,
    schemaName: t.schemaName || '',
    tableName: t.tableName || '',
    upsertKeys: t.upsertKeys || [],
    updateTimeField: t.updateTimeField || '',
    targetName: t.name || ''
  })
  editingRoute.value = true
  routeDialogVisible.value = true
  await loadDatasources()
  await loadSchemas()
  await loadTables()
  await loadColumns()
  // loadColumns 会补默认值，编辑时要用已保存的值覆盖回来
  form.upsertKeys = t.upsertKeys || form.upsertKeys
  form.updateTimeField = t.updateTimeField || form.updateTimeField
}

function resetRouteForm() {
  Object.assign(form, emptyForm())
  schemas.value = []
  tables.value = []
  columns.value = []
  routeFormRef.value?.clearValidate()
}

async function onSubmitRoute() {
  const valid = await routeFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (!form.upsertKeys.length) {
    ElMessage.error('至少选择一个 Upsert Key')
    return
  }
  if (!form.updateTimeField) {
    ElMessage.error('必须选择单调守卫字段')
    return
  }
  const targetPayload = {
    name: (form.targetName || suggestedTargetName.value).trim(),
    datasourceId: form.datasourceId,
    schemaName: form.schemaName,
    tableName: form.tableName,
    upsertKeys: form.upsertKeys,
    updateTimeField: form.updateTimeField
  }

  savingRoute.value = true
  try {
    if (editingRoute.value) {
      // 目标表先改：路由仍指向同一个 targetId
      await updateTarget(form.targetId, targetPayload)
      await updateRoute(form.routeId, {
        name: form.name,
        topic: form.topic,
        tag: form.tag,
        targetId: form.targetId
      })
      ElMessage.success('已保存')
    } else {
      const target = await createTarget(targetPayload)
      await createRoute({
        name: form.name,
        topic: form.topic,
        tag: form.tag,
        targetId: target.id
      })
      ElMessage.success('已创建。接着用「配置」填字段映射，再发布并激活即可开始消费。')
    }
    routeDialogVisible.value = false
    await load()
  } catch {
    // 请求层已弹出后端给出的中文原因
  } finally {
    savingRoute.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(
    `确认删除路由「${row.name}」？它的配置版本会一并删除。`,
    '删除路由',
    { type: 'warning' }
  )
  try {
    await deleteRoute(row.id, false)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    // 还有未完成的重试任务时后端会拒绝，并提供 force 选项
    if (!String(e?.message || '').includes('重试任务')) {
      return
    }
    try {
      await ElMessageBox.confirm(
        '该路由还有未完成的重试任务，删除会让它们成为孤儿（历史记录仍保留可查）。确认丢弃？',
        '存在未完成的重试任务',
        { type: 'warning', confirmButtonText: '仍然删除' }
      )
    } catch {
      return
    }
    await deleteRoute(row.id, true)
    ElMessage.success('已删除')
    await load()
  }
}

// ==================================================================
// 版本
// ==================================================================

/** 只重取版本列表。保存草稿后也要刷新，但绝不能顺手把抽屉弹开。 */
async function reloadVersions() {
  versionsLoading.value = true
  try {
    versions.value = (await listVersions(current.value.id)) || []
  } finally {
    versionsLoading.value = false
  }
}

async function openVersions(row) {
  current.value = row
  versionsVisible.value = true
  await reloadVersions()
}

async function onCreateVersion() {
  await createVersion(current.value.id)
  ElMessage.success('已新建草稿版本')
  await reloadVersions()
}

async function onActivate(row) {
  await ElMessageBox.confirm(
    `确认把生效版本切到 v${row.version}？` +
      `切换只影响之后接收的消息，已在重试队列里的任务仍按绑定版本重放。`,
    '切换生效版本',
    { type: 'warning' }
  )
  await activateVersion(current.value.id, row.version)
  ElMessage.success('已切换')
  await reloadVersions()
  await load()
}

async function onRollback() {
  await ElMessageBox.confirm(
    '确认回滚到当前生效版本之前最近的一个已发布版本？',
    '回滚',
    { type: 'warning' }
  )
  const result = await rollbackRoute(current.value.id)
  ElMessage.success(`已回滚到 v${result.activatedVersion}`)
  await reloadVersions()
  await load()
}

async function onDeleteVersion(row) {
  await ElMessageBox.confirm(`确认删除版本 v${row.version}？`, '删除版本', { type: 'warning' })
  await deleteVersion(current.value.id, row.version)
  ElMessage.success('已删除')
  await reloadVersions()
}

// ==================================================================
// 映射编辑器
// ==================================================================

function valuePlaceholder(kind) {
  if (kind === 'constant') return '常量值，如 ORDER_SYSTEM'
  if (kind === 'expression') return '表达式，如 amount * 100'
  return 'JSONPath，如 $.user.id'
}

/** 把后端的一条 MappingDef 转成编辑器行。 */
function toEditorRow(m) {
  let kind = 'source'
  let value = m.source || ''
  if (m.constant != null) {
    kind = 'constant'
    value = m.constant
  } else if (m.expression) {
    kind = 'expression'
    value = m.expression
  }
  const t = m.transform || {}
  return {
    target: m.target || '',
    kind,
    value,
    transformType: (t.type || 'string').toLowerCase(),
    pattern: t.pattern || '',
    zone: t.zone || '',
    enumMapping: t.mapping ? JSON.stringify(t.mapping) : '',
    required: !!m.required,
    defaultValue: m.defaultValue || ''
  }
}

/** 把编辑器行转回后端期望的 MappingDef 结构。 */
function toPayloadRow(r) {
  const m = { target: r.target }
  if (r.kind === 'constant') {
    m.constant = r.value
  } else if (r.kind === 'expression') {
    m.expression = r.value
  } else {
    m.source = r.value
  }
  if (r.transformType) {
    const t = { type: r.transformType }
    if (r.transformType === 'timestamp') {
      if (r.pattern) t.pattern = r.pattern
      if (r.zone) t.zone = r.zone
    }
    if (r.transformType === 'enum' && r.enumMapping) {
      t.mapping = JSON.parse(r.enumMapping)
    }
    m.transform = t
  }
  if (r.required) m.required = true
  if (r.defaultValue) m.defaultValue = r.defaultValue
  return m
}

async function openMappings(row) {
  current.value = row
  issues.value = []
  mappingVisible.value = true
  // 映射编辑器需要目标表列，先补齐
  const detail = await getRoute(row.id)
  const t = detail.target || {}
  try {
    await loadColumnsFor(t.datasourceId, t.schemaName, t.tableName)
  } catch {
    columns.value = []
  }
  const d = await getDraft(row.id)
  draft.version = d.version
  draft.status = d.status
  draft.persisted = d.persisted
  draft.changeNote = d.changeNote || ''
  draft.mappingStrategy = d.mappingStrategy || 'EXACT'
  draft.jslt = d.jslt ?? null
  mappings.value = (d.content?.mappings || []).map(toEditorRow)
}

async function loadColumnsFor(datasourceId, schemaName, tableName) {
  if (!datasourceId || !schemaName || !tableName) {
    columns.value = []
    return
  }
  columns.value = (await listColumns(datasourceId, schemaName, tableName)) || []
}

function resetMapping() {
  mappings.value = []
  issues.value = []
  draft.version = null
  draft.persisted = false
  draft.changeNote = ''
  draft.mappingStrategy = 'EXACT'
  draft.jslt = null
}

function addRow() {
  mappings.value.push({
    target: '',
    kind: 'source',
    value: '',
    transformType: 'string',
    pattern: '',
    zone: '',
    enumMapping: '',
    required: false,
    defaultValue: ''
  })
}

async function onAutoGenerate() {
  const d = await autoGenerateMappings(current.value.id)
  draft.version = d.version
  draft.status = d.status
  draft.persisted = d.persisted
  draft.changeNote = d.changeNote || ''
  draft.mappingStrategy = d.mappingStrategy || 'EXACT'
  draft.jslt = d.jslt ?? null
  mappings.value = (d.content?.mappings || []).map(toEditorRow)
  issues.value = []
  ElMessage.success(`已按目标表列生成 ${mappings.value.length} 条映射，检查后发布`)
}

/** 保存草稿。返回是否成功，供发布复用。 */
async function persistDraft() {
  let payload
  try {
    payload = mappings.value.map(toPayloadRow)
  } catch {
    ElMessage.error('enum 转换的映射表不是合法 JSON')
    return false
  }
  const d = await saveDraft(current.value.id, {
    content: {
      mappingStrategy: draft.mappingStrategy || 'EXACT',
      jslt: draft.jslt,
      mappings: payload
    },
    changeNote: draft.changeNote || '控制台编辑'
  })
  draft.version = d.version
  draft.status = d.status
  draft.persisted = d.persisted
  draft.changeNote = d.changeNote || ''
  if (d.mappingStrategy) draft.mappingStrategy = d.mappingStrategy
  draft.jslt = d.jslt
  // 版本号可能刚生成，抽屉里的列表要跟着变（但不能把抽屉弹开）
  await reloadVersions()
  return true
}

async function onSaveDraft() {
  savingMapping.value = true
  try {
    if (await persistDraft()) {
      ElMessage.success('草稿已保存')
    }
  } catch {
    // 请求层已提示
  } finally {
    savingMapping.value = false
  }
}

async function onValidate() {
  if (!(await persistDraft())) {
    return
  }
  const result = await validateVersion(current.value.id, draft.version)
  issues.value = result.issues || []
  if (result.valid) {
    ElMessage.success(issues.value.length ? '校验通过（有提示）' : '校验通过')
  } else {
    ElMessage.error('校验未通过，见下方问题清单')
  }
}

async function onPublish() {
  publishing.value = true
  try {
    if (!(await persistDraft())) {
      return
    }
    const result = await publishVersion(current.value.id, draft.version)
    issues.value = result.issues || []
    if (!result.valid) {
      ElMessage.error('发布被拦下，见下方问题清单')
      return
    }
    ElMessage.success(`v${draft.version} 已发布。点「版本」→「设为生效」即可开始消费。`)
    await reloadVersions()
  } catch {
    // 请求层已提示
  } finally {
    publishing.value = false
  }
}

function goDatasources() {
  router.push('/config/datasources')
}

onMounted(async () => {
  await loadDatasources()
  await load()
})
</script>
