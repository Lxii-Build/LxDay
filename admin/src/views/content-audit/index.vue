<!-- 内容审核（待办 / 日记） -->
<template>
  <div class="content-audit-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ElTabs v-model="activeTab">
        <ElTabPane label="待办" name="todo">
          <TodoTable v-if="activeTab === 'todo'" />
        </ElTabPane>
        <ElTabPane label="日记" name="diary">
          <DiaryTable v-if="activeTab === 'diary'" />
        </ElTabPane>
      </ElTabs>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import TodoTable from './modules/todo-table.vue'
  import DiaryTable from './modules/diary-table.vue'

  defineOptions({ name: 'ContentAudit' })

  const activeTab = ref<string | number>('todo')
</script>
