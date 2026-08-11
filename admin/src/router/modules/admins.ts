import { AppRouteRecord } from '@/types/router'

export const adminsRoutes: AppRouteRecord = {
  name: 'AdminManage',
  path: '/admin-manage',
  component: '/admin-manage/index',
  meta: {
    title: 'menus.adminManage.title',
    icon: 'ri:admin-line',
    keepAlive: true,
    roles: ['super']
  }
}
