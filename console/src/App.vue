<template>
  <!-- 登录(FR-853):沿用 MySQL 账号体系,不引入 OAuth -->
  <div v-if="!token" class="login-wrap">
    <el-card class="login-card" shadow="always">
      <div class="tang-logo" style="font-size: 22px; margin-bottom: 6px">tang_db 控制台</div>
      <div style="color: #909399; font-size: 12px; margin-bottom: 18px">
        自研 HTAP + AI 原生数据库 · P8
      </div>
      <el-form :model="loginForm" label-position="top" @submit.prevent="doLogin">
        <el-form-item label="用户名">
          <el-input v-model="loginForm.username" placeholder="root" autocomplete="username" />
        </el-form-item>
        <el-form-item label="口令">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="tangdb"
            show-password
            autocomplete="current-password"
            @keyup.enter="doLogin"
          />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loggingIn" @click="doLogin">
          登录
        </el-button>
      </el-form>
      <el-alert
        v-if="loginError"
        type="error"
        :closable="false"
        show-icon
        style="margin-top: 14px"
        :title="loginError"
      />
    </el-card>
  </div>

  <!-- 主界面:左树 / 上 SQL / 右下结果 / 右状态(FR-854 ~ FR-858) -->
  <el-container v-else class="console-shell">
    <el-header height="52px" class="console-header">
      <span class="tang-logo">tang_db 控制台</span>
      <span class="header-sep">/</span>
      <span style="font-size: 12px; color: #909399">{{ serverVersion }}</span>
      <div style="flex: 1"></div>
      <el-tag v-if="status.be_alive" type="success" size="small" effect="dark">BE 在线</el-tag>
      <el-tag v-else type="danger" size="small" effect="dark">BE 离线</el-tag>
      <span style="font-size: 12px; color: #909399; margin: 0 10px">
        会话 #{{ sessionId || '-' }}
      </span>
      <el-button size="small" @click="refreshAll">刷新</el-button>
      <el-button size="small" type="danger" plain @click="doLogout">退出</el-button>
    </el-header>

    <el-container style="height: calc(100% - 52px)">
      <!-- ① 左侧库/表树 -->
      <el-aside width="290px" class="console-aside">
        <div class="panel-title">
          库 / 表
          <el-button link size="small" @click="loadTree">重新加载</el-button>
        </div>
        <el-tree
          v-loading="loadingTree"
          :data="treeData"
          :props="{ label: 'label', children: 'children' }"
          node-key="id"
          default-expand-all
          highlight-current
          @node-click="onNodeClick"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <span class="tree-kind">{{
                data.type === 'db' ? '▣' : data.type === 'table' ? '▤' : data.type === 'index' ? '⚿' : '·'
              }}</span>
              <span>{{ data.label }}</span>
              <span v-if="data.tag" class="tree-tag">{{ data.tag }}</span>
            </span>
          </template>
        </el-tree>
        <el-divider style="margin: 10px 0" />
        <div class="panel-title" style="margin-bottom: 6px">DDL</div>
        <el-input
          type="textarea"
          :rows="8"
          readonly
          class="mono"
          placeholder="点击左侧表名查看 SHOW CREATE TABLE"
          v-model="ddlText"
        />
      </el-aside>

      <el-container>
        <el-main class="console-main">
          <!-- ② SQL 输入 -->
          <div class="sql-box">
            <el-input
              ref="sqlInput"
              v-model="sql"
              type="textarea"
              :rows="6"
              class="sql-input"
              placeholder="输入 SQL(支持多语句,分号分隔)。Ctrl+Enter 执行 / Ctrl+Shift+Enter 看执行计划"
              @keydown="onEditorKeydown"
            />
            <div class="sql-actions">
              <el-button type="primary" :loading="running" @click="runSql">执行 (Ctrl+Enter)</el-button>
              <el-button :loading="explaining" @click="runExplain">执行计划</el-button>
              <el-button @click="sql = ''">清空</el-button>
              <span v-if="writeHint" class="write-hint">
                写操作({{ writeHint }})需要二次确认
              </span>
              <div style="flex: 1"></div>
              <span v-if="lastElapsed !== null" class="write-hint">
        耗时 {{ lastElapsed }} ms
              </span>
            </div>
          </div>

          <!-- 错误展示:错误码 + 文案 + 会话 id(FR-860) -->
          <el-alert
            v-if="errorInfo"
            type="error"
            :closable="true"
            show-icon
            style="margin: 8px 0"
            :title="`错误 ${errorInfo.code}: ${errorInfo.message}`"
            :description="`会话 id: ${errorInfo.sessionId ?? '-'}(可与服务端日志对齐)`"
            @close="errorInfo = null"
          />

          <!-- ③ 结果表格 -->
          <el-tabs v-if="results.length" v-model="activeTab" type="card" class="result-tabs">
            <el-tab-pane
              v-for="(result, index) in results"
              :key="index"
              :name="String(index)"
              :label="resultLabel(result, index)"
            >
              <template v-if="result.kind === 'rows'">
                <div class="result-toolbar">
                  <span class="write-hint">
                    {{ result.row_count }} 行 · 每页 {{ pageSize }} 行
                    <span v-if="result.truncated" style="color: #e6a23c">
                      (超出上限已截断,请加 LIMIT)
                    </span>
                  </span>
                  <div style="flex: 1"></div>
                  <el-button size="small" @click="copyCsv(result)">复制 CSV</el-button>
                </div>
                <el-table
                  :data="pageRows(result)"
                  border
                  stripe
                  size="small"
                  height="330"
                  style="width: 100%"
                >
                  <el-table-column
                    v-for="(column, columnIndex) in result.columns"
                    :key="column + columnIndex"
                    :prop="String(columnIndex)"
                    :label="column"
                    sortable
                    min-width="120"
                    show-overflow-tooltip
                  >
                    <template #default="{ row }">
                      <span v-if="row[columnIndex] === null" class="null-cell">NULL</span>
                      <span v-else>{{ row[columnIndex] }}</span>
                    </template>
                  </el-table-column>
                </el-table>
                <el-pagination
                  v-if="result.row_count > pageSize"
                  style="margin-top: 8px; justify-content: flex-end"
                  layout="total, sizes, prev, pager, next"
                  :total="result.row_count"
                  :page-size="pageSize"
                  :current-page="pageOf(result)"
                  :page-sizes="[100, 200, 500, 1000]"
                  @current-change="(page) => setPage(result, page)"
                  @size-change="(size) => setPageSize(result, size)"
                />
              </template>
              <template v-else>
                <el-alert type="success" :closable="false" show-icon :title="result.message" />
              </template>
            </el-tab-pane>
          </el-tabs>
          <el-empty v-else description="还没有执行结果" :image-size="70" />
        </el-main>

        <!-- ④ 自身状态(FR-858) -->
        <el-aside width="340px" class="console-aside">
          <div class="panel-title">
            自身状态
            <span class="tree-tag">{{ status.time || '' }}</span>
          </div>
          <el-row :gutter="8">
            <el-col :span="12">
              <el-card shadow="never" class="status-card">
                <div class="status-value">{{ fmt(status.qps) }}</div>
                <div class="status-label">QPS(1 分钟窗口)</div>
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card shadow="never" class="status-card">
                <div class="status-value">{{ fmt(status.active_sessions) }}</div>
                <div class="status-label">活跃会话</div>
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card shadow="never" class="status-card">
                <div class="status-value">{{ fmt(status.p95_ms) }}</div>
                <div class="status-label">P95 (ms)</div>
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card shadow="never" class="status-card">
                <div class="status-value">{{ fmt(status.p99_ms) }}</div>
                <div class="status-label">P99 (ms)</div>
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card shadow="never" class="status-card">
                <div class="status-value">{{ fmtBytes(status.memtable_bytes) }}</div>
                <div class="status-label">memtable</div>
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card shadow="never" class="status-card">
                <div class="status-value">{{ fmt(status.segment_count) }}</div>
                <div class="status-label">
                  segment 数({{ fmtBytes(status.segment_bytes) }})
                </div>
              </el-card>
            </el-col>
          </el-row>

          <el-divider style="margin: 6px 0 10px" />
          <div class="panel-title" style="margin-bottom: 6px">
            慢查询 Top10
            <span class="tree-tag">共 {{ status.slow_query_count || 0 }} 条</span>
          </div>
          <el-table :data="status.slow_queries || []" size="small" border height="150">
            <el-table-column prop="duration_ms" label="ms" width="62" />
            <el-table-column prop="rows_returned" label="行" width="56" />
            <el-table-column prop="sql_text" label="SQL" show-overflow-tooltip />
          </el-table>

          <div class="panel-title" style="margin: 12px 0 6px">向量索引</div>
          <el-table :data="status.vector_indexes || []" size="small" border height="120">
            <el-table-column prop="index_name" label="索引" width="110" />
            <el-table-column prop="state" label="状态" width="80" />
            <el-table-column prop="node_count" label="节点" />
            <el-table-column prop="segment_count" label="段" width="56" />
          </el-table>
        </el-aside>
      </el-container>
    </el-container>

    <!-- 执行计划(FR-857) -->
    <el-dialog v-model="explainVisible" title="执行计划(EXPLAIN)" width="760px">
      <pre class="advice-text">{{ explainText }}</pre>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getToken, setToken, toCsv, writeKeyword } from './api'

