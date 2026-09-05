import { AppRouteRecord } from '@/types/router'

export const listenRoutes: AppRouteRecord = {
  name: 'ListenTogether',
  path: '/listen-together',
  component: '/listen-together/index',
  meta: {
    title: 'menus.listenTogether.title',
    icon: 'ri:music-2-line',
    keepAlive: true,
    roles: ['super', 'admin']
  }
}
