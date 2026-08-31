<!-- 一个让 SVG 图片跟随主题的组件，只对特定 svg 图片生效，不建议开发者使用 -->
<!-- 图片地址 https://iconpark.oceanengine.com/illustrations/13 -->
<template>
  <div class="theme-svg" :style="sizeStyle">
    <!-- SVG 作为图片加载，不把 fetched 文本插入 DOM。这样即使未来调用方误传了
         外部 SVG，也不能借 v-html 执行脚本并窃取后台登录态。 -->
    <img v-if="src" :src="src" alt="" class="svg-image" />
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue'

  interface Props {
    size?: string | number
    themeColor?: string
    src?: string
  }

  const props = withDefaults(defineProps<Props>(), {
    size: 500,
    themeColor: 'var(--el-color-primary)'
  })

  // 计算样式
  const sizeStyle = computed(() => {
    const sizeValue = typeof props.size === 'number' ? `${props.size}px` : props.size
    return {
      width: sizeValue,
      height: sizeValue
    }
  })

</script>

<style lang="scss" scoped>
  .theme-svg {
    display: inline-block;

    .svg-image {
      width: 100%;
      height: 100%;
      object-fit: contain;
    }
  }
</style>
