<template>
  <div class="dash">
    <div class="dash-head">
      <div class="dash-head-left">
        <h2>数据概览</h2>
        <p class="dash-sub">{{ todayText }} · 数据实时统计，刷新页面或点击右上角刷新获取最新</p>
      </div>
      <el-button :loading="loading" @click="fetchAll">
        <el-icon style="margin-right: 4px;"><Refresh /></el-icon>
        刷新
      </el-button>
    </div>

    <!-- KPI 卡片 -->
    <div class="kpi-grid" v-loading="loading">
      <div class="kpi-card" v-for="k in kpis" :key="k.label">
        <div class="kpi-icon" :style="{ background: k.bg, color: k.color }">
          <el-icon :size="24"><component :is="k.icon" /></el-icon>
        </div>
        <div class="kpi-info">
          <span class="kpi-label">{{ k.label }}</span>
          <span class="kpi-value">{{ k.value }}</span>
        </div>
      </div>
    </div>

    <!-- 图表行 -->
    <div class="charts-row">
      <section class="panel">
        <header class="panel-head">
          <span class="panel-title">近 7 日订单趋势</span>
          <div class="legend">
            <span><i class="dot" style="background: #5B8DEF"></i>下单量</span>
            <span><i class="dot" style="background: #3DD68C"></i>支付量</span>
          </div>
        </header>
        <div class="trend-body" v-loading="loading">
          <template v-if="trendTotal > 0">
            <div class="trend-day" v-for="d in trendData" :key="d.date" :title="trendTip(d)">
              <div class="bar-group">
                <div class="bar bar-order" :style="{ height: barH(d.orderCount) }"></div>
                <div class="bar bar-paid" :style="{ height: barH(d.paidCount) }"></div>
              </div>
              <span class="day-label">{{ shortDate(d.date) }}</span>
            </div>
          </template>
          <el-empty v-else description="近 7 日暂无订单" :image-size="60" />
        </div>
      </section>

      <section class="panel">
        <header class="panel-head">
          <span class="panel-title">订单状态分布</span>
          <span class="panel-hint">共 {{ orderTotal }} 单</span>
        </header>
        <div class="dist-body" v-loading="loading">
          <div class="dist-row" v-for="s in distData" :key="s.status">
            <span class="dist-label">
              <i class="dot" :style="{ background: distColor(s.status) }"></i>
              {{ statusLabel(s.status) }}
            </span>
            <div class="dist-track">
              <div
                class="dist-fill"
                :style="{ width: distPct(s.count), background: distColor(s.status) }"
              ></div>
            </div>
            <span class="dist-num">{{ s.count }}</span>
          </div>
        </div>
      </section>
    </div>

    <!-- 快捷看板行 -->
    <div class="lists-row">
      <section class="panel">
        <header class="panel-head clickable" @click="go('/flash-sales')">
          <span class="panel-title">进行中秒杀</span>
          <span class="panel-more">查看全部 <el-icon><ArrowRight /></el-icon></span>
        </header>
        <div class="mini-list" v-loading="loading">
          <template v-if="activeSales.length > 0">
            <div class="mini-row" v-for="a in activeSales" :key="a.id" @click="go('/flash-sales')">
              <div class="mini-main">
                <span class="mini-name">{{ a.itemName || '秒杀场次 #' + a.id }}</span>
                <span class="mini-sub">{{ fmtRange(a.startTime, a.endTime) }}</span>
              </div>
              <div class="mini-side">
                <span class="mini-price">{{ fmtMoney(a.flashPrice) }}</span>
                <span class="mini-tag">剩 {{ a.stock ?? '-' }} 件</span>
              </div>
            </div>
          </template>
          <div v-else class="mini-empty">当前没有进行中的秒杀场次</div>
        </div>
      </section>

      <section class="panel">
        <header class="panel-head clickable" @click="go('/orders')">
          <span class="panel-title">最近订单</span>
          <span class="panel-more">查看全部 <el-icon><ArrowRight /></el-icon></span>
        </header>
        <div class="mini-list" v-loading="loading">
          <template v-if="recentOrders.length > 0">
            <div class="mini-row" v-for="o in recentOrders" :key="o.id" @click="go('/orders')">
              <div class="mini-main">
                <span class="mini-name">订单 #{{ tailId(o.id) }}</span>
                <span class="mini-sub">用户 #{{ o.userId }} · {{ fmtTime(o.createTime) }}</span>
              </div>
              <div class="mini-side">
                <span class="mini-price">{{ fmtMoney(o.flashPrice) }}</span>
                <el-tag :type="statusTag(o.status)" size="small">{{ statusLabel(o.status) }}</el-tag>
              </div>
            </div>
          </template>
          <div v-else class="mini-empty">还没有订单</div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, ArrowRight, ShoppingCart, Wallet, Clock, Timer, Goods, User } from '@element-plus/icons-vue'
