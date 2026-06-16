<template>
  <div class="orders-page">
    <header class="header">
      <div class="header-inner">
        <button class="back-btn" @click="goBack">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="15 18 9 12 15 6"/>
          </svg>
          返回
        </button>
        <h1 class="header-title">我的订单</h1>
        <div class="header-spacer"></div>
      </div>
    </header>

    <main class="main">
      <!-- Loading -->
      <div v-if="loading" class="state-box">
        <div class="spinner"></div>
        <p class="state-text">加载订单中...</p>
      </div>

      <!-- Error -->
      <div v-else-if="error" class="state-box error-state">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="8" x2="12" y2="12"/>
          <line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
        <p class="state-text">{{ error }}</p>
        <button class="retry-btn" @click="fetchOrders">重试</button>
      </div>

      <!-- Empty -->
      <div v-else-if="orders.length === 0" class="state-box empty-state">
        <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z"/>
          <line x1="3" y1="6" x2="21" y2="6"/>
          <path d="M16 10a4 4 0 01-8 0"/>
        </svg>
        <p class="state-text">暂无订单</p>
        <router-link to="/" class="browse-link">去逛逛</router-link>
      </div>

      <!-- Order List -->
      <div v-else class="order-list">
        <p class="order-count">共 {{ total }} 笔订单</p>
        <div v-for="order in orders" :key="order.id" class="order-card">
          <div class="order-header">
            <span class="order-id">订单号 #{{ order.id }}</span>
            <span class="status-badge" :class="statusClass(order.status)">{{ statusLabel(order.status) }}</span>
          </div>
          <div class="order-body">
            <div class="order-field">
              <span class="field-label">商品 ID</span>
              <span class="field-value">{{ order.itemId }}</span>
            </div>
            <div class="order-field">
              <span class="field-label">秒杀价</span>
              <span class="field-value price">&yen;{{ order.flashPrice }}</span>
            </div>
            <div class="order-field">
              <span class="field-label">下单时间</span>
              <span class="field-value">{{ formatTime(order.createTime) }}</span>
            </div>
          </div>
          <div class="order-actions" v-if="order.status === 0">
            <button class="action-btn pay-btn" @click="handlePay(order)" :disabled="order._loading">
              立即支付
            </button>
            <button class="action-btn cancel-btn" @click="handleCancel(order)" :disabled="order._loading">
              取消订单
            </button>
          </div>
          <div class="order-actions" v-if="order.status === 1">
            <button class="action-btn refund-btn" @click="handleRefund(order)" :disabled="order._loading">
              申请退款
            </button>
          </div>
        </div>

        <!-- Pagination -->
        <div class="pagination" v-if="totalPages > 1">
          <button
            class="page-btn"
            :disabled="currentPage <= 1"
            @click="changePage(currentPage - 1)"
          >
            上一页
          </button>
          <span class="page-info">{{ currentPage }} / {{ totalPages }}</span>
          <button
            class="page-btn"
            :disabled="currentPage >= totalPages"
            @click="changePage(currentPage + 1)"
          >
            下一页
          </button>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getMyOrders, payOrder, cancelOrder, refundOrder } from '../api/order'
import { useToast } from '../composables/useToast'
import { useConfirm } from '../composables/useConfirm'

const router = useRouter()
const toast = useToast()
const { confirm } = useConfirm()

const orders = ref([])
const loading = ref(true)
const error = ref('')
const currentPage = ref(1)
const total = ref(0)
const totalPages = ref(0)
const pageSize = 10

const statusMap = {
  0: { label: '待支付', cls: 'status-pending' },
  1: { label: '已支付', cls: 'status-paid' },
  2: { label: '已取消', cls: 'status-cancelled' },
  3: { label: '已退款', cls: 'status-refunded' }
}

function statusLabel(status) {
  return (statusMap[status] || { label: '未知' }).label
}

function statusClass(status) {
  return (statusMap[status] || { cls: '' }).cls
}

async function fetchOrders(page = 1) {
  loading.value = true
  error.value = ''
  currentPage.value = page
  try {
    const res = await getMyOrders(page, pageSize)
    const data = res.data || {}
    orders.value = data.records || []
    total.value = data.total || 0
    totalPages.value = data.pages || Math.ceil((data.total || 0) / pageSize)
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '加载订单失败'
  } finally {
    loading.value = false
  }
}

function changePage(page) {
  if (page < 1 || page > totalPages.value) return
  fetchOrders(page)
}

function formatTime(time) {
  if (!time) return '-'
  const d = new Date(time)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const min = String(d.getMinutes()).padStart(2, '0')
  const s = String(d.getSeconds()).padStart(2, '0')
  return `${y}-${m}-${day} ${h}:${min}:${s}`
}

async function handlePay(order) {
  order._loading = true
  try {
    await payOrder(order.id)
    await fetchOrders(currentPage.value)
    toast.success('支付成功')
  } catch (e) {
    toast.error(e.response?.data?.msg || e.message || '支付失败')
  } finally {
    order._loading = false
  }
}

