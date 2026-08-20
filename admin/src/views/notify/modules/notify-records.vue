<!-- 通知 - 下发记录 -->
<template>
  <div class="notify-records">
    <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData" />
    <ArtTable
      :loading="loading"
      :data="data"
      :columns="columns"
      :pagination="pagination"
      :empty-text="$t('notify.records.empty')"
      @pagination:size-change="handleSizeChange"
      @pagination:current-change="handleCurrentChange"
    >
    </ArtTable>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchNotifyRecords } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'

  defineOptions({ name: 'NotifyRecords' })

  const { t } = useI18n()

  /** target 展示：all → 全站广播；uid:1,2 → 指定用户(2) */
  const targetText = (target: string): string => {
    if (!target || target === 'all') return t('notify.send.targetAll')
    if (target.startsWith('uid:')) {
      const count = target
        .slice(4)
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean).length
      return t('notify.records.targetUsers', { count })
    }
    return target
  }

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
        { prop: 'id', label: t('notify.records.table.id'), width: 80 },
        { prop: 'title', label: t('notify.records.table.title'), minWidth: 160 },
        {
          prop: 'body',
          label: t('notify.records.table.body'),
          minWidth: 220,
          showOverflowTooltip: true
        },
        {
          prop: 'template_code',
          label: t('notify.records.table.template'),
          width: 140,
          formatter: (row) => row.template_code || '-'
        },
        {
          prop: 'target',
          label: t('notify.records.table.target'),
          width: 150,
          formatter: (row) => targetText(row.target)
        },
        { prop: 'sent_count', label: t('notify.records.table.sentCount'), width: 100 },
        {
          prop: 'created_at',
          label: t('notify.records.table.createdAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.created_at)
        }
      ]
    }
  })
</script>
