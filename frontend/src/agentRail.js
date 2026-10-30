// 智能体状态条：新建任务页把自己的实时进度挂到这里，左侧导航栏负责显示。
import { ref } from 'vue'

// 只有新建任务页在用（任一模式）时才显示
export const railVisible = ref(false)
// 谁在写这条状态：standard（标准模式）/ bridge（智能体模式）
export const railScope = ref('standard')
// 当前状态：待交接 / 读取中 / 等待核对 / 执行中 / 已交付
export const railState = ref('待交接')
export const railTone = ref('idle')
// 已经执行了几步
export const railSteps = ref(0)
// 这一轮要用到的能力，标准模式最多 6 项，智能体模式按规划出的步骤列
export const railTools = ref([])
// 状态下方的一句摘要：智能体模式写方案规模（对齐/计算/输出列数），标准模式留空
export const railDetail = ref('')
// 记忆条数：标准模式是历史方案，智能体模式是自定义字段匹配规则
export const railMemory = ref(0)
// 智能体模式另外还有经验记忆：跑成功的历史配置，下一单同类数据集会优先复用
export const railLessons = ref(0)
// 导航栏点「管理」时加一，页面看到变化就打开对应的弹窗
export const railMemoryRequest = ref(0)
