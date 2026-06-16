<template>
  <div class="home">
    <!-- Header — Glass -->
    <header class="header">
      <div class="header-inner">
        <div class="header-brand">
          <svg class="brand-logo" width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
          </svg>
          <span class="header-title">限时秒杀</span>
        </div>
        <div class="header-actions">
          <router-link to="/orders" class="nav-link">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z"/>
              <line x1="3" y1="6" x2="21" y2="6"/>
              <path d="M16 10a4 4 0 01-8 0"/>
            </svg>
            我的订单
          </router-link>
          <button @click="handleLogout" class="logout-btn">退出</button>
        </div>
      </div>
    </header>

    <!-- Hero -->
    <section class="hero">
      <div class="hero-glow"></div>
      <div class="hero-content">
        <p class="hero-eyebrow">专属特权</p>
        <h2 class="hero-title">精选好物，限时开抢</h2>
        <p class="hero-subtitle">仅此一次，错过不再。</p>
      </div>
    </section>

    <main class="main">
      <section class="content">
        <!-- Loading Skeleton -->
        <div v-if="loading" class="sale-grid">
          <div v-for="n in 4" :key="'skeleton-' + n" class="sale-card skeleton-card">
            <div class="card-image skeleton"></div>
            <div class="card-body">
              <div class="skeleton" style="height:18px;width:70%;margin-bottom:10px"></div>
              <div class="skeleton" style="height:32px;width:45%;margin-bottom:12px"></div>
              <div class="skeleton" style="height:6px;width:100%;margin-bottom:8px"></div>
              <div class="skeleton" style="height:28px;width:100%"></div>
            </div>
          </div>
        </div>

        <!-- Error -->
        <div v-else-if="error" class="state-box">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <p class="state-text">{{ error }}</p>
          <button @click="fetchSales" class="retry-btn">重试</button>
        </div>

        <!-- Empty -->
        <div v-else-if="sales.length === 0" class="state-box">
          <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"/>
            <path d="M12 6v6l4 2"/>
          </svg>
          <p class="state-text">暂无活动</p>
          <p class="state-hint">新品即将上线</p>
        </div>

        <!-- Sale Grid -->
        <div v-else class="sale-grid">
          <div
            v-for="sale in sales"
            :key="sale.id"
            class="sale-card"
            @click="goToDetail(sale.id)"
            role="button"
            tabindex="0"
            @keydown.enter="goToDetail(sale.id)"
          >
            <div class="card-image">
              <img v-if="sale.itemImage" :src="sale.itemImage" class="card-img" :alt="sale.itemName" />
              <div v-else class="image-placeholder">
                <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round">
                  <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
                </svg>
              </div>
              <div class="image-shine"></div>
              <span v-if="isUrgent(sale)" class="urgent-badge">即将结束</span>
            </div>
            <div class="card-body">
              <h3 class="item-name">{{ sale.itemName || '商品 #' + sale.itemId }}</h3>

              <div class="price-row">
                <span class="flash-price">&yen;{{ sale.flashPrice }}</span>
                <span v-if="sale.originalPrice" class="original-price">&yen;{{ sale.originalPrice }}</span>
              </div>

              <div class="stock-section">
                <div class="stock-bar">
                  <div
                    class="stock-fill"
                    :class="{ low: sale.stock < 10 }"
                    :style="{ width: stockPercent(sale) + '%' }"
                  ></div>
                </div>
                <span class="stock-text" :class="{ urgent: sale.stock < 10 }">
                  {{ sale.stock > 0 ? sale.stock + ' 件' : '已售罄' }}
                </span>
              </div>

              <div class="countdown" :class="{ urgent: isUrgent(sale) }">
                <svg class="clock-icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10"/>
                  <path d="M12 6v6l4 2"/>
                </svg>
                <span class="countdown-digits">{{ countdown(sale) }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getActiveFlashSales } from '../api/flash-sale'

const router = useRouter()
const sales = ref([])
const loading = ref(true)
const error = ref('')
const tick = ref(0)
let timer = null

async function fetchSales() {
  loading.value = true
  error.value = ''
  try {
    const res = await getActiveFlashSales()
    sales.value = res.data || []
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function countdown(sale) {
  if (!sale.endTime) return '--:--:--'
  tick.value
  const now = Date.now()
  const end = new Date(sale.endTime).getTime()
  const diff = Math.max(0, end - now)
  if (diff <= 0) return '已结束'
  const h = Math.floor(diff / 3600000)
  const m = Math.floor((diff % 3600000) / 60000)
  const s = Math.floor((diff % 60000) / 1000)
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

function isUrgent(sale) {
  if (!sale.endTime) return false
  tick.value
  const now = Date.now()
  const end = new Date(sale.endTime).getTime()
  return end - now > 0 && end - now < 3600000
}

function stockPercent(sale) {
  if (sale.stock == null) return 0
  if (sale.stock <= 0) return 0
  return Math.min(100, (sale.stock / 100) * 100)
}

function goToDetail(id) {
  router.push('/flash-sale/' + id)
}

function handleLogout() {
  localStorage.removeItem('accessToken')
  localStorage.removeItem('refreshToken')
  router.push('/login')
}

onMounted(() => {
  fetchSales()
  timer = setInterval(() => { tick.value++ }, 1000)
})

onUnmounted(() => {
  if (timer) { clearInterval(timer); timer = null }
})
</script>

<style scoped>
.home {
  min-height: 100vh;
  background: var(--color-bg);
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
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 var(--space-6);
  height: 56px;
}
.header-brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.brand-logo {
  color: var(--color-accent);
}
.header-title {
  font-family: var(--font-heading);
  font-size: 20px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 2px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.nav-link {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--color-text-secondary);
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  transition: all var(--transition-fast);
  letter-spacing: 0.3px;
}
.nav-link:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.06);
}
.logout-btn {
  padding: var(--space-2) var(--space-4);
  background: transparent;
  color: var(--color-text-secondary);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition-fast);
  min-height: 36px;
  letter-spacing: 0.3px;
}
.logout-btn:hover {
  background: rgba(231, 76, 60, 0.12);
  border-color: var(--color-danger);
  color: var(--color-danger);
}

