<!-- 用户管理 -->
<template>
  <div class="user-manage-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElInput
              v-model="searchForm.keyword"
              placeholder="搜索用户名 / 昵称 / 邮箱"
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
  import { fetchUserList, updateUserStatus } from '@/api/admin'
  import { ElButton, ElImage, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'UserManage' })

  type UserItem = Api.Admin.UserItem

  const searchForm = ref({ keyword: '' })

  const genderText = (g: number) => (g === 1 ? '男' : g === 2 ? '女' : '未知')

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
      apiFn: fetchUserList,
      apiParams: { current: 1, size: 20, keyword: '' },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 80 },
        {
          prop: 'nickname',
          label: '用户',
          minWidth: 220,
          formatter: (row) =>
            h('div', { class: 'flex-c' }, [
              h(ElImage, {
                class: 'size-9 rounded-md',
                src: row.avatar_url || '',
                fit: 'cover'
              }),
              h('div', { class: 'ml-2' }, [
                h('p', { class: 'font-medium' }, row.nickname || '-'),
                h('p', { class: 'text-xs art-text-gray-500' }, row.username || '-')
              ])
            ])
        },
        { prop: 'email', label: '邮箱', minWidth: 180, formatter: (row) => row.email || '-' },
        { prop: 'gender', label: '性别', width: 90, formatter: (row) => genderText(row.gender) },
        {
          prop: 'status',
          label: '状态',
          width: 100,
          formatter: (row) =>
            h(
              ElTag,
              { type: row.status === 1 ? 'success' : 'danger' },
              () => (row.status === 1 ? '正常' : '已禁用')
            )
        },
        { prop: 'created_at', label: '注册时间', minWidth: 180 },
        {
          prop: 'operation',
          label: '操作',
          width: 120,
          fixed: 'right',
          formatter: (row) =>
            h(
              ElButton,
              {
                type: row.status === 1 ? 'danger' : 'success',
                link: true,
                onClick: () => toggleStatus(row)
              },
              () => (row.status === 1 ? '禁用' : '启用')
            )
        }
      ]
    }
  })

  const handleSearch = () => {
    replaceSearchParams({ keyword: searchForm.value.keyword })
    getData()
  }

  const toggleStatus = (row: UserItem) => {
    const next = row.status === 1 ? 0 : 1
    const actionText = next === 1 ? '启用' : '禁用'
    ElMessageBox.confirm(`确定要${actionText}用户「${row.nickname || row.username}」吗？`, `${actionText}用户`, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      await updateUserStatus(row.id, next)
      ElMessage.success(`${actionText}成功`)
      refreshData()
    })
  }
</script>
