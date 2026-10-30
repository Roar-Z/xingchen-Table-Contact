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
    apiState.lastError = '服务连接中断'
    throw new ApiError('暂时连不上服务，请检查网络后刷新页面重试', 0)
  }

  const type = res.headers.get('content-type') || ''
  // 开发时代理连不上后端会回一个非 JSON 的 5xx，这种不算“后端在跑”
  if (res.status >= 500 && !type.includes('application/json')) {
    apiState.online = false
    apiState.lastError = '服务连接中断'
  } else {
    apiState.online = true
  }

  if (!res.ok) {
    let message = statusText(res.status)
    try {
      const data = await res.json()
      // 后端自己给的中文提示（比如「至少要两列」）比状态码更好懂，优先用它
      if (data && data.detail) message = typeof data.detail === 'string' ? data.detail : JSON.stringify(data.detail)
    } catch (e) {
      // 后端返回非 JSON 时用上面的通用提示
    }
    throw new ApiError(message, res.status)
  }

  if (type.includes('application/json')) return res.json()
  return res
}

// 出错时的中文说明：按市面常见的做法给结论 + 下一步动作，不把状态码丢给用户看。
function statusText(status) {
  if (status === 400) return '提交的内容有问题，请检查后重试'
  if (status === 401 || status === 403) return '没有权限执行该操作'
  if (status === 404) return '内容不存在，可能已被删除'
  if (status === 409) return '数据已变化，请刷新后重试'
  if (status === 413) return '文件太大，请压缩后重新上传'
  if (status === 415) return '文件格式不支持，请换一个文件'
  if (status === 429) return '操作太频繁，请稍等一下再试'
  if (status >= 500) return '服务出了点问题，请稍后重试'
  return '操作没有成功，请稍后重试'
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

  // ==================== 运行观测 ====================

  agentStats() {
    return request('/api/ops/stats')
  },

  agentTools() {
    return request('/api/ops/tools')
  },

  agentSelfCheck() {
    return request('/api/ops/self-check')
  },

  // 运行记录与审计日志都走服务端分页，本地各留 100 条
  agentRuns(page = 1, pageSize = 10) {
    return request(`/api/ops/runs${query({ page, page_size: pageSize })}`)
  },

  auditLogs(page = 1, pageSize = 20) {
    return request(`/api/ops/audit${query({ page, page_size: pageSize })}`)
  },

  // 运行观测的清理：后端要求带上确认文字，避免误触或脚本直连
  clearAgentRuns(confirm) {
    return request('/api/ops/runs/clear', { method: 'POST', body: JSON.stringify({ confirm }) })
  },

  clearAuditLogs(confirm) {
    return request('/api/ops/audit/clear', { method: 'POST', body: JSON.stringify({ confirm }) })
  },

  // 删除所有表格数据：整库清空，表格文件、表格日志和撤销快照一起删
  clearAllSheets(confirm) {
    return request('/api/ops/sheets/clear', { method: 'POST', body: JSON.stringify({ confirm }) })
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

  // ==================== 智能体模式：跨系统桥接 ====================

  // 上传整个数据集：一个 zip，或者文件夹里选中的一批 Excel
  bridgeCreate(files, name, source = 'files', access = 'auto') {
    const fd = new FormData()
    Array.from(files || []).forEach((file) => fd.append('files', file))
    fd.append('name', name || '')
    fd.append('source', source)
    // 可执行权限在上传时就定下来：手动审批要赶在“方案出完”之前生效
    fd.append('access', access || 'auto')
    return request('/api/bridge/datasets', { method: 'POST', body: fd })
  },

  bridgeDatasets() {
    return request('/api/bridge/datasets')
  },

  bridgeDataset(id) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}`)
  },

  // 清理最近记录：ids 为空数组表示清空全部；返回删除条数与剩余列表
  bridgeDelete(ids = []) {
    return request('/api/bridge/datasets/delete', {
      method: 'POST',
      body: JSON.stringify({ ids })
    })
  },

  // 全自主处理：推断目标 → 规划 → 执行 → 按验收标准自检 → 修正/回滚，一次请求跑完
  // goal 可空：空表示让智能体按数据结构自己推断（会优先沿用同类数据集上次达标的目标）
  bridgeAuto(id, goal) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/auto`, {
      method: 'POST',
      body: JSON.stringify({ instruction: goal || '' })
    })
  },

  // 真实进度：since 是已经拿到的事件序号，只返回之后的新事件
  bridgeEvents(id, since = 0) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/events?since=${since}`)
  },

  // 取消这一单：只置标记，当前这一步结束后停止，已跑出的结果照常交付
  bridgeCancel(id) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
  },

  // 可执行权限：manual 手动审批 / auto 自动审批 / full 完全访问
  bridgeAccess(id, access) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/access`, {
      method: 'POST',
      body: JSON.stringify({ access })
    })
  },

  // 手动审批的确认执行：方案已就绪，点完直接开跑
  bridgeApprove(id) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/approve`, { method: 'POST' })
  },

  // 会话式追问：只是问就只回答，带改动会改完方案重跑
  bridgeChat(id, instruction) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/chat`, {
      method: 'POST',
      body: JSON.stringify({ instruction })
    })
  },

  // kind=ok 标准表 / kind=anomaly 异常清单
  bridgeRows(id, params = {}) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/rows${query(params)}`)
  },

  // ---------------- 知识库：用户上传的制度、口径说明、对照表 ----------------
  // 上传多份文档（Word / Excel / PDF / 文本），后端取字、切片、入本地库
  bridgeKnowledgeUpload(files) {
    const fd = new FormData()
    Array.from(files || []).forEach((file) => fd.append('files', file))
    return request('/api/bridge/knowledge', { method: 'POST', body: fd })
  },

  bridgeKnowledge() {
    return request('/api/bridge/knowledge')
  },

  bridgeRemoveKnowledge(id) {
    return request(`/api/bridge/knowledge/${encodeURIComponent(id)}`, { method: 'DELETE' })
  },

  // 试检索：给一句业务目标，看看会引用到哪几段（用来验证文档真的被用上了）
  bridgeSearchKnowledge(q) {
    return request(`/api/bridge/knowledge/search${query({ q })}`)
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

  // 智能体模式的经验记忆：跑成功的配置会自动沉淀，规划时优先复用
  bridgeExperiences() {
    return request('/api/bridge/experiences')
  },

  bridgeDeleteExperience(id) {
    return request(`/api/bridge/experiences${query({ id })}`, { method: 'DELETE' })
  },

  // 人工给经验打分：有用 / 不适用。连续两次不适用会下线
  bridgeFeedbackExperience(id, useful) {
    return request(`/api/bridge/experiences/${encodeURIComponent(id)}/feedback`, {
      method: 'POST',
      body: JSON.stringify({ useful })
    })
  },

  // 打开导出文件所在文件夹（Windows 会定位到该文件），返回服务端实际使用的目录
  bridgeReveal(id, artifact) {
    return request(`/api/bridge/datasets/${encodeURIComponent(id)}/reveal`, {
      method: 'POST',
      body: JSON.stringify({ artifact: artifact || '' })
    })
  },

  // 目录浏览：不给 path 就列默认导出目录 + 磁盘根，给了就列子目录
  bridgeBrowse(path) {
    const suffix = path ? `?path=${encodeURIComponent(path)}` : ''
    return request(`/api/bridge/export-dir/browse${suffix}`)
  },

  // 选择器里新建文件夹：在 path 下建一个名叫 name 的目录，回 { path, error }
  bridgeMkdir(path, name) {
    return request('/api/bridge/export-dir/mkdir', {
      method: 'POST',
      body: JSON.stringify({ path, name })
    })
  },

  // 选择器里右键重命名：把 path 这个目录改名成 name，回 { path, error }
  bridgeRename(path, name) {
    return request('/api/bridge/export-dir/rename', {
      method: 'POST',
      body: JSON.stringify({ path, name })
    })
  },

  // 导出位置：custom 为用户自定义目录（空=用默认）。
  bridgeExportDir() {
    return request('/api/bridge/export-dir')
  },

  // 设置导出目录：传空字符串恢复默认（前端目录下的 downloads）；
  // 带上 dataset_id 时，后端会把这一单已交付的导出文件搬到新目录，不重跑
  bridgeSetExportDir(dir, datasetId = '') {
    return request('/api/bridge/export-dir', {
      method: 'POST',
      body: JSON.stringify({ dir: dir || '', dataset_id: datasetId || '' })
    })
  },

  // ==================== 表格智作 ====================

  // 我的表格：名称或列名模糊匹配
  sheets(params = {}) {
    return request(`/api/sheets${query(params)}`)
  },

  // 仓库列表服务端分页（一页 10 张），同时返回全库汇总
  sheetPage({ page = 1, pageSize = 10, q = '' } = {}) {
    return request(`/api/sheets/page${query({ page, page_size: pageSize, q })}`)
  },

  // 所有表格的操作日志，按时间倒序分页；服务端只保留最近 100 条
  sheetLogs(page = 1, pageSize = 10) {
    return request(`/api/sheets/logs${query({ page, page_size: pageSize })}`)
  },

  // 删日志：传 ids 只删选中的，传空数组清空全部
  deleteSheetLogs(ids = []) {
    return request('/api/sheets/logs/delete', {
      method: 'POST',
      body: JSON.stringify({ ids })
    })
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

  // 批量删除：仓库列表勾选多张后一次删掉
  deleteSheets(ids = []) {
    return request('/api/sheets/delete', { method: 'POST', body: JSON.stringify({ ids }) })
  },

  sheetDownloadUrl(id) {
    return `${BASE}/api/sheets/${encodeURIComponent(id)}/download`
  }
}
