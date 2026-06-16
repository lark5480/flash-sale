<template>
  <div class="page-container">
    <div class="page-header">
      <h3>秒杀管理</h3>
      <el-button type="primary" @click="openAddDialog">
        <el-icon style="margin-right: 4px;"><Plus /></el-icon>
        创建秒杀
      </el-button>
    </div>

    <el-table :data="sales" stripe border style="width: 100%" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="itemName" label="商品名称" min-width="140">
        <template #default="{ row }">
          {{ itemNameMap[row.itemId] || `商品#${row.itemId}` }}
        </template>
      </el-table-column>
      <el-table-column prop="flashPrice" label="秒杀价" width="120">
        <template #default="{ row }">
          ${{ Number(row.flashPrice).toFixed(2) }}
        </template>
      </el-table-column>
      <el-table-column prop="stock" label="库存" width="80" />
      <el-table-column label="时间段" min-width="200">
        <template #default="{ row }">
          {{ formatTime(row.startTime) }} ~ {{ formatTime(row.endTime) }}
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openEditDialog(row)">编辑</el-button>
          <el-button
            size="small"
            :type="row.status === 0 ? 'success' : 'danger'"
            link
            :disabled="row.status !== 0 && row.status !== 1"
            @click="handleStatusChange(row)"
          >
            {{ row.status === 0 ? '启用' : row.status === 1 ? '取消' : '' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap" v-if="total > 0">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
        @current-change="fetchSales"
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑秒杀' : '创建秒杀'" width="550px">
      <el-form :model="form" label-width="120px" :rules="rules" ref="formRef">
        <el-form-item label="商品ID" prop="itemId">
          <el-input-number v-model="form.itemId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="秒杀价" prop="flashPrice">
          <el-input-number v-model="form.flashPrice" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="库存" prop="stock">
          <el-input-number v-model="form.stock" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="限购数量" prop="limitPerUser">
          <el-input-number v-model="form.limitPerUser" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="开始时间" prop="startTime">
          <el-date-picker
            v-model="form.startTime"
            type="datetime"
            placeholder="请选择开始时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结束时间" prop="endTime">
          <el-date-picker
            v-model="form.endTime"
            type="datetime"
            placeholder="请选择结束时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { getFlashSales, createFlashSale, updateFlashSale, updateFlashSaleStatus } from '../api/flash-sale'
import { getItems } from '../api/item'
import { ElMessage, ElMessageBox } from 'element-plus'

const sales = ref([])
const loading = ref(false)
const submitting = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const itemNameMap = ref({})

const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref(null)
const form = ref({
  itemId: null,
  flashPrice: 0,
  stock: 1,
  limitPerUser: 1,
  startTime: '',
  endTime: ''
})
const editingId = ref(null)

const statusMap = {
  0: { label: '待开始', type: 'info' },
  1: { label: '进行中', type: 'success' },
  2: { label: '已结束', type: '' },
  3: { label: '已取消', type: 'danger' }
}

function statusType(status) {
  return statusMap[status]?.type || 'info'
}

function statusLabel(status) {
  return statusMap[status]?.label || 'UNKNOWN'
}

function formatTime(t) {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 16)
}

const rules = {
  itemId: [{ required: true, message: '请输入商品ID', trigger: 'blur' }],
  flashPrice: [{ required: true, message: '请输入秒杀价', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }],
  limitPerUser: [{ required: true, message: '请输入限购数量', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }]
}

async function fetchSales() {
  loading.value = true
  try {
    const res = await getFlashSales({ page: page.value, size: size.value })
    sales.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    ElMessage.error('获取秒杀列表失败')
  } finally {
    loading.value = false
  }
}

function openAddDialog() {
  isEdit.value = false
  editingId.value = null
  form.value = { itemId: null, flashPrice: 0, stock: 1, limitPerUser: 1, startTime: '', endTime: '' }
  dialogVisible.value = true
}

function openEditDialog(row) {
  isEdit.value = true
  editingId.value = row.id
  form.value = {
    itemId: row.itemId,
    flashPrice: row.flashPrice,
    stock: row.stock,
    limitPerUser: row.limitPerUser,
    startTime: row.startTime || '',
    endTime: row.endTime || ''
  }
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateFlashSale({ id: editingId.value, ...form.value })
      ElMessage.success('秒杀更新成功')
    } else {
      await createFlashSale(form.value)
      ElMessage.success('秒杀创建成功')
    }
    dialogVisible.value = false
    await fetchSales()
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '操作失败')
  } finally {
    submitting.value = false
  }
}

async function handleStatusChange(row) {
  const actionText = row.status === 0 ? '启用' : '取消'
  try {
    await ElMessageBox.confirm(
      `确定要${actionText}该秒杀活动吗？`,
      '确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    const newStatus = row.status === 0 ? 1 : 3
    await updateFlashSaleStatus(row.id, newStatus)
    ElMessage.success(`秒杀已${actionText}`)
    await fetchSales()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(`秒杀${actionText}失败`)
    }
  }
}

async function loadItemNames() {
  try {
    // 加载所有商品用于显示名称，最多200条
    const res = await getItems({ page: 1, size: 200 })
    const records = res.data.records || []
    const map = {}
    records.forEach(item => {
      map[item.id] = item.name
    })
    itemNameMap.value = map
  } catch (e) {
    // 加载失败不影响主流程
  }
}

onMounted(() => {
  fetchSales()
  loadItemNames()
})
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
</style>
