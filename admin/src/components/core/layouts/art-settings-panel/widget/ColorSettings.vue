<template>
  <div>
    <SectionTitle :title="$t('setting.color.title')" class="mt-10" />
    <div class="-mr-4">
      <div class="flex flex-wrap">
        <button
          v-for="color in configOptions.mainColors"
          :key="color"
          type="button"
          class="flex items-center justify-center size-11 mr-2 mb-1 cursor-pointer rounded-full border-0 transition-opacity duration-200 hover:opacity-85"
          :aria-label="$t('setting.color.choose', { color })"
          :style="{ background: `${color} !important` }"
          @click="colorHandlers.selectColor(color)"
        >
          <ArtSvgIcon
            icon="ri:check-fill"
            class="text-base !text-white"
            v-show="color === systemThemeColor"
          />
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import SectionTitle from './SectionTitle.vue'
  import { useSettingStore } from '@/store/modules/setting'
  import { useSettingsConfig } from '../composables/useSettingsConfig'
  import { useSettingsHandlers } from '../composables/useSettingsHandlers'
  import { storeToRefs } from 'pinia'

  const settingStore = useSettingStore()
  const { systemThemeColor } = storeToRefs(settingStore)
  const { configOptions } = useSettingsConfig()
  const { colorHandlers } = useSettingsHandlers()
</script>
