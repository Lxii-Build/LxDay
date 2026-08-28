<!-- 绑定关系管理 -->
<template>
  <div class="pair-manage-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElInput
              v-model="searchForm.keyword"
              :placeholder="$t('pairManage.searchPlaceholder')"
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
        :empty-text="$t('pairManage.empty')"
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
  import { fetchPairList, unbindPair } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'
  import { ElButton, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'PairManage' })

  type PairItem = Api.Admin.PairItem

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
      apiFn: fetchPairList,
      apiParams: { current: 1, size: 20, keyword: '' },
      columnsFactory: () => [
        { prop: 'id', label: t('pairManage.table.id'), width: 80 },
        {
          prop: 'name_a',
          label: t('pairManage.table.userA'),
          minWidth: 160,
          formatter: (row) => `${row.name_a || '-'} (#${row.user_a_id})`
        },
        {
          prop: 'name_b',
          label: t('pairManage.table.userB'),
          minWidth: 160,
          formatter: (row) => `${row.name_b || '-'} (#${row.user_b_id})`
        },
        // 只显示「有没有挂起的邀请」，不显示邀请码本身。
        // 邀请码是"成为某人伴侣"的凭据，下发给后台等于让任何管理员都能拿它去绑定
        // 陌生用户，从而读到对方全部私密内容（相册/状态/待办）。
        {
          prop: 'has_invite',
          label: t('pairManage.table.inviteState'),
          width: 120,
          formatter: (row) =>
            h(ElTag, { type: row.has_invite ? 'warning' : 'info' }, () =>
              row.has_invite
                ? t('pairManage.invite.pending')
                : t('pairManage.invite.none')
            )
        },
        {
          prop: 'status',
          label: t('pairManage.table.status'),
          width: 110,
          formatter: (row) =>
            h(ElTag, { type: row.status === 1 ? 'success' : 'info' }, () =>
              row.status === 1 ? t('pairManage.status.bound') : t('pairManage.status.unbound')
            )
        },
        {
          prop: 'created_at',
          label: t('pairManage.table.createdAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.created_at)
        },
        {
          prop: 'operation',
          label: t('common.operation'),
          width: 100,
          fixed: 'right',
          formatter: (row) =>
            row.status === 1
              ? h(
                  ElButton,
                  { type: 'danger', link: true, onClick: () => handleUnbind(row) },
                  () => t('pairManage.unbind')
                )
              : h('span', { class: 'art-text-gray-400' }, '-')
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

  const handleUnbind = (row: PairItem) => {
    ElMessageBox.confirm(
      t('pairManage.unbindConfirm', { id: row.id }),
      t('pairManage.unbindTitle'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    ).then(async () => {
      await unbindPair(row.id)
      ElMessage.success(t('pairManage.unbindSuccess'))
      refreshData()
    })
  }
</script>
