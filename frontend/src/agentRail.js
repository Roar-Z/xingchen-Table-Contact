/**
 * 智能体状态条：新建任务页把自己的实时进度挂到这里，左侧导航栏负责显示。
 *
 * 单独抽一个模块，是因为导航栏和页面不在同一棵组件树里；页面切走后状态条自动收起，
 * 不会在别的页面留着上一单的进度。
 */
import { ref } from 'vue'

// 只有新建任务页在用时才显示
export const railVisible = ref(false)
// 当前状态：待交接 / 读取中 / 等待核对 / 执行中 / 已交付
export const railState = ref('待交接')
export const railTone = ref('idle')
// 已经执行了几步
export const railSteps = ref(0)
// 这一轮要用到的能力，最多 6 项
export const railTools = ref([])
// 经验记忆条数
export const railMemory = ref(0)
// 导航栏点「管理」时加一，页面看到变化就打开记忆弹窗
export const railMemoryRequest = ref(0)
