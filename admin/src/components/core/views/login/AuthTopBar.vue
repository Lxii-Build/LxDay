<!-- 授权页右上角组件 -->
<template>
  <div
    class="absolute w-full flex-cb top-4.5 z-10 flex-c !justify-end max-[1180px]:!justify-between"
  >
    <div class="flex-cc !hidden max-[1180px]:!flex ml-2 max-sm:ml-6">
      <ArtLogo class="icon" size="46" />
      <h1 class="text-xl ont-mediumf ml-2">{{ AppConfig.systemInfo.name }}</h1>
    </div>

    <div class="flex-cc gap-1.5 mr-2 max-sm:mr-5">
      <div class="color-picker-expandable relative flex-c max-sm:!hidden">
        <div
          class="color-dots absolute right-0 rounded-full flex-c gap-2 rounded-5 px-2.5 py-2 pr-9 pl-2.5 opacity-0"
        >
          <button
            type="button"
            v-for="(color, index) in mainColors"
            :key="color"
            class="color-dot relative size-5 min-h-11 min-w-11 c-p flex-cc rounded-full border-0 opacity-0"
            :aria-label="$t('setting.color.choose', { color })"
            :class="{ active: color === systemThemeColor }"
            :style="{ background: color, '--index': index }"
            @click="changeThemeColor(color)"
          >
            <ArtSvgIcon v-if="color === systemThemeColor" icon="ri:check-fill" class="text-white" />
          </button>
        </div>
        <button
          type="button"
          class="btn palette-btn relative z-[2] size-11 c-p flex-cc tad-300 border-0 bg-transparent"
          :aria-label="$t('topBar.actions.themeColor')"
        >
          <ArtSvgIcon
            icon="ri:palette-line"
            class="text-xl text-g-800 transition-colors duration-300"
          />
        </button>
      </div>
      <ElDropdown
        v-if="shouldShowLanguage"
        @command="changeLanguage"
        popper-class="langDropDownStyle"
      >
        <button
          type="button"
          class="btn language-btn size-11 c-p flex-cc tad-300 border-0 bg-transparent"
          :aria-label="$t('topBar.actions.language')"
        >
          <ArtSvgIcon
            icon="ri:translate-2"
            class="text-[19px] text-g-800 transition-colors duration-300"
          />
        </button>
        <template #dropdown>
          <ElDropdownMenu>
            <div v-for="lang in languageOptions" :key="lang.value" class="lang-btn-item">
              <ElDropdownItem
                :command="lang.value"
                :class="{ 'is-selected': locale === lang.value }"
              >
                <span class="menu-txt">{{ lang.label }}</span>
                <ArtSvgIcon icon="ri:check-fill" class="text-base" v-if="locale === lang.value" />
              </ElDropdownItem>
            </div>
          </ElDropdownMenu>
        </template>
      </ElDropdown>
      <button
        type="button"
        v-if="shouldShowThemeToggle"
        class="btn theme-btn size-11 c-p flex-cc tad-300 border-0 bg-transparent"
        :aria-label="$t(isDark ? 'topBar.actions.useLightTheme' : 'topBar.actions.useDarkTheme')"
        @click="themeAnimation"
      >
        <ArtSvgIcon
          :icon="isDark ? 'ri:sun-fill' : 'ri:moon-line'"
          class="text-xl text-g-800 transition-colors duration-300"
        />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useSettingStore } from '@/store/modules/setting'
  import { useUserStore } from '@/store/modules/user'
  import { useHeaderBar } from '@/hooks/core/useHeaderBar'
  import { themeAnimation } from '@/utils/ui/animation'
  import { languageOptions } from '@/locales'
  import { LanguageEnum } from '@/enums/appEnum'
  import AppConfig from '@/config'

  defineOptions({ name: 'AuthTopBar' })

  const settingStore = useSettingStore()
  const userStore = useUserStore()
  const { isDark, systemThemeColor } = storeToRefs(settingStore)
  const { shouldShowThemeToggle, shouldShowLanguage } = useHeaderBar()
  const { locale } = useI18n()

  const mainColors = AppConfig.systemMainColor
  const color = systemThemeColor // css v-bind 使用

  const changeLanguage = (lang: LanguageEnum) => {
    if (locale.value === lang) return
    locale.value = lang
    userStore.setLanguage(lang)
  }

  const changeThemeColor = (color: string) => {
    if (systemThemeColor.value === color) return
    settingStore.setElementTheme(color)
    settingStore.reload()
  }
</script>

<style scoped>
  .color-dots {
    pointer-events: none;
    background: var(--lx-surface);
    backdrop-filter: blur(10px);
    box-shadow:
      -8px -8px 18px var(--lx-highlight-raised),
      8px 8px 18px var(--lx-shadow-raised);
    transition:
      opacity 0.3s ease,
      transform 0.3s ease;
    transform: translateX(10px);
  }

  .color-dot {
    box-shadow:
      -3px -3px 7px var(--lx-highlight-raised),
      3px 3px 7px var(--lx-shadow-raised);
    transition:
      box-shadow 0.3s cubic-bezier(0.4, 0, 0.2, 1),
      transform 0.3s cubic-bezier(0.4, 0, 0.2, 1),
      opacity 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    transition-delay: calc(var(--index) * 0.05s);
    transform: translateX(20px) scale(0.8);
  }

  .color-dot:hover {
    box-shadow:
      inset 2px 2px 5px var(--lx-shadow-inset),
      inset -2px -2px 5px var(--lx-highlight-inset);
    transform: translateX(0) scale(1.1);
  }

  .color-picker-expandable:hover .color-dots {
    pointer-events: auto;
    opacity: 1;
    transform: translateX(0);
  }

  .color-picker-expandable:focus-within .color-dots {
    pointer-events: auto;
    opacity: 1;
    transform: translateX(0);
  }

  .color-picker-expandable:hover .color-dot {
    opacity: 1;
    transform: translateX(0) scale(1);
  }

  .color-picker-expandable:focus-within .color-dot {
    opacity: 1;
    transform: translateX(0) scale(1);
  }

  .dark .color-dots {
    background-color: var(--lx-surface);
    box-shadow:
      -8px -8px 18px var(--lx-highlight-raised),
      8px 8px 18px var(--lx-shadow-raised);
  }

  .palette-btn,
  .language-btn,
  .theme-btn {
    color: var(--lx-text-secondary);
    background: var(--lx-surface) !important;
    border-radius: var(--lx-radius-control);
    box-shadow:
      -4px -4px 9px var(--lx-highlight-raised),
      4px 4px 9px var(--lx-shadow-raised);
    transition:
      color var(--lx-motion-fast) ease,
      box-shadow var(--lx-motion-fast) ease,
      transform var(--lx-motion-fast) ease;
  }

  .palette-btn:hover,
  .language-btn:hover,
  .theme-btn:hover,
  .palette-btn:focus-visible,
  .language-btn:focus-visible,
  .theme-btn:focus-visible {
    color: var(--lx-link);
    transform: translateY(-1px);
  }

  .palette-btn:active,
  .language-btn:active,
  .theme-btn:active {
    box-shadow:
      inset 3px 3px 7px var(--lx-shadow-inset),
      inset -3px -3px 7px var(--lx-highlight-inset);
    transform: translateY(0);
  }

  .color-picker-expandable:hover .palette-btn :deep(.art-svg-icon) {
    color: v-bind(color);
  }
</style>
