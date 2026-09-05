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
        <el-menu-item index="/dashboard">
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
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Monitor, Goods, Timer, Document, User } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()

const activeMenu = computed(() => route.path)

const pageTitle = computed(() => {
  const titles = {
    '/dashboard': '控制台',
    '/items': '商品管理',
    '/flash-sales': '秒杀管理',
    '/orders': '订单管理',
    '/users': '用户管理'
  }
  return titles[route.path] || '控制台'
})

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
</style>
