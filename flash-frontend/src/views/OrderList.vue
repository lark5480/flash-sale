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
      <!-- 搜索与筛选 -->
      <div class="filter-bar">
        <div class="search-box">
          <svg class="search-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="11" cy="11" r="7"/>
            <line x1="20" y1="20" x2="16.5" y2="16.5"/>
          </svg>
          <input
            v-model="keywordInput"
            class="search-input"
            type="text"
            placeholder="搜索订单号或商品名称"
            aria-label="搜索订单"
            @keyup.enter="flushKeyword"
          />
          <button v-if="keywordInput" class="clear-btn" type="button" title="清空" @click="clearKeyword">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>

        <div class="status-tabs" role="tablist" aria-label="订单状态筛选">
          <button
            v-for="opt in statusOptions"
            :key="String(opt.value)"
            class="status-tab"
            :class="{ active: statusFilter === opt.value }"
            role="tab"
            :aria-selected="statusFilter === opt.value"
            @click="selectStatus(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>

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
        <button class="retry-btn" @click="fetchOrders(currentPage)">重试</button>
      </div>

      <!-- Empty -->
      <div v-else-if="orders.length === 0 && !hasFilter" class="state-box empty-state">
        <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z"/>
          <line x1="3" y1="6" x2="21" y2="6"/>
          <path d="M16 10a4 4 0 01-8 0"/>
        </svg>
        <p class="state-text">暂无订单</p>
        <router-link to="/" class="browse-link">去逛逛</router-link>
      </div>

      <!-- Empty（筛选无结果） -->
      <div v-else-if="orders.length === 0" class="state-box empty-state">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="11" cy="11" r="7"/>
          <line x1="20" y1="20" x2="16.5" y2="16.5"/>
        </svg>
        <p class="state-text">没有匹配的订单</p>
        <p class="state-hint">试试更换关键词或订单状态</p>
        <button class="retry-btn" @click="resetFilter">清空筛选</button>
      </div>

      <!-- Order List -->
      <div v-else class="order-list" :class="{ 'is-refreshing': refreshing }">
        <div class="list-header">
          <p class="order-count">共 {{ total }} 笔订单</p>
          <button v-if="hasFilter" class="reset-link" @click="resetFilter">清空筛选</button>
        </div>
        <div v-for="order in orders" :key="order.id" class="order-card">
          <div class="order-header">
            <span class="order-id">订单号 #{{ order.id }}</span>
            <span class="status-badge" :class="statusClass(order.status)">{{ statusLabel(order.status) }}</span>
          </div>
          <div class="order-body">
            <div class="order-product">
              <img v-if="order.itemImage" class="product-thumb" :src="order.itemImage" :alt="itemTitle(order)" />
              <div v-else class="product-thumb product-thumb-empty">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z"/>
                  <line x1="3" y1="6" x2="21" y2="6"/>
                </svg>
              </div>
              <div class="product-info">
                <p class="product-name">{{ itemTitle(order) }}</p>
                <p class="product-sub">商品 #{{ order.itemId }} · 活动 #{{ order.flashSaleId }}</p>
              </div>
            </div>
            <div class="order-fields">
              <div class="order-field">
                <span class="field-label">秒杀价</span>
                <span class="field-value price">&yen;{{ order.flashPrice }}</span>
              </div>
              <div class="order-field">
                <span class="field-label">下单时间</span>
                <span class="field-value">{{ formatTime(order.createTime) }}</span>
              </div>
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
          <div class="order-actions" v-if="order.status === 2">
            <button class="action-btn delete-btn" @click="handleDelete(order)" :disabled="order._loading">
              删除订单
            </button>
          </div>
        </div>

        <!-- Pagination -->
        <Pagination
          v-if="total > 0"
          v-model:page="currentPage"
          v-model:size="pageSize"
          :total="total"
          :page-sizes="pageSizeOptions"
          :disabled="refreshing"
          @change="handlePageChange"
        />
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getMyOrders, payOrder, cancelOrder, refundOrder, deleteOrder } from '../api/order'
import { useToast } from '../composables/useToast'
import { useConfirm } from '../composables/useConfirm'
import Pagination from '../components/Pagination.vue'
import { parseTime } from '../utils/time'

