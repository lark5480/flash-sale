<template>
  <div class="login-page">
    <div class="login-hero">
      <div class="hero-glow"></div>
      <div class="hero-content">
        <div class="brand-icon">
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
          </svg>
        </div>
        <h1>限时秒杀</h1>
        <p>管理后台</p>
      </div>
    </div>

    <div class="login-container">
      <div class="login-card">
        <h2 class="card-title">登录</h2>
        <p class="card-subtitle">登录以访问管理后台</p>
        <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="handleLogin" label-position="top">
          <el-form-item prop="username" label="用户名">
            <el-input v-model="form.username" placeholder="请输入用户名" :prefix-icon="User" size="large" />
          </el-form-item>
          <el-form-item prop="password" label="密码">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" :prefix-icon="Lock" size="large" show-password />
          </el-form-item>
          <el-form-item v-if="error">
            <el-alert :title="error" type="error" :closable="false" show-icon />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="large" :loading="loading" native-type="submit" class="login-btn">
              {{ loading ? '登录中...' : '登录' }}
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { login } from '../api/auth'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)
const error = ref('')

const form = reactive({ username: '', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  error.value = ''
  loading.value = true
  try {
    const res = await login(form.username, form.password)
    localStorage.setItem('adminToken', res.data.accessToken)
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  background: var(--color-bg);
  display: flex;
  flex-direction: column;
}

.login-hero {
  position: relative;
  padding: 48px 24px 32px;
  text-align: center;
  overflow: hidden;
}
.hero-glow {
  position: absolute;
  top: -80px;
  left: 50%;
  transform: translateX(-50%);
  width: 400px;
  height: 200px;
  background: radial-gradient(ellipse, rgba(200,164,92,0.06) 0%, transparent 70%);
  pointer-events: none;
}
.hero-content { position: relative; }
.brand-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: var(--radius-lg);
  background: rgba(200,164,92,0.08);
  color: var(--color-accent);
  margin-bottom: 16px;
  border: 1px solid rgba(200,164,92,0.1);
  backdrop-filter: blur(8px);
}
.hero-content h1 {
  font-family: var(--font-heading);
  font-size: 32px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 3px;
  margin-bottom: 8px;
}
.hero-content p {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 3px;
  text-transform: uppercase;
  color: var(--color-accent);
}

.login-container {
  display: flex;
  justify-content: center;
  padding: 0 16px 32px;
  flex: 1;
}

.login-card {
  background: var(--glass-bg);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-xl);
  padding: 40px;
  width: 100%;
  max-width: 420px;
  align-self: flex-start;
}

.card-title {
  font-family: var(--font-heading);
  text-align: center;
  font-size: 24px;
  font-weight: 600;
  color: #fff;
  margin-bottom: 4px;
}
.card-subtitle {
  text-align: center;
  font-size: 14px;
  color: var(--color-text-muted);
  margin-bottom: 32px;
}

.login-btn {
  width: 100%;
  font-weight: 600 !important;
  font-size: 16px !important;
  min-height: 48px !important;
  letter-spacing: 0.5px !important;
}

/* Match C-end input style */
:deep(.el-form-item__label) {
  color: var(--color-text-secondary) !important;
  font-size: 13px !important;
  font-weight: 500 !important;
  letter-spacing: 0.3px !important;
}
:deep(.el-input__wrapper) {
  background: rgba(255,255,255,0.04) !important;
  border: 1px solid rgba(255,255,255,0.08) !important;
  border-radius: var(--radius-md) !important;
  box-shadow: none !important;
  padding: 0 var(--space-4) !important;
  min-height: 48px !important;
  transition: all var(--transition-fast) !important;
}
:deep(.el-input__wrapper:hover) {
  border-color: rgba(255,255,255,0.12) !important;
}
:deep(.el-input.is-focus .el-input__wrapper) {
  border-color: var(--color-accent) !important;
  box-shadow: 0 0 0 3px var(--color-accent-light) !important;
  background: rgba(255,255,255,0.06) !important;
}
:deep(.el-input__inner) {
  color: var(--color-text) !important;
  font-size: 15px !important;
}
:deep(.el-input__inner::placeholder) {
  color: var(--color-text-muted) !important;
}
:deep(.el-input__prefix) {
  color: var(--color-text-muted) !important;
}
:deep(.el-input.is-focus .el-input__prefix) {
  color: var(--color-accent) !important;
}
</style>
