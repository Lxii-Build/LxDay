import { AppRouteRecord } from '@/types/router'

/**
 * 相册管理（0821 新增，管理员 Q28=D）。
 * 限超管：相册是全站最私密的内容，服务端对应路由也挂在 sup 组。
 */
export const albumRoutes: AppRouteRecord = {
  name: 'AlbumManage',
  path: '/album-manage',
  component: '/album-manage/index',
  meta: {
    title: 'menus.albumManage.title',
    icon: 'ri:image-2-line',
    keepAlive: true,
    roles: ['super']
  }
}