const token = ref(getToken())
const loginForm = reactive({ username: 'root', password: '' })
const loggingIn = ref(false)
const loginError = ref('')
const sessionId = ref(null)
const serverVersion = ref('')

const treeData = ref([])
const loadingTree = ref(false)
const ddlText = ref('')

const sql = ref('SELECT * FROM information_schema.TABLES LIMIT 20')
const running = ref(false)
const explaining = ref(false)
const writeHint = computed(() => writeKeyword(sql.value))

const results = ref([])
const activeTab = ref('0')
const lastElapsed = ref(null)
const errorInfo = ref(null)

const pageSize = ref(100)
const pages = reactive({})
const pageSizes = reactive({})

const status = reactive({
  be_alive: false,
  qps: 0,
  p95_ms: 0,
  p99_ms: 0,
  p50_ms: 0,
  memtable_bytes: 0,
  segment_count: 0,
  segment_bytes: 0,
  slow_query_count: 0,
  active_sessions: 0,
  slow_queries: [],
  vector_indexes: [],
  time: ''
})

const explainVisible = ref(false)
const explainText = ref('')

let statusTimer = null

onMounted(async () => {
  if (token.value) {
    await refreshAll()
    startStatusTimer()
  }
})

onBeforeUnmount(stopStatusTimer)

