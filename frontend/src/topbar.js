import { reactive } from 'vue'

/**
 * 顶栏的页面级内容。默认顶栏只显示当前路由名，
 * 页面需要放标题、说明和快捷按钮时，在 setup 里调 useTopbar 覆盖，
 * 离开页面时 clearTopbar 还原，避免下一个页面带着上一页的按钮。
 */
export const topbar = reactive({
  title: '',
  desc: '',
  actions: [],
  busyKey: '',
  handler: null,
  // 页面级的分段切换（新建任务页的 标准模式 / 智能体模式），放在顶栏右侧
  segments: [],
  segment: '',
  onSegment: null
})

export function useTopbar(config) {
  topbar.title = config.title || ''
  topbar.desc = config.desc || ''
  topbar.actions = config.actions || []
  topbar.busyKey = ''
  topbar.handler = config.onAction || null
  return topbar
}

/**
 * 在顶栏右侧挂一组分段按钮。只有当前页面需要时才有内容，切走时随 clearTopbar 一起收掉，
 * 免得别的页面顶上还留着上一页的模式切换。
 */
export function useTopbarSegments(segments, onSegment) {
  topbar.segments = segments || []
  topbar.onSegment = onSegment || null
}

export function setTopbarSegment(key) {
  topbar.segment = key || ''
}

export function clearTopbar() {
  topbar.title = ''
  topbar.desc = ''
  topbar.actions = []
  topbar.busyKey = ''
  topbar.handler = null
  topbar.segments = []
  topbar.segment = ''
  topbar.onSegment = null
}
