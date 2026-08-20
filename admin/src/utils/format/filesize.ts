/**
 * 文件大小格式化工具
 *
 * 后端的 `size_bytes` 是裸字节数（相册照片单张上限 20MB），表格里直出 8 位数字无法比较大小，
 * 统一格式化成 KB / MB。
 *
 * @module utils/format/filesize
 * @author LxDay
 */

/** 空值占位符 */
const EMPTY = '-'

const KB = 1024
const MB = KB * 1024
const GB = MB * 1024

/**
 * 把字节数格式化为可读大小（B / KB / MB / GB）
 *
 * 用 1024 进制而非 1000：与操作系统文件管理器的显示口径一致，
 * 否则管理员按「20MB 上限」核对时会看到 20.9MB 这类对不上的数字。
 *
 * @param bytes 字节数
 * @param fallback 无效值时的占位符，默认 '-'
 */
export function formatFileSize(bytes?: number | null, fallback = EMPTY): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes) || bytes < 0) return fallback
  if (bytes === 0) return '0 B'

  if (bytes < KB) return `${bytes} B`
  if (bytes < MB) return `${(bytes / KB).toFixed(1)} KB`
  if (bytes < GB) return `${(bytes / MB).toFixed(2)} MB`
  return `${(bytes / GB).toFixed(2)} GB`
}
