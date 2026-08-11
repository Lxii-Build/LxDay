<!-- 通知 - 下发记录 -->
<template>
  <div class="notify-records">
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
  </div>
</template>

<script setup lang="ts">
  import { useTable } from '@/hooks/core/useTable'
  import { fetchNotifyRecords } from '@/api/admin'

  defineOptions({ name: 'NotifyRecords' })

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
      apiFn: fetchNotifyRecords,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 80 },
        { prop: 'title', label: '标题', minWidth: 160 },
        { prop: 'body', label: '内容', minWidth: 220, showOverflowTooltip: true },
        { prop: 'template_code', label: '模板', width: 140, formatter: (row) => row.template_code || '-' },
        { prop: 'target', label: '目标', width: 120 },
        { prop: 'sent_count', label: '推送数', width: 100 },
        { prop: 'created_at', label: '下发时间', minWidth: 180 }
      ]
    }
  })
</script>
