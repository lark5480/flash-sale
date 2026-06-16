<template>
  <ToastMessage :toasts="toasts" />
  <ConfirmDialog />
  <router-view />
</template>

<script setup>
import ToastMessage from './components/ToastMessage.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import { useToast } from './composables/useToast'

const { toasts } = useToast()
</script>
