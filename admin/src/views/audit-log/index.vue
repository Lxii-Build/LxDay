<!-- 系统日志 / 审计 -->
<template>
  <div class="audit-log-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElInput
              v-model.trim="searchForm.admin_name"
              :placeholder="$t('auditLog.filter.operator')"
              clearable
              style="width: 180px"
              @keyup.enter="handleSearch"
              @clear="handleSearch"
            />
            <ElInput
              v-model.trim="searchForm.action"
              :placeholder="$t('auditLog.filter.action')"
              clearable
              style="width: 180px"
              @keyup.enter="handleSearch"
              @clear="handleSearch"
            />
            <ElDatePicker
              v-model="dateRange"
              type="datetimerange"
              :start-placeholder="$t('auditLog.filter.start')"
              :end-placeholder="$t('auditLog.filter.end')"
              format="YYYY-MM-DD HH:mm:ss"
              value-format="YYYY-MM-DD HH:mm:ss"
              unlink-panels
              style="width: 380px"
              @change="handleSearch"
            />
            <ElButton type="primary" @click="handleSearch">{{ $t('common.search') }}</ElButton>
            <ElButton @click="handleReset">{{ $t('common.reset') }}</ElButton>
          </ElSpace>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        :empty-text="$t('auditLog.empty')"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchAuditLogs } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'
  import { ElDatePicker } from 'element-plus'

  defineOptions({ name: 'AuditLog' })

  const { t } = useI18n()

  const searchForm = ref<{ admin_name: string; action: string }>({ admin_name: '', action: '' })
  /** 时间范围，value-format 已是服务端约定的 YYYY-MM-DD HH:mm:ss */
  const dateRange = ref<[string, string] | null>(null)

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    resetSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchAuditLogs,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: t('auditLog.table.id'), width: 80 },
        {
          prop: 'admin_name',
          label: t('auditLog.table.operator'),
          width: 140,
          formatter: (row) => row.admin_name || `#${row.admin_id}`
        },
        { prop: 'action', label: t('auditLog.table.action'), width: 180 },
        {
          prop: 'detail',
          label: t('auditLog.table.detail'),
          minWidth: 220,
          showOverflowTooltip: true,
          formatter: (row) => row.detail || '-'
        },
        {
          prop: 'ip',
          label: t('auditLog.table.ip'),
          width: 150,
          formatter: (row) => row.ip || '-'
        },
        {
          prop: 'created_at',
          label: t('auditLog.table.createdAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.created_at)
        }
      ]
    }
  })

  const handleSearch = () => {
    const params: Api.Admin.AuditLogSearchParams = {}
    if (searchForm.value.admin_name) params.admin_name = searchForm.value.admin_name
    if (searchForm.value.action) params.action = searchForm.value.action
    if (dateRange.value?.length === 2) {
      params.start = dateRange.value[0]
      params.end = dateRange.value[1]
    }
    replaceSearchParams(params)
    getData()
  }

  const handleReset = () => {
    searchForm.value = { admin_name: '', action: '' }
    dateRange.value = null
    resetSearchParams()
  }
</script>