function startStatusTimer() {
  stopStatusTimer()
  statusTimer = setInterval(loadStatus, 5000)
}

function stopStatusTimer() {
  if (statusTimer) {
    clearInterval(statusTimer)
    statusTimer = null
  }
}

async function doLogin() {
  loggingIn.value = true
  loginError.value = ''
  try {
    const response = await api.login(loginForm.username, loginForm.password)
    setToken(response.token)
    token.value = response.token
    sessionId.value = response.session_id
    ElMessage.success('登录成功')
    await refreshAll()
    startStatusTimer()
  } catch (e) {
    loginError.value = e.message || '登录失败'
  } finally {
    loggingIn.value = false
  }
}

async function doLogout() {
  try {
    await api.logout()
  } catch (e) {
    // 忽略:退出失败也要让本地 token 失效
  }
  setToken('')
  token.value = ''
  results.value = []
  explainText.value = ''
  stopStatusTimer()
}

async function refreshAll() {
  await Promise.all([loadTree(), loadStatus()])
}

async function loadTree() {
  loadingTree.value = true
  try {
    const response = await api.tree()
    treeData.value = (response.databases || []).map((db) => ({
      id: 'db:' + db.name,
      label: db.name,
      type: 'db',
      tag: `${(db.tables || []).length} 表`,
      children: (db.tables || []).map((table) => ({
        id: 'table:' + db.name + '.' + table.name,
        label: table.name,
        type: 'table',
        tag: `ID ${table.table_id}`,
        ddl: table.ddl,
        children: [
          ...(table.columns || []).map((column) => ({
            id: `col:${db.name}.${table.name}.${column.name}`,
            label: `列 ${column.name} ${column.type}`,
            type: 'column',
            tag: column.primary_key ? 'PK' : column.nullable ? '' : 'NOT NULL'
          })),
          ...(table.indexes || []).map((index) => ({
            id: `idx:${db.name}.${table.name}.${index.name}`,
            label: `${index.vector ? '向量索引' : '索引'} ${index.name}(${index.column})`,
            type: 'index',
            tag: index.unique ? 'UNIQUE' : ''
          }))
        ]
      }))
    }))
  } catch (e) {
    if (e.status === 401) {
      handleUnauthorized()
    } else {
      ElMessage.error('加载库表树失败: ' + (e.message || ''))
    }
  } finally {
    loadingTree.value = false
  }
}

function onNodeClick(data) {
  if (data.type === 'table') {
    ddlText.value = data.ddl || ''
    const parts = data.id.replace('table:', '').split('.')
    sql.value = `SELECT * FROM \`${parts[0]}\`.\`${parts[1]}\` LIMIT 100;`
  }
}

async function loadStatus() {
  if (!token.value) {
    return
  }
  try {
    const response = await api.status()
    Object.assign(status, response)
    serverVersion.value = response.server_version || serverVersion.value
    if (sessionId.value === null && response.active_sessions !== undefined) {
      // 会话 id 由 /api/login 给出;刷新页面后从 query 响应补
    }
  } catch (e) {
    if (e.status === 401) {
      handleUnauthorized()
      return
    }
    status.be_alive = false
  }
}

function handleUnauthorized() {
  ElMessage.warning('登录已失效,请重新登录')
  setToken('')
  token.value = ''
  stopStatusTimer()
}

function onEditorKeydown(event) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    if (event.shiftKey) {
      runExplain()
    } else {
      runSql()
    }
  }
}

