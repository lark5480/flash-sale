<template>
  <div class="page-container">
    <div class="page-header">
      <h3>秒杀管理</h3>
      <div class="header-actions">
        <el-input
          v-model="searchKey"
          placeholder="搜索商品名称"
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
          <el-option label="待开始" :value="0" />
          <el-option label="进行中" :value="1" />
          <el-option label="已结束" :value="2" />
          <el-option label="已取消" :value="3" />
        </el-select>
        <el-button @click="handleRefresh">
          <el-icon style="margin-right: 4px;"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button type="primary" @click="openAddDialog">
          <el-icon style="margin-right: 4px;"><Plus /></el-icon>
          创建秒杀
        </el-button>
      </div>
    </div>

    <el-table :data="filteredSales" stripe border style="width: 100%" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="itemName" label="商品名称" min-width="140">
        <template #default="{ row }">
          {{ itemNameOf(row) }}
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
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 2" size="small" type="info" link @click="openDetail(row)">
            查看详情
          </el-button>
          <template v-else>
            <el-button size="small" type="primary" link @click="openEditDialog(row)">编辑</el-button>
            <el-button v-if="row.status === 0" size="small" type="success" link @click="handleAction(row, 'enable')">
              启用
            </el-button>
            <el-button
              v-if="row.status === 0 || row.status === 1"
              size="small"
              type="danger"
              link
              @click="handleAction(row, 'cancel')"
            >
              取消
            </el-button>
            <el-button v-if="row.status === 3" size="small" type="success" link @click="handleAction(row, 'reopen')">
              重新启用
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap" v-if="filteredSales.length > 0">
      <span class="total-info">共 {{ filteredSales.length }} 条</span>
    </div>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑秒杀' : '创建秒杀'" width="550px">
      <el-form :model="form" label-width="120px" :rules="rules" ref="formRef">
        <div v-if="editLock" class="stock-tip">
          进行中的活动仅可修改秒杀价、限购数量与结束时间。商品、库存与开始时间已锁定：库存是已成交台账，换品会让已售订单错位，改开始时间会造成状态错乱。如需补货或调整，请先「取消」活动，编辑后再「重新启用」。
        </div>
        <el-form-item label="商品" prop="itemId">
          <el-select
            v-model="form.itemId"
            filterable
            clearable
            :disabled="editLock"
            :loading="itemLoading"
            placeholder="搜索并选择商品"
            style="width: 100%"
          >
            <el-option
              v-for="opt in items"
              :key="opt.id"
              :label="opt.name"
              :value="Number(opt.id)"
              :disabled="opt.status === 0"
            >
              <div class="item-option">
                <span class="item-option-name">{{ opt.name }}</span>
                <span class="item-option-sub">
                  <template v-if="opt.price != null">${{ Number(opt.price).toFixed(2) }}</template>
                  <el-tag v-if="opt.status === 0" size="small" type="info">已下架</el-tag>
                </span>
              </div>
            </el-option>
          </el-select>
          <div v-if="selectedItem" class="item-selected-tip">
            已选「{{ selectedItem.name }}」，原价
            {{ selectedItem.price != null ? '$' + Number(selectedItem.price).toFixed(2) : '—' }}
            ，可参考设置秒杀价
          </div>
        </el-form-item>
        <el-form-item label="秒杀价" prop="flashPrice">
          <el-input-number v-model="form.flashPrice" :min="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="库存" prop="stock">
          <el-input-number v-model="form.stock" :min="1" :disabled="stockDisabled" style="width: 100%" />
          <div v-if="stockDisabled" class="stock-tip">
            进行中的活动不可修改库存（该列是已成交库存台账），补货请先「取消」活动再调整并「重新启用」
          </div>
        </el-form-item>
        <el-form-item label="限购数量" prop="limitPerUser">
          <el-input-number v-model="form.limitPerUser" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="开始时间" prop="startTime">
          <el-date-picker
            v-model="form.startTime"
            type="datetime"
            :disabled="editLock"
            placeholder="请选择开始时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结束时间" prop="endTime">
          <el-date-picker
            v-model="form.endTime"
            type="datetime"
            placeholder="请选择结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="秒杀活动详情" width="560px">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detail" :column="1" border>
          <el-descriptions-item label="活动 ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="商品">
            {{ detail.itemName || itemNameOf(detailRow) }}
            <template v-if="detail.originalPrice != null">
              （原价 ${{ Number(detail.originalPrice).toFixed(2) }}）
            </template>
          </el-descriptions-item>
          <el-descriptions-item label="秒杀价">
            {{ detail.flashPrice != null ? '$' + Number(detail.flashPrice).toFixed(2) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="库存">{{ detail.stock }}</el-descriptions-item>
          <el-descriptions-item label="限购数量">{{ detail.limitPerUser }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ formatTime(detail.startTime) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ formatTime(detail.endTime) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detail.status)" size="small">{{ statusLabel(detail.status) }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="detail && detail.status === 2" class="archived-note">
          已结束的场次为归档数据，仅可查看，不可编辑。
        </div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  getFlashSales,
  getFlashSale,
  createFlashSale,
  updateFlashSale,
  updateFlashSaleStatus
} from '../api/flash-sale'
import { getItems } from '../api/item'
import { ElMessage, ElMessageBox } from 'element-plus'

const sales = ref([])
const loading = ref(false)
const submitting = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const items = ref([])
const itemLoading = ref(false)
const searchKey = ref('')
const statusFilter = ref('')

const filteredSales = computed(() => {
  let result = sales.value
  if (searchKey.value) {
    const key = searchKey.value.toLowerCase()
    result = result.filter(row => itemNameOf(row).toLowerCase().includes(key))
  }
  if (statusFilter.value !== '') {
    result = result.filter(row => row.status === statusFilter.value)
  }
  return result
})

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
const editingStatus = ref(null)

// 进行中（ACTIVE）活动：商品/库存/开始时间锁定（后端 checkEditAllowed / checkStockEditable 兜底）
const editLock = computed(() => isEdit.value && editingStatus.value === 1)
const stockDisabled = editLock

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
  itemId: [{ required: true, message: '请选择商品', trigger: 'change' }],
  flashPrice: [{ required: true, message: '请输入秒杀价', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }],
  limitPerUser: [{ required: true, message: '请输入限购数量', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }]
}

