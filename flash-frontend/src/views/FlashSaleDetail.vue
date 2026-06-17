<template>
  <div class="detail-page">
    <header class="header">
      <div class="header-inner">
        <button class="back-btn" @click="goBack">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="15 18 9 12 15 6"/>
          </svg>
          返回
        </button>
        <h1 class="header-title">秒杀详情</h1>
        <div class="header-spacer"></div>
      </div>
    </header>

    <main class="main" v-if="!loading && sale">
      <div class="product-section">
        <!-- Image -->
        <div class="image-area">
          <img v-if="item.image" :src="item.image" class="detail-img" :alt="item.name" />
          <div v-else class="product-image">
            <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
            </svg>
          </div>
        </div>

        <!-- Info -->
        <div class="info-area">
          <h2 class="product-name">{{ item.name || sale.itemName || '商品 #' + sale.itemId }}</h2>

          <div class="tags" v-if="flashSaleStatus !== 'ended' && sale.stock > 0">
            <span class="tag tag-hot">限时秒杀</span>
            <span class="tag tag-limit" v-if="sale.limitPerUser">限购 {{ sale.limitPerUser }} 件/人</span>
          </div>

          <div class="price-section">
            <div class="price-row">
              <span class="flash-price">&yen;{{ sale.flashPrice }}</span>
              <span class="original-price">&yen;{{ item.price || sale.originalPrice || '-' }}</span>
              <span class="discount-tag" v-if="item.price && item.price > 0 && sale.flashPrice">
                -{{ Math.round((1 - sale.flashPrice / item.price) * 100) }}%
              </span>
            </div>
          </div>

          <div class="info-grid">
            <div class="info-item">
              <span class="info-label">状态</span>
              <span class="info-value" :class="statusClass">{{ statusText }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">剩余库存</span>
              <span class="info-value" :class="{ 'text-danger': sale.stock < 10 }">{{ sale.stock }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">剩余时间</span>
              <span class="info-value time-value" :class="{ urgent: isUrgent }">{{ countdownText }}</span>
            </div>
          </div>

          <div class="stock-bar-wrapper">
            <div class="stock-bar">
              <div class="stock-fill" :class="{ low: sale.stock < 10 }" :style="{ width: stockPercent + '%' }"></div>
            </div>
          </div>

          <p class="description" v-if="item.description">{{ item.description }}</p>
        </div>
      </div>

      <!-- Captcha -->
      <div class="captcha-section" v-if="canPurchase">
        <div class="captcha-row">
          <div class="captcha-img" @click="refreshCaptcha" title="点击刷新验证码">
            <div v-if="captchaSvg" v-html="captchaSvg"></div>
            <span v-else class="captcha-placeholder">加载中...</span>
          </div>
          <input
            v-model="captchaAnswer"
            type="text"
            class="captcha-input"
            placeholder="输入计算结果"
            autocomplete="off"
          />
        </div>
      </div>

      <!-- CTA -->
      <div class="cta-section">
        <button
          class="cta-btn"
          :class="{ disabled: !canPurchase, loading: purchasing }"
          :disabled="!canPurchase || purchasing"
          @click="handlePurchase"
        >
          <span v-if="purchasing" class="spinner spinner--sm" style="border-top-color: #fff; border-color: rgba(255,255,255,0.3);"></span>
          <span v-else>{{ purchaseBtnText }}</span>
        </button>
      </div>
    </main>

    <!-- Loading -->
    <main class="main" v-else-if="loading">
      <div class="loading-state">
        <div class="spinner"></div>
        <p>加载中...</p>
      </div>
    </main>

    <!-- Error -->
    <main class="main" v-else-if="loadError">
      <div class="error-state">
        <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="8" x2="12" y2="12"/>
          <line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
        <p>{{ loadError }}</p>
        <button class="retry-btn" @click="fetchData">重试</button>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFlashSaleDetail, purchase } from '../api/flash-sale'
import { getItemDetail } from '../api/item'
import { getOrderStatus } from '../api/order'
import { useToast } from '../composables/useToast'

const route = useRoute()
const router = useRouter()
const toast = useToast()

const sale = ref(null)
const item = ref({})
const loading = ref(true)
const loadError = ref('')
const purchasing = ref(false)
const tick = ref(0)
let timer = null

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

const flashSaleStatus = computed(() => {
  if (!sale.value) return ''
  const now = Date.now()
  const start = new Date(sale.value.startTime).getTime()
  const end = new Date(sale.value.endTime).getTime()
  if (now < start) return 'upcoming'
  if (now > end) return 'ended'
  if (sale.value.stock <= 0) return 'soldout'
  return 'active'
})

const statusText = computed(() => {
  const map = { upcoming: '即将开始', ended: '已结束', soldout: '已售罄', active: '进行中' }
  return map[flashSaleStatus.value] || ''
})

const statusClass = computed(() => 'status-' + flashSaleStatus.value)

const isUrgent = computed(() => {
  if (!sale.value?.endTime) return false
  const now = Date.now()
  const end = new Date(sale.value.endTime).getTime()
  const diff = end - now
  return diff > 0 && diff < 3600000
})

const countdownText = computed(() => {
  if (!sale.value?.endTime) return '--:--:--'
  tick.value
  const now = Date.now()
  const end = new Date(sale.value.endTime).getTime()
  const diff = Math.max(0, end - now)
  if (diff <= 0) return '已结束'
  const h = Math.floor(diff / 3600000)
  const m = Math.floor((diff % 3600000) / 60000)
  const s = Math.floor((diff % 60000) / 1000)
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
})

const stockPercent = computed(() => {
  if (!sale.value?.stock) return 0
  return Math.min(100, (sale.value.stock / 100) * 100)
})

const canPurchase = computed(() => flashSaleStatus.value === 'active')

const purchaseBtnText = computed(() => {
  const map = { upcoming: '尚未开始', ended: '已结束', soldout: '已售罄', active: '立即抢购' }
  return map[flashSaleStatus.value] || '不可用'
})

async function fetchData() {
  loading.value = true
  loadError.value = ''
  const id = route.params.id
  try {
    const res = await getFlashSaleDetail(id)
    sale.value = res.data
    if (res.data.itemId) {
      try {
        const itemRes = await getItemDetail(res.data.itemId)
        item.value = itemRes.data || {}
      } catch (e) {
        item.value = {}
      }
    }
  } catch (e) {
    loadError.value = e.response?.data?.msg || e.message || '加载秒杀详情失败'
  } finally {
    loading.value = false
  }
}

async function handlePurchase() {
  if (!canPurchase.value || purchasing.value) return
  purchasing.value = true
  try {
    const res = await purchase(route.params.id, captchaId.value, captchaAnswer.value)
    const messageKey = res.data?.messageKey
    if (!messageKey) {
      toast.success('抢购成功！正在跳转到订单页...')
      setTimeout(() => router.push('/orders'), 1000)
      return
    }

    toast.success('抢购请求已提交，正在确认订单...')

    // Poll for order creation (max 20 attempts, ~10s)
    let confirmed = false
    for (let i = 0; i < 20; i++) {
      await new Promise(r => setTimeout(r, 500))
      try {
        const statusRes = await getOrderStatus(messageKey)
        if (statusRes.data?.status === 'DONE') {
          confirmed = true
          break
        }
      } catch (e) {
        // ignore poll errors, keep retrying
      }
    }

    if (confirmed) {
      toast.success('订单已创建，正在跳转...')
    } else {
      toast.success('抢购请求已提交，请稍后查看订单')
    }
    router.push('/orders')
  } catch (e) {
    toast.error(e.response?.data?.msg || e.message || '抢购失败')
    refreshCaptcha()
  } finally {
    purchasing.value = false
  }
}

function goBack() {
  router.push('/')
}

onMounted(() => {
  fetchData()
  refreshCaptcha()
  timer = setInterval(() => {
    tick.value++
  }, 1000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.detail-page {
  min-height: 100vh;
  background: var(--color-bg);
  padding-bottom: 100px;
}

/* ===== Header (Glass) ===== */
.header {
  position: sticky;
  top: 0;
  z-index: 10;
  background: rgba(6, 6, 10, 0.8);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  min-height: 56px;
}
.header-inner {
  max-width: 800px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  padding: 0 var(--space-4);
  height: 56px;
}
.back-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 500;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all var(--transition-fast);
  min-height: 38px;
}
.back-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}
.header-title {
  flex: 1;
  text-align: center;
  font-family: var(--font-heading);
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 1px;
}
.header-spacer { width: 70px; }

/* ===== Main ===== */
.main {
  max-width: 800px;
  margin: 0 auto;
  padding: var(--space-5);
}

/* ===== Product Image ===== */
.image-area { margin-bottom: var(--space-4); }
.product-image {
  width: 100%;
  height: 280px;
  background: linear-gradient(145deg, rgba(200,164,92,0.06) 0%, rgba(200,164,92,0.02) 40%, rgba(255,255,255,0.02) 100%);
  border-radius: var(--radius-lg);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-accent);
  opacity: 0.4;
  border: 1px solid rgba(255, 255, 255, 0.04);
}
.detail-img {
  width: 100%;
  height: 280px;
  object-fit: contain;
  border-radius: var(--radius-lg);
  border: 1px solid rgba(255, 255, 255, 0.04);
  background: rgba(255,255,255,0.02);
}

/* ===== Info Card (Glass) ===== */
.info-area {
  background: var(--glass-bg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: var(--radius-lg);
  padding: var(--space-6);
  border: 1px solid var(--glass-border);
}

.product-name {
  font-family: var(--font-heading);
  font-size: 24px;
  font-weight: 600;
  color: #fff;
  margin-bottom: var(--space-3);
  line-height: 1.3;
}

/* Tags */
.tags { display: flex; gap: var(--space-2); margin-bottom: var(--space-4); flex-wrap: wrap; }
.tag {
  font-size: 11px;
  padding: 4px 10px;
  border-radius: var(--radius-full);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.6px;
}
.tag-hot { background: rgba(231,76,60,0.15); color: var(--color-danger); border: 1px solid rgba(231,76,60,0.2); }
.tag-limit { background: rgba(200,164,92,0.1); color: var(--color-accent); border: 1px solid rgba(200,164,92,0.15); }

/* Price */
.price-section { margin-bottom: var(--space-5); }
.price-row { display: flex; align-items: baseline; gap: var(--space-3); flex-wrap: wrap; }
.flash-price {
  font-family: var(--font-mono);
  font-size: 38px;
  font-weight: 600;
  color: var(--color-accent);
  letter-spacing: -1px;
}
.original-price { font-size: 15px; color: var(--color-text-muted); text-decoration: line-through; }
.discount-tag {
  font-size: 12px;
  color: var(--color-accent);
  font-weight: 700;
  background: var(--color-accent-light);
  padding: 2px 10px;
  border-radius: var(--radius-full);
  border: 1px solid rgba(200,164,92,0.12);
}

/* Info Grid */
.info-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-3); margin-bottom: var(--space-4); }
.info-item { display: flex; flex-direction: column; gap: 2px; }
.info-label { font-size: 11px; color: var(--color-text-muted); font-weight: 500; text-transform: uppercase; letter-spacing: 0.5px; }
.info-value { font-size: 15px; font-weight: 600; color: var(--color-text); }
.info-value.text-danger { color: var(--color-danger); }
.time-value { font-family: var(--font-mono); font-size: 14px; }
.time-value.urgent { color: var(--color-danger); }
.status-active { color: #4ADE80; }
.status-upcoming { color: var(--color-warning); }
.status-ended, .status-soldout { color: var(--color-text-muted); }

/* Stock Bar */
.stock-bar-wrapper { margin-bottom: var(--space-4); }
.stock-bar { height: 6px; background: rgba(255,255,255,0.05); border-radius: var(--radius-full); overflow: hidden; }
.stock-fill { height: 100%; background: var(--color-accent); border-radius: var(--radius-full); transition: width 0.5s ease; }
.stock-fill.low { background: var(--color-danger); }

.description { font-size: 14px; color: var(--color-text-secondary); line-height: 1.7; }

/* ===== CTA ===== */
.cta-section { margin-top: var(--space-5); }
.cta-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: var(--space-4);
  background: linear-gradient(135deg, #C8A45C 0%, #D4B96A 50%, #A8883E 100%);
  color: #06060A;
  border: none;
  border-radius: var(--radius-lg);
  font-size: 17px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-fast);
  min-height: 56px;
  letter-spacing: 1px;
  text-transform: uppercase;
}
.cta-btn:hover:not(.disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 30px rgba(200, 164, 92, 0.3);
}
.cta-btn:active:not(.disabled) { transform: translateY(0); }
.cta-btn.disabled {
  background: rgba(255, 255, 255, 0.04);
  color: var(--color-text-muted);
  cursor: not-allowed;
  box-shadow: none;
}
.cta-btn.loading { cursor: not-allowed; }

