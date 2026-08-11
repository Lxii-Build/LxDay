<!-- 绑定关系管理 -->
<template>
  <div class="pair-manage-page art-full-height">
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
  import { fetchPairList, unbindPair } from '@/api/admin'
  import { ElButton, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'PairManage' })

  type PairItem = Api.Admin.PairItem

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
      apiFn: fetchPairList,
      apiParams: { current: 1, size: 20 },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 80 },
        {
          prop: 'name_a',
          label: '用户 A',
          minWidth: 160,
          formatter: (row) => `${row.name_a || '-'} (#${row.user_a_id})`
        },
        {
          prop: 'name_b',
          label: '用户 B',
          minWidth: 160,
          formatter: (row) => `${row.name_b || '-'} (#${row.user_b_id})`
        },
        { prop: 'invite_code', label: '邀请码', width: 140 },
        {
          prop: 'status',
          label: '状态',
          width: 110,
          formatter: (row) =>
            h(
              ElTag,
              { type: row.status === 1 ? 'success' : 'info' },
              () => (row.status === 1 ? '已绑定' : '已解绑')
            )
        },
        { prop: 'created_at', label: '绑定时间', minWidth: 180 },
        {
          prop: 'operation',
          label: '操作',
          width: 100,
          fixed: 'right',
          formatter: (row) =>
            row.status === 1
              ? h(
                  ElButton,
                  { type: 'danger', link: true, onClick: () => handleUnbind(row) },
                  () => '解绑'
                )
              : h('span', { class: 'art-text-gray-400' }, '-')
        }
      ]
    }
  })

  const handleUnbind = (row: PairItem) => {
    ElMessageBox.confirm(`确定要解除该绑定关系（#${row.id}）吗？`, '解绑', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      await unbindPair(row.id)
      ElMessage.success('已解绑')
      refreshData()
    })
  }
</script>