/* ===== Hero ===== */
.hero {
  position: relative;
  padding: var(--space-16) var(--space-6) var(--space-12);
  text-align: center;
  overflow: hidden;
}
.hero-glow {
  position: absolute;
  top: -120px;
  left: 50%;
  transform: translateX(-50%);
  width: 500px;
  height: 300px;
  background: radial-gradient(ellipse, rgba(200, 164, 92, 0.08) 0%, transparent 70%);
  pointer-events: none;
}
.hero-content {
  position: relative;
  max-width: 600px;
  margin: 0 auto;
}
.hero-eyebrow {
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 4px;
  text-transform: uppercase;
  color: var(--color-accent);
  margin-bottom: var(--space-4);
}
.hero-title {
  font-family: var(--font-heading);
  font-size: clamp(36px, 6vw, 56px);
  font-weight: 600;
  line-height: 1.15;
  color: #fff;
  margin-bottom: var(--space-4);
  letter-spacing: -0.5px;
}
.hero-subtitle {
  font-size: 16px;
  color: var(--color-text-secondary);
  font-weight: 300;
  letter-spacing: 0.3px;
}

/* ===== Main ===== */
.main {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 var(--space-4) var(--space-16);
}

/* ===== States ===== */
.state-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-16) var(--space-6);
  text-align: center;
  color: var(--color-text-muted);
}
.state-text {
  font-size: 16px;
  color: var(--color-text-secondary);
  margin-top: var(--space-4);
}
.state-hint {
  font-size: 14px;
  color: var(--color-text-muted);
  margin-top: var(--space-2);
}
.retry-btn {
  margin-top: var(--space-4);
  padding: var(--space-2) var(--space-8);
  background: var(--color-accent);
  color: #06060A;
  border: none;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition-fast);
  min-height: 44px;
}
.retry-btn:hover {
  background: var(--color-accent-dark);
}

/* ===== Grid ===== */
.sale-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-5);
}
@media (min-width: 600px) {
  .sale-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (min-width: 960px) {
  .sale-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (min-width: 1200px) {
  .sale-grid { grid-template-columns: repeat(4, 1fr); }
}

/* ===== Sale Card (Glass) ===== */
.sale-card {
  background: var(--glass-bg);
  backdrop-filter: blur(var(--glass-blur));
  -webkit-backdrop-filter: blur(var(--glass-blur));
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  cursor: pointer;
  transition: all var(--transition-normal);
}
.sale-card:hover {
  transform: translateY(-2px);
  border-color: rgba(255, 255, 255, 0.12);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(200, 164, 92, 0.06);
}
.sale-card:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 2px;
}
.skeleton-card {
  pointer-events: none;
}

/* Card Image */
.card-image {
  position: relative;
  height: 180px;
  background: linear-gradient(145deg, rgba(200, 164, 92, 0.06) 0%, rgba(200, 164, 92, 0.02) 40%, rgba(255, 255, 255, 0.02) 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.card-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.image-placeholder {
  color: var(--color-accent);
  opacity: 0.15;
}
.image-shine {
  position: absolute;
  top: 0;
  left: -100%;
  width: 60%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.03), transparent);
  transform: skewX(-15deg);
  transition: left 0.6s ease;
}
.sale-card:hover .image-shine {
  left: 120%;
}
.urgent-badge {
  position: absolute;
  top: var(--space-3);
  right: var(--space-3);
  background: rgba(231, 76, 60, 0.15);
  color: var(--color-danger);
  font-size: 10px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: var(--radius-full);
  letter-spacing: 0.8px;
  text-transform: uppercase;
  border: 1px solid rgba(231, 76, 60, 0.2);
}

/* Card Body */
.card-body {
  padding: var(--space-5) var(--space-5);
}

.item-name {
  font-size: 15px;
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: var(--space-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price-row {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}
.flash-price {
  font-family: var(--font-mono);
  font-size: 22px;
  font-weight: 600;
  color: var(--color-accent);
}
.original-price {
  font-size: 13px;
  color: var(--color-text-muted);
  text-decoration: line-through;
}

/* Stock */
.stock-section {
  margin-bottom: var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.stock-bar {
  flex: 1;
  height: 4px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: var(--radius-full);
  overflow: hidden;
}
.stock-fill {
  height: 100%;
  background: var(--color-accent);
  border-radius: var(--radius-full);
  transition: width 0.6s ease;
}
.stock-fill.low {
  background: var(--color-danger);
}
.stock-text {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-text-secondary);
  font-weight: 500;
  white-space: nowrap;
}
.stock-text.urgent {
  color: var(--color-danger);
  font-weight: 600;
}

/* Countdown */
.countdown {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: rgba(255, 255, 255, 0.03);
  border-radius: var(--radius-sm);
  border: 1px solid rgba(255, 255, 255, 0.04);
}
.countdown.urgent {
  background: rgba(231, 76, 60, 0.08);
  border-color: rgba(231, 76, 60, 0.15);
}
.clock-icon {
  flex-shrink: 0;
  color: var(--color-text-muted);
}
.countdown.urgent .clock-icon {
  color: var(--color-danger);
}
.countdown-digits {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
}
.countdown.urgent .countdown-digits {
  color: var(--color-danger);
}
</style>
