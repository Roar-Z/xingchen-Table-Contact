// 把前端静态 HTML 文档转成 Word（.docx），只取正文 <div class="doc"> 里的内容。
// 临时脚本，放在工作区里，不提交。
const fs = require('fs')
const path = require('path')
const { parse } = require('node-html-parser')
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  HeadingLevel, AlignmentType, LevelFormat, BorderStyle, WidthType, ShadingType,
  Header, Footer, PageBreak, PageNumber
} = require('docx')

const CONTENT_WIDTH = 9638 // A4 去掉左右各 2cm 页边距
const FONT = { ascii: 'Arial', hAnsi: 'Arial', eastAsia: 'Microsoft YaHei' }
const MONO = { ascii: 'Consolas', hAnsi: 'Consolas', eastAsia: 'Microsoft YaHei' }

// 网页上的“本页”到了 Word 里说“本文”，其余口语保持不动
const TEXT_FIXES = [[/本页/g, '本文']]

function fixText(text) {
  let out = text || ''
  for (const [pattern, replacement] of TEXT_FIXES) out = out.replace(pattern, replacement)
  return out
}

function clean(text) {
  return (text || '').replace(/\s+/g, ' ').trim()
}

// 行内节点 -> TextRun 数组，保留加粗、行内代码、链接颜色
function inlineRuns(node, opts = {}) {
  const runs = []
  for (const child of node.childNodes || []) {
    if (child.nodeType === 3) {
      // 只把连续空白压成一个空格，不 trim：行内元素之间的空格是有意义的
      const text = fixText((child.text || '').replace(/\s+/g, ' '))
      if (text.trim()) runs.push(new TextRun({ text, ...opts }))
      continue
    }
    if (child.nodeType !== 1) continue
    const tag = child.tagName.toLowerCase()
    if (tag === 'br') {
      runs.push(new TextRun({ break: 1, ...opts }))
    } else if (tag === 'strong' || tag === 'b') {
      runs.push(...inlineRuns(child, { ...opts, bold: true }))
    } else if (tag === 'em' || tag === 'i') {
      runs.push(...inlineRuns(child, { ...opts, italics: true }))
    } else if (tag === 'code') {
      runs.push(...inlineRuns(child, {
        ...opts,
        font: MONO,
        size: 18,
        shading: { fill: 'F2F3F5', type: ShadingType.CLEAR }
      }))
    } else if (tag === 'a') {
      runs.push(...inlineRuns(child, { ...opts, color: '2B5FD9' }))
    } else {
      runs.push(...inlineRuns(child, opts))
    }
  }
  return runs
}

function hasText(node) {
  return clean(node.text).length > 0
}

function blockChildren(node) {
  return (node.childNodes || []).filter((n) => n.nodeType === 1)
}

function makeDocument(blocks, meta) {
  return new Document({
    creator: '星尘汇表智联',
    title: meta.title,
    description: meta.description,
    styles: {
      default: {
        document: { run: { font: FONT, size: 21 } }
      },
      paragraphStyles: [
        {
          id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: { size: 32, bold: true, color: '1F2329', font: FONT },
          paragraph: { spacing: { before: 0, after: 180 }, outlineLevel: 0, keepNext: false, keepLines: false }
        },
        {
          id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: { size: 26, bold: true, color: '1F2329', font: FONT },
          paragraph: { spacing: { before: 300, after: 120 }, outlineLevel: 1, keepNext: false, keepLines: false }
        },
        {
          id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: { size: 23, bold: true, color: '1F2329', font: FONT },
          paragraph: { spacing: { before: 200, after: 80 }, outlineLevel: 2, keepNext: false, keepLines: false }
        },
        {
          id: 'Heading4', name: 'Heading 4', basedOn: 'Normal', next: 'Normal', quickFormat: true,
          run: { size: 21, bold: true, color: '4E5969', font: FONT },
          paragraph: { spacing: { before: 140, after: 60 }, outlineLevel: 3, keepNext: false, keepLines: false }
        }
      ]
    },
    numbering: {
      config: [
        {
          reference: 'bullets',
          levels: [{
            level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT,
            style: { paragraph: { indent: { left: 520, hanging: 260 } } }
          }]
        },
        ...meta.numbering
      ]
    },
    sections: [{
      properties: {
        titlePage: true,
        page: {
          size: { width: 11906, height: 16838 },
          margin: { top: 1134, right: 1134, bottom: 1134, left: 1134 }
        }
      },
      headers: {
        first: new Header({ children: [new Paragraph({ children: [] })] }),
        default: new Header({
          children: [new Paragraph({
            alignment: AlignmentType.RIGHT,
            border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: 'E2E5EA', space: 6 } },
            children: [new TextRun({ text: meta.headerText, size: 16, color: '86909C' })]
          })]
        })
      },
      footers: {
        first: new Footer({ children: [new Paragraph({ children: [] })] }),
        default: new Footer({
          children: [new Paragraph({
            alignment: AlignmentType.CENTER,
            spacing: { before: 0, after: 0 },
            children: [
              new TextRun({ text: '第 ', size: 16, color: '86909C' }),
              new TextRun({ children: [PageNumber.CURRENT], size: 16, color: '86909C' }),
              new TextRun({ text: ' 页 / 共 ', size: 16, color: '86909C' }),
              new TextRun({ children: [PageNumber.TOTAL_PAGES], size: 16, color: '86909C' }),
              new TextRun({ text: ' 页', size: 16, color: '86909C' })
            ]
          })]
        })
      },
      children: blocks
    }]
  })
}

