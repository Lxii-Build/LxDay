import { AppRouteRecord } from '@/types/router'

export const pairsRoutes: AppRouteRecord = {
  name: 'PairManage',
  path: '/pair-manage',
  component: '/pair-manage/index',
  meta: {
    title: 'menus.pairManage.title',
    icon: 'ri:heart-2-line',
    keepAlive: true,
    roles: ['super', 'admin']
  }
}
