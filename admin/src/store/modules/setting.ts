/**
 * 系统设置状态管理模块
 *
 * 提供完整的系统设置状态管理
 *
 * ## 主要功能
 *
 * - 菜单布局配置（左侧、顶部、混合、双栏）
 * - 主题管理（亮色、暗色、自动）
 * - 菜单主题样式配置
 * - 界面显示开关（面包屑、标签页、语言切换等）
 * - 功能开关（手风琴模式、色弱模式、水印等）
 * - 样式配置（边框、圆角、容器宽度、页面过渡）
 * - 节日功能配置
 * - Element Plus 主题色动态设置
 *
 * ## 使用场景
 *
 * - 设置面板配置管理
 * - 主题切换和样式定制
 * - 界面功能开关控制
 * - 用户偏好设置持久化
 *
 * ## 持久化
 *
 * - 使用 localStorage 存储
 * - 存储键：sys-v{version}-setting
 * - 支持跨版本数据迁移
 *
 * @module store/modules/setting
 * @author LxDay
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { MenuThemeType } from '@/types/store'
import AppConfig from '@/config'
import { SystemThemeEnum, MenuThemeEnum, MenuTypeEnum, ContainerWidthEnum } from '@/enums/appEnum'
import { setElementThemeColor } from '@/utils/ui'
import { StorageConfig } from '@/utils'
import { SETTING_DEFAULT_CONFIG } from '@/config/setting'

/**
 * 侧边栏宽度的历史默认值。
 *
 * 默认宽度从 230 收窄到 190 后，**已访问过后台的浏览器**会从 localStorage 里
 * 读回旧值 230，导致新默认值对老用户完全不生效（刷新、重启都不行）。
 *
 * 这里做一次「仅当用户从未自定义过」才生效的迁移：
 * 存量值为旧默认 230 视为没动过 → 升级到新默认 190；
 * 任何其他值都视为用户主动调整过 → 原样保留，绝不覆盖用户意图。
 *
 * 迁移只跑一次（用 MIGRATION_FLAG_KEY 标记），因此用户此后把宽度改回 230
 * 不会被二次改写。
 */
const LEGACY_MENU_OPEN_WIDTH = 230
const MENU_WIDTH_MIGRATION_FLAG_KEY = 'sys-menu-width-migrated-to-190'

function migrateMenuOpenWidth(stored: unknown): unknown {
  try {
    if (localStorage.getItem(MENU_WIDTH_MIGRATION_FLAG_KEY)) return stored
    localStorage.setItem(MENU_WIDTH_MIGRATION_FLAG_KEY, '1')
  } catch {
    // 隐私模式等场景下 localStorage 不可写：不做迁移，保持原值即可。
    return stored
  }
  return stored === LEGACY_MENU_OPEN_WIDTH ? SETTING_DEFAULT_CONFIG.menuOpenWidth : stored
}

/**
 * 归一化菜单布局类型（修复「后台界面消失」）。
 *
 * 本项目采用的是**扁平路由**：每个页面都是独立的一级路由（如 /dashboard、/users），
 * 路由之间没有父子层级。而混合菜单（top-left）与双列菜单（dual-menu）这两种布局
 * 在结构上都依赖「一级目录 + 二级子菜单」的层级数据：
 *   - 混合菜单：左侧只渲染当前一级目录下的子菜单，一级目录本身渲染在顶栏；
 *   - 双列菜单：左列渲染一级目录，右侧列渲染对应子菜单。
 * 扁平路由既没有目录也没有子菜单，于是这两种布局的菜单列表恒为空 ——
 * 左侧栏虽然渲染出来却**一个菜单项都没有**，用户看到的就是「侧边栏/导航消失」，
 * 进而以为整个后台坏了。
 *
 * 因此把这两个布局视为对本项目无效的存量值：读到时一律回退到左侧菜单（left）。
 * 这是对所有老用户都生效的兜底修复——即便某台浏览器里已经存了坏值，刷新后也能恢复。
 * 顶栏菜单（top）与左侧菜单（left）不依赖层级，保留不动。
 */
