<!-- 顶部快速入口面板 -->
<template>
  <ElPopover
    ref="popoverRef"
    v-model:visible="visible"
    :width="700"
    :offset="0"
    :show-arrow="false"
    trigger="click"
    placement="bottom-start"
    popper-class="fast-enter-popover"
    :popper-style="{
      border: '1px solid var(--default-border)',
      borderRadius: 'calc(var(--custom-radius) / 2 + 4px)'
    }"
  >
    <template #reference>
      <div class="flex-c gap-2">
        <slot />
      </div>
    </template>

    <div class="grid grid-cols-[2fr_0.8fr]" role="menu">
      <div>
        <div class="grid grid-cols-2 gap-1.5">
          <!-- 应用列表 -->
          <button
            type="button"
            v-for="application in enabledApplications"
            :key="application.name"
            role="menuitem"
            class="mr-3 flex-c gap-3 rounded-lg border-0 bg-transparent p-2 text-left hover:bg-g-200/70 dark:hover:bg-g-200/90 hover:[&_.app-icon]:!bg-transparent focus-visible:outline focus-visible:outline-2 focus-visible:outline-theme"
            @click="handleApplicationClick(application)"
          >
            <div class="app-icon size-12 flex-cc rounded-lg bg-g-200/80 dark:bg-g-300/30">
              <ArtSvgIcon
                class="text-xl"
                :icon="application.icon"
                :style="{ color: application.iconColor }"
              />
            </div>
            <div>
              <h3 class="m-0 text-sm font-medium text-g-800">{{ application.name }}</h3>
              <p class="mt-1 text-xs text-g-600">{{ application.description }}</p>
            </div>
          </button>
        </div>
      </div>

      <div class="border-l-d pl-6 pt-2">
        <h3 class="mb-2.5 text-base font-medium text-g-800">快速链接</h3>
        <ul>
          <li v-for="quickLink in enabledQuickLinks" :key="quickLink.name">
            <button
              type="button"
              role="menuitem"
              class="w-full border-0 bg-transparent py-2 text-left hover:[&_span]:text-theme focus-visible:outline focus-visible:outline-2 focus-visible:outline-theme"
              @click="handleQuickLinkClick(quickLink)"
            >
              <span class="text-g-600 no-underline">{{ quickLink.name }}</span>
            </button>
          </li>
        </ul>
      </div>
    </div>
  </ElPopover>
</template>

<script setup lang="ts">
  import { useFastEnter } from '@/hooks/core/useFastEnter'
  import type { FastEnterApplication, FastEnterQuickLink } from '@/types/config'
  import { isExternalHttpUrl, openExternalLink } from '@/utils/navigation/jump'

  defineOptions({ name: 'ArtFastEnter' })

  const router = useRouter()
  const popoverRef = ref()
  const visible = ref(false)

  // 使用快速入口配置
  const { enabledApplications, enabledQuickLinks } = useFastEnter()

  /**
   * 处理导航跳转
   * @param routeName 路由名称
   * @param link 外部链接
   */
  const handleNavigate = (routeName?: string, link?: string): void => {
    const targetPath = routeName || link

    if (!targetPath) {
      console.warn('导航配置无效：缺少路由名称或链接')
      return
    }

    if (isExternalHttpUrl(targetPath)) {
      openExternalLink(targetPath)
    } else {
      router.push({ name: targetPath })
    }

    popoverRef.value?.hide()
    visible.value = false
  }

  /**
   * 处理应用项点击
   * @param application 应用配置对象
   */
  const handleApplicationClick = (application: FastEnterApplication): void => {
    handleNavigate(application.routeName, application.link)
  }

  /**
   * 处理快速链接点击
   * @param quickLink 快速链接配置对象
   */
  const handleQuickLinkClick = (quickLink: FastEnterQuickLink): void => {
    handleNavigate(quickLink.routeName, quickLink.link)
  }
</script>
