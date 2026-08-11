<!-- 内容审核 - 日记列表 -->
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
  import { fetchDiaryList, deleteDiary } from '@/api/admin'
  import { ElButton, ElMessage, ElMessageBox } from 'element-plus'

  defineOptions({ name: 'ContentAuditDiary' })

  type DiaryItem = Api.Admin.DiaryItem

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
      apiFn: fetchDiaryList,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 80 },
        { prop: 'title', label: '标题', minWidth: 200, formatter: (row) => row.title || '(无标题)' },
        {
          prop: 'author_name',
          label: '作者',
          minWidth: 140,
          formatter: (row) => `${row.author_name || '-'} (#${row.author_id})`
        },
        { prop: 'pair_id', label: '关系ID', width: 90 },
        { prop: 'diary_date', label: '日记日期', width: 130 },
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

  const handleDelete = (row: DiaryItem) => {
    ElMessageBox.confirm(`确定要删除日记（#${row.id}）吗？`, '删除日记', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      await deleteDiary(row.id)
      ElMessage.success('删除成功')
      refreshData()
    })
  }
</script>
