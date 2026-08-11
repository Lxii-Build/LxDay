import { AppRouteRecord } from '@/types/router'

export const usersRoutes: AppRouteRecord = {
  name: 'UserManage',
  path: '/user-manage',
  component: '/user-manage/index',
  meta: {
    title: 'menus.userManage.title',
    icon: 'ri:user-3-line',
    keepAlive: true,
    roles: ['super', 'admin']
  }
}
