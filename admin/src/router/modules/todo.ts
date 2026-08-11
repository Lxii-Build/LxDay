import { AppRouteRecord } from '@/types/router'

export const todoRoutes: AppRouteRecord = {
  name: 'TodoManage',
  path: '/todo-manage',
  component: '/todo-manage/index',
  meta: {
    title: 'menus.todoManage.title',
    icon: 'ri:task-line',
    keepAlive: true,
    roles: ['super', 'admin']
  }
}
