<!-- 系统日志 / 审计 -->
<template>
  <div class="audit-log-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData" />
      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { useTable } from '@/hooks/core/useTable'
  import { fetchAuditLogs } from '@/api/admin'

  defineOptions({ name: 'AuditLog' })

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchAuditLogs,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 80 },
        { prop: 'admin_name', label: '操作人', width: 140, formatter: (row) => row.admin_name || `#${row.admin_id}` },
        { prop: 'action', label: '操作', width: 180 },
        { prop: 'detail', label: '详情', minWidth: 220, formatter: (row) => row.detail || '-' },
        { prop: 'ip', label: 'IP', width: 150, formatter: (row) => row.ip || '-' },
        { prop: 'created_at', label: '时间', minWidth: 180 }
      ]
    }
  })
</script>
