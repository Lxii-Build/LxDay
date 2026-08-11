import { AppRouteRecord } from '@/types/router'

export const contentRoutes: AppRouteRecord = {
  name: 'ContentAudit',
  path: '/content-audit',
  component: '/content-audit/index',
  meta: {
    title: 'menus.contentAudit.title',
    icon: 'ri:file-list-3-line',
    keepAlive: true,
    roles: ['super', 'admin']
  }
}
