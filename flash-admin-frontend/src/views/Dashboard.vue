<template>
  <div class="admin-layout">
    <aside class="sidebar">
      <div class="logo">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
        </svg>
        <span>限时秒杀</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        class="nav-menu"
      >
        <el-menu-item index="/">
          <el-icon><Monitor /></el-icon>
          <span>控制台</span>
        </el-menu-item>
        <el-menu-item index="/items">
          <el-icon><Goods /></el-icon>
          <span>商品管理</span>
        </el-menu-item>
        <el-menu-item index="/flash-sales">
          <el-icon><Timer /></el-icon>
          <span>秒杀管理</span>
        </el-menu-item>
        <el-menu-item index="/orders">
          <el-icon><Document /></el-icon>
          <span>订单管理</span>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
      </el-menu>
    </aside>

    <div class="main-area">
      <header class="topbar">
        <span class="topbar-title">{{ pageTitle }}</span>
        <div class="topbar-right">
          <span class="welcome-text">管理员</span>
          <el-button type="default" size="small" @click="handleLogout" class="logout-btn">退出</el-button>
        </div>
      </header>

      <main class="content">
        <router-view v-if="hasChildRoute" />
        <div v-else class="welcome-page">
          <div class="welcome-header">
            <h2>管理控制台</h2>
            <p>管理您的秒杀平台。</p>
          </div>

          <div class="stats-grid">
            <div class="stat-card">
              <div class="stat-icon" style="background: rgba(96,165,250,0.1); color: #60A5FA;">
                <el-icon :size="24"><Goods /></el-icon>
              </div>
              <div class="stat-info">
                <span class="stat-label">商品</span>
                <span class="stat-value">{{ stats.items }}</span>
              </div>
            </div>
            <div class="stat-card">
              <div class="stat-icon" style="background: rgba(45,106,79,0.15); color: #4ADE80;">
                <el-icon :size="24"><Timer /></el-icon>
              </div>
              <div class="stat-info">
                <span class="stat-label">进行中秒杀</span>
                <span class="stat-value">{{ stats.activeSales }}</span>
              </div>
            </div>
            <div class="stat-card">
              <div class="stat-icon" style="background: rgba(245,158,11,0.1); color: #F59E0B;">
                <el-icon :size="24"><Document /></el-icon>
              </div>
              <div class="stat-info">
                <span class="stat-label">订单</span>
                <span class="stat-value">{{ stats.orders }}</span>
              </div>
            </div>
            <div class="stat-card">
              <div class="stat-icon" style="background: rgba(139,92,246,0.1); color: #A78BFA;">
                <el-icon :size="24"><User /></el-icon>
              </div>
              <div class="stat-info">
                <span class="stat-label">用户</span>
                <span class="stat-value">{{ stats.users }}</span>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Monitor, Goods, Timer, Document, User } from '@element-plus/icons-vue'
import { getItems } from '../api/item'
import { getFlashSales } from '../api/flash-sale'
import { getOrders } from '../api/order'
import { getUsers } from '../api/user'

const router = useRouter()
const route = useRoute()

const hasChildRoute = computed(() => route.path !== '/')

const activeMenu = computed(() => {
  if (route.path === '/') return '/'
  return route.path
})

const pageTitle = computed(() => {
  const titles = {
    '/': '控制台',
    '/items': '商品管理',
    '/flash-sales': '秒杀管理',
    '/orders': '订单管理',
    '/users': '用户管理'
  }
  return titles[route.path] || '控制台'
})

const stats = ref({
  items: 0,
  activeSales: 0,
  orders: 0,
  users: 0
})

async function fetchStats() {
  try {
    const [itemsRes, salesRes, ordersRes, usersRes] = await Promise.all([
      getItems({ page: 1, size: 1 }),
      getFlashSales({ page: 1, size: 1, status: 1 }),
      getOrders({ page: 1, size: 1 }),
      getUsers({ page: 1, size: 1 })
    ])
    stats.value.items = itemsRes.data.total || 0
    stats.value.activeSales = salesRes.data.total || 0
    stats.value.orders = ordersRes.data.total || 0
    stats.value.users = usersRes.data.total || 0
  } catch (e) {
    // silently fail
  }
}

onMounted(fetchStats)

function handleLogout() {
  localStorage.removeItem('adminToken')
  router.push('/login')
}
</script>

<style scoped>
.admin-layout {
  display: flex;
  min-height: 100vh;
}

/* ===== Sidebar ===== */
.sidebar {
  width: 230px;
  background: var(--color-dark);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  border-right: 1px solid rgba(255,255,255,0.05);
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px;
  font-family: var(--font-heading);
  font-size: 18px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 2px;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}
.logo svg {
  color: var(--color-accent);
  flex-shrink: 0;
}
.nav-menu {
  border-right: none !important;
  flex: 1;
}

/* ===== Main Area ===== */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* ===== Topbar ===== */
.topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  background: rgba(6,6,10,0.8);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  height: 56px;
  flex-shrink: 0;
  border-bottom: 1px solid rgba(255,255,255,0.05);
}
.topbar-title {
  font-family: var(--font-heading);
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 1px;
}
.topbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.welcome-text {
  font-size: 13px;
  color: var(--color-text-muted);
}
.logout-btn {
  border-color: rgba(255,255,255,0.1) !important;
  color: var(--color-text-secondary) !important;
}
.logout-btn:hover {
  border-color: var(--color-danger) !important;
  color: var(--color-danger) !important;
  background: var(--color-danger-light) !important;
}

/* ===== Content ===== */
.content {
  padding: 24px;
  flex: 1;
  background: var(--color-bg);
  overflow-y: auto;
}
.welcome-page {
  max-width: 960px;
}

/* ===== Welcome Header ===== */
.welcome-header {
  margin-bottom: 32px;
}
.welcome-header h2 {
  font-family: var(--font-heading);
  font-size: 28px;
  color: #fff;
  margin-bottom: 6px;
  font-weight: 600;
  letter-spacing: 0.5px;
}
.welcome-header p {
  font-size: 14px;
  color: var(--color-text-muted);
}

/* ===== Stats Grid ===== */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
@media (max-width: 1024px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 640px) {
  .stats-grid {
    grid-template-columns: 1fr;
  }
}

/* ===== Stat Card ===== */
.stat-card {
  background: var(--glass-bg);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  padding: 24px;
  display: flex;
  align-items: center;
  gap: 16px;
  transition: all var(--transition-normal);
  cursor: default;
}
.stat-card:hover {
  border-color: rgba(255,255,255,0.1);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}
.stat-icon {
  width: 52px;
  height: 52px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-info { display: flex; flex-direction: column; }
.stat-label {
  font-size: 11px;
  color: var(--color-text-muted);
  margin-bottom: 2px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.8px;
}
.stat-value {
  font-family: var(--font-mono);
  font-size: 28px;
  font-weight: 600;
  color: #fff;
}
</style>