const INCOMPATIBLE_MENU_TYPES = ['top-left', 'dual-menu']

function migrateMenuType(stored: unknown): unknown {
  return typeof stored === 'string' && INCOMPATIBLE_MENU_TYPES.includes(stored)
    ? SETTING_DEFAULT_CONFIG.menuType
    : stored
}

/**
 * 系统设置状态管理
 * 管理应用的菜单、主题、界面显示等各项设置
 */
export const useSettingStore = defineStore(
  'settingStore',
  () => {
    // 菜单相关设置
    /** 菜单类型 */
    const menuType = ref(SETTING_DEFAULT_CONFIG.menuType)
    /** 菜单展开宽度 */
    const menuOpenWidth = ref(SETTING_DEFAULT_CONFIG.menuOpenWidth)
    /** 菜单是否展开 */
    const menuOpen = ref(SETTING_DEFAULT_CONFIG.menuOpen)
    /** 双菜单是否显示文本 */
    const dualMenuShowText = ref(SETTING_DEFAULT_CONFIG.dualMenuShowText)

    // 主题相关设置
    /** 系统主题类型 */
    const systemThemeType = ref(SETTING_DEFAULT_CONFIG.systemThemeType)
    /** 系统主题模式 */
    const systemThemeMode = ref(SETTING_DEFAULT_CONFIG.systemThemeMode)
    /** 菜单主题类型 */
    const menuThemeType = ref(SETTING_DEFAULT_CONFIG.menuThemeType)
    /** 系统主题颜色 */
    const systemThemeColor = ref(SETTING_DEFAULT_CONFIG.systemThemeColor)

    // 界面显示设置
    /** 是否显示菜单按钮 */
    const showMenuButton = ref(SETTING_DEFAULT_CONFIG.showMenuButton)
    /** 是否显示快速入口 */
    const showFastEnter = ref(SETTING_DEFAULT_CONFIG.showFastEnter)
    /** 是否显示刷新按钮 */
    const showRefreshButton = ref(SETTING_DEFAULT_CONFIG.showRefreshButton)
    /** 是否显示面包屑 */
    const showCrumbs = ref(SETTING_DEFAULT_CONFIG.showCrumbs)
    /** 是否显示工作台标签 */
    const showWorkTab = ref(SETTING_DEFAULT_CONFIG.showWorkTab)
    /** 是否显示语言切换 */
    const showLanguage = ref(SETTING_DEFAULT_CONFIG.showLanguage)
    /** 是否显示进度条 */
    const showNprogress = ref(SETTING_DEFAULT_CONFIG.showNprogress)
    /** 是否显示设置引导 */
    const showSettingGuide = ref(SETTING_DEFAULT_CONFIG.showSettingGuide)
    /** 是否显示水印 */
    const watermarkVisible = ref(SETTING_DEFAULT_CONFIG.watermarkVisible)

    // 功能设置
    /** 是否自动关闭 */
    const autoClose = ref(SETTING_DEFAULT_CONFIG.autoClose)
    /** 是否唯一展开 */
    const uniqueOpened = ref(SETTING_DEFAULT_CONFIG.uniqueOpened)
    /** 是否色弱模式 */
    const colorWeak = ref(SETTING_DEFAULT_CONFIG.colorWeak)
    /** 是否刷新 */
    const refresh = ref(SETTING_DEFAULT_CONFIG.refresh)

    // 样式设置
    /** 边框模式 */
    const boxBorderMode = ref(SETTING_DEFAULT_CONFIG.boxBorderMode)
    /** 页面过渡效果 */
    const pageTransition = ref(SETTING_DEFAULT_CONFIG.pageTransition)
    /** 标签页样式 */
    const tabStyle = ref(SETTING_DEFAULT_CONFIG.tabStyle)
    /** 自定义圆角 */
    const customRadius = ref(SETTING_DEFAULT_CONFIG.customRadius)
    /** 容器宽度 */
    const containerWidth = ref(SETTING_DEFAULT_CONFIG.containerWidth)

    /**
     * 获取菜单主题
     * 根据当前主题类型和暗色模式返回对应的主题配置
     */
    const getMenuTheme = computed((): MenuThemeType => {
      const list = AppConfig.themeList.filter((item) => item.theme === menuThemeType.value)
      if (isDark.value) {
        return AppConfig.darkMenuStyles[0]
      } else {
        return list[0]
      }
    })

    /**
     * 判断是否为暗色模式
     */
    const isDark = computed((): boolean => {
      return systemThemeType.value === SystemThemeEnum.DARK
    })

    /**
     * 获取菜单展开宽度
     */
    const getMenuOpenWidth = computed((): string => {
      return menuOpenWidth.value + 'px' || SETTING_DEFAULT_CONFIG.menuOpenWidth + 'px'
    })

    /**
     * 获取自定义圆角
     */
    const getCustomRadius = computed((): string => {
      return customRadius.value + 'rem' || SETTING_DEFAULT_CONFIG.customRadius + 'rem'
    })

    /**
     * 切换菜单布局
     * @param type 菜单类型
     */
    const switchMenuLayouts = (type: MenuTypeEnum) => {
      menuType.value = type
    }

    /**
     * 设置菜单展开宽度
     * @param width 宽度值
     */
    const setMenuOpenWidth = (width: number) => {
      menuOpenWidth.value = width
    }

    /**
     * 设置全局主题
     * @param theme 主题类型
     * @param themeMode 主题模式
     */
    const setGlopTheme = (theme: SystemThemeEnum, themeMode: SystemThemeEnum) => {
      systemThemeType.value = theme
      systemThemeMode.value = themeMode
      localStorage.setItem(StorageConfig.THEME_KEY, theme)
    }

    /**
     * 切换菜单样式
     * @param theme 菜单主题
     */
    const switchMenuStyles = (theme: MenuThemeEnum) => {
      menuThemeType.value = theme
    }

    /**
     * 设置Element Plus主题颜色
     * @param theme 主题颜色
     */
    const setElementTheme = (theme: string) => {
      systemThemeColor.value = theme
      setElementThemeColor(theme)
    }

    /**
     * 切换边框模式
     */
    const setBorderMode = () => {
      boxBorderMode.value = !boxBorderMode.value
    }

    /**
     * 设置容器宽度
     * @param width 容器宽度枚举值
     */
    const setContainerWidth = (width: ContainerWidthEnum) => {
      containerWidth.value = width
    }

    /**
     * 切换唯一展开模式
     */
    const setUniqueOpened = () => {
      uniqueOpened.value = !uniqueOpened.value
    }

    /**
     * 切换菜单按钮显示
     */
    const setButton = () => {
      showMenuButton.value = !showMenuButton.value
    }

    /**
     * 切换快速入口显示
     */
    const setFastEnter = () => {
      showFastEnter.value = !showFastEnter.value
    }

    /**
     * 切换自动关闭
     */
    const setAutoClose = () => {
      autoClose.value = !autoClose.value
    }

    /**
     * 切换刷新按钮显示
     */
    const setShowRefreshButton = () => {
      showRefreshButton.value = !showRefreshButton.value
    }

    /**
     * 切换面包屑显示
     */
    const setCrumbs = () => {
      showCrumbs.value = !showCrumbs.value
    }

    /**
     * 设置工作台标签显示
     * @param show 是否显示
     */
    const setWorkTab = (show: boolean) => {
      showWorkTab.value = show
    }

    /**
     * 切换语言切换显示
     */
    const setLanguage = () => {
      showLanguage.value = !showLanguage.value
    }

    /**
     * 切换进度条显示
     */
    const setNprogress = () => {
      showNprogress.value = !showNprogress.value
    }

    /**
     * 切换色弱模式
     */
    const setColorWeak = () => {
      colorWeak.value = !colorWeak.value
    }

    /**
     * 隐藏设置引导
     */
    const hideSettingGuide = () => {
      showSettingGuide.value = false
    }

    /**
     * 显示设置引导
     */
    const openSettingGuide = () => {
      showSettingGuide.value = true
    }

    /**
     * 设置页面过渡效果
     * @param transition 过渡效果名称
     */
    const setPageTransition = (transition: string) => {
      pageTransition.value = transition
    }

    /**
     * 设置标签页样式
     * @param style 样式名称
     */
    const setTabStyle = (style: string) => {
      tabStyle.value = style
    }

    /**
     * 设置菜单展开状态
     * @param open 是否展开
     */
    const setMenuOpen = (open: boolean) => {
      menuOpen.value = open
    }

    /**
     * 刷新页面
     */
    const reload = () => {
      refresh.value = !refresh.value
    }

    /**
     * 设置水印显示
     * @param visible 是否显示
     */
    const setWatermarkVisible = (visible: boolean) => {
      watermarkVisible.value = visible
    }

    /**
     * 设置自定义圆角
     * @param radius 圆角值
     */
    const setCustomRadius = (radius: string) => {
      customRadius.value = radius
      document.documentElement.style.setProperty('--custom-radius', `${radius}rem`)
    }

    const setDualMenuShowText = (show: boolean) => {
      dualMenuShowText.value = show
    }

    return {
      menuType,
      menuOpenWidth,
      systemThemeType,
      systemThemeMode,
      menuThemeType,
      systemThemeColor,
      boxBorderMode,
      uniqueOpened,
      showMenuButton,
      showFastEnter,
      showRefreshButton,
      showCrumbs,
      autoClose,
      showWorkTab,
      showLanguage,
      showNprogress,
      colorWeak,
      showSettingGuide,
      pageTransition,
      tabStyle,
      menuOpen,
      refresh,
      watermarkVisible,
      customRadius,
      dualMenuShowText,
      containerWidth,
      getMenuTheme,
      isDark,
      getMenuOpenWidth,
      getCustomRadius,
      switchMenuLayouts,
      setMenuOpenWidth,
      setGlopTheme,
      switchMenuStyles,
      setElementTheme,
      setBorderMode,
      setContainerWidth,
      setUniqueOpened,
      setButton,
      setFastEnter,
      setAutoClose,
      setShowRefreshButton,
      setCrumbs,
      setWorkTab,
      setLanguage,
      setNprogress,
      setColorWeak,
      hideSettingGuide,
      openSettingGuide,
      setPageTransition,
      setTabStyle,
      setMenuOpen,
      reload,
      setWatermarkVisible,
      setCustomRadius,
      setDualMenuShowText
    }
  },
  {
    persist: {
      key: 'setting',
      storage: localStorage,
      /**
       * 反序列化钩子：
       *  - menuOpenWidth：「侧边栏默认宽度 230 → 190」，见文件顶部 migrateMenuOpenWidth；
       *  - menuType：把对本项目扁平路由无效的混合/双列布局回退到左侧菜单，
       *    见文件顶部 migrateMenuType —— 这是「后台界面（侧边栏菜单）消失」的兜底修复。
       */
      serializer: {
        serialize: JSON.stringify,
        deserialize: (raw: string) => {
          const parsed = JSON.parse(raw)
          if (parsed && typeof parsed === 'object') {
            if ('menuOpenWidth' in parsed) {
              parsed.menuOpenWidth = migrateMenuOpenWidth(parsed.menuOpenWidth)
            }
            if ('menuType' in parsed) {
              parsed.menuType = migrateMenuType(parsed.menuType)
            }
          }
          return parsed
        }
      }
    }
  }
)
