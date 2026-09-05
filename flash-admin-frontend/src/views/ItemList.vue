<template>
  <div class="page-container">
    <div class="page-header">
      <h3>商品管理</h3>
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
          <el-option label="上架" :value="1" />
          <el-option label="下架" :value="0" />
        </el-select>
        <el-button @click="handleRefresh">
          <el-icon style="margin-right: 4px;"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button type="primary" @click="openAddDialog">
          <el-icon style="margin-right: 4px;"><Plus /></el-icon>
          新增商品
        </el-button>
      </div>
    </div>

    <el-table :data="filteredItems" stripe border style="width: 100%" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="图片" width="110" align="center">
        <template #default="{ row }">
          <img
            class="thumb-img"
            :src="thumbSrc(row)"
            :alt="row.name"
            :data-seed="itemSeed(row.id, row.name)"
            :title="row.image || '自动占位图'"
            loading="lazy"
            @error="onImgError"
          />
        </template>
      </el-table-column>
      <el-table-column prop="name" label="商品名称" min-width="150" />
      <el-table-column prop="price" label="价格" width="120">
        <template #default="{ row }">
          {{ row.price ? '$' + Number(row.price).toFixed(2) : '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '上架' : '下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            type="primary"
            link
            :disabled="row.status === 1"
            :title="row.status === 1 ? '上架商品不可编辑，请先下架' : ''"
            @click="openEditDialog(row)"
          >
            编辑
          </el-button>
          <el-button
            size="small"
            :type="row.status === 1 ? 'warning' : 'success'"
            link
            @click="handleStatusChange(row)"
          >
            {{ row.status === 1 ? '下架' : '上架' }}
          </el-button>
          <el-button
            size="small"
            type="danger"
            link
            :disabled="row.status === 1"
            :title="row.status === 1 ? '上架商品不可删除，请先下架' : ''"
            @click="handleDelete(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap" v-if="filteredItems.length > 0">
      <span class="total-info">共 {{ filteredItems.length }} 条</span>
    </div>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑商品' : '新增商品'" width="500px">
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入商品名称" />
        </el-form-item>
        <el-form-item label="价格" prop="price">
          <el-input-number v-model="form.price" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="选填" />
        </el-form-item>
        <el-form-item label="图片" prop="image">
          <div class="image-picker">
            <div class="image-preview">
              <img
                class="preview-img"
                :src="previewSrc"
                :alt="form.name || '商品图'"
                :data-seed="previewSeed"
                @error="onImgError"
              />
              <span v-if="!form.image" class="preview-tag">自动占位图</span>
            </div>
            <div class="image-controls">
              <el-input
                v-model="form.image"
                placeholder="图片 URL：https://… 或 /images/xxx.png（留空自动占位）"
                clearable
              />
              <div class="image-actions">
                <el-button size="small" @click="applyRandomImage">随机换一张</el-button>
                <el-button size="small" link type="danger" @click="form.image = ''">清空图片</el-button>
              </div>
              <p class="image-hint">
                可填完整 http(s) 图片地址，或本服务相对路径 /images/xxx.png；留空时商品将使用
                picsum 稳定占位图（同一商品始终同一张）。
              </p>
            </div>
          </div>
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
import { ref, computed, onMounted } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { getItems, createItem, updateItem, deleteItem, updateItemStatus } from '../api/item'
import { picsumUrl, itemImageUrl, itemSeed, fallbackToPicsum } from '../utils/image'
import { ElMessage, ElMessageBox } from 'element-plus'

const items = ref([])
const loading = ref(false)
const submitting = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const searchKey = ref('')
const statusFilter = ref('')

const filteredItems = computed(() => {
  let result = items.value
  if (searchKey.value) {
    const key = searchKey.value.toLowerCase()
    result = result.filter(item => item.name?.toLowerCase().includes(key))
  }
  if (statusFilter.value !== '') {
    result = result.filter(item => item.status === statusFilter.value)
  }
  return result
})

const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref(null)
const form = ref({
  name: '',
  price: 0,
  description: '',
  image: ''
})
const editingId = ref(null)

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }]
}

async function fetchItems() {
  loading.value = true
  try {
    const res = await getItems({ page: 1, size: 200 })
    items.value = res.data.records || []
    total.value = res.data.total || 0
    page.value = 1
  } catch (e) {
    ElMessage.error('获取商品列表失败')
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
  fetchItems()
}

function openAddDialog() {
  isEdit.value = false
  editingId.value = null
  form.value = { name: '', price: 0, description: '', image: '' }
  dialogVisible.value = true
}

function openEditDialog(row) {
  isEdit.value = true
  editingId.value = row.id
  form.value = {
    name: row.name,
    price: row.price,
    description: row.description || '',
    image: row.image || ''
  }
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateItem({ id: editingId.value, ...form.value })
      ElMessage.success('商品更新成功')
    } else {
      await createItem(form.value)
      ElMessage.success('商品创建成功')
    }
    dialogVisible.value = false
    await fetchItems()
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || e.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

// ===== 图片预览与占位 =====
// 新增态用名称、编辑态用 id 作 picsum seed：同一商品占位图始终稳定一致
const previewSeed = computed(() => itemSeed(editingId.value, form.value.name || 'new-item'))
const previewSrc = computed(() => itemImageUrl(form.value.image, previewSeed.value))

function thumbSrc(row) {
  return itemImageUrl(row.image, itemSeed(row.id, row.name))
}

function onImgError(e) {
  fallbackToPicsum(e)
}

let randomSeq = 0
function applyRandomImage() {
  randomSeq += 1
  const base = (form.value.name && form.value.name.trim()) || 'item'
  const seed = `${base}-r${randomSeq}-${Date.now().toString(36)}`
  // 填入带唯一 seed 的 picsum URL：保存后即固化为该商品的正式图片
  form.value.image = picsumUrl(seed, 480)
}

async function handleStatusChange(row) {
  const nextStatus = row.status === 1 ? 0 : 1
  const actionText = nextStatus === 0 ? '下架' : '上架'
  try {
    await ElMessageBox.confirm(`确定要${actionText}「${row.name}」吗？`, '确认', {
      confirmButtonText: actionText,
      cancelButtonText: '取消',
      type: 'warning'
    })
    await updateItemStatus(row.id, nextStatus)
    ElMessage.success(`商品已${actionText}`)
    await fetchItems()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e.response?.data?.msg || e.message || `${actionText}商品失败`)
    }
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm('确定要删除该商品吗？', '确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteItem(row.id)
    ElMessage.success('商品已删除')
    await fetchItems()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e.response?.data?.msg || e.message || '删除商品失败')
    }
  }
}

onMounted(fetchItems)
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

/* 列表缩略图 */
.thumb-img {
  width: 52px;
  height: 52px;
  border-radius: 8px;
  object-fit: cover;
  background: rgba(156, 163, 175, 0.08);
  vertical-align: middle;
}

/* 表单图片：左预览右控制 */
.image-picker {
  display: flex;
  gap: 16px;
  width: 100%;
}
.image-preview {
  position: relative;
  flex-shrink: 0;
  width: 160px;
  height: 160px;
  border-radius: 10px;
  border: 1px dashed rgba(255, 255, 255, 0.14);
  background: rgba(156, 163, 175, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.preview-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.preview-tag {
  position: absolute;
  left: 8px;
  bottom: 8px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
  pointer-events: none;
}
.image-controls {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.image-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.image-hint {
  margin: 0;
  color: #9CA3AF;
  font-size: 12px;
  line-height: 1.6;
}
</style>
