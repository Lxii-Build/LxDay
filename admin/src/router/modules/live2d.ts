import { AppRouteRecord } from '@/types/router'

/** Live2D 模型资源库：模型包属于可执行/渲染资源，仅超管可管理。 */
export const live2dRoutes: AppRouteRecord = {
  name: 'Live2DManage',
  path: '/live2d-manage',
  component: '/live2d-manage/index',
  meta: {
    title: 'menus.live2dManage.title',
    icon: 'ri:user-smile-line',
    keepAlive: true,
    roles: ['super']
  }
}
