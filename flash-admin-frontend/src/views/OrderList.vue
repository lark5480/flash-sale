<template>
  <div class="page-container">
    <div class="page-header">
      <h3>订单管理</h3>
    </div>

    <el-table :data="orders" stripe border style="width: 100%" v-loading="loading">
      <el-table-column prop="id" label="订单ID" width="90" />
      <el-table-column prop="userId" label="用户ID" width="90" />
      <el-table-column prop="itemId" label="商品ID" width="90" />
      <el-table-column prop="flashSaleId" label="秒杀ID" width="90" />
      <el-table-column label="秒杀价" width="110">
        <template #default="{ row }">
          {{ row.flashPrice ? '$' + Number(row.flashPrice).toFixed(2) : '-' }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下单时间" min-width="160">
        <template #default="{ row }">
          {{ formatTime(row.createTime) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 0"
            size="small" type="success" link
            @click="handlePay(row)"
          >
            支付
          </el-button>
          <el-button
            v-if="row.status === 1"
            size="small" type="warning" link
            @click="handleRefund(row)"
          >
            退款
          </el-button>
          <span v-if="row.status !== 0 && row.status !== 1" class="text-muted">-</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap" v-if="total > 0">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
        @current-change="fetchOrders"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getOrders, payOrder, refundOrder } from '../api/order'
import { ElMessage, ElMessageBox } from 'element-plus'

const orders = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)

const statusMap = {
  0: { label: '待支付', type: 'info' },
  1: { label: '已支付', type: 'success' },
  2: { label: '已取消', type: 'danger' },
  3: { label: '已退款', type: 'warning' }
}

function statusType(status) {
  return statusMap[status]?.type || 'info'
}

function statusLabel(status) {
  return statusMap[status]?.label || 'UNKNOWN'
}

function formatTime(t) {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

async function fetchOrders() {
  loading.value = true
  try {
    const res = await getOrders({ page: page.value, size: size.value })
    orders.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    ElMessage.error('获取订单列表失败')
  } finally {
    loading.value = false
  }
}

async function handlePay(row) {
  try {
    await ElMessageBox.confirm(
      `确定将订单 #${row.id} 标记为已支付吗？`,
      '确认支付',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'info' }
    )
    await payOrder(row.id)
    ElMessage.success('支付成功')
    await fetchOrders()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e.response?.data?.msg || '支付失败')
    }
  }
}

async function handleRefund(row) {
  try {
    await ElMessageBox.confirm(
      `确定对订单 #${row.id} 进行退款吗？库存将归还。`,
      '确认退款',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    await refundOrder(row.id)
    ElMessage.success('退款成功')
    await fetchOrders()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e.response?.data?.msg || '退款失败')
    }
  }
}

onMounted(fetchOrders)
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
.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.text-muted {
  color: #9CA3AF;
  font-size: 13px;
}
</style>
