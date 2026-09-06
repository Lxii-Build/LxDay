<!-- 日志中心：审计与网络访问按用途切换，避免两个分散菜单。 -->
<template>
  <div class="audit-log-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ElTabs v-model="activeTab">
        <ElTabPane :label="$t('auditLog.tabs.audit')" name="audit">
          <AuditTable v-if="activeTab === 'audit'" />
        </ElTabPane>
        <ElTabPane :label="$t('auditLog.tabs.network')" name="network">
          <NetworkLog v-if="activeTab === 'network'" />
        </ElTabPane>
      </ElTabs>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import NetworkLog from '../network-log/index.vue'
  import AuditTable from './modules/audit-table.vue'

  defineOptions({ name: 'AuditLog' })
  const activeTab = ref<'audit' | 'network'>('audit')
</script>
