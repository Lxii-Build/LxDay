import { AppRouteRecord } from '@/types/router'

export const networkLogRoutes: AppRouteRecord = {
  name: 'NetworkLog',
  path: '/network-log',
  component: '/network-log/index',
  meta: {
    title: 'menus.networkLog.title',
    icon: 'ri:global-line',
    keepAlive: true,
    roles: ['super', 'admin']
  }
}
