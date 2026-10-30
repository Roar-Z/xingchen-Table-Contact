// 离线模式：连不上后端时，前端自己把「能离线做的那部分」做完。
export const OFFLINE_HINT =
  '当前离线模式：Excel（含老版 .xls）、CSV 在本地解析，字段按内置别名词典对齐，表格智作可按规则建表/合并并下载 CSV；AI 规划与大模型问答暂不可用。'

// ==================== 标准字段别名词典 ====================
const STD = [
  { label: '工号', aliases: ['工号', '员工编号', '人员编号', '员工号', '职员编号', '人员代码', 'empno', 'emp_no', 'employeeid', 'employee_id'] },
  { label: '姓名', aliases: ['姓名', '员工姓名', '人员姓名', '职员名称', '职员姓名', '申请人', '员工', 'name'] },
  { label: '部门', aliases: ['部门', '部门名称', '所属部门', '组织', '班组', '车间'] },
  { label: '职位', aliases: ['职位', '岗位', '职务', '职位名称', '岗位名称'] },
  { label: '入职日期', aliases: ['入职日期', '入职时间', '到岗日期', '录用日期', '进厂日期', '聘用日期', '入职年月'] },
  { label: '在职状态', aliases: ['在职状态', '员工状态', '人员状态', '在职'] },
  { label: '基本工资', aliases: ['基本工资', '基本薪资', '底薪', '岗位工资', '月薪', '标准工资'] },
  { label: '绩效', aliases: ['绩效', '绩效工资', '绩效奖金', '考核工资'] },
  { label: '补贴', aliases: ['补贴', '津贴', '补助', '餐补', '交通补贴'] },
  { label: '扣款', aliases: ['扣款', '代扣', '个税', '社保', '公积金', '扣减'] },
  { label: '应发工资', aliases: ['应发工资', '应发合计', '应付工资', '应发'] },
  { label: '实发工资', aliases: ['实发工资', '实发合计', '到手工资', '实发'] },
  { label: '加班小时', aliases: ['加班小时', '加班时长', '申请加班', '加班时数', 'overtime'] },
  { label: '请假类型', aliases: ['请假类型', '假别', '缺勤类别', '假期类型'] },
  { label: '开始日期', aliases: ['开始日期', '起始日期', '请假开始日期', '加班日期'] },
  { label: '结束日期', aliases: ['结束日期', '截止日期', '终止日期', '请假结束日期'] },
  { label: '天数', aliases: ['天数', '天', '请假天数', '合计天数', '休假天数'] },
  { label: '请假小时', aliases: ['请假小时', '小时', '合计小时'] },
  { label: '年假余额', aliases: ['年假余额', '剩余年假', '可用年假'] },
  { label: '金额', aliases: ['金额', '合计金额', '费用'] }
]

