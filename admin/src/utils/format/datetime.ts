/**
 * 时间格式化工具
 *
 * 后端返回的时间为 RFC3339 / SQLite datetime 字符串（如 2026-08-20T12:34:56Z、
 * 2026-08-20 12:34:56）。表格里直出这些字符串既难读又不统一，统一走这里格式化。
 *
 * @module utils/format/datetime
 * @author LxDay
 */

/** 空值占位符 */
const EMPTY = '-'

/**
 * 将后端时间值解析为 Date
 * 兼容 RFC3339（含 Z / 时区偏移）与 "YYYY-MM-DD HH:mm:ss"（按本地时间解析）
 * @param value 时间字符串 / 时间戳 / Date
 * @returns 解析成功返回 Date，否则返回 null
 */
export function parseDateTime(value?: string | number | Date | null): Date | null {
  if (value === null || value === undefined || value === '') return null

  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value
  }

  if (typeof value === 'number') {
    const d = new Date(value)
    return Number.isNaN(d.getTime()) ? null : d
  }

  const raw = value.trim()
  if (!raw) return null

  // 零值时间（Go 的 time.Time 零值）视为空
  if (raw.startsWith('0001-01-01')) return null

  // "YYYY-MM-DD HH:mm:ss" → 补 T 让 Safari/iOS 也能解析（按本地时间）
  const normalized = /^\d{4}-\d{2}-\d{2}[ ]\d{2}:\d{2}/.test(raw) ? raw.replace(' ', 'T') : raw

  const d = new Date(normalized)
  return Number.isNaN(d.getTime()) ? null : d
}

/** 补零 */
const pad = (n: number): string => String(n).padStart(2, '0')

/**
 * 格式化为 YYYY-MM-DD HH:mm:ss
 * @param value 时间值
 * @param fallback 解析失败时的占位符，默认 '-'
 */
export function formatDateTime(value?: string | number | Date | null, fallback = EMPTY): string {
  const d = parseDateTime(value)
  if (!d) return fallback

  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    ` ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

/**
 * 格式化为 YYYY-MM-DD
 * @param value 时间值
 * @param fallback 解析失败时的占位符，默认 '-'
 */
export function formatDate(value?: string | number | Date | null, fallback = EMPTY): string {
  const d = parseDateTime(value)
  if (!d) return fallback

  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 相对时间阈值（秒） */
const MINUTE = 60
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
const MONTH = 30 * DAY
const YEAR = 365 * DAY

/**
 * 格式化为相对时间（刚刚 / 5 分钟前 / 3 天前 …）
 * 超过一年的直接回落到绝对时间，避免「2 年前」这类无用信息
 * @param value 时间值
 * @param fallback 解析失败时的占位符，默认 '-'
 */
export function formatRelative(value?: string | number | Date | null, fallback = EMPTY): string {
  const d = parseDateTime(value)
  if (!d) return fallback

  const diff = Math.floor((Date.now() - d.getTime()) / 1000)

  // 未来时间：直接给绝对时间，避免出现「-3 分钟前」
  if (diff < 0) return formatDateTime(d, fallback)

  if (diff < MINUTE) return '刚刚'
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)} 分钟前`
  if (diff < DAY) return `${Math.floor(diff / HOUR)} 小时前`
  if (diff < MONTH) return `${Math.floor(diff / DAY)} 天前`
  if (diff < YEAR) return `${Math.floor(diff / MONTH)} 个月前`

  return formatDateTime(d, fallback)
}
