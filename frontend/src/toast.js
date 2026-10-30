import { reactive } from 'vue'

// 同一时间只显示一条提示：新动作直接覆盖上一条的内容，连点也不会在右下角堆成一列
const toasts = reactive([])
let seq = 0
let timer = 0

export function useToast() {
  function show(message, type = 'ok') {
    const current = toasts[0]
    if (current) {
      current.message = message
      current.type = type
    } else {
      toasts.push({ id: ++seq, message, type })
    }
    // 每条提示都重新计时，避免被上一条的定时器提前收走
    window.clearTimeout(timer)
    timer = window.setTimeout(() => {
      toasts.splice(0, toasts.length)
    }, 2800)
  }
  return { toasts, show }
}

export { toasts }
