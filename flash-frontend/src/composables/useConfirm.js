import { ref } from 'vue'

const state = ref({
  visible: false,
  message: '',
  resolve: null
})

export function useConfirm() {
  function confirm(message) {
    return new Promise(resolve => {
      state.value = { visible: true, message, resolve }
    })
  }

  function onConfirm() {
    state.value.resolve?.(true)
    state.value.visible = false
  }

  function onCancel() {
    state.value.resolve?.(false)
    state.value.visible = false
  }

  return { confirmState: state, confirm, onConfirm, onCancel }
}
