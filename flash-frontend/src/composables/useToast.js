import { ref } from 'vue'

const toasts = ref([])
let seq = 0

export function useToast() {
  function show(message, type, duration = 3000) {
    const id = ++seq
    toasts.value.push({ id, message, type })
    if (duration > 0) {
      setTimeout(() => {
        toasts.value = toasts.value.filter(t => t.id !== id)
      }, duration)
    }
  }

  return {
    toasts,
    success(msg) { show(msg, 'success') },
    error(msg) { show(msg, 'error') }
  }
}
