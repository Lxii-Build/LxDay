import { AppRouteRecord } from '@/types/router'

export const settingsRoutes: AppRouteRecord = {
  name: 'SystemSettings',
  path: '/system-settings',
  component: '/system-settings/index',
  meta: {
    title: 'menus.systemSettings.title',
    icon: 'ri:settings-3-line',
    keepAlive: true,
    roles: ['super', 'admin']
  }
}