async function fetchSales() {
  loading.value = true
  try {
    const res = await getFlashSales({ page: 1, size: 200 })
    sales.value = res.data.records || []
    total.value = res.data.total || 0
    page.value = 1
    mergeMissingItems()
  } catch (e) {
    ElMessage.error('获取秒杀列表失败')
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
  fetchSales()
}

function openAddDialog() {
  isEdit.value = false
  editingId.value = null
  editingStatus.value = null
  form.value = { itemId: null, flashPrice: 0, stock: 1, limitPerUser: 1, startTime: '', endTime: '' }
  loadItems()
  dialogVisible.value = true
}

function openEditDialog(row) {
  isEdit.value = true
  editingId.value = row.id
  editingStatus.value = row.status
  form.value = {
    itemId: row.itemId != null ? Number(row.itemId) : null,
    flashPrice: row.flashPrice,
    stock: row.stock,
    limitPerUser: row.limitPerUser,
    startTime: row.startTime || '',
    endTime: row.endTime || ''
  }
  loadItems()
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (isEdit.value) {
      const payload = { id: editingId.value, ...form.value }
      // 进行中活动：列表行里的 stock 是打开页面那一刻的旧快照，携带提交会被后端当作
      // 「修改台账」拦截，导致连改价格/时间都被误伤——这里主动剥离，由后端跳过该字段
      if (editingStatus.value === 1) {
        delete payload.stock
      }
      await updateFlashSale(payload)
      ElMessage.success('秒杀更新成功')
    } else {
      await createFlashSale(form.value)
      ElMessage.success('秒杀创建成功')
    }
    dialogVisible.value = false
    await fetchSales()
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || e.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

// 状态操作与后端状态机流转一一对应：启用 0→1 / 取消(待开始、进行中)→3 / 重新启用 3→0
// 重新启用由后端按时间自动分派：开始时间已过 → 直接恢复「进行中」；未到 → 回「待开始」排队
const statusActions = {
  enable: { status: 1, verb: '启用' },
  cancel: { status: 3, verb: '取消' },
  reopen: { status: 0, verb: '重新启用' }
}

// 该场次开始时间是否已过（仅用于提示文案与后端分派结果保持一致，最终以后端判定为准）
function hasStarted(row) {
  if (!row.startTime) return false
  const t = new Date(String(row.startTime).replace(' ', 'T')).getTime()
  return !Number.isNaN(t) && t <= Date.now()
}

async function handleAction(row, action) {
  const cfg = statusActions[action]
  // 重新启用已取消的场次：开始时间已过 → 后端直接恢复为「进行中」，一步到位恢复售卖
  const reopenImmediate = action === 'reopen' && hasStarted(row)
  const hint =
    action === 'reopen'
      ? reopenImmediate
        ? '确定要重新启用该秒杀活动吗？该场次开始时间已过，将立即恢复为「进行中」并开售。'
        : '确定要重新启用该秒杀活动吗？将回到「待开始」，到达开始时间后自动开售。'
      : `确定要${cfg.verb}该秒杀活动吗？`
  try {
    await ElMessageBox.confirm(hint, '确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await updateFlashSaleStatus(row.id, cfg.status)
    ElMessage.success(reopenImmediate ? '秒杀已重新启用，恢复为「进行中」' : `秒杀已${cfg.verb}`)
    await fetchSales()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e.response?.data?.msg || e.message || `秒杀${cfg.verb}失败`)
    }
  }
}

// 归档（已结束）场次只读查看
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailRow = ref(null)

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detailRow.value = row
  detail.value = null
  try {
    const res = await getFlashSale(row.id)
    detail.value = res.data
  } catch (e) {
    // 详情接口异常时退化为展示列表行快照，保证仍可查看基本信息
    detail.value = row
  } finally {
    detailLoading.value = false
  }
}

