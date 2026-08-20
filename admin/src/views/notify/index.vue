<!-- 通知中心（下发 / 模板 / 记录） -->
<template>
  <div class="notify-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ElTabs v-model="activeTab">
        <ElTabPane :label="$t('notify.tabs.send')" name="send">
          <NotifySend v-if="activeTab === 'send'" />
        </ElTabPane>
        <ElTabPane :label="$t('notify.tabs.templates')" name="templates">
          <NotifyTemplates v-if="activeTab === 'templates'" />
        </ElTabPane>
        <ElTabPane :label="$t('notify.tabs.records')" name="records">
          <NotifyRecords v-if="activeTab === 'records'" />
        </ElTabPane>
      </ElTabs>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import NotifySend from './modules/notify-send.vue'
  import NotifyTemplates from './modules/notify-templates.vue'
  import NotifyRecords from './modules/notify-records.vue'

  defineOptions({ name: 'Notify' })

  const activeTab = ref<string | number>('send')
</script>
