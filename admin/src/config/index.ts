/**
 * 系统全局配置
 *
 * 这是系统的核心配置文件，集中管理所有全局配置项。
 * 包含系统信息、主题样式、菜单布局、颜色方案等所有可配置项。
 *
 * ## 主要功能
 *
 * - 系统信息 - 系统名称等基础信息
 * - 主题配置 - 亮色/暗色/自动主题的样式配置
 * - 菜单配置 - 菜单布局、主题、宽度等配置
 * - 颜色方案 - 系统主色和预设颜色列表
 * - 快速入口 - 快速入口应用和链接配置
 * - 顶部栏配置 - 顶部栏功能模块配置
 *
 * ## 配置项说明
 *
 * - systemInfo: 系统基础信息（名称等）
 * - systemThemeStyles: 系统主题样式映射
 * - settingThemeList: 可选的系统主题列表
 * - menuLayoutList: 可选的菜单布局列表
 * - themeList: 菜单主题样式列表
 * - darkMenuStyles: 暗黑模式下的菜单样式
 * - systemMainColor: 预设的系统主色列表
 * - fastEnter: 快速入口配置
 * - headerBar: 顶部栏功能配置
 *
 * @module config
 * @author LxDay
 */

import { MenuThemeEnum, MenuTypeEnum, SystemThemeEnum } from '@/enums/appEnum'
import { SystemConfig } from '@/types/config'
import { configImages } from './assets/images'
import fastEnterConfig from './modules/fastEnter'
import { headerBarConfig } from './modules/headerBar'

const appConfig: SystemConfig = {
  // 系统信息
  systemInfo: {
    name: '林曦日记' // 系统名称（默认值，运行时由后端 site.name 覆盖）
  },
  // 系统主题
  systemThemeStyles: {
    [SystemThemeEnum.LIGHT]: { className: '' },
    [SystemThemeEnum.DARK]: { className: SystemThemeEnum.DARK }
  },
  // 系统主题列表
  settingThemeList: [
    {
      name: 'Light',
      theme: SystemThemeEnum.LIGHT,
      color: ['#fff', '#fff'],
      leftLineColor: '#EDEEF0',
      rightLineColor: '#EDEEF0',
      img: configImages.themeStyles.light
    },
    {
      name: 'Dark',
      theme: SystemThemeEnum.DARK,
      color: ['#22252A'],
      leftLineColor: '#3F4257',
      rightLineColor: '#3F4257',
      img: configImages.themeStyles.dark
    },
    {
      name: 'System',
      theme: SystemThemeEnum.AUTO,
      color: ['#fff', '#22252A'],
      leftLineColor: '#EDEEF0',
      rightLineColor: '#3F4257',
      img: configImages.themeStyles.system
    }
  ],
  // 菜单布局列表。
  //
  // 本项目为扁平路由（每个页面都是独立一级路由，无目录/子菜单层级），
  // 因此只保留不依赖层级的两种布局：
  //   - Left：左侧竖向菜单；
  //   - Top：顶栏横向菜单。
  // 「Mixed（混合）」与「Dual Column（双列）」都要求「一级目录 + 二级子菜单」的两层
  // 菜单数据，在扁平路由下左侧栏会渲染成空列表 —— 即用户反馈的「后台界面/侧边栏消失」。
  // 与其让用户选到一个必然坏掉的布局，不如直接不提供（存量已选中的坏值会在
  // store/modules/setting.ts 的 migrateMenuType 里自动回退到 Left）。
  menuLayoutList: [
    { name: 'Left', value: MenuTypeEnum.LEFT, img: configImages.menuLayouts.vertical },
    { name: 'Top', value: MenuTypeEnum.TOP, img: configImages.menuLayouts.horizontal }
  ],
  // 菜单主题列表
  themeList: [
    {
      theme: MenuThemeEnum.DESIGN,
      background: '#FFFFFF',
      systemNameColor: 'var(--art-gray-800)',
      iconColor: '#6B6B6B',
      textColor: '#29343D',
      img: configImages.menuStyles.design
    },
    {
      theme: MenuThemeEnum.DARK,
      background: '#191A23',
      systemNameColor: '#D9DADB',
      iconColor: '#BABBBD',
      textColor: '#BABBBD',
      img: configImages.menuStyles.dark
    },
    {
      theme: MenuThemeEnum.LIGHT,
      background: '#ffffff',
      systemNameColor: 'var(--art-gray-800)',
      iconColor: '#6B6B6B',
      textColor: '#29343D',
      img: configImages.menuStyles.light
    }
  ],
  // 暗黑模式菜单样式
  darkMenuStyles: [
    {
      theme: MenuThemeEnum.DARK,
      background: 'var(--default-box-color)',
      systemNameColor: '#DDDDDD',
      iconColor: '#BABBBD',
      textColor: 'rgba(#FFFFFF, 0.7)'
    }
  ],
  // 系统主色
  systemMainColor: [
    '#5D87FF',
    '#B48DF3',
    '#1D84FF',
    '#60C041',
    '#38C0FC',
    '#F9901F',
    '#FF80C8'
  ] as const,
  // 快速入口配置
  fastEnter: fastEnterConfig,
  // 顶部栏功能配置
  headerBar: headerBarConfig
}

export default Object.freeze(appConfig)
