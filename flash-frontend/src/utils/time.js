/**
 * 时间处理工具
 *
 * 后端 LocalDateTime 统一序列化为 'yyyy-MM-dd HH:mm:ss'（无 T 分隔符、无时区标记），
 * Safari / iOS 等严格解析器会将其判定为 Invalid Date，因此统一通过 parseTime 解析。
 *
 * @module utils/time
 */

/**
 * 解析后端返回的时间。
 *
 * @param {string|number|Date} value 时间值
 * @returns {number} 毫秒时间戳，无法解析时返回 NaN
 */
export function parseTime(value) {
  if (value == null || value === '') return NaN
  if (value instanceof Date) return value.getTime()
  if (typeof value === 'number') return value
  const raw = String(value).trim()
  const normalized = raw.indexOf('T') === -1 ? raw.replace(' ', 'T') : raw
  return Date.parse(normalized)
}
