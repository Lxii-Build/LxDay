<!-- 用户管理 -->
<template>
  <div class="user-manage-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElInput
              v-model="searchForm.keyword"
              :placeholder="$t('userManage.searchPlaceholder')"
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
        :empty-text="$t('userManage.empty')"
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
  import { fetchUserList, updateUserStatus } from '@/api/admin'
  import { formatDate, formatDateTime } from '@/utils/format/datetime'
  import { ElButton, ElImage, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'UserManage' })

  type UserItem = Api.Admin.UserItem

  const { t } = useI18n()

  const searchForm = ref({ keyword: '' })

  /** 性别映射：1 男 / 2 女 / 其他 保密 */
  const genderText = (g: number) => {
    if (g === 1) return t('userManage.gender.male')
    if (g === 2) return t('userManage.gender.female')
    return t('userManage.gender.unknown')
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
      apiFn: fetchUserList,
      apiParams: { current: 1, size: 20, keyword: '' },
      columnsFactory: () => [
        { prop: 'id', label: t('userManage.table.id'), width: 80 },
        {
          prop: 'nickname',
          label: t('userManage.table.user'),
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
        {
          prop: 'email',
          label: t('userManage.table.email'),
          minWidth: 180,
          formatter: (row) => row.email || '-'
        },
        {
          prop: 'gender',
          label: t('userManage.table.gender'),
          width: 90,
          formatter: (row) => genderText(row.gender)
        },
        {
          prop: 'signature',
          label: t('userManage.table.signature'),
          minWidth: 180,
          showOverflowTooltip: true,
          formatter: (row) => row.signature || '-'
        },
        {
          prop: 'birthday',
          label: t('userManage.table.birthday'),
          width: 130,
          formatter: (row) => formatDate(row.birthday)
        },
        {
          prop: 'anniversary',
          label: t('userManage.table.anniversary'),
          width: 130,
          formatter: (row) => formatDate(row.anniversary)
        },
        {
          prop: 'status',
          label: t('userManage.table.status'),
          width: 100,
          formatter: (row) =>
            h(ElTag, { type: row.status === 1 ? 'success' : 'danger' }, () =>
              row.status === 1 ? t('userManage.status.normal') : t('userManage.status.disabled')
            )
        },
        {
          prop: 'created_at',
          label: t('userManage.table.createdAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.created_at)
        },
        {
          prop: 'operation',
          label: t('common.operation'),
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
              () => (row.status === 1 ? t('common.disable') : t('common.enable'))
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

  const toggleStatus = (row: UserItem) => {
    const next = row.status === 1 ? 0 : 1
    const name = row.nickname || row.username || `#${row.id}`

    ElMessageBox.confirm(
      next === 1
        ? t('userManage.enableConfirm', { name })
        : t('userManage.disableConfirm', { name }),
      next === 1 ? t('userManage.enableTitle') : t('userManage.disableTitle'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    ).then(async () => {
      await updateUserStatus(row.id, next)
      ElMessage.success(next === 1 ? t('userManage.enableSuccess') : t('userManage.disableSuccess'))
      refreshData()
    })
  }
</script>