import { getStatsOverview } from '../api/stats'
import { getFlashSales, getFlashSale } from '../api/flash-sale'
import { getOrders } from '../api/order'

const router = useRouter()
const loading = ref(false)
const overview = ref({})

const statusMap = {
  0: { label: '待支付', type: 'info' },
  1: { label: '已支付', type: 'success' },
  2: { label: '已取消', type: 'danger' },
  3: { label: '已退款', type: 'warning' }
}
const distColors = { 0: '#E6A23C', 1: '#67C23A', 2: '#909399', 3: '#F56C6C' }

function statusLabel(s) {
  return statusMap[s]?.label || '未知'
}
function statusTag(s) {
  return statusMap[s]?.type || 'info'
}
function distColor(s) {
  return distColors[s] || '#909399'
}

function fmtMoney(v) {
  const n = Number(v || 0)
  return '$' + n.toFixed(2)
}
function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(5, 16)
}
function fmtRange(start, end) {
  if (!start || !end) return '-'
  return fmtTime(start) + ' ~ ' + fmtTime(end).slice(6)
}
function shortDate(dateStr) {
  return dateStr ? dateStr.slice(5) : ''
}
function tailId(id) {
  const s = String(id || '')
  return s.length > 8 ? '…' + s.slice(-8) : s
}

const todayText = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'long'
})

const kpis = computed(() => {
  const o = overview.value || {}
  return [
    { label: '今日订单', value: String(o.todayOrderCount ?? 0), icon: ShoppingCart, color: '#5B8DEF', bg: 'rgba(91,141,239,0.12)' },
    { label: '今日成交额', value: fmtMoney(o.todayPaidAmount), icon: Wallet, color: '#3DD68C', bg: 'rgba(61,214,140,0.12)' },
    { label: '待支付订单', value: String(o.pendingPaymentOrderCount ?? 0), icon: Clock, color: '#F0A04B', bg: 'rgba(240,160,75,0.12)' },
    { label: '进行中秒杀', value: String(o.activeFlashSaleCount ?? 0), icon: Timer, color: '#C084FC', bg: 'rgba(192,132,252,0.12)' },
    { label: '上架商品', value: String(o.onSaleItemCount ?? 0), icon: Goods, color: '#22D3EE', bg: 'rgba(34,211,238,0.12)' },
    { label: '注册用户', value: String(o.userCount ?? 0), icon: User, color: '#F472B6', bg: 'rgba(244,114,182,0.12)' }
  ]
})

const trendData = computed(() => overview.value.trend || [])
const trendTotal = computed(() => trendData.value.reduce((s, d) => s + (d.orderCount || 0) + (d.paidCount || 0), 0))
const trendMax = computed(() => Math.max(1, ...trendData.value.flatMap(d => [d.orderCount || 0, d.paidCount || 0])))
function barH(count) {
  const c = count || 0
  return c === 0 ? '2px' : Math.max(6, Math.round((c / trendMax.value) * 100)) + '%'
}
function trendTip(d) {
  return `${d.date}  下单 ${d.orderCount || 0} 单 · 支付 ${d.paidCount || 0} 单 · 成交 ${fmtMoney(d.paidAmount)}`
}

const distData = computed(() => overview.value.statusDist || [])
const orderTotal = computed(() => distData.value.reduce((s, x) => s + (x.count || 0), 0))
function distPct(count) {
  const total = orderTotal.value
  if (!total) return '0%'
  return Math.max(count > 0 ? 4 : 0, Math.round((count / total) * 100)) + '%'
}

const activeSales = ref([])
const recentOrders = ref([])

async function fetchStats() {
  const res = await getStatsOverview()
  overview.value = res.data || {}
}

async function fetchActiveSales() {
  const res = await getFlashSales({ page: 1, size: 5, status: 1 })
  const records = (res.data && res.data.records) || []
  // 并行补商品名：管理端列表不含 join，详情接口一次可带出
  const detailed = await Promise.allSettled(
    records.map(r => getFlashSale(r.id).then(d => ({ ...r, itemName: d.data?.itemName })))
  )
  activeSales.value = detailed
    .filter(x => x.status === 'fulfilled' && x.value)
    .map(x => x.value)
}

