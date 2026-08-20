<!-- 网络日志 -->
<template>
  <div class="network-log-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElSelect
              v-model="searchForm.method"
              :placeholder="$t('networkLog.filter.method')"
              clearable
              style="width: 130px"
              @change="handleSearch"
              @clear="handleSearch"
            >
              <ElOption v-for="m in METHODS" :key="m" :label="m" :value="m" />
            </ElSelect>
            <ElInput
              v-model.trim="searchForm.path"
              :placeholder="$t('networkLog.filter.path')"
              clearable
              style="width: 240px"
              @keyup.enter="handleSearch"
              @clear="handleSearch"
            />
            <ElInput
              v-model.trim="searchForm.status"
              :placeholder="$t('networkLog.filter.status')"
              clearable
              style="width: 150px"
              @keyup.enter="handleSearch"
              @clear="handleSearch"
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
        :empty-text="$t('networkLog.empty')"
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
  import { fetchNetworkLogs } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'
  import { ElTag } from 'element-plus'

  defineOptions({ name: 'NetworkLog' })

  const { t } = useI18n()

  const METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS', 'HEAD']

  const searchForm = ref<{ method: string; path: string; status: string }>({
    method: '',
    path: '',
    status: ''
  })

  /** 请求方法标签配色 */
  const methodTagType = (method: string) => {
    switch ((method || '').toUpperCase()) {
      case 'GET':
        return 'success'
      case 'POST':
        return 'primary'
      case 'PUT':
        return 'warning'
      case 'DELETE':
        return 'danger'
      default:
        return 'info'
    }
  }

  /** 状态码标签配色（2xx 成功 / 3xx 提示 / 其余异常） */
  const statusTagType = (status: number) => {
    if (status >= 200 && status < 300) return 'success'
    if (status >= 300 && status < 400) return 'warning'
    return 'danger'
  }

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
      apiFn: fetchNetworkLogs,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: t('networkLog.table.id'), width: 80 },
        {
          prop: 'method',
          label: t('networkLog.table.method'),
          width: 100,
          formatter: (row) =>
            h(ElTag, { type: methodTagType(row.method), effect: 'light' }, () => row.method || '-')
        },
        {
          prop: 'path',
          label: t('networkLog.table.path'),
          minWidth: 240,
          showOverflowTooltip: true
        },
        {
          prop: 'status',
          label: t('networkLog.table.status'),
          width: 100,
          formatter: (row) =>
            h(ElTag, { type: statusTagType(row.status) }, () => String(row.status ?? '-'))
        },
        {
          prop: 'latency_ms',
          label: t('networkLog.table.latency'),
          width: 110,
          formatter: (row) => (row.latency_ms != null ? `${row.latency_ms}` : '-')
        },
        {
          prop: 'ip',
          label: t('networkLog.table.ip'),
          width: 150,
          formatter: (row) => row.ip || '-'
        },
        {
          prop: 'ua',
          label: t('networkLog.table.ua'),
          minWidth: 200,
          showOverflowTooltip: true,
          formatter: (row) => row.ua || '-'
        },
        {
          prop: 'request_id',
          label: t('networkLog.table.requestId'),
          width: 200,
          showOverflowTooltip: true,
          formatter: (row) => row.request_id || '-'
        },
        {
          prop: 'created_at',
          label: t('networkLog.table.createdAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.created_at)
        }
      ]
    }
  })

  const handleSearch = () => {
    const params: Api.Admin.NetworkLogSearchParams = {}
    if (searchForm.value.method) params.method = searchForm.value.method
    if (searchForm.value.path) params.path = searchForm.value.path
    if (searchForm.value.status !== '') {
      const status = Number(searchForm.value.status)
      if (!Number.isNaN(status)) params.status = status
    }
    replaceSearchParams(params)
    getData()
  }

  const handleReset = () => {
    searchForm.value = { method: '', path: '', status: '' }
    resetSearchParams()
  }
</script>