async function runSql() {
  const text = sql.value.trim()
  if (!text) {
    ElMessage.warning('SQL 不能为空')
    return
  }
  const keyword = writeKeyword(text)
  let confirmed = false
  if (keyword) {
    // FR-859:写操作必须显式确认,确认后才带 confirm=true 再发一次。
    try {
      await ElMessageBox.confirm(
        `这条语句是写操作(${keyword}),会修改数据库。确认执行?`,
        '二次确认',
        { confirmButtonText: '确认执行', cancelButtonText: '取消', type: 'warning' }
      )
      confirmed = true
    } catch (e) {
      ElMessage.info('已取消执行')
      return
    }
  }
  running.value = true
  errorInfo.value = null
  try {
    const response = await api.query(text, confirmed)
    results.value = response.results || []
    activeTab.value = '0'
    lastElapsed.value = response.elapsed_ms
    sessionId.value = response.session_id
    for (const result of results.value) {
      pages[resultKey(result)] = 1
    }
    if (response.truncated) {
      ElMessage.warning(response.hint || '结果被截断,请加 LIMIT')
    }
    if (keyword) {
      await Promise.all([loadTree(), loadStatus()])
    }
  } catch (e) {
    if (e.status === 401) {
      handleUnauthorized()
      return
    }
    errorInfo.value = {
      code: e.code ?? 0,
      message: e.message || '执行失败',
      sessionId: e.sessionId ?? sessionId.value
    }
    results.value = []
  } finally {
    running.value = false
  }
}

async function runExplain() {
  const text = sql.value.trim().replace(/;\s*$/, '')
  if (!text) {
    ElMessage.warning('SQL 不能为空')
    return
  }
  explaining.value = true
  errorInfo.value = null
  try {
    const response = await api.explain(text)
    explainText.value = response.plan || '(空计划)'
    explainVisible.value = true
    sessionId.value = response.session_id
  } catch (e) {
    if (e.status === 401) {
      handleUnauthorized()
      return
    }
    errorInfo.value = {
      code: e.code ?? 0,
      message: e.message || 'EXPLAIN 失败',
      sessionId: e.sessionId ?? sessionId.value
    }
  } finally {
    explaining.value = false
  }
}

function resultKey(result) {
  return result.columns ? result.columns.join('|') + result.row_count : 'message'
}

function pageOf(result) {
  return pages[resultKey(result)] || 1
}

function pageRows(result) {
  const size = pageSizes[resultKey(result)] || pageSize.value
  const page = pageOf(result)
  return (result.rows || []).slice((page - 1) * size, page * size)
}

function setPage(result, page) {
  pages[resultKey(result)] = page
}

function setPageSize(result, size) {
  pageSizes[resultKey(result)] = size
  pages[resultKey(result)] = 1
}

function resultLabel(result, index) {
  if (result.kind === 'rows') {
    return `结果 ${index + 1}(${result.row_count} 行)`
  }
  return `结果 ${index + 1}(提示)`
}

async function copyCsv(result) {
  const csv = toCsv(result.columns || [], result.rows || [])
  try {
    await navigator.clipboard.writeText(csv)
    ElMessage.success('已复制 CSV(当前结果集全部行)')
  } catch (e) {
    const textarea = document.createElement('textarea')
    textarea.value = csv
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    ElMessage.success('已复制 CSV')
  }
}

function fmt(value) {
  if (value === null || value === undefined) {
    return '-'
  }
  if (typeof value === 'number') {
    return Number.isInteger(value) ? String(value) : value.toFixed(2)
  }
  return String(value)
}

function fmtBytes(value) {
  const number = Number(value || 0)
  if (number < 1024) {
    return number + ' B'
  }
  if (number < 1024 * 1024) {
    return (number / 1024).toFixed(1) + ' KB'
  }
  if (number < 1024 * 1024 * 1024) {
    return (number / 1024 / 1024).toFixed(1) + ' MB'
  }
  return (number / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}
</script>

<style scoped>
.login-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2c3e50 45%, #34495e 100%);
}

.login-card {
  width: 360px;
}

.console-shell {
  height: 100%;
}

.console-header {
  display: flex;
  align-items: center;
  background: #1f2d3d;
  color: #fff;
  padding: 0 16px;
}

.console-header .tang-logo {
  color: #fff;
}

.header-sep {
  margin: 0 8px;
  color: #7f8c8d;
}

.console-aside {
  background: #fff;
  border-right: 1px solid #ebeef5;
  padding: 10px;
  overflow: auto;
}

.console-main {
  padding: 10px;
  overflow: auto;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.sql-box {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px;
}

.sql-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.write-hint {
  font-size: 12px;
  color: #909399;
}

.result-tabs {
  margin-top: 10px;
}

.result-toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 6px;
}
</style>
