import { AppRouteRecord } from '@/types/router'

export const auditRoutes: AppRouteRecord = {
  name: 'AuditLog',
  path: '/audit-log',
  component: '/audit-log/index',
  meta: {
    title: 'menus.auditLog.title',
    icon: 'ri:file-shield-2-line',
    keepAlive: true,
    roles: ['super', 'admin']
  }
}
