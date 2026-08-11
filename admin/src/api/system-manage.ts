import request from '@/utils/http'
import { AppRouteRecord } from '@/types/router'

// 获取菜单列表（后端菜单模式使用；本项目使用 frontend 模式，此函数保留占位）
export function fetchGetMenuList() {
  return request.get<AppRouteRecord[]>({
    url: '/api/admin/menus'
  })
}
