import { AppRouteRecord } from '@/types/router'

export const appVersionRoutes: AppRouteRecord = {
  name: 'AppVersion',
  path: '/app-version',
  component: '/app-version/index',
  meta: {
    title: 'menus.appVersion.title',
    icon: 'ri:smartphone-line',
    keepAlive: true,
    roles: ['super', 'admin']
  }
}