function tableToBlocks(table, meta) {
  const rows = table.querySelectorAll('tr')
  if (!rows.length) return []
  const cells = rows.map((row) => blockChildren(row).filter((c) => ['td', 'th'].includes(c.tagName.toLowerCase())))
  const colCount = Math.max(...cells.map((r) => r.length))
  if (!colCount) return []

  // 列宽按内容长度分，太窄的列给个下限，最后把余量补到最后一列
  const weights = new Array(colCount).fill(0)
  cells.forEach((row, ri) => {
    row.forEach((cell, ci) => {
      weights[ci] = Math.max(weights[ci], Math.min(clean(cell.text).length, 60))
    })
  })
  const sum = weights.reduce((a, b) => a + b + 8, 0)
  let widths = weights.map((w) => Math.max(900, Math.round(((w + 8) / sum) * CONTENT_WIDTH)))
  const diff = CONTENT_WIDTH - widths.reduce((a, b) => a + b, 0)
  widths[widths.length - 1] += diff

  const border = { style: BorderStyle.SINGLE, size: 4, color: 'D9DEE6' }
  const borders = { top: border, bottom: border, left: border, right: border }

  const trs = cells.map((row, ri) => {
    const header = ri === 0 && rows[ri].querySelectorAll('th').length > 0
    const tcs = []
    for (let ci = 0; ci < colCount; ci++) {
      const cell = row[ci]
      const runs = cell
        ? inlineRuns(cell, { size: 19, bold: header || undefined })
        : [new TextRun({ text: '', size: 19 })]
      tcs.push(new TableCell({
        borders,
        width: { size: widths[ci], type: WidthType.DXA },
        shading: header ? { fill: 'F5F7FA', type: ShadingType.CLEAR } : undefined,
        margins: { top: 60, bottom: 60, left: 100, right: 100 },
        children: [new Paragraph({ spacing: { before: 0, after: 0, line: 280 }, children: runs })]
      }))
    }
    return new TableRow({ tableHeader: header, cantSplit: true, children: tcs })
  })

  meta.skipSpacing = true
  return [new Table({
    width: { size: CONTENT_WIDTH, type: WidthType.DXA },
    columnWidths: widths,
    rows: trs
  })]
}

function codeBlocks(pre) {
  const raw = (pre.text || '').replace(/\r/g, '')
  const lines = raw.split('\n')
  while (lines.length && !lines[0].trim()) lines.shift()
  while (lines.length && !lines[lines.length - 1].trim()) lines.pop()
  const out = []
  lines.forEach((line, i) => {
    out.push(new Paragraph({
      shading: { fill: 'F7F8FA', type: ShadingType.CLEAR },
      spacing: { before: i === 0 ? 120 : 0, after: i === lines.length - 1 ? 120 : 0, line: 240 },
      indent: { left: 200, right: 200 },
      children: [new TextRun({ text: line || ' ', font: MONO, size: 18 })]
    }))
  })
  return out
}

