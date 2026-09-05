<template>
  <nav class="pagination" :class="{ 'is-disabled': disabled }" aria-label="分页导航">
    <div class="pagination-summary">
      共 <strong>{{ total }}</strong> 条
      <span v-if="total > 0" class="summary-range">
        （第 {{ rangeStart }}-{{ rangeEnd }} 条）
      </span>
    </div>

    <div class="pagination-controls">
      <select
        class="size-select"
        :value="size"
        :disabled="disabled"
        aria-label="每页条数"
        @change="handleSizeChange"
      >
        <option v-for="s in pageSizes" :key="s" :value="s">{{ s }} 条/页</option>
      </select>

      <button
        class="page-btn edge-btn"
        :disabled="disabled || currentPage <= 1"
        title="第一页"
        @click="goTo(1)"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="11 17 6 12 11 7"/>
          <line x1="18" y1="7" x2="18" y2="17"/>
        </svg>
        <span class="btn-text">首页</span>
      </button>

      <button
        class="page-btn"
        :disabled="disabled || currentPage <= 1"
        title="上一页"
        @click="goTo(currentPage - 1)"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="15 18 9 12 15 6"/>
        </svg>
        <span class="btn-text">上一页</span>
      </button>

      <ul class="page-numbers">
        <li v-for="(item, index) in pageList" :key="index">
          <span v-if="item === ELLIPSIS" class="page-ellipsis">…</span>
          <button
            v-else
            class="page-num"
            :class="{ active: item === currentPage }"
            :aria-current="item === currentPage ? 'page' : undefined"
            :disabled="disabled"
            @click="goTo(item)"
          >
            {{ item }}
          </button>
        </li>
      </ul>

      <button
        class="page-btn"
        :disabled="disabled || currentPage >= totalPages"
        title="下一页"
        @click="goTo(currentPage + 1)"
      >
        <span class="btn-text">下一页</span>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="9 18 15 12 9 6"/>
        </svg>
      </button>

      <button
        class="page-btn edge-btn"
        :disabled="disabled || currentPage >= totalPages"
        title="最后一页"
        @click="goTo(totalPages)"
      >
        <span class="btn-text">末页</span>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="13 17 18 12 13 7"/>
          <line x1="6" y1="7" x2="6" y2="17"/>
        </svg>
      </button>

      <div class="jump-box">
        <span class="jump-label">跳至</span>
        <input
          v-model="jumpInput"
          class="jump-input"
          type="text"
          inputmode="numeric"
          :disabled="disabled"
          aria-label="跳转到指定页"
          @keyup.enter="handleJump"
        />
        <span class="jump-label">页</span>
        <button class="jump-btn" :disabled="disabled" @click="handleJump">前往</button>
      </div>
    </div>
  </nav>
</template>

<script setup>
import { computed, ref } from 'vue'

const ELLIPSIS = 'ellipsis'

const currentPage = defineModel('page', { type: Number, default: 1 })
const size = defineModel('size', { type: Number, default: 10 })

const props = defineProps({
  /** 总记录数 */
  total: { type: Number, default: 0 },
  /** 可选每页条数 */
  pageSizes: { type: Array, default: () => [10, 20, 50] },
  /** 中间连续页码按钮的最大数量（不含首尾页与省略号） */
  maxVisible: { type: Number, default: 5 },
  /** 加载中禁用操作，避免重复请求 */
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['change'])

const jumpInput = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(props.total / size.value)))

const rangeStart = computed(() => {
  if (props.total === 0) return 0
  return (currentPage.value - 1) * size.value + 1
})
const rangeEnd = computed(() => Math.min(props.total, currentPage.value * size.value))

