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
            <ElSelect v-model="searchForm.status" clearable style="width: 140px" @change="handleSearch">
              <ElOption :label="$t('userManage.status.normal')" :value="1" />
              <ElOption :label="$t('userManage.status.disabled')" :value="2" />
            </ElSelect>
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

    <ElDialog v-model="editVisible" :title="$t('userManage.editTitle')" width="520px" align-center>
      <ElForm ref="editFormRef" :model="editForm" :rules="editRules" label-width="90px">
        <ElFormItem :label="$t('userManage.form.username')">
          <ElInput :model-value="editingUser?.username || '-'" disabled />
        </ElFormItem>
        <ElFormItem :label="$t('userManage.form.nickname')" prop="nickname">
          <ElInput v-model.trim="editForm.nickname" maxlength="32" show-word-limit />
        </ElFormItem>
        <ElFormItem :label="$t('userManage.form.email')" prop="email">
          <ElInput v-model.trim="editForm.email" maxlength="254" />
        </ElFormItem>
        <ElFormItem :label="$t('userManage.form.gender')" prop="gender">
          <ElSelect v-model="editForm.gender" style="width: 100%">
            <ElOption :label="$t('userManage.gender.unknown')" :value="0" />
            <ElOption :label="$t('userManage.gender.male')" :value="1" />
            <ElOption :label="$t('userManage.gender.female')" :value="2" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="$t('userManage.form.signature')" prop="signature">
          <ElInput
            v-model="editForm.signature"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
          />
        </ElFormItem>
        <ElFormItem :label="$t('userManage.form.birthday')" prop="birthday">
          <ElDatePicker
            v-model="editForm.birthday"
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
  import { fetchUserList, updateUserProfile, updateUserStatus } from '@/api/admin'
  import { formatDate, formatDateTime } from '@/utils/format/datetime'
  import {
    ElButton,
    ElDatePicker,
    ElForm,
    ElImage,
    ElMessage,
    ElMessageBox,
    ElOption,
    ElSelect,
    ElTag,
    type FormInstance,
    type FormRules
  } from 'element-plus'

  defineOptions({ name: 'UserManage' })

  type UserItem = Api.Admin.UserItem

  const { t } = useI18n()

  const searchForm = ref<{ keyword: string; status?: number }>({ keyword: '' })
  const editVisible = ref(false)
  const editSubmitting = ref(false)
  const editingUser = ref<UserItem | null>(null)
  const editFormRef = ref<FormInstance>()
  const editForm = reactive({
    nickname: '',
    email: '',
    gender: 0,
    signature: '',
    birthday: ''
  })

  const editRules = computed<FormRules>(() => ({
    nickname: [
      { required: true, message: t('userManage.rules.nickname'), trigger: 'blur' },
      { min: 2, max: 32, message: t('userManage.rules.nicknameLength'), trigger: 'blur' }
    ],
    email: [{ type: 'email', message: t('userManage.rules.email'), trigger: 'blur' }],
    signature: [{ max: 200, message: t('userManage.rules.signature'), trigger: 'blur' }]
  }))

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
      apiParams: { current: 1, size: 20, keyword: '', status: undefined },
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
          width: 180,
          fixed: 'right',
          formatter: (row) =>
            h('div', [
              h(ElButton, { type: 'primary', link: true, onClick: () => openEdit(row) }, () =>
                t('common.edit')
              ),
              h(
                ElButton,
                {
                  type: row.status === 1 ? 'danger' : 'success',
                  link: true,
                  onClick: () => toggleStatus(row)
                },
                () => (row.status === 1 ? t('common.disable') : t('common.enable'))
              )
            ])
        }
      ]
    }
  })

  const handleSearch = () => {
    replaceSearchParams({ keyword: searchForm.value.keyword, status: searchForm.value.status })
    getData()
  }

  const handleReset = () => {
    searchForm.value = { keyword: '' }
    resetSearchParams()
  }

  const openEdit = (row: UserItem) => {
    editingUser.value = row
    Object.assign(editForm, {
      nickname: row.nickname || '',
      email: row.email || '',
      gender: row.gender ?? 0,
      signature: row.signature || '',
      birthday: row.birthday || ''
    })
    editVisible.value = true
    nextTick(() => editFormRef.value?.clearValidate())
  }

  const handleEditSubmit = async () => {
    if (!editingUser.value || !editFormRef.value) return
    const valid = await editFormRef.value.validate().catch(() => false)
    if (!valid) return
    editSubmitting.value = true
    try {
      await updateUserProfile(editingUser.value.id, {
        nickname: editForm.nickname.trim(),
        email: editForm.email.trim() || null,
        gender: editForm.gender,
        signature: editForm.signature.trim() || null,
        birthday: editForm.birthday || null
      })
      ElMessage.success(t('userManage.editSuccess'))
      editVisible.value = false
      refreshData()
    } finally {
      editSubmitting.value = false
    }
  }

  const toggleStatus = (row: UserItem) => {
    const next = row.status === 1 ? 2 : 1
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
