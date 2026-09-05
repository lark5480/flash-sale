/**
 * 秒杀倒计时工具
 *
 * 统一处理「时间解析 → 剩余时长拆解 → 展示格式化 / 紧迫度判定」，供秒杀列表页与秒杀详情页复用。
 *
 * 展示规则（与主流电商秒杀一致）：
 * - 未开始：距开始 3天 02:15:09
 * - 活动进行中且剩余 ≥ 1 天：距结束 3天 02:15:09（秒仍逐秒跳动，保留紧迫感）
 * - 活动进行中且剩余 < 1 天：距结束 02:15:09
 * - 剩余 < 1 小时：紧迫度 soon（红色高亮 + 「即将结束」角标）
 * - 剩余 < 5 分钟：紧迫度 critical（强化提醒 + 脉冲动画）
 * - 已结束：已结束
 *
 * @module utils/countdown
 */

import { ref, onUnmounted } from 'vue'
import { parseTime } from './time.js'

/** 时间单位（毫秒） */
const SECOND = 1000
const MINUTE = 60 * SECOND
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** 紧迫度等级：普通 / 即将结束（< 1 小时） / 最后冲刺（< 5 分钟） */
export const URGENCY = {
  NORMAL: 'normal',
  SOON: 'soon',
  CRITICAL: 'critical'
}

/** 紧迫度阈值（毫秒） */
const SOON_THRESHOLD = HOUR
const CRITICAL_THRESHOLD = 5 * MINUTE

/**
 * 拆解目标时间与当前时间的差值。
 *
 * @param {string|number|Date} target 目标时间
 * @param {number} now 当前时间戳（默认取系统时间）
 * @returns {{valid: boolean, ended: boolean, total: number, days: number, hours: number, minutes: number, seconds: number}}
 */
export function getRemaining(target, now = Date.now()) {
  const end = parseTime(target)
  if (Number.isNaN(end)) {
    return { valid: false, ended: false, total: 0, days: 0, hours: 0, minutes: 0, seconds: 0 }
  }
  const total = Math.max(0, end - now)
  return {
    valid: true,
    ended: total <= 0,
    total,
    days: Math.floor(total / DAY),
    hours: Math.floor((total % DAY) / HOUR),
    minutes: Math.floor((total % HOUR) / MINUTE),
    seconds: Math.floor((total % MINUTE) / SECOND)
  }
}

/**
 * 格式化为 HH:MM:SS（不含天）。
 *
 * @param {{hours: number, minutes: number, seconds: number}} remaining getRemaining 的返回值
 * @returns {string} 如 '02:15:09'
 */
export function formatHms(remaining) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(remaining.hours)}:${pad(remaining.minutes)}:${pad(remaining.seconds)}`
}

/**
 * 根据剩余时长判定紧迫度。
 *
 * @param {{valid: boolean, ended: boolean, total: number}} remaining getRemaining 的返回值
 * @returns {string} URGENCY 之一
 */
export function getUrgency(remaining) {
  if (!remaining.valid || remaining.ended) return URGENCY.NORMAL
  if (remaining.total <= CRITICAL_THRESHOLD) return URGENCY.CRITICAL
  if (remaining.total <= SOON_THRESHOLD) return URGENCY.SOON
  return URGENCY.NORMAL
}

/**
 * 计算秒杀活动的倒计时信息，自动选择「距开始」或「距结束」作为目标时间。
 *
 * @param {{startTime?: string, endTime?: string}} sale 秒杀活动
 * @param {number} now 当前时间戳（默认取系统时间）
 * @returns {object} 在 getRemaining 结果基础上附加 notStarted / label / days / hms / text / urgency
 */
export function getSaleCountdown(sale, now = Date.now()) {
  const start = parseTime(sale?.startTime)
  const end = parseTime(sale?.endTime)
  const notStarted = !Number.isNaN(start) && now < start
  const target = notStarted ? start : end
  const remaining = getRemaining(target, now)
  const hms = formatHms(remaining)
  // 未开始阶段不触发紧迫样式：「距开始 00:40:00」并不代表即将结束
  const urgency = remaining.ended || notStarted ? URGENCY.NORMAL : getUrgency(remaining)
  return {
    ...remaining,
    notStarted,
    label: notStarted ? '距开始' : '距结束',
    days: remaining.days,
    hms,
    text: !remaining.valid ? '--:--:--' : remaining.ended ? '已结束' : remaining.days > 0 ? `${remaining.days}天 ${hms}` : hms,
    urgency
  }
}

/* ===== 共享秒级时钟 ===== */
const nowRef = ref(Date.now())
let timer = null
let subscribers = 0

/**
 * 共享的秒级时钟：所有使用方复用同一个定时器，最后一个使用方卸载后自动停表。
 * 相比每个组件各自 setInterval，可避免列表页出现大量定时器。
 *
 * @returns {import('vue').Ref<number>} 每秒更新的当前时间戳
 */
export function useNow() {
  subscribers += 1
  if (timer === null) {
    timer = setInterval(() => {
      nowRef.value = Date.now()
    }, 1000)
  }
  onUnmounted(() => {
    subscribers -= 1
    if (subscribers <= 0) {
      subscribers = 0
      clearInterval(timer)
      timer = null
      // 停表期间时间会过期，重新订阅前校准一次
      nowRef.value = Date.now()
    }
  })
  return nowRef
}
