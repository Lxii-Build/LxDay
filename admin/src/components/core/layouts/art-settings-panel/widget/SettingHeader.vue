<template>
  <div>
    <div class="flex justify-end">
      <button
        type="button"
        @click="$emit('close')"
        class="flex size-11 items-center justify-center border-0 bg-transparent c-p rounded hover:bg-g-300/80 transition-colors duration-200"
        :aria-label="$t('common.close')"
      >
        <ArtSvgIcon icon="ri:close-fill" class="block text-xl text-g-600" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
  defineEmits<{
    close: []
  }>()
</script>
