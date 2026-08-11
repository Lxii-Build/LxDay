<!-- 待办管理（只读，展示与 App 共用同库的情侣待办数据） -->
<template>
  <div class="todo-manage-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElInput
              v-model="searchForm.keyword"
              placeholder="搜索事件 / 详情"
              clearable
              style="width: 260px"
              @keyup.enter="handleSearch"
              @clear="handleSearch"
            />
            <ElButton type="primary" @click="handleSearch">查询</ElButton>
          </ElSpace>
        </template>
      </ArtTableHeader>

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
  import { fetchTodoList } from '@/api/admin'
  import { ElTag } from 'element-plus'

  defineOptions({ name: 'TodoManage' })

  const searchForm = ref({ keyword: '' })

  // 提醒频率映射：0 仅一次 / 1 每天 / 2 每周
  const repeatText = (t: number) => (t === 1 ? '每天' : t === 2 ? '每周' : '仅一次')

  // 状态映射：0 待办 / 1 完成 / 2 删除
  const statusMap: Record<number, { text: string; type: 'warning' | 'success' | 'info' }> = {
    0: { text: '待办', type: 'warning' },
    1: { text: '完成', type: 'success' },
    2: { text: '删除', type: 'info' }
  }

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    getData,
    replaceSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchTodoList,
      apiParams: { current: 1, size: 20, keyword: '' },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 80 },
        { prop: 'title', label: '事件', minWidth: 180 },
        { prop: 'note', label: '详情', minWidth: 200, formatter: (row) => row.note || '-' },
        {
          prop: 'creator_name',
          label: '提出者',
          minWidth: 120,
          formatter: (row) => row.creator_name || '-'
        },
        {
          prop: 'assignee_name',
          label: '被提醒者',
          minWidth: 120,
          formatter: (row) => row.assignee_name || '-'
        },
        {
          prop: 'remind_enabled',
          label: '提醒开关',
          width: 100,
          formatter: (row) =>
            h(
              ElTag,
              { type: row.remind_enabled ? 'success' : 'info' },
              () => (row.remind_enabled ? '开' : '关')
            )
        },
        {
          prop: 'repeat_type',
          label: '提醒频率',
          width: 110,
          formatter: (row) => repeatText(row.repeat_type)
        },
        {
          prop: 'remind_at',
          label: '提醒时间',
          minWidth: 180,
          formatter: (row) => row.remind_at || '-'
        },
        {
          prop: 'status',
          label: '状态',
          width: 100,
          formatter: (row) => {
            const s = statusMap[row.status] || { text: String(row.status), type: 'info' as const }
            return h(ElTag, { type: s.type }, () => s.text)
          }
        }
      ]
    }
  })

  const handleSearch = () => {
    replaceSearchParams({ keyword: searchForm.value.keyword })
    getData()
  }
</script>