// 归一化：忽略空格、下划线、中英文标点和全角，只留下字面，避免「工号 / 工 号 / 工号（必填）」对不上
function norm(text) {
  return String(text == null ? '' : text)
    .toLowerCase()
    .replace(/[\s_\-/\\（）()【】\[\]「」『』.。,，:：;；!！?？*#@]+/g, '')
}

function stdOf(name) {
  const key = norm(name)
  if (!key) return null
  return STD.find((item) => item.aliases.some((alias) => norm(alias) === key)) || null
}

// ==================== 读文件 ====================

const MAX_PREVIEW_ROWS = 3000

function toTable(name, rawRows) {
  const rows = rawRows
    .map((row) => (row || []).map((cell) => String(cell == null ? '' : cell).trim()))
    .filter((row) => row.some((cell) => cell))
  if (!rows.length) throw new Error('这个文件里没有读到内容，换一份再试')
  // 有的表第一行是标题（「2026年休假明细 2月」），真正的表头在下一两行；
  // 首行只有一格有字时，往下找第一行有多列内容的当表头，避免把标题当列名、整表错位
  let head = 0
  if (rows[0].filter((cell) => cell).length < 2) {
    const found = rows.slice(0, 10).findIndex((row) => row.filter((cell) => cell).length >= 2)
    if (found > 0) head = found
  }
  const width = rows.slice(head).reduce((max, row) => Math.max(max, row.length), 0)
  // 整列都是空表头的列直接丢掉，模板里带出来的空列不该出现在映射里
  const columns = []
  for (let i = 0; i < width; i += 1) {
    const label = rows[head][i] || ''
    columns.push(label || `第${i + 1}列`)
  }
  const keep = columns.map((label, i) => (rows.slice(head + 1).some((row) => (row[i] || '') !== '') || label !== `第${i + 1}列`))
  const finalColumns = columns.filter((label, i) => keep[i])
  const body = rows.slice(head + 1, head + MAX_PREVIEW_ROWS + 1)
    .map((row) => columns.map((label, i) => (keep[i] ? row[i] || '' : '')))
  const finalRows = body.map((row) => row.filter((cell, i) => keep[i]))
  return { name, columns: finalColumns, rows: finalRows }
}

// CSV/TSV：手写一个最小解析，支持引号包裹和引号内的分隔符/换行。
function splitRows(text, delimiter) {
  const rows = []
  let row = []
  let value = ''
  let quoted = false
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i]
    if (quoted) {
      if (ch === '"') {
        if (text[i + 1] === '"') {
          value += '"'
          i += 1
        } else {
          quoted = false
        }
      } else {
        value += ch
      }
      continue
    }
    if (ch === '"') {
      quoted = true
    } else if (ch === delimiter) {
      row.push(value)
      value = ''
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i += 1
      row.push(value)
      rows.push(row)
      row = []
      value = ''
    } else {
      value += ch
    }
  }
  if (value || row.length) {
    row.push(value)
    rows.push(row)
  }
  return rows
}

function detectDelimiter(text) {
  const line = (text.split(/\r?\n/)[0] || '')
  const tab = line.split('\t').length
  const comma = line.split(',').length
  const semi = line.split(';').length
  if (tab > 1 && tab >= comma) return '\t'
  return semi > comma ? ';' : ','
}

// ---------- xlsx：浏览器解 zip，读 sharedStrings + sheet1 ----------

async function zipEntries(buffer) {
  const view = new DataView(buffer)
  let eocd = -1
  const floor = Math.max(0, view.byteLength - 65558)
  for (let i = view.byteLength - 22; i >= floor; i -= 1) {
    if (view.getUint32(i, true) === 0x06054b50) {
      eocd = i
      break
    }
  }
  if (eocd < 0) throw new Error('不是有效的 xlsx 文件（打不开压缩结构）')
  const count = view.getUint16(eocd + 10, true)
  let cursor = view.getUint32(eocd + 16, true)
  const list = []
  for (let i = 0; i < count; i += 1) {
    if (view.getUint32(cursor, true) !== 0x02014b50) break
    const method = view.getUint16(cursor + 10, true)
    const compSize = view.getUint32(cursor + 20, true)
    const nameLen = view.getUint16(cursor + 28, true)
    const extraLen = view.getUint16(cursor + 30, true)
    const noteLen = view.getUint16(cursor + 32, true)
    const localOffset = view.getUint32(cursor + 42, true)
    const name = new TextDecoder().decode(new Uint8Array(buffer, cursor + 46, nameLen))
    list.push({ name, method, compSize, localOffset })
    cursor += 46 + nameLen + extraLen + noteLen
  }
  return list
}

async function zipText(buffer, entry) {
  if (!entry) return ''
  const view = new DataView(buffer)
  const p = entry.localOffset
  if (view.getUint32(p, true) !== 0x04034b50) throw new Error('xlsx 内部结构损坏，换一份文件再试')
  const nameLen = view.getUint16(p + 26, true)
  const extraLen = view.getUint16(p + 28, true)
  const start = p + 30 + nameLen + extraLen
  // 流式写出的压缩包不写压缩后长度（记 0），没有长度就没法截取，明确报错好过读出乱码
  if (!entry.compSize) throw new Error('这份 xlsx 的压缩块没有写长度，离线读不了，请联网后再传')
  const raw = new Uint8Array(buffer, start, entry.compSize)
  if (entry.method === 0) return new TextDecoder().decode(raw)
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('当前浏览器不支持离线解压，请换 Chrome / Edge 或联网后再传')
  }
  const stream = new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
  return new Response(stream).text()
}