function listBlocks(list, meta, level = 0) {
  const ordered = list.tagName.toLowerCase() === 'ol'
  let ref = 'bullets'
  if (ordered) {
    ref = `num_${meta.counter++}`
    meta.numbering.push({
      reference: ref,
      levels: [{
        level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 520, hanging: 260 } } }
      }]
    })
  }
  const out = []
  for (const li of blockChildren(list)) {
    if (li.tagName.toLowerCase() !== 'li') continue
    const nested = blockChildren(li).filter((n) => ['ul', 'ol'].includes(n.tagName.toLowerCase()))
    const runs = []
    for (const child of li.childNodes || []) {
      if (child.nodeType === 3) {
        const text = fixText((child.text || '').replace(/\s+/g, ' '))
        if (text.trim()) runs.push(new TextRun({ text }))
      } else if (child.nodeType === 1) {
        const tag = child.tagName.toLowerCase()
        if (tag === 'ul' || tag === 'ol') continue
        runs.push(...inlineRuns(child, {}))
      }
    }
    if (runs.length) {
      out.push(new Paragraph({
        numbering: level === 0 ? { reference: ref, level: 0 } : undefined,
        indent: level === 0 ? undefined : { left: 520 + 360 * level, hanging: 260 },
        spacing: { after: 60, line: 300 },
        children: runs
      }))
    }
    for (const sub of nested) out.push(...listBlocks(sub, meta, level + 1))
  }
  return out
}

function convertBlocks(node, meta) {
  const out = []
  for (const child of blockChildren(node)) {
    const tag = child.tagName.toLowerCase()
    const cls = child.getAttribute('class') || ''
    // 封面已经用了小标题、大标题和导语；网页页脚里的导航链接在 Word 里没有意义
    if ((tag === 'div' && cls.includes('eyebrow')) || tag === 'h1' || tag === 'footer') continue
    if (tag === 'p' && cls.includes('lead')) continue
    if (['div', 'section', 'article', 'main'].includes(tag)) {
      out.push(...convertBlocks(child, meta))
    } else if (tag === 'h2') {
      out.push(new Paragraph({ heading: HeadingLevel.HEADING_2, children: inlineRuns(child, { size: 26, bold: true }) }))
    } else if (tag === 'h3') {
      out.push(new Paragraph({ heading: HeadingLevel.HEADING_3, children: inlineRuns(child, { size: 23, bold: true }) }))
    } else if (tag === 'h4') {
      out.push(new Paragraph({ heading: HeadingLevel.HEADING_4, children: inlineRuns(child, { size: 21, bold: true, color: '4E5969' }) }))
    } else if (tag === 'p') {
      if (!hasText(child)) continue
      out.push(new Paragraph({
        spacing: { before: 0, after: 120, line: 320 },
        children: inlineRuns(child, {})
      }))
    } else if (tag === 'ul' || tag === 'ol') {
      out.push(...listBlocks(child, meta))
    } else if (tag === 'table') {
      out.push(...tableToBlocks(child, meta))
    } else if (tag === 'pre') {
      out.push(...codeBlocks(child))
    } else if (tag === 'blockquote') {
      const paras = blockChildren(child).filter((n) => n.tagName.toLowerCase() === 'p')
      const targets = paras.length ? paras : [child]
      for (const p of targets) {
        out.push(new Paragraph({
          spacing: { before: 120, after: 120, line: 300 },
          indent: { left: 240, right: 240 },
          shading: { fill: 'FFF8EC', type: ShadingType.CLEAR },
          border: { left: { style: BorderStyle.SINGLE, size: 12, color: 'D9A441', space: 8 } },
          children: inlineRuns(p, { size: 19, color: '7A4F0C' })
        }))
      }
    } else if (tag === 'hr') {
      out.push(new Paragraph({
        spacing: { before: 200, after: 200 },
        border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: 'E2E5EA', space: 1 } },
        children: []
      }))
    }
  }
  return out
}

