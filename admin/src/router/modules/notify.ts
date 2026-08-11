import { AppRouteRecord } from '@/types/router'

export const notifyRoutes: AppRouteRecord = {
  name: 'Notify',
  path: '/notify',
  component: '/notify/index',
  meta: {
    title: 'menus.notify.title',
    icon: 'ri:notification-3-line',
    keepAlive: true,
    roles: ['super', 'admin']
  }
}