function parseXml(text) {
  const doc = new DOMParser().parseFromString(text, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length) throw new Error('xlsx 里的内容读不出来')
  return doc
}

function textsOf(node, tag) {
  return Array.from(node.getElementsByTagName(tag)).map((item) => item.textContent || '').join('')
}

function sharedStringsOf(xml) {
  if (!xml) return []
  const doc = parseXml(xml)
  return Array.from(doc.getElementsByTagName('si')).map((si) => textsOf(si, 't'))
}

function columnIndex(ref) {
  const letters = String(ref || '').replace(/[^A-Za-z]/g, '').toUpperCase()
  let index = 0
  for (let i = 0; i < letters.length; i += 1) index = index * 26 + (letters.charCodeAt(i) - 64)
  return Math.max(0, index - 1)
}

function sheetRowsOf(xml, shared) {
  const doc = parseXml(xml)
  const out = []
  Array.from(doc.getElementsByTagName('row')).forEach((row) => {
    const cells = []
    Array.from(row.getElementsByTagName('c')).forEach((cell) => {
      const type = cell.getAttribute('t')
      let value = ''
      if (type === 'inlineStr') {
        value = textsOf(cell, 't')
      } else {
        const node = cell.getElementsByTagName('v')[0]
        const raw = node ? node.textContent || '' : ''
        value = type === 's' ? shared[Number(raw)] || '' : raw
      }
      cells[columnIndex(cell.getAttribute('r'))] = value
    })
    // Excel 会省略空单元格（A1、C1），下标中间就有空洞；必须按长度铺满，否则整行少列
    out.push(Array.from(cells, (cell) => (cell == null ? '' : String(cell))))
  })
  return out
}

async function readXlsx(buffer) {
  const entries = await zipEntries(buffer)
  const sheet = entries.find((item) => item.name === 'xl/worksheets/sheet1.xml')
    || entries.find((item) => /^xl\/worksheets\/sheet\d+\.xml$/.test(item.name))
  if (!sheet) throw new Error('这份 xlsx 里没有找到工作表')
  const shared = sharedStringsOf(await zipText(buffer, entries.find((item) => item.name === 'xl/sharedStrings.xml')))
  return sheetRowsOf(await zipText(buffer, sheet), shared)
}

// .xls 是 Excel 97-2003 的二进制格式（OLE2 复合文档 + BIFF 记录）。
async function readLegacyXls(file) {
  let xlsx
  try {
    xlsx = await import('xlsx')
  } catch (e) {
    throw new Error(`${file.name} 需要 .xls 解析器，当前取不到；请另存为 .xlsx 后重传，或联网后使用在线模式`)
  }
  const book = xlsx.read(new Uint8Array(await file.arrayBuffer()), { type: 'array' })
  const target = book.SheetNames && book.SheetNames[0]
  const sheet = target ? book.Sheets[target] : null
  if (!sheet) throw new Error(`${file.name} 里没有找到工作表`)
  // raw:false 让日期、金额按单元格格式输出成看得懂的字符串，和读取 .xlsx 的口径一致
  const rows = xlsx.utils.sheet_to_json(sheet, { header: 1, raw: false, defval: '', blankrows: false })
  return rows.map((row) => (Array.isArray(row) ? row.map((cell) => (cell == null ? '' : String(cell))) : []))
}