// Word 版有自己的封面：抬头、标题、副标题、版本信息，摘要之后另起一页进正文
function coverBlocks(meta) {
  return [
    new Paragraph({ spacing: { before: 1400, after: 0 }, children: [] }),
    new Paragraph({
      spacing: { after: 200 },
      children: [new TextRun({ text: meta.eyebrow, size: 20, color: '86909C', characterSpacing: 40 })]
    }),
    new Paragraph({
      spacing: { after: 320 },
      children: [new TextRun({ text: meta.title, size: 56, bold: true, color: '1F2329' })]
    }),
    new Paragraph({
      spacing: { after: 1400 },
      border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: '2B5FD9', space: 12 } },
      children: [new TextRun({ text: meta.subtitle, size: 24, color: '4E5969' })]
    }),
    new Paragraph({
      spacing: { after: 60, line: 300 },
      children: [new TextRun({ text: `版　本：${meta.version}`, size: 21, color: '4E5969' })]
    }),
    new Paragraph({
      spacing: { after: 60, line: 300 },
      children: [new TextRun({ text: '团　队：星尘汇表智联', size: 21, color: '4E5969' })]
    }),
    new Paragraph({
      spacing: { after: 0, line: 300 },
      children: [new TextRun({ text: `最后更新：${meta.updated}`, size: 21, color: '4E5969' })]
    }),
    new Paragraph({ children: [new PageBreak()] })
  ]
}

// 摘要放在正文第一页顶部，不单独占一页
function summaryBlocks(meta) {
  if (!meta.summary) return []
  return [
    new Paragraph({
      heading: HeadingLevel.HEADING_1,
      children: [new TextRun({ text: '摘要', size: 32, bold: true, color: '1F2329' })]
    }),
    new Paragraph({
      spacing: { before: 0, after: 200, line: 320 },
      children: [new TextRun({ text: meta.summary, size: 21 })]
    })
  ]
}

async function main() {
  const args = process.argv.slice(2)
  const input = args[0]
  const output = args[1]
  const summary = args[2] || ''
  if (!input || !output) {
    console.error('用法: node convert.js <input.html> <output.docx> [摘要]')
    process.exit(1)
  }
  const html = fs.readFileSync(input, 'utf8')
  const root = parse(html, { comment: false })
  const docNode = root.querySelector('div.doc') || root.querySelector('main') || root.querySelector('body')
  if (!docNode) {
    console.error('没找到正文容器')
    process.exit(1)
  }
  const h1 = docNode.querySelector('h1')
  const eyebrowNode = docNode.querySelector('div.eyebrow')
  const leadNode = docNode.querySelector('p.lead')
  const rawTitle = h1 ? fixText(clean(h1.text)) : path.basename(input, '.html')
  // 网页大标题是“星尘汇表智联 · 设计文档”，Word 封面只要文档名，品牌放在抬头里
  const shortTitle = rawTitle.replace(/^星尘汇表智联\s*·\s*/, '')
  const leadText = leadNode ? clean(leadNode.text) : ''
  const pick = (pattern, fallback) => {
    const hit = leadText.match(pattern)
    return hit ? hit[1] : fallback
  }
  const titleMap = { '设计文档': '产品设计文档', '开发文档': '开发文档' }
  const title = titleMap[shortTitle] || shortTitle
  const subtitles = {
    '产品设计文档': '为什么做、做成什么样、每一处细节怎么定的',
    '开发文档': '这版代码怎么搭起来、怎么跑起来'
  }
  const meta = {
    title,
    eyebrow: eyebrowNode ? fixText(clean(eyebrowNode.text)) : '2026 东莞百景大赛 · 参赛作品文档',
    subtitle: subtitles[title] || '参赛作品文档',
    headerText: rawTitle,
    version: pick(/版本[：:]\s*([^\s　]+)/, '1.0.0'),
    updated: pick(/最后更新[：:]\s*([^\s　]+)/, '2026-10-30'),
    description: '星尘汇表智联 · 2026 东莞百景大赛',
    summary: fixText(summary.trim()),
    numbering: [],
    counter: 0
  }
  const body = convertBlocks(docNode, meta)
  const blocks = [...coverBlocks(meta), ...summaryBlocks(meta), ...body]
  const doc = makeDocument(blocks, meta)
  const buffer = await Packer.toBuffer(doc)
  fs.writeFileSync(output, buffer)
  console.log(`${output}  ${buffer.length} bytes, ${blocks.length} blocks`)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
