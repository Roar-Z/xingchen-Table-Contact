import { reactive } from 'vue'

const toasts = reactive([])
let seq = 0

export function useToast() {
  function show(message, type = 'ok') {
    const id = ++seq
    toasts.push({ id, message, type })
    window.setTimeout(() => {
      const i = toasts.findIndex((t) => t.id === id)
      if (i >= 0) toasts.splice(i, 1)
    }, 2800)
  }
  return { toasts, show }
}

export { toasts }
