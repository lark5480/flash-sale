<template>
  <div class="page-container">
    <div class="page-header">
      <h3>用户管理</h3>
      <div class="header-actions">
        <el-input
          v-model="searchKey"
          placeholder="搜索用户名"
          clearable
          style="width: 200px"
          @input="handleSearch"
        />
        <el-select
          v-model="statusFilter"
          placeholder="状态筛选"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option label="全部" :value="''" />
          <el-option label="正常" :value="1" />
          <el-option label="已禁用" :value="0" />
        </el-select>
        <el-button @click="handleRefresh">
          <el-icon style="margin-right: 4px;"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <el-table :data="filteredUsers" stripe border style="width: 100%" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" min-width="130" />
      <el-table-column prop="phone" label="手机号" width="140" />
      <el-table-column prop="email" label="邮箱" min-width="180" />
      <el-table-column prop="role" label="角色" width="100" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
            {{ row.status === 1 ? '正常' : '已禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            :type="row.status === 1 ? 'warning' : 'success'"
            link
            @click="handleToggleStatus(row)"
          >
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap" v-if="filteredUsers.length > 0">
      <span class="total-info">共 {{ filteredUsers.length }} 条</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getUsers, updateUserStatus } from '../api/user'
import { ElMessage, ElMessageBox } from 'element-plus'

const users = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const searchKey = ref('')
const statusFilter = ref('')

const filteredUsers = computed(() => {
  let result = users.value
  if (searchKey.value) {
    const key = searchKey.value.toLowerCase()
    result = result.filter(user => user.username?.toLowerCase().includes(key))
  }
  if (statusFilter.value !== '') {
    result = result.filter(user => user.status === statusFilter.value)
  }
  return result
})

async function fetchUsers() {
  loading.value = true
  try {
    const res = await getUsers({ page: 1, size: 200 })
    const data = res.data || {}
    users.value = data.records || []
    total.value = data.total || 0
    page.value = 1
  } catch (e) {
    ElMessage.error('获取用户列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
}

function handleRefresh() {
  searchKey.value = ''
  statusFilter.value = ''
  fetchUsers()
}

async function handleToggleStatus(row) {
  const actionText = row.status === 1 ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定要${actionText}用户 "${row.username}" 吗？`,
      '确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    const newStatus = row.status === 1 ? 0 : 1
    await updateUserStatus(row.id, newStatus)
    ElMessage.success(`用户${actionText}成功`)
    await fetchUsers()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(`用户${actionText}失败`)
    }
  }
}

onMounted(fetchUsers)
</script>

<style scoped>
.page-container {
  background: var(--glass-bg);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-header h3 {
  font-family: var(--font-heading);
  font-size: 20px;
  color: #fff;
  letter-spacing: 0.5px;
  margin: 0;
}
pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
  align-items: center;
}
.total-info {
  color: #9CA3AF;
  font-size: 13px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