// 读到一张表：{ name, columns, rows }（rows 不含表头，Excel 单元格原值都转成字符串）
export async function readTable(file) {
  const name = file.name || 'file'
  if (/\.[cm]?tsv$/i.test(name) || /\.txt$/i.test(name)) {
    return toTable(name, splitRows(await file.text(), '\t'))
  }
  if (/\.csv$/i.test(name)) {
    const text = await file.text()
    return toTable(name, splitRows(text, detectDelimiter(text)))
  }
  if (/\.xlsx$/i.test(name)) {
    return toTable(name, await readXlsx(await file.arrayBuffer()))
  }
  if (/\.xls$/i.test(name)) {
    return toTable(name, await readLegacyXls(file))
  }
  throw new Error(`${name} 离线只能读 .xlsx / .xls / .csv / .tsv / .txt；其他格式请联网后再传`)
}

// ==================== 字段规则匹配 ====================

// 目标列 ← 源列：先看是不是同一标准字段的不同叫法（工号 / 员工编号），再退到同名、包含。
export function guessMapping(sources, targets) {
  const list = []
  targets.forEach((target) => {
    let best = null
    sources.forEach((source) => {
      (source.columns || []).forEach((column) => {
        const t = norm(target)
        const c = norm(column)
        if (!t || !c) return
        const stdTarget = stdOf(target)
        const stdColumn = stdOf(column)
        let score = 0
        let reason = ''
        if (stdTarget && stdColumn && stdTarget.label === stdColumn.label) {
          score = 90
          reason = `同一类字段（${stdTarget.label}）`
        } else if (t === c) {
          score = 70
          reason = '叫法完全一致'
        } else if (c.length >= 2 && (t.includes(c) || c.includes(t))) {
          score = 45
          reason = '名称包含'
        }
        if (!score || (best && best.score >= score)) return
        best = { target, file: source.name, column, score, reason }
      })
    })
    list.push(best || { target, file: '', column: '', score: 0, reason: '没找到对应列' })
  })
  return list
}

// 把多张源表按模板列拼成一张表。
export function mappedRows(targets, sources) {
  const perSource = sources.map((source) => ({ source, mapping: guessMapping([source], targets) }))
  const keyIndex = targets.findIndex(
    (target, i) => /工号|姓名/.test(target) && perSource.every((item) => item.mapping[i].column)
  )

  const collect = (source, mapping, row) => {
    const indexOf = new Map((source.columns || []).map((column, i) => [column, i]))
    return mapping.map((item) => {
      if (!item.column) return ''
      const at = indexOf.get(item.column)
      const value = at == null || !row ? '' : row[at]
      return value == null ? '' : String(value)
    })
  }

  if (keyIndex < 0 || perSource.length < 2) {
    const out = []
    perSource.forEach((item) => {
      ;(item.source.rows || []).forEach((row) => out.push(collect(item.source, item.mapping, row)))
    })
    return out
  }

  const merged = new Map()
  perSource.forEach((item) => {
    ;(item.source.rows || []).forEach((row) => {
      const line = collect(item.source, item.mapping, row)
      const key = String(line[keyIndex] || '').trim()
      if (!key) return
      const slot = merged.get(key) || new Array(targets.length).fill('')
      line.forEach((value, i) => {
        if (!slot[i] && value) slot[i] = value
      })
      merged.set(key, slot)
    })
  })
  return [...merged.values()]
}

// ==================== 下载 ====================

