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

    <ElDialog v-model="editVisible" :title="$t('pairManage.editTitle')" width="420px" align-center>
      <ElForm ref="editFormRef" :model="editForm" label-width="90px">
        <ElFormItem :label="$t('pairManage.form.members')">
          <span>{{ editingPair ? `${editingPair.name_a} / ${editingPair.name_b}` : '-' }}</span>
        </ElFormItem>
        <ElFormItem :label="$t('pairManage.form.anniversary')">
          <ElDatePicker
            v-model="editForm.anniversary_date"
            type="date"
            value-format="YYYY-MM-DD"
            clearable
            style="width: 100%"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editVisible = false">{{ $t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="editSubmitting" @click="handleEditSubmit">
          {{ $t('common.save') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useTable } from '@/hooks/core/useTable'
  import { cancelPendingInvite, fetchPairList, updatePair, unbindPair } from '@/api/admin'
  import { useUserStore } from '@/store/modules/user'
  import { formatDate, formatDateTime } from '@/utils/format/datetime'
  import {
    ElButton,
    ElDatePicker,
    ElForm,
    ElFormItem,
    ElMessage,
    ElMessageBox,
    ElTag,
    type FormInstance
  } from 'element-plus'

  defineOptions({ name: 'PairManage' })

  type PairItem = Api.Admin.PairItem

  const { t } = useI18n()
  const userStore = useUserStore()
  const isSuper = computed(() => userStore.getUserInfo.roles?.includes('super') ?? false)

  const searchForm = ref({ keyword: '' })
  const editVisible = ref(false)
  const editSubmitting = ref(false)
  const editingPair = ref<PairItem | null>(null)
  const editFormRef = ref<FormInstance>()
  const editForm = reactive({ anniversary_date: '' })

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
              row.has_invite ? t('pairManage.invite.pending') : t('pairManage.invite.none')
            )
        },
        {
          prop: 'anniversary',
          label: t('pairManage.table.anniversary'),
          width: 130,
          formatter: (row) => formatDate(row.anniversary)
        },
        {
          prop: 'status',
          label: t('pairManage.table.status'),
          width: 110,
          formatter: (row) => {
            const bound = row.status === 1 && row.user_a_id > 0 && row.user_b_id > 0
            return h(
              ElTag,
              { type: row.has_invite ? 'warning' : bound ? 'success' : 'info' },
              () =>
                row.has_invite
                  ? t('pairManage.invite.pending')
                  : bound
                    ? t('pairManage.status.bound')
                    : t('pairManage.status.unbound')
            )
          }
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
          width: 250,
          fixed: 'right',
          formatter: (row) =>
            isSuper.value
              ? h('div', [
                  row.status === 1 && !row.has_invite
                    ? h(
                        ElButton,
                        { type: 'primary', link: true, onClick: () => openEdit(row) },
                        () => t('common.edit')
                      )
                    : null,
                  row.has_invite
                    ? h(
                        ElButton,
                        { type: 'warning', link: true, onClick: () => handleCancelInvite(row) },
                        () => t('pairManage.cancelInvite')
                      )
                    : null,
                  row.status === 1 && row.user_a_id > 0 && row.user_b_id > 0
                    ? h(
                        ElButton,
                        { type: 'danger', link: true, onClick: () => handleUnbind(row) },
                        () => t('pairManage.unbind')
                      )
                    : null
                ])
              : h('span', { class: 'art-text-gray-400' }, t('systemSettings.runtime.superOnly'))
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

  const openEdit = (row: PairItem) => {
    editingPair.value = row
    editForm.anniversary_date = row.anniversary || ''
    editVisible.value = true
  }

  const handleEditSubmit = async () => {
    if (!editingPair.value) return
    editSubmitting.value = true
    try {
      await updatePair(editingPair.value.id, {
        anniversary_date: editForm.anniversary_date || null
      })
      ElMessage.success(t('pairManage.editSuccess'))
      editVisible.value = false
      await refreshData()
    } finally {
      editSubmitting.value = false
    }
  }

  const handleUnbind = async (row: PairItem) => {
    try {
      await ElMessageBox.confirm(
        t('pairManage.unbindConfirm', { id: row.id }),
        t('pairManage.unbindTitle'),
        {
          confirmButtonText: t('common.confirm'),
          cancelButtonText: t('common.cancel'),
          type: 'warning'
        }
      )
      await unbindPair(row.id)
      ElMessage.success(t('pairManage.unbindSuccess'))
      await refreshData()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }

  const handleCancelInvite = async (row: PairItem) => {
    try {
      await ElMessageBox.confirm(
        t('pairManage.cancelInviteConfirm', { id: row.id }),
        t('pairManage.cancelInviteTitle'),
        {
          confirmButtonText: t('common.confirm'),
          cancelButtonText: t('common.cancel'),
          type: 'warning'
        }
      )
      await cancelPendingInvite(row.id)
      ElMessage.success(t('pairManage.cancelInviteSuccess'))
      await refreshData()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }
</script>
