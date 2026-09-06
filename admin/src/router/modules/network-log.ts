import { AppRouteRecord } from '@/types/router'

export const networkLogRoutes: AppRouteRecord = {
  name: 'NetworkLog',
  path: '/network-log',
  component: '/network-log/index',
  meta: {
    title: 'menus.networkLog.title',
    icon: 'ri:global-line',
    keepAlive: true,
    // 保留旧链接，避免书签失效；入口已合并到“日志中心”。
    isHide: true,
    roles: ['super', 'admin']
  }
}