function csvCell(value) {
  const text = String(value == null ? '' : value)
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

// 带 BOM，Excel 直接双击打开中文不乱码
export function csvText(columns, rows) {
  const lines = [columns.map(csvCell).join(',')]
  rows.forEach((row) => lines.push(row.map(csvCell).join(',')))
  return `\ufeff${lines.join('\r\n')}\r\n`
}

export function downloadCsv(name, columns, rows) {
  const safe = String(name || '离线结果').replace(/\.csv$/i, '')
  const blob = new Blob([csvText(columns, rows)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${safe}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

// ==================== 本地表格（表格智作离线） ====================

const LOCAL_KEY = 'huibiao.offline.sheets.v1'

function nowText() {
  const d = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function localId() {
  return `local-${Math.random().toString(36).slice(2, 10)}`
}

export function loadLocalSheets() {
  try {
    const list = JSON.parse(localStorage.getItem(LOCAL_KEY) || '[]')
    return Array.isArray(list) ? list : []
  } catch (e) {
    return []
  }
}

export function saveLocalSheets(list) {
  try {
    localStorage.setItem(LOCAL_KEY, JSON.stringify(list))
  } catch (e) {
    // 浏览器禁用了本地存储时只影响「下次打开还在」，当前这一屏仍然可用
  }
}

export function makeLocalSheet(name, columns, rows = []) {
  return {
    id: localId(),
    name: name || '未命名表',
    columns: columns || [],
    rows: rows || [],
    updated_at: nowText(),
    origin: 'nl',
    local: true
  }
}

function nameFromSentence(text) {
  const head = String(text).split(/列[:：]/)[0] || ''
  let name = head
    .replace(/^.*?新建/, '')
    .replace(/^(一张|一个|一)/, '')
    .replace(/^(名为|叫)/, '')
    .replace(/[，,。；;\s]+$/g, '')
    .trim()
  if (!name || name === '表' || name.length > 20) name = '未命名表'
  return name
}

function columnsFromSentence(text) {
  const seg = String(text).split(/列[:：]/)[1]
  if (!seg) return []
  return seg
    .replace(/[。；;].*$/, '')
    .split(/[、,，/|]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

// 一句话里的合并键：按车间合并 / 以工号对齐 / 按车间、日期合并 / 按「部门」这一列合并
function keysFromSentence(text) {
  const match = String(text || '').match(
    /(?:按|按照|以|用|依据|根据)\s*([^，,。；;]+?)\s*(?:这一列|这列|列|字段|两列)?\s*(?:来)?\s*(?:合并|融合|关联|拼接|拼起来|汇总|对起来)/
  )
  if (!match) return []
  return match[1]
    .replace(/^[「『"“]|[」』"”]$/g, '')
    .split(/[、,，和与+]/)
    .map((item) => item.replace(/[「」『』"“”’\s]/g, '').replace(/(这一列|这列|列|字段)$/, '').trim())
    .filter((item) => item && !/^(列名|列|字段|名字|相同列|公共列|这两张|这些|选中的表|表格)$/.test(item))
}

// 自动挑键列时的优先名字：工号、单号、日期这类每行大概率不重样的列。
const KEY_LIKE = /(工号|员工编号|人员编号|员工号|编号|序号|单号|订单号|编码|代码|姓名|员工姓名|手机|手机号|电话|身份证|日期|时间|月份)/

// 没指定列时自动挑一列：先看像主键的名字，再看值的重复程度。
function autoMergeKey(list) {
  // 能当键的列不要求名字一模一样：工号 / 员工编号 这类同义列也算
  const usable = list[0].columns.filter((column) => list.every((item) => findMergeColumn(item, column)))
  if (!usable.length) return ''
  const scored = usable.map((column, order) => {
    if (KEY_LIKE.test(column)) return { column, score: 100 - order }
    const first = list[0]
    const values = first.rows
      .map((row) => String(row[first.columns.indexOf(column)] || '').trim())
      .filter(Boolean)
    const ratio = values.length ? new Set(values).size / values.length : 0
    return { column, score: ratio >= 0.9 ? 50 - order : ratio >= 0.6 ? 20 - order : -1 }
  })
  scored.sort((a, b) => b.score - a.score)
  return scored[0].score > 0 ? scored[0].column : ''
}

// 在一张表里找用户说的那一列：先同名，再忽略空格/标点，最后按别名词典认同一类字段
function findMergeColumn(item, key) {
  const exact = item.columns.find((column) => column === key)
  if (exact) return exact
  const wanted = norm(key)
  const same = item.columns.find((column) => norm(column) === wanted)
  if (same) return same
  const std = stdOf(key)
  if (!std) return ''
  return item.columns.find((column) => {
    const other = stdOf(column)
    return other && other.label === std.label
  }) || ''
}

// 两张以上本地表合并。
function mergeSheets(list, requested = []) {
  const plans = []
  for (const key of requested) {
    const per = list.map((item) => findMergeColumn(item, key))
    if (per.some((column) => !column)) {
      const missing = list.filter((item, i) => !per[i]).map((item) => `「${item.name}」`)
      // 「能用来合并的列」= 每张表都能找到同名或同义列的那些
      const usable = list[0].columns.filter((column) => list.every((item) => findMergeColumn(item, column)))
      throw new Error(usable.length
        ? `「${key}」这一列在${missing.join('、')}里没有；这几张表能用来合并的列有：${usable.join('、')}`
        : `「${key}」这一列在${missing.join('、')}里没有，这几张表也没有同名的列；补上这一列再合并，或者先说「新建一张表，列：…」`)
    }
    plans.push({ label: key, per })
  }
  if (!plans.length) {
    const auto = autoMergeKey(list)
    // 自动挑出来的列在各张表里可能叫法不同（工号 / 员工编号），要逐表再解析一次
    if (auto) plans.push({ label: auto, per: list.map((item) => findMergeColumn(item, auto)) })
  }

  // 输出列名：被选作键的列统一成用户说的那个名字，避免工号/员工编号各占一列
  const renames = list.map(() => ({}))
  plans.forEach((plan) => plan.per.forEach((column, i) => {
    renames[i][column] = plan.label
  }))
  const columns = []
  list.forEach((item, i) => item.columns.forEach((column) => {
    const label = renames[i][column] || column
    if (!columns.includes(label)) columns.push(label)
  }))

  if (!plans.length) {
    // 没有公共列：按列名并排拼起来，缺的地方留空
    const rows = []
    const width = Math.max(...list.map((item) => item.rows.length))
    for (let i = 0; i < width; i += 1) {
      const row = []
      list.forEach((item, s) => item.columns.forEach((column, j) => {
        row[columns.indexOf(renames[s][column] || column)] = item.rows[i] ? item.rows[i][j] : ''
      }))
      rows.push(columns.map((column, j) => row[j] || ''))
    }
    return { sheet: makeLocalSheet(`合并 ${list.length} 张表`, columns, rows), key: '' }
  }

  const merged = new Map()
  list.forEach((item, s) => {
    const at = plans.map((plan) => item.columns.indexOf(plan.per[s]))
    item.rows.forEach((row) => {
      const id = at.map((index) => String((row && row[index]) || '').trim()).join('\u0001')
      if (!id.split('\u0001').some((part) => part)) return
      const line = merged.get(id) || new Array(columns.length).fill('')
      item.columns.forEach((column, j) => {
        const value = row[j] == null ? '' : String(row[j])
        const slot = columns.indexOf(renames[s][column] || column)
        if (!line[slot]) line[slot] = value
      })
      merged.set(id, line)
    })
  })
  return {
    sheet: makeLocalSheet(`合并 ${list.length} 张表`, columns, [...merged.values()]),
    key: plans.map((plan) => plan.label).join('、')
  }
}

// 一句话在本地的规则实现：只认「新建…列：…」和「合并选中的表（可指定按哪一列）」两种说法。
export function composeLocalSheet(text, selected = []) {
  const sentence = String(text || '').trim()
  if (!sentence) throw new Error('先说一句要做什么')
  if (/合并|融合|关联|拼起来|汇总/.test(sentence) && !/新建|建一张|创建|做一张/.test(sentence)) {
    if (selected.length < 2) throw new Error('离线合并需要先在左边勾选两张以上的本地表')
    const result = mergeSheets(selected, keysFromSentence(sentence))
    return { kind: 'merge', sheet: result.sheet, key: result.key }
  }
  const columns = columnsFromSentence(sentence)
  if (columns.length < 2) {
    throw new Error('离线建表要先说清列名，例如：新建一张表，列：检查日期、车间、负责人')
  }
  return { kind: 'create', sheet: makeLocalSheet(nameFromSentence(sentence), columns, []), key: '' }
}
