<!-- 系统logo -->
<template>
  <div class="flex-cc">
    <img :style="logoStyle" :src="logoSrc" alt="logo" class="w-full h-full" />
  </div>
</template>

<script setup lang="ts">
  import { useSiteStore } from '@/store/modules/site'
  import defaultLogo from '@imgs/common/logo.webp'

  defineOptions({ name: 'ArtLogo' })

  interface Props {
    /** logo 大小 */
    size?: number | string
  }

  const props = withDefaults(defineProps<Props>(), {
    size: 36
  })

  const siteStore = useSiteStore()

  /** 优先使用站点设置中的 LOGO，为空时回退到内置默认 LOGO */
  const logoSrc = computed(() => siteStore.logo || defaultLogo)

  const logoStyle = computed(() => ({ width: `${props.size}px` }))
</script>
