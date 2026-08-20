<!-- 内容审核 - 日记列表 -->
<template>
  <div>
    <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
      <template #left>
        <ElSpace wrap>
          <ElInput
            v-model="searchForm.keyword"
            :placeholder="$t('contentAudit.diary.searchPlaceholder')"
            clearable
            style="width: 260px"
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
      :empty-text="$t('contentAudit.diary.empty')"
      @pagination:size-change="handleSizeChange"
      @pagination:current-change="handleCurrentChange"
    >
    </ArtTable>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchDiaryList, deleteDiary } from '@/api/admin'
  import { formatDate, formatDateTime } from '@/utils/format/datetime'
  import { ElButton, ElMessage, ElMessageBox } from 'element-plus'

  defineOptions({ name: 'ContentAuditDiary' })

  type DiaryItem = Api.Admin.DiaryItem

  const { t } = useI18n()

  const searchForm = ref({ keyword: '' })

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
      apiFn: fetchDiaryList,
      apiParams: { current: 1, size: 20, keyword: '' },
      columnsFactory: () => [
        { prop: 'id', label: t('contentAudit.diary.table.id'), width: 80 },
        {
          prop: 'title',
          label: t('contentAudit.diary.table.title'),
          minWidth: 200,
          showOverflowTooltip: true,
          formatter: (row) => row.title || t('contentAudit.diary.untitled')
        },
        {
          prop: 'author_name',
          label: t('contentAudit.diary.table.author'),
          minWidth: 140,
          formatter: (row) => `${row.author_name || '-'} (#${row.author_id})`
        },
        { prop: 'pair_id', label: t('contentAudit.diary.table.pairId'), width: 100 },
        {
          prop: 'diary_date',
          label: t('contentAudit.diary.table.diaryDate'),
          width: 130,
          formatter: (row) => formatDate(row.diary_date)
        },
        {
          prop: 'created_at',
          label: t('contentAudit.diary.table.createdAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.created_at)
        },
        {
          prop: 'operation',
          label: t('common.operation'),
          width: 100,
          fixed: 'right',
          formatter: (row) =>
            h(
              ElButton,
              { type: 'danger', link: true, onClick: () => handleDelete(row) },
              () => t('common.delete')
            )
        }
      ]
    }
  })

  const handleSearch = () => {
    replaceSearchParams({ keyword: searchForm.value.keyword })
    getData()
  }

  const handleReset = () => {
    searchForm.value.keyword = ''
    resetSearchParams()
  }

  /** 日记删除为物理删除，二次确认里必须写明不可恢复 */
  const handleDelete = (row: DiaryItem) => {
    ElMessageBox.confirm(
      t('contentAudit.diary.deleteConfirm', { id: row.id }),
      t('contentAudit.diary.deleteTitle'),
      {
        confirmButtonText: t('common.confirmDelete'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
        dangerouslyUseHTMLString: false
      }
    ).then(async () => {
      await deleteDiary(row.id)
      ElMessage.success(t('common.deleteSuccess'))
      refreshData()
    })
  }
</script>