async function fetchRecentOrders() {
  const res = await getOrders({ page: 1, size: 5 })
  recentOrders.value = (res.data && res.data.records) || []
}

async function fetchAll() {
  loading.value = true
  try {
    await Promise.all([fetchStats(), fetchActiveSales(), fetchRecentOrders()])
  } catch (e) {
    overview.value = {}
    activeSales.value = []
    recentOrders.value = []
  } finally {
    loading.value = false
  }
}

function go(path) {
  router.push(path)
}

onMounted(fetchAll)
</script>

<style scoped>
.dash {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.dash-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 4px;
}
.dash-head-left h2 {
  font-family: var(--font-heading);
  font-size: 22px;
  color: #fff;
  margin: 0 0 6px;
  letter-spacing: 0.5px;
}
.dash-sub {
  margin: 0;
  font-size: 13px;
  color: #9CA3AF;
}

/* ===== KPI ===== */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}
@media (max-width: 1400px) {
  .kpi-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 860px) {
  .kpi-grid { grid-template-columns: repeat(2, 1fr); }
}
.kpi-card {
  background: var(--glass-bg);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  padding: 18px;
  display: flex;
  align-items: center;
  gap: 14px;
  transition: all var(--transition-normal);
}
.kpi-card:hover {
  border-color: rgba(255, 255, 255, 0.1);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}
.kpi-icon {
  width: 46px;
  height: 46px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.kpi-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.kpi-label {
  font-size: 12px;
  color: #9CA3AF;
  margin-bottom: 4px;
}
.kpi-value {
  font-family: var(--font-mono);
  font-size: 22px;
  font-weight: 600;
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ===== Panels ===== */
.charts-row,
.lists-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 16px;
}
.lists-row {
  grid-template-columns: 1fr 1fr;
}
@media (max-width: 1100px) {
  .charts-row,
  .lists-row { grid-template-columns: 1fr; }
}
.panel {
  background: var(--glass-bg);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  padding: 18px 20px;
  min-width: 0;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.panel-head.clickable { cursor: pointer; }
.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 0.3px;
}
.panel-hint {
  font-size: 12px;
  color: #9CA3AF;
}
.panel-more {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: var(--color-accent, #6366f1);
}
.legend {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: #9CA3AF;
}
.legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

/* ===== 趋势柱状图 ===== */
.trend-body {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
  height: 170px;
}
.trend-day {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  height: 100%;
}
.bar-group {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 4px;
}
.bar {
  width: 14px;
  border-radius: 4px 4px 0 0;
  min-height: 2px;
  transition: height 0.3s ease;
}
.bar-order { background: linear-gradient(180deg, #7DA6F3, #5B8DEF); }
.bar-paid { background: linear-gradient(180deg, #6FE0A8, #3DD68C); }
.day-label {
  font-size: 11px;
  color: #9CA3AF;
  font-family: var(--font-mono);
}

/* ===== 状态分布 ===== */
.dist-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 4px;
}
.dist-row {
  display: grid;
  grid-template-columns: 96px 1fr 44px;
  align-items: center;
  gap: 10px;
}
.dist-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #D1D5DB;
  white-space: nowrap;
}
.dist-track {
  height: 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  overflow: hidden;
}
.dist-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.4s ease;
}
.dist-num {
  text-align: right;
  font-family: var(--font-mono);
  font-size: 13px;
  color: #fff;
}

/* ===== 迷你列表 ===== */
.mini-list {
  display: flex;
  flex-direction: column;
}
.mini-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background 0.15s ease;
}
.mini-row + .mini-row { border-top: 1px solid rgba(255, 255, 255, 0.04); }
.mini-row:hover { background: rgba(255, 255, 255, 0.05); }
.mini-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.mini-name {
  font-size: 13px;
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mini-sub {
  font-size: 11px;
  color: #9CA3AF;
  margin-top: 2px;
  font-family: var(--font-mono);
}
.mini-side {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.mini-price {
  font-family: var(--font-mono);
  font-size: 14px;
  font-weight: 600;
  color: #F59E0B;
}
.mini-tag {
  font-size: 11px;
  color: #9CA3AF;
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 1px 7px;
  border-radius: 999px;
}
.mini-empty {
  padding: 26px 0;
  text-align: center;
  font-size: 13px;
  color: #6B7280;
}
</style>
