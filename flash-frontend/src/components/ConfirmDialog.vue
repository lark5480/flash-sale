<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="confirmState.visible" class="dialog-overlay" @click.self="onCancel">
        <div class="dialog-card">
          <div class="dialog-icon">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="10"/>
              <line x1="12" y1="8" x2="12" y2="12"/>
              <line x1="12" y1="16" x2="12.01" y2="16"/>
            </svg>
          </div>
          <p class="dialog-message">{{ confirmState.message }}</p>
          <div class="dialog-actions">
            <button class="dialog-btn cancel-btn" @click="onCancel">取消</button>
            <button class="dialog-btn confirm-btn" @click="onConfirm">确定</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { useConfirm } from '../composables/useConfirm'

const { confirmState, onConfirm, onCancel } = useConfirm()
</script>

<style scoped>
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.dialog-card {
  background: rgba(20, 20, 28, 0.95);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  padding: 32px;
  max-width: 380px;
  width: 100%;
  text-align: center;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.5);
}

.dialog-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.1);
  color: #F59E0B;
  margin-bottom: 16px;
}

.dialog-message {
  font-size: 16px;
  color: var(--color-text);
  line-height: 1.6;
  margin-bottom: 28px;
  font-weight: 500;
}

.dialog-actions {
  display: flex;
  gap: 12px;
}

.dialog-btn {
  flex: 1;
  padding: 10px 0;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  min-height: 44px;
  border: none;
}

.cancel-btn {
  background: rgba(255, 255, 255, 0.05);
  color: var(--color-text-muted);
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.cancel-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.confirm-btn {
  background: linear-gradient(135deg, #C8A45C 0%, #D4B96A 50%, #A8883E 100%);
  color: #06060A;
}
.confirm-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 16px rgba(200, 164, 92, 0.3);
}

.dialog-enter-active { transition: all 0.2s ease; }
.dialog-leave-active { transition: all 0.15s ease; }
.dialog-enter-from { opacity: 0; }
.dialog-enter-from .dialog-card { transform: scale(0.95); }
.dialog-leave-to { opacity: 0; }
.dialog-leave-to .dialog-card { transform: scale(0.95); }
</style>
