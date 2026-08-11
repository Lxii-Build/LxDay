<!-- 内容审核 - 待办列表 -->
<template>
  <div>
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
  import { fetchTodoList, deleteTodo } from '@/api/admin'
  import { ElButton, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'ContentAuditTodo' })

  type TodoItem = Api.Admin.TodoItem

  const statusMap: Record<number, { text: string; type: 'info' | 'warning' | 'success' }> = {
    0: { text: '未开始', type: 'info' },
    1: { text: '进行中', type: 'warning' },
    2: { text: '已完成', type: 'success' }
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
      apiFn: fetchTodoList,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 80 },
        { prop: 'title', label: '标题', minWidth: 200 },
        { prop: 'note', label: '备注', minWidth: 200, formatter: (row) => row.note || '-' },
        { prop: 'pair_id', label: '关系ID', width: 90 },
        { prop: 'creator_id', label: '创建者', width: 90 },
        {
          prop: 'status',
          label: '状态',
          width: 100,
          formatter: (row) => {
            const s = statusMap[row.status] || { text: String(row.status), type: 'info' as const }
            return h(ElTag, { type: s.type }, () => s.text)
          }
        },
        { prop: 'created_at', label: '创建时间', minWidth: 180 },
        {
          prop: 'operation',
          label: '操作',
          width: 100,
          fixed: 'right',
          formatter: (row) =>
            h(ElButton, { type: 'danger', link: true, onClick: () => handleDelete(row) }, () => '删除')
        }
      ]
    }
  })

  const handleDelete = (row: TodoItem) => {
    ElMessageBox.confirm(`确定要删除待办「${row.title}」吗？`, '删除待办', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      await deleteTodo(row.id)
      ElMessage.success('删除成功')
      refreshData()
    })
  }
</script>
