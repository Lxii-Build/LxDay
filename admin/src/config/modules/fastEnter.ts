/**
 * 快速入口配置
 * 包含：应用列表、快速链接等配置
 */
import type { FastEnterConfig } from '@/types/config'

const fastEnterConfig: FastEnterConfig = {
  // 显示条件（屏幕宽度）
  minWidth: 1200,
  // 应用列表
  applications: [
    {
      name: '数据看板',
      description: '系统概览与数据统计',
      icon: 'ri:dashboard-line',
      iconColor: '#377dff',
      enabled: true,
      order: 1,
      routeName: 'Dashboard'
    },
    {
      name: '用户管理',
      description: '用户列表与状态管理',
      icon: 'ri:user-3-line',
      iconColor: '#13DEB9',
      enabled: true,
      order: 2,
      routeName: 'UserManage'
    },
    {
      name: '内容审核',
      description: '待办与相册内容审核',
      icon: 'ri:file-list-3-line',
      iconColor: '#ffb100',
      enabled: true,
      order: 3,
      routeName: 'ContentAudit'
    },
    {
      name: '系统设置',
      description: '站点、运行参数、推送与 SMTP',
      icon: 'ri:settings-3-line',
      iconColor: '#7A7FFF',
      enabled: true,
      order: 4,
      routeName: 'SystemSettings'
    }
  ],
  // 快速链接
  quickLinks: [
    {
      name: '退出登录',
      enabled: true,
      order: 1,
      routeName: 'Login'
    }
  ]
}

export default Object.freeze(fastEnterConfig)