const router = useRouter()
const toast = useToast()
const { confirm } = useConfirm()

const orders = ref([])
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const currentPage = ref(1)
const total = ref(0)
const totalPages = ref(0)
const pageSize = ref(10)
const pageSizeOptions = [10, 20, 50]

/** 搜索输入框的原始值（防抖后同步到 keyword） */
const keywordInput = ref('')
const keyword = ref('')
const statusFilter = ref(null)

const statusMap = {
  0: { label: '待支付', cls: 'status-pending' },
  1: { label: '已支付', cls: 'status-paid' },
  2: { label: '已取消', cls: 'status-cancelled' },
  3: { label: '已退款', cls: 'status-refunded' }
}

const statusOptions = [
  { value: null, label: '全部' },
  { value: 0, label: '待支付' },
  { value: 1, label: '已支付' },
  { value: 2, label: '已取消' },
  { value: 3, label: '已退款' }
]

/** 是否处于筛选状态（用于区分"无订单"与"筛选无结果"） */
const hasFilter = computed(() => !!keyword.value || statusFilter.value !== null)

function statusLabel(status) {
  return (statusMap[status] || { label: '未知' }).label
}

function statusClass(status) {
  return (statusMap[status] || { cls: '' }).cls
}

let searchTimer = null

async function requestOrders(page) {
  const res = await getMyOrders({
    page,
    size: pageSize.value,
    status: statusFilter.value,
    keyword: keyword.value
  })
  const data = res.data || {}
  const records = data.records || []
  total.value = data.total || 0
  totalPages.value = data.pages || Math.ceil(total.value / pageSize.value)
  // 末页数据被删空时（如删掉了最后一页的唯一一条），自动回退到最后一页
  if (records.length === 0 && totalPages.value > 0 && page > totalPages.value) {
    return requestOrders(totalPages.value)
  }
  orders.value = records
  currentPage.value = page
}