/** 生成带省略号的页码列表，如 1 … 4 5 6 … 20 */
const pageList = computed(() => {
  const pages = totalPages.value
  const max = props.maxVisible
  if (pages <= max) {
    return Array.from({ length: pages }, (_, i) => i + 1)
  }
  const half = Math.floor(max / 2)
  let start = Math.max(1, currentPage.value - half)
  let end = Math.min(pages, start + max - 1)
  start = Math.max(1, end - max + 1)

  const list = []
  if (start > 1) {
    list.push(1)
    if (start > 2) {
      list.push(ELLIPSIS)
    }
  }
  for (let i = start; i <= end; i++) {
    list.push(i)
  }
  if (end < pages) {
    if (end < pages - 1) {
      list.push(ELLIPSIS)
    }
    list.push(pages)
  }
  return list
})

function goTo(page) {
  const target = Math.min(Math.max(Number(page) || 1, 1), totalPages.value)
  if (target === currentPage.value) return
  currentPage.value = target
  emit('change', { page: target, size: size.value })
}

function handleSizeChange(event) {
  const nextSize = Number(event.target.value)
  if (nextSize === size.value) return
  size.value = nextSize
  // 每页条数变化时回到第一页，避免出现空白页
  currentPage.value = 1
  emit('change', { page: 1, size: nextSize })
}

function handleJump() {
  const target = Number(String(jumpInput.value).trim())
  if (!target) return
  jumpInput.value = ''
  goTo(target)
}
</script>

<style scoped>
.pagination {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-6);
  padding: var(--space-4) 0;
  user-select: none;
}
.pagination.is-disabled {
  opacity: 0.6;
  pointer-events: none;
}

.pagination-summary {
  font-size: 12px;
  color: var(--color-text-muted);
  letter-spacing: 0.3px;
}
.pagination-summary strong {
  color: var(--color-text-secondary);
  font-weight: 600;
}
.summary-range {
  margin-left: 2px;
}

.pagination-controls {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  gap: var(--space-2);
}

.page-btn,
.page-num,
.jump-btn,
.size-select {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 36px;
  padding: 0 var(--space-3);
  background: var(--glass-bg);
  color: var(--color-text-secondary);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all var(--transition-fast);
}
.page-btn:hover:not(:disabled),
.page-num:hover:not(:disabled),
.jump-btn:hover:not(:disabled),
.size-select:hover:not(:disabled) {
  border-color: var(--color-accent);
  color: var(--color-accent);
}
.page-btn:disabled,
.page-num:disabled,
.jump-btn:disabled,
.size-select:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.page-numbers {
  display: flex;
  align-items: center;
  gap: 4px;
  list-style: none;
  margin: 0;
  padding: 0;
}
.page-num {
  min-width: 36px;
  padding: 0 8px;
  font-family: var(--font-mono);
}
.page-num.active {
  background: var(--color-accent);
  border-color: var(--color-accent);
  color: #06060A;
  font-weight: 600;
}
.page-num.active:hover:not(:disabled) {
  background: var(--color-accent-dark);
  border-color: var(--color-accent-dark);
  color: #06060A;
}
.page-ellipsis {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  color: var(--color-text-muted);
}

.jump-box {
  display: flex;
  align-items: center;
  gap: 4px;
}
.jump-label {
  font-size: 12px;
  color: var(--color-text-muted);
}
.jump-input {
  width: 52px;
  min-height: 36px;
  padding: 0 8px;
  text-align: center;
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  color: var(--color-text);
  font-size: 13px;
  font-family: var(--font-mono);
  transition: all var(--transition-fast);
}
.jump-input:focus {
  outline: none;
  border-color: var(--color-accent);
  color: var(--color-accent);
}
.jump-input:disabled {
  opacity: 0.3;
}

.size-select {
  padding: 0 var(--space-2);
  -webkit-appearance: none;
  appearance: none;
  background-image: linear-gradient(45deg, transparent 50%, var(--color-text-muted) 50%),
                    linear-gradient(135deg, var(--color-text-muted) 50%, transparent 50%);
  background-position: right 12px center, right 7px center;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
  padding-right: 26px;
}
.size-select option {
  background: var(--color-dark);
  color: var(--color-text);
}

@media (max-width: 640px) {
  .page-numbers,
  .jump-box {
    display: none;
  }
  .btn-text {
    display: none;
  }
  .page-btn {
    min-width: 40px;
    padding: 0 var(--space-2);
  }
}
</style>
