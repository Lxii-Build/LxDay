/**
 * 站点信息状态管理
 *
 * 站点名称 / LOGO / 描述从后端 /api/admin/site-info 读取，
 * 未登录或取数失败时使用默认值兜底。
 *
 * 注意别改回 /api/admin/settings：那个接口含 SMTP 与存储密钥、已收敛到超管，
 * 普通 admin 会 403，且首登强制改密期间任何人都读不到。
 *
 * @module store/modules/site
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchSiteInfo } from '@/api/admin'
import AppConfig from '@/config'

const DEFAULT_NAME = AppConfig.systemInfo.name

export const useSiteStore = defineStore(
  'siteStore',
  () => {
    const name = ref(DEFAULT_NAME)
    const logo = ref('')
    const description = ref('')

    /** 从后端加载站点信息 */
    const loadSiteInfo = async (): Promise<void> => {
      try {
        const settings = await fetchSiteInfo()
        name.value = settings['site.name']?.trim() || DEFAULT_NAME
        logo.value = settings['site.logo'] || ''
        description.value = settings['site.description'] || ''
      } catch {
        // 静默兜底，保持默认值
      }
    }

    /** 直接设置（保存设置后同步刷新） */
    const setSiteInfo = (payload: { name?: string; logo?: string; description?: string }): void => {
      if (payload.name !== undefined) name.value = payload.name.trim() || DEFAULT_NAME
      if (payload.logo !== undefined) logo.value = payload.logo
      if (payload.description !== undefined) description.value = payload.description
    }

    return { name, logo, description, loadSiteInfo, setSiteInfo }
  },
  {
    persist: {
      key: 'site',
      storage: localStorage
    }
  }
)
