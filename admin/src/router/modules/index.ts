import { AppRouteRecord } from '@/types/router'
import { dashboardRoutes } from './dashboard'
import { usersRoutes } from './users'
import { pairsRoutes } from './pairs'
import { todoRoutes } from './todo'
import { contentRoutes } from './content'
import { appVersionRoutes } from './app-version'
import { notifyRoutes } from './notify'
import { settingsRoutes } from './settings'
import { auditRoutes } from './audit'
import { networkLogRoutes } from './network-log'
import { adminsRoutes } from './admins'

/**
 * 导出所有模块化路由（林曦日记运营后台）
 */
export const routeModules: AppRouteRecord[] = [
  dashboardRoutes,
  usersRoutes,
  pairsRoutes,
  todoRoutes,
  contentRoutes,
  appVersionRoutes,
  notifyRoutes,
  settingsRoutes,
  auditRoutes,
  networkLogRoutes,
  adminsRoutes
]