/* ===== States ===== */
.loading-state, .error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--space-12) var(--space-6);
  color: var(--color-text-secondary);
  gap: var(--space-4);
}
.retry-btn {
  padding: var(--space-2) var(--space-8);
  background: var(--color-accent);
  color: #06060A;
  border: none;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--transition-fast);
  min-height: 44px;
}
.retry-btn:hover { background: var(--color-accent-dark); }

@media (max-width: 600px) {
  .info-grid { grid-template-columns: 1fr 1fr; }
  .product-image { height: 200px; border-radius: 0; }
  .info-area { border-radius: 0; }
  .cta-btn {
    border-radius: 0;
    position: fixed; bottom: 0; left: 0; right: 0; z-index: 20;
  }
  .detail-page { padding-bottom: 72px; }
}

/* ===== Captcha ===== */
.captcha-section {
  margin-bottom: var(--space-3);
}
.captcha-row {
  display: flex;
  gap: 12px;
  align-items: center;
}
.captcha-img {
  flex-shrink: 0;
  cursor: pointer;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.08);
  transition: all 0.15s ease;
  line-height: 0;
}
.captcha-img:hover {
  border-color: var(--color-accent);
}
.captcha-input {
  flex: 1;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
  color: var(--color-text);
  font-size: 15px;
  min-height: 50px;
  outline: none;
  transition: all 0.15s ease;
}
.captcha-input:focus {
  border-color: var(--color-accent);
  box-shadow: 0 0 0 3px var(--color-accent-light);
  background: rgba(255, 255, 255, 0.06);
}
.captcha-input::placeholder {
  color: var(--color-text-muted);
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