async function handleCancel(order) {
  const ok = await confirm('确定要取消该订单吗？')
  if (!ok) return
  order._loading = true
  try {
    await cancelOrder(order.id)
    await fetchOrders(currentPage.value)
    toast.success('订单已取消')
  } catch (e) {
    toast.error(e.response?.data?.msg || e.message || '取消失败')
  } finally {
    order._loading = false
  }
}

async function handleRefund(order) {
  const ok = await confirm('确定要退款吗？退款后库存将归还。')
  if (!ok) return
  order._loading = true
  try {
    await refundOrder(order.id)
    await fetchOrders(currentPage.value)
    toast.success('退款成功')
  } catch (e) {
    toast.error(e.response?.data?.msg || e.message || '退款失败')
  } finally {
    order._loading = false
  }
}

function goBack() {
  router.push('/')
}

onMounted(() => {
  fetchOrders()
})
</script>

<style scoped>
.orders-page {
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
.back-btn:hover { background: rgba(255,255,255,0.1); color: #fff; }
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
.main { max-width: 800px; margin: 0 auto; padding: var(--space-5); }

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
.state-text { font-size: 16px; color: var(--color-text-secondary); margin-top: var(--space-4); }
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
  min-height: 44px;
}
.retry-btn:hover { background: var(--color-accent-dark); }
.browse-link {
  margin-top: var(--space-4);
  color: var(--color-accent);
  text-decoration: none;
  font-size: 14px;
  font-weight: 600;
  padding: var(--space-2) var(--space-6);
  border: 1px solid var(--color-accent);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}
.browse-link:hover { background: var(--color-accent-light); }

/* ===== Order Count ===== */
.order-count {
  font-size: 13px;
  color: var(--color-text-muted);
  margin-bottom: var(--space-3);
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

/* ===== Order Card (Glass) ===== */
.order-card {
  background: var(--glass-bg);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-radius: var(--radius-md);
  padding: var(--space-4) var(--space-5);
  margin-bottom: var(--space-3);
  border: 1px solid var(--glass-border);
  transition: all var(--transition-fast);
}
.order-card:hover {
  border-color: rgba(255, 255, 255, 0.1);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
}
.order-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-3);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}
.order-id { font-size: 14px; font-weight: 600; color: var(--color-text); }

/* Status Badges */
.status-badge { font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: var(--radius-full); text-transform: uppercase; letter-spacing: 0.4px; }
.status-pending { background: var(--color-warning-light); color: var(--color-warning); border: 1px solid rgba(245,158,11,0.2); }
.status-paid { background: var(--color-success-light); color: #4ADE80; border: 1px solid rgba(74,222,128,0.2); }
.status-cancelled { background: rgba(255,255,255,0.03); color: var(--color-text-muted); border: 1px solid rgba(255,255,255,0.05); }
.status-refunded { background: var(--color-info-light); color: var(--color-info); border: 1px solid rgba(96,165,250,0.2); }

.order-body { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-2); }
.order-field { display: flex; flex-direction: column; gap: 2px; }
.field-label { font-size: 11px; color: var(--color-text-muted); font-weight: 500; text-transform: uppercase; letter-spacing: 0.3px; }
.field-value { font-size: 14px; color: var(--color-text); font-weight: 500; }
.field-value.price { color: var(--color-accent); font-family: var(--font-mono); font-weight: 600; }

/* ===== Actions ===== */
.order-actions {
  display: flex; gap: var(--space-3);
  margin-top: var(--space-4); padding-top: var(--space-3);
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}
.action-btn {
  padding: var(--space-2) var(--space-5);
  border: none; border-radius: var(--radius-md);
  font-size: 13px; font-weight: 600;
  cursor: pointer; transition: all var(--transition-fast);
  min-height: 38px; letter-spacing: 0.3px;
}
.action-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.pay-btn { background: linear-gradient(135deg, #C8A45C, #D4B96A); color: #06060A; }
.pay-btn:hover:not(:disabled) { box-shadow: 0 4px 15px var(--color-accent-glow); }
.cancel-btn { background: transparent; color: var(--color-text-muted); border: 1px solid rgba(255,255,255,0.08); }
.cancel-btn:hover:not(:disabled) { border-color: var(--color-danger); color: var(--color-danger); }
.refund-btn { background: rgba(96,165,250,0.1); color: var(--color-info); border: 1px solid rgba(96,165,250,0.15); }
.refund-btn:hover:not(:disabled) { background: rgba(96,165,250,0.18); }

/* ===== Pagination ===== */
.pagination {
  display: flex; justify-content: center; align-items: center;
  gap: var(--space-4); margin-top: var(--space-6); padding: var(--space-4) 0;
}
.page-btn {
  padding: var(--space-2) var(--space-5);
  background: var(--glass-bg);
  color: var(--color-text-secondary);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  font-size: 14px; font-weight: 500;
  cursor: pointer; transition: all var(--transition-fast);
  min-height: 40px;
}
.page-btn:hover:not(:disabled) { border-color: var(--color-accent); color: var(--color-accent); }
.page-btn:disabled { opacity: 0.25; cursor: not-allowed; }
.page-info { font-size: 14px; color: var(--color-text-muted); font-weight: 500; }

@media (max-width: 600px) {
  .order-body { grid-template-columns: 1fr 1fr; }
  .order-card { padding: var(--space-3) var(--space-4); }
}
</style>
