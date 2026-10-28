import { reactive } from 'vue'

// 开发时由 Vite 代理到 127.0.0.1:8000，打包后默认同源访问
const BASE = import.meta.env.VITE_API_BASE || ''

export const apiState = reactive({
  online: null,
  lastError: ''
})

export class ApiError extends Error {
  constructor(message, status = 0) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export function isNetworkError(err) {
  return err instanceof ApiError && err.status === 0
}

async function request(path, options = {}) {
  let res
  try {
    res = await fetch(BASE + path, {
      headers: options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' },
      ...options
    })
  } catch (err) {
    apiState.online = false
    apiState.lastError = '后端没有响应'
    throw new ApiError('后端没有连上，请先运行 backend/start.cmd', 0)
  }

  const type = res.headers.get('content-type') || ''
  // 开发时代理连不上后端会回一个非 JSON 的 5xx，这种不算“后端在跑”
  if (res.status >= 500 && !type.includes('application/json')) {
    apiState.online = false
    apiState.lastError = '后端没有响应'
  } else {
    apiState.online = true
  }

  if (!res.ok) {
    let message = `请求失败（${res.status}）`
    try {
      const data = await res.json()
      if (data && data.detail) message = typeof data.detail === 'string' ? data.detail : JSON.stringify(data.detail)
    } catch (e) {
      // 后端返回非 JSON 时保留状态码提示
    }
    throw new ApiError(message, res.status)
  }

  if (type.includes('application/json')) return res.json()
  return res
}

function query(params = {}) {
  const q = new URLSearchParams()
  Object.keys(params).forEach((k) => {
    if (params[k] !== undefined && params[k] !== null && params[k] !== '') q.set(k, params[k])
  })
  const s = q.toString()
  return s ? `?${s}` : ''
}

export const api = {
  health() {
    return request('/api/health')
  },

  tasks(params = {}) {
    return request(`/api/tasks${query(params)}`)
  },

  task(id) {
    return request(`/api/tasks/${encodeURIComponent(id)}`)
  },

  // 结果表分页查询：page / page_size / q（空格分隔多个关键字）/ column（不传为全列）
  taskRows(id, params = {}) {
    return request(`/api/tasks/${encodeURIComponent(id)}/rows${query(params)}`)
  },

  upload(file, role = 'auto') {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('role', role)
    return request('/api/files/upload', { method: 'POST', body: fd })
  },

  analyze(payload) {
    return request('/api/tasks/analyze', { method: 'POST', body: JSON.stringify(payload) })
  },

  // 智能体配置编排：一次返回意图 + 工具调用轨迹 + 自动修正 + 经验命中
  agentPlan(payload) {
    return request('/api/agent/plan', { method: 'POST', body: JSON.stringify(payload) })
  },

  agentExperience() {
    return request('/api/agent/experience')
  },

  deleteAgentExperience(id) {
    return request(`/api/agent/experience/${encodeURIComponent(id)}`, { method: 'DELETE' })
  },

  /* ==================== 运行观测 ==================== */

  agentStats() {
    return request('/api/ops/stats')
  },

  agentTools() {
    return request('/api/ops/tools')
  },

  agentSelfCheck() {
    return request('/api/ops/self-check')
  },

  auditLogs(limit = 40) {
    return request(`/api/ops/audit${query({ limit })}`)
  },

  prepareDemo(preset) {
    return request('/api/demo/prepare', { method: 'POST', body: JSON.stringify({ preset }) })
  },

  preview(payload) {
    return request('/api/tasks/preview', { method: 'POST', body: JSON.stringify(payload) })
  },

  createTask(payload) {
    return request('/api/tasks', { method: 'POST', body: JSON.stringify(payload) })
  },

  confirmReview(id) {
    return request(`/api/tasks/${encodeURIComponent(id)}/review`, { method: 'POST' })
  },

  rerunTask(id) {
    return request(`/api/tasks/${encodeURIComponent(id)}/rerun`, { method: 'POST' })
  },

  // 用一句话改结果表：后端把话翻译成操作，重算后重新导出
  reviseTask(id, instruction) {
    return request(`/api/tasks/${encodeURIComponent(id)}/revise`, {
      method: 'POST',
      body: JSON.stringify({ instruction })
    })
  },

  undoTaskEdit(id) {
    return request(`/api/tasks/${encodeURIComponent(id)}/undo-edit`, { method: 'POST' })
  },

  deleteTasks(ids) {
    return request('/api/tasks/delete', { method: 'POST', body: JSON.stringify({ ids }) })
  },

  mappingSchemes() {
    return request('/api/mapping-schemes')
  },

  saveMappingScheme(payload) {
    return request('/api/mapping-schemes', { method: 'POST', body: JSON.stringify(payload) })
  },

  deleteMappingScheme(id) {
    return request(`/api/mapping-schemes/${encodeURIComponent(id)}`, { method: 'DELETE' })
  },

  downloadUrl(taskId, artifact) {
    return `${BASE}/api/tasks/${encodeURIComponent(taskId)}/download/${encodeURIComponent(artifact)}`
  },

  /* ==================== 智能体模式：跨系统桥接 ==================== */

  // 上传整个数据集：一个 zip，或者文件夹里选中的一批 Excel
  bridgeCreate(files, name, source = 'files') {
    const fd = new FormData()
    Array.from(files || []).forEach((file) => fd.append('files', file))
    fd.append('name', name || '')
    fd.append('source', source)
    return request('/api/bridge/datasets', { method: 'POST', body: fd })
  },

  bridgeDatasets() {
    return request('/api/bridge/datasets')
  },

  bridgeDataset(id) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}`)
  },

  // 自然语言业务指令 → 规划方案，只规划不执行
  bridgePlan(id, instruction) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/plan`, {
      method: 'POST',
      body: JSON.stringify({ instruction })
    })
  },

  bridgeRun(id) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/run`, { method: 'POST' })
  },

  // kind=ok 标准表 / kind=anomaly 异常清单
  bridgeRows(id, params = {}) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/rows${query(params)}`)
  },

  bridgeRules() {
    return request('/api/bridge/rules')
  },

  bridgeAddRule(standard, alias) {
    return request('/api/bridge/rules', { method: 'POST', body: JSON.stringify({ standard, alias }) })
  },

  bridgeDeleteRule(alias) {
    return request(`/api/bridge/rules${query({ alias })}`, { method: 'DELETE' })
  },

  bridgeStandardFields() {
    return request('/api/bridge/standard-fields')
  },

  bridgeDownloadUrl(id, artifact) {
    return `${BASE}/api/bridge/datasets/${encodeURIComponent(id)}/download/${encodeURIComponent(artifact)}`
  },

  /* ==================== 表格智作 ==================== */

  // 我的表格：名称或列名模糊匹配
  sheets(params = {}) {
    return request(`/api/sheets${query(params)}`)
  },

  sheetTemplates() {
    return request('/api/sheets/templates')
  },

  // 所有表格的操作日志，按时间倒序
  sheetLogs(limit = 60) {
    return request(`/api/sheets/logs${query({ limit })}`)
  },

  sheet(id) {
    return request(`/api/sheets/${encodeURIComponent(id)}`)
  },

  // 表格分页查询：page / page_size / q（空格分隔多个关键字）/ column
  sheetRows(id, params = {}) {
    return request(`/api/sheets/${encodeURIComponent(id)}/rows${query(params)}`)
  },

  // 一次上传一个或多个 Excel（xlsx / xls）
  uploadSheets(files) {
    const fd = new FormData()
    Array.from(files || []).forEach((file) => fd.append('files', file))
    return request('/api/sheets/upload', { method: 'POST', body: fd })
  },

  sheetFromTemplate(templateId, name) {
    return request('/api/sheets/from-template', {
      method: 'POST',
      body: JSON.stringify({ template_id: templateId, name })
    })
  },

  sheetBlank(name, columns) {
    return request('/api/sheets/blank', { method: 'POST', body: JSON.stringify({ name, columns }) })
  },

  // 一句话建表 / 多表融合：sheet_ids 传了就在这几张表之间做
  sheetCompose(instruction, sheetIds = []) {
    return request('/api/sheets/compose', {
      method: 'POST',
      body: JSON.stringify({ instruction, sheet_ids: sheetIds })
    })
  },

  reviseSheet(id, instruction) {
    return request(`/api/sheets/${encodeURIComponent(id)}/revise`, {
      method: 'POST',
      body: JSON.stringify({ instruction })
    })
  },

  updateSheetCell(id, payload) {
    return request(`/api/sheets/${encodeURIComponent(id)}/cell`, {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },

  addSheetRow(id) {
    return request(`/api/sheets/${encodeURIComponent(id)}/rows`, { method: 'POST' })
  },

  deleteSheetRow(id, row) {
    return request(`/api/sheets/${encodeURIComponent(id)}/rows/${encodeURIComponent(row)}`, {
      method: 'DELETE'
    })
  },

  addSheetColumn(id, name, value = '') {
    return request(`/api/sheets/${encodeURIComponent(id)}/columns`, {
      method: 'POST',
      body: JSON.stringify({ name, value })
    })
  },

  renameSheet(id, name) {
    return request(`/api/sheets/${encodeURIComponent(id)}/rename`, {
      method: 'POST',
      body: JSON.stringify({ name })
    })
  },

  undoSheet(id) {
    return request(`/api/sheets/${encodeURIComponent(id)}/undo`, { method: 'POST' })
  },

  deleteSheet(id) {
    return request(`/api/sheets/${encodeURIComponent(id)}`, { method: 'DELETE' })
  },

  sheetDownloadUrl(id) {
    return `${BASE}/api/sheets/${encodeURIComponent(id)}/download`
  }
}
