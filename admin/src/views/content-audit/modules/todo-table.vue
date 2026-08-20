<!-- 内容审核 - 待办列表 -->
<template>
  <div>
    <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
      <template #left>
        <ElSpace wrap>
          <ElInput
            v-model="searchForm.keyword"
            :placeholder="$t('contentAudit.todo.searchPlaceholder')"
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
      :empty-text="$t('contentAudit.todo.empty')"
      @pagination:size-change="handleSizeChange"
      @pagination:current-change="handleCurrentChange"
    >
    </ArtTable>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchTodoList, deleteTodo } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'
  import { ElButton, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'ContentAuditTodo' })

  type TodoItem = Api.Admin.TodoItem

  const { t } = useI18n()

  const searchForm = ref({ keyword: '' })

  /** 提醒频率映射：0 仅一次 / 1 每天 / 2 每周 */
  const repeatText = (type: number) => {
    if (type === 1) return t('contentAudit.todo.repeat.daily')
    if (type === 2) return t('contentAudit.todo.repeat.weekly')
    return t('contentAudit.todo.repeat.once')
  }

  /**
   * 状态映射，取值与服务端 store.go 一致：
   * 0 = 待办（CreateTodo 写入 0）、1 = 已完成（CompleteTodo 置 1）、2 = 已删除（DeleteTodo 置 2）
   */
  const statusMap: Record<number, { key: string; type: 'warning' | 'success' | 'info' }> = {
    0: { key: 'contentAudit.todo.status.pending', type: 'warning' },
    1: { key: 'contentAudit.todo.status.done', type: 'success' },
    2: { key: 'contentAudit.todo.status.deleted', type: 'info' }
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
      apiFn: fetchTodoList,
      apiParams: { current: 1, size: 20, keyword: '' },
      columnsFactory: () => [
        { prop: 'id', label: t('contentAudit.todo.table.id'), width: 80 },
        { prop: 'title', label: t('contentAudit.todo.table.title'), minWidth: 180 },
        {
          prop: 'note',
          label: t('contentAudit.todo.table.note'),
          minWidth: 200,
          showOverflowTooltip: true,
          formatter: (row) => row.note || '-'
        },
        {
          prop: 'creator_name',
          label: t('contentAudit.todo.table.creator'),
          minWidth: 120,
          formatter: (row) => row.creator_name || `#${row.creator_id}`
        },
        {
          prop: 'assignee_name',
          label: t('contentAudit.todo.table.assignee'),
          minWidth: 120,
          formatter: (row) => row.assignee_name || '-'
        },
        {
          prop: 'remind_enabled',
          label: t('contentAudit.todo.table.remindEnabled'),
          width: 110,
          formatter: (row) =>
            h(ElTag, { type: row.remind_enabled ? 'success' : 'info' }, () =>
              row.remind_enabled
                ? t('contentAudit.todo.remind.on')
                : t('contentAudit.todo.remind.off')
            )
        },
        {
          prop: 'repeat_type',
          label: t('contentAudit.todo.table.repeat'),
          width: 110,
          formatter: (row) => repeatText(row.repeat_type)
        },
        {
          prop: 'remind_at',
          label: t('contentAudit.todo.table.remindAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.remind_at)
        },
        { prop: 'pair_id', label: t('contentAudit.todo.table.pairId'), width: 100 },
        {
          prop: 'status',
          label: t('contentAudit.todo.table.status'),
          width: 110,
          formatter: (row) => {
            const s = statusMap[row.status]
            return s
              ? h(ElTag, { type: s.type }, () => t(s.key))
              : h(ElTag, { type: 'info' }, () => String(row.status))
          }
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

  const handleDelete = (row: TodoItem) => {
    ElMessageBox.confirm(
      t('contentAudit.todo.deleteConfirm', { title: row.title }),
      t('contentAudit.todo.deleteTitle'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    ).then(async () => {
      await deleteTodo(row.id)
      ElMessage.success(t('common.deleteSuccess'))
      refreshData()
    })
  }
</script>