function findItem(itemId) {
  if (itemId === null || itemId === undefined || itemId === '') return null
  return items.value.find(it => String(it.id) === String(itemId)) || null
}

// 兜底：列表中出现的商品若不在已加载选项里（被删除/超出加载范围），
// 补一条占位选项，保证列表名称展示与下拉回显不丢失
function mergeMissingItems() {
  const existing = new Set(items.value.map(it => String(it.id)))
  const ids = new Set()
  sales.value.forEach(s => {
    if (s.itemId !== null && s.itemId !== undefined) ids.add(String(s.itemId))
  })
  if (form.value.itemId !== null && form.value.itemId !== undefined) {
    ids.add(String(form.value.itemId))
  }
  ids.forEach(id => {
    if (!existing.has(id)) {
      items.value.push({ id, name: `商品#${id}`, price: null, status: null })
    }
  })
}

function itemNameOf(sale) {
  const item = findItem(sale?.itemId)
  return item ? item.name : `商品#${sale.itemId}`
}

// 当前已选商品，用于表单下方展示原价等辅助信息
const selectedItem = computed(() => findItem(form.value.itemId))

async function loadItems() {
  itemLoading.value = true
  try {
    // 一次拉取商品列表，同时供下拉选择与表格名称展示使用
    const res = await getItems({ page: 1, size: 500 })
    items.value = res.data.records || []
  } catch (e) {
    // 加载失败不影响主流程
  } finally {
    itemLoading.value = false
    mergeMissingItems()
  }
}

onMounted(() => {
  fetchSales()
  loadItems()
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
.archived-note {
  margin-top: 12px;
  padding: 8px 12px;
  border-radius: 6px;
  background: rgba(156, 163, 175, 0.08);
  color: #9CA3AF;
  font-size: 12px;
  line-height: 1.5;
}
.stock-tip {
  color: #E6A23C;
  font-size: 12px;
  line-height: 1.5;
  margin-top: 2px;
}
.item-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.item-option-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item-option-sub {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: 10px;
  color: #9CA3AF;
  font-size: 12px;
  white-space: nowrap;
}
.item-selected-tip {
  color: #9CA3AF;
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
