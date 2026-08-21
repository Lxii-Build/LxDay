<!-- 内容审核（待办 / 日记 / 相册照片） -->
<template>
  <div class="content-audit-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ElTabs v-model="activeTab">
        <ElTabPane :label="$t('contentAudit.tabs.todo')" name="todo">
          <TodoTable v-if="activeTab === 'todo'" />
        </ElTabPane>
        <ElTabPane v-if="isSuper" :label="$t('contentAudit.tabs.photo')" name="photo">
          <PhotoTable v-if="activeTab === 'photo'" />
        </ElTabPane>
      </ElTabs>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { useUserStore } from '@/store/modules/user'
  import TodoTable from './modules/todo-table.vue'
    import PhotoTable from './modules/photo-table.vue'

  defineOptions({ name: 'ContentAudit' })

  const activeTab = ref<string | number>('todo')

  /**
   * 相册照片 tab 仅超管可见。
   *
   * 服务端把 GET/DELETE /api/admin/photos 挂在 requireSuper 分组（相册是全站最私密的内容，
   * 普通 admin 连元数据都不给看），普通 admin 点开只会拿到 403。与其让人看着报错，不如不给这个 tab。
   */
  const userStore = useUserStore()
  const isSuper = computed(() => userStore.getUserInfo.roles?.includes('super') ?? false)
</script>
