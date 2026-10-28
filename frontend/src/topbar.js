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
  handler: null
})

export function useTopbar(config) {
  topbar.title = config.title || ''
  topbar.desc = config.desc || ''
  topbar.actions = config.actions || []
  topbar.busyKey = ''
  topbar.handler = config.onAction || null
  return topbar
}

export function clearTopbar() {
  topbar.title = ''
  topbar.desc = ''
  topbar.actions = []
  topbar.busyKey = ''
  topbar.handler = null
}