async function fetchOrders(page = currentPage.value) {
  if (orders.value.length === 0) {
    loading.value = true
  } else {
    refreshing.value = true
  }
  error.value = ''
  try {
    await requestOrders(page)
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '加载订单失败'
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

function handlePageChange() {
  fetchOrders(currentPage.value)
}

function applyKeyword() {
  const next = keywordInput.value.trim()
  if (next === keyword.value) return
  keyword.value = next
  currentPage.value = 1
  fetchOrders(1)
}

/** 回车立即搜索，跳过防抖等待 */
function flushKeyword() {
  if (searchTimer) {
    clearTimeout(searchTimer)
    searchTimer = null
  }
  applyKeyword()
}

function clearKeyword() {
  keywordInput.value = ''
  flushKeyword()
}

function selectStatus(status) {
  if (statusFilter.value === status) return
  statusFilter.value = status
  currentPage.value = 1
  fetchOrders(1)
}

function resetFilter() {
  if (searchTimer) {
    clearTimeout(searchTimer)
    searchTimer = null
  }
  keywordInput.value = ''
  keyword.value = ''
  statusFilter.value = null
  currentPage.value = 1
  fetchOrders(1)
}

// 输入防抖 300ms，避免每敲一个字符都打一次接口
watch(keywordInput, () => {
  if (searchTimer) {
    clearTimeout(searchTimer)
  }
  searchTimer = setTimeout(applyKeyword, 300)
})

function itemTitle(order) {
  return order.itemName || '商品 #' + order.itemId
}

function formatTime(time) {
  if (!time) return '-'
  const ts = parseTime(time)
  if (Number.isNaN(ts)) return String(time)
  const d = new Date(ts)
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

async function handleDelete(order) {
  const ok = await confirm('确定要删除该订单吗？')
  if (!ok) return
  order._loading = true
  try {
    await deleteOrder(order.id)
    await fetchOrders(currentPage.value)
    toast.success('订单已删除')
  } catch (e) {
    toast.error(e.response?.data?.msg || e.message || '删除失败')
  } finally {
    order._loading = false
  }
}

function goBack() {
  router.push('/')
}

onMounted(() => {
  fetchOrders(1)
})

onUnmounted(() => {
  if (searchTimer) {
    clearTimeout(searchTimer)
    searchTimer = null
  }
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

/* ===== Filter Bar ===== */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-5);
}
.search-box {
  position: relative;
  display: flex;
  align-items: center;
  flex: 1 1 240px;
  min-width: 200px;
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}
.search-box:focus-within {
  border-color: var(--color-accent);
  background: rgba(255, 255, 255, 0.05);
}
.search-icon {
  flex-shrink: 0;
  margin-left: var(--space-3);
  color: var(--color-text-muted);
}
.search-input {
  flex: 1;
  min-width: 0;
  min-height: 42px;
  padding: 0 var(--space-3);
  background: transparent;
  border: none;
  outline: none;
  color: var(--color-text);
  font-size: 14px;
  font-family: inherit;
}
.search-input::placeholder {
  color: var(--color-text-muted);
}
.search-input::-webkit-search-cancel-button {
  display: none;
}
.clear-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin-right: var(--space-2);
  background: rgba(255, 255, 255, 0.06);
  border: none;
  border-radius: var(--radius-full);
  color: var(--color-text-muted);
  cursor: pointer;
  transition: all var(--transition-fast);
}
.clear-btn:hover {
  background: rgba(255, 255, 255, 0.12);
  color: var(--color-text);
}

.status-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px;
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
}
.status-tab {
  padding: 0 var(--space-3);
  min-height: 34px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all var(--transition-fast);
  white-space: nowrap;
}
.status-tab:hover {
  color: var(--color-text);
  background: rgba(255, 255, 255, 0.05);
}
.status-tab.active {
  background: var(--color-accent);
  color: #06060A;
  font-weight: 600;
}

/* ===== List Header ===== */
.list-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
}
.order-count {
  font-size: 13px;
  color: var(--color-text-muted);
  margin-bottom: var(--space-3);
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.reset-link {
  margin-bottom: var(--space-3);
  background: none;
  border: none;
  padding: 0;
  color: var(--color-accent);
  font-size: 12px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: color var(--transition-fast);
}
.reset-link:hover {
  color: var(--color-accent-dark);
}
.state-hint {
  font-size: 13px;
  color: var(--color-text-muted);
  margin-top: var(--space-2);
}
.is-refreshing .order-card {
  opacity: 0.55;
  pointer-events: none;
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

.order-body { display: flex; flex-direction: column; gap: var(--space-4); }
.order-product { display: flex; align-items: center; gap: var(--space-3); }
.product-thumb {
  flex-shrink: 0;
  width: 52px;
  height: 52px;
  border-radius: var(--radius-sm);
  object-fit: cover;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.05);
}
.product-thumb-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-accent);
  opacity: 0.35;
}
.product-info { min-width: 0; }
.product-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.product-sub {
  margin-top: 2px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-text-muted);
}
.order-fields { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--space-2); }
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
.delete-btn { background: transparent; color: var(--color-text-muted); border: 1px solid rgba(255,255,255,0.08); }
.delete-btn:hover:not(:disabled) { border-color: var(--color-danger); color: var(--color-danger); }

@media (max-width: 600px) {
  .filter-bar { gap: var(--space-2); }
  .status-tabs { width: 100%; overflow-x: auto; }
  .order-card { padding: var(--space-3) var(--space-4); }
  .order-fields { grid-template-columns: 1fr 1fr; }
}
</style>
