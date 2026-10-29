/**
 * 全局提示条：页面级的报错统一在顶栏下方显示一条，各页不再各自在正文中间摆红带。
 * 页面只需要把「出什么事 + 怎么重试」交给这里，显示位置和样式由 AppShell 统一负责。
 */
import { ref } from 'vue'

export const noticeText = ref('')
// 点「重试」时调用；没有可重试动作时为 null，只显示一条说明
export const noticeRetry = ref(null)

export function showNotice(text, retry = null) {
  noticeText.value = text || ''
  noticeRetry.value = typeof retry === 'function' ? retry : null
}

export function clearNotice() {
  noticeText.value = ''
  noticeRetry.value = null
}

export function retryNotice() {
  const action = noticeRetry.value
  clearNotice()
  if (action) action()
}
