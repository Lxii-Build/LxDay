<!-- 按钮组件 -->
<template>
  <button
    type="button"
    class="art-icon-button size-11 inline-flex flex-cc c-p p-0 border-0 bg-transparent text-g-600 dark:text-g-800 text-xl rounded tad-300 hover:bg-hover-color"
    :class="{ 'rounded-full': circle }"
    :aria-label="label"
    :title="title || label"
  >
    <ArtSvgIcon :icon="icon" aria-hidden="true"></ArtSvgIcon>
    <slot></slot>
  </button>
</template>

<script lang="ts" setup>
  defineOptions({ name: 'ArtIconButton' })

  interface Props {
    /** 图标名称 */
    icon: string
    /** 圆角按钮 */
    circle?: boolean
    /** 无文字按钮的可访问名称 */
    label: string
    /** 鼠标悬停提示；默认与可访问名称一致 */
    title?: string
  }

  withDefaults(defineProps<Props>(), {})
</script>

<style scoped>
  .art-icon-button {
    font: inherit;
    appearance: none;
  }

  .art-icon-button:focus-visible {
    outline: 2px solid var(--main-color);
    outline-offset: 2px;
  }
</style>
