import { AppRouteRecord } from '@/types/router'

/**
 * 磁盘占用统计（0821 新增）。
 * 管理员此前完全不知道磁盘被谁占了多少，直到它满。
 */
export const storageRoutes: AppRouteRecord = {
  name: 'StorageStats',
  path: '/storage-stats',
  component: '/storage-stats/index',
  meta: {
    title: 'menus.storageStats.title',
    icon: 'ri:hard-drive-2-line',
    keepAlive: true,
    roles: ['super']
  }
}
