<template>
  <div class="auth-page">
    <!-- Brand Hero -->
    <div class="auth-hero">
      <div class="hero-glow"></div>
      <div class="auth-hero-content">
        <div class="brand-icon">
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
          </svg>
        </div>
        <h1 class="brand-title">限时秒杀</h1>
        <p class="brand-tagline">会员专享</p>
      </div>
    </div>

    <!-- Auth Card -->
    <div class="auth-container">
      <div class="auth-card">
        <h2 class="card-title">欢迎回来</h2>
        <p class="card-subtitle">登录后继续</p>
        <form @submit.prevent="handleLogin">
          <div class="form-group">
            <label class="form-label" for="username">用户名</label>
            <input
              id="username"
              v-model="username"
              type="text"
              placeholder="请输入用户名"
              autocomplete="username"
              required
            />
          </div>
          <div class="form-group">
            <label class="form-label" for="password">密码</label>
            <input
              id="password"
              v-model="password"
              type="password"
              placeholder="请输入密码"
              autocomplete="current-password"
              required
            />
          </div>
          <div class="form-group">
            <label class="form-label">验证码</label>
            <div class="captcha-row">
              <input
                v-model="captchaAnswer"
                type="text"
                class="captcha-input"
                placeholder="请输入计算结果"
                autocomplete="off"
              />
              <div class="captcha-img" @click="refreshCaptcha" title="点击刷新">
                <div v-if="captchaSvg" v-html="captchaSvg"></div>
                <span v-else class="captcha-placeholder">加载中...</span>
              </div>
            </div>
          </div>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button type="submit" :disabled="loading" class="submit-btn">
            <span v-if="loading" class="spinner spinner--sm"></span>
            <span v-else>登录</span>
          </button>
        </form>
        <p class="auth-link">
          还没有账号？ <router-link to="/register">注册账号</router-link>
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api/auth'

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)
const router = useRouter()

const captchaId = ref('')
const captchaSvg = ref('')
const captchaAnswer = ref('')

async function refreshCaptcha() {
  try {
    const res = await fetch('/api/auth/captcha')
    const json = await res.json()
    if (json.code === 200) {
      captchaId.value = json.data.captchaId
      captchaSvg.value = json.data.svg
    }
  } catch (e) { /* ignore */ }
}

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    const res = await login(username.value, password.value, captchaId.value, captchaAnswer.value)
    localStorage.setItem('accessToken', res.data.accessToken)
    localStorage.setItem('refreshToken', res.data.refreshToken)
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '登录失败'
    refreshCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  refreshCaptcha()
})
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  background: var(--color-bg);
  display: flex;
  flex-direction: column;
}

/* ===== Hero ===== */
.auth-hero {
  position: relative;
  padding: var(--space-12) var(--space-6) var(--space-8);
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
  background: radial-gradient(ellipse, rgba(200, 164, 92, 0.06) 0%, transparent 70%);
  pointer-events: none;
}
.auth-hero-content {
  position: relative;
}
.brand-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: var(--radius-lg);
  background: rgba(200, 164, 92, 0.08);
  color: var(--color-accent);
  margin-bottom: var(--space-4);
  border: 1px solid rgba(200, 164, 92, 0.1);
  backdrop-filter: blur(8px);
}
.brand-title {
  font-family: var(--font-heading);
  font-size: 32px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 3px;
  margin-bottom: var(--space-2);
}
.brand-tagline {
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 3px;
  text-transform: uppercase;
  color: var(--color-accent);
}

/* ===== Card ===== */
.auth-container {
  display: flex;
  justify-content: center;
  padding: 0 var(--space-4) var(--space-8);
  flex: 1;
}

.auth-card {
  background: var(--glass-bg);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-xl);
  padding: var(--space-8) var(--space-8);
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
  margin-bottom: var(--space-1);
}
.card-subtitle {
  text-align: center;
  font-size: 14px;
  color: var(--color-text-muted);
  margin-bottom: var(--space-8);
}

/* ===== Form ===== */
.form-group {
  margin-bottom: var(--space-5);
}

.form-label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
  margin-bottom: var(--space-1);
  letter-spacing: 0.3px;
}

input {
  width: 100%;
  padding: var(--space-3) var(--space-4);
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-md);
  font-size: 15px;
  color: var(--color-text);
  transition: all var(--transition-fast);
  min-height: 48px;
}
input::placeholder {
  color: var(--color-text-muted);
}
input:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: 0 0 0 3px var(--color-accent-light);
  background: rgba(255, 255, 255, 0.06);
}

.form-error {
  color: var(--color-danger);
  font-size: 13px;
  margin-bottom: var(--space-4);
  text-align: center;
  padding: var(--space-2) var(--space-3);
  background: var(--color-danger-light);
  border-radius: var(--radius-sm);
  font-weight: 500;
}

/* ===== Button ===== */
.submit-btn {
  width: 100%;
  padding: var(--space-3) var(--space-4);
  background: linear-gradient(135deg, #C8A45C 0%, #D4B96A 50%, #A8883E 100%);
  color: #06060A;
  border: none;
  border-radius: var(--radius-md);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-fast);
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  letter-spacing: 0.5px;
}
.submit-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 20px var(--color-accent-glow);
}
.submit-btn:active:not(:disabled) {
  transform: translateY(0);
}
.submit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ===== Link ===== */
.auth-link {
  text-align: center;
  margin-top: var(--space-6);
  font-size: 14px;
  color: var(--color-text-muted);
}
.auth-link a {
  color: var(--color-accent);
  font-weight: 600;
}
.auth-link a:hover {
  text-decoration: underline;
}

/* ===== Captcha ===== */
.captcha-row {
  display: flex;
  gap: 12px;
  align-items: center;
}
.captcha-input {
  flex: 1;
}
.captcha-img {
  flex-shrink: 0;
  cursor: pointer;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.08);
  transition: all 0.15s ease;
  line-height: 0;
}
.captcha-img:hover {
  border-color: var(--color-accent);
  opacity: 0.9;
}
.captcha-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 160px;
  height: 50px;
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
