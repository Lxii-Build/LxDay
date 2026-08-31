<!--
  相册管理（0821 新增，管理员 Q28=D）

  按 pair 列出相册、张数与占用空间，可删相册（软删，照片退回「未归类」）。

  **刻意不显示任何照片内容**：相册是全站最私密的数据。
  0820 那轮刚修掉「私密照片三重泄露」（/upload 全公开 + 网络日志页能点开情侣私照
  + 无 Referrer-Policy），这一页的用途是**容量与归属管理**，不是浏览照片。
  确需查看单张缩略图时走「内容审核 → 照片」页，那里每次查看都会写审计。
-->
<template>
  <div class="album-manage-page">
    <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
      <template #left>
        <ElSpace wrap>
          <ElInput
            v-model="searchForm.keyword"
            :placeholder="$t('albumManage.searchPlaceholder')"
            clearable
            style="width: 240px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <ElInput
            v-model="searchForm.pairId"
            :placeholder="$t('albumManage.pairIdPlaceholder')"
            clearable
            style="width: 160px"
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
      :empty-text="$t('albumManage.empty')"
      @pagination:size-change="handleSizeChange"
      @pagination:current-change="handleCurrentChange"
    >
      <template #size_bytes="{ row }">
        {{ formatFileSize(row.size_bytes) }}
      </template>
      <template #created_at="{ row }">
        {{ formatDateTime(row.created_at) }}
      </template>
      <template #operation="{ row }">
        <ElButton type="primary" link @click="openEdit(row)">{{ $t('common.edit') }}</ElButton>
        <ElButton type="danger" link @click="handleDelete(row)">{{ $t('common.delete') }}</ElButton>
      </template>
    </ArtTable>

    <ElDialog v-model="editVisible" :title="$t('albumManage.editTitle')" width="440px" align-center>
      <ElForm ref="editFormRef" :model="editForm" :rules="editRules" label-width="76px">
        <ElFormItem :label="$t('albumManage.form.name')" prop="name">
          <ElInput v-model.trim="editForm.name" maxlength="32" show-word-limit />
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
  import { ref } from 'vue'
  import { useTable } from '@/hooks/core/useTable'
  import { deleteAlbum, fetchAlbumList, updateAlbum } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'
  import { formatFileSize } from '@/utils/format/filesize'
  import {
    ElButton,
    ElForm,
    ElInput,
    ElMessage,
    ElMessageBox,
    ElSpace,
    type FormInstance,
    type FormRules
  } from 'element-plus'

  defineOptions({ name: 'AlbumManage' })

  type AlbumItem = Api.Admin.AlbumItem

  const { t } = useI18n()
  const editVisible = ref(false)
  const editSubmitting = ref(false)
  const editingAlbum = ref<AlbumItem | null>(null)
  const editFormRef = ref<FormInstance>()
  const editForm = reactive({ name: '' })
  const editRules = computed<FormRules>(() => ({
    name: [
      { required: true, message: t('albumManage.rules.name'), trigger: 'blur' },
      { max: 32, message: t('albumManage.rules.nameLength'), trigger: 'blur' }
    ]
  }))

  /** pair_id 用字符串收集：ElInput 清空后是 ''，转数字才好判断「没填」 */
  const searchForm = ref({ keyword: '', pairId: '' })

  const {
    columns,
    columnChecks,
    data,
    loading,
    pagination,
    replaceSearchParams,
    resetSearchParams,
    handleSizeChange,
    handleCurrentChange,
    refreshRemove,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchAlbumList,
      apiParams: { current: 1, size: 20, keyword: '' },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 80 },
        { prop: 'name', label: t('albumManage.table.name'), minWidth: 140 },
        { prop: 'couple', label: t('albumManage.table.couple'), minWidth: 160 },
        { prop: 'pair_id', label: t('albumManage.table.pairId'), width: 100 },
        { prop: 'photo_count', label: t('albumManage.table.photoCount'), width: 100 },
        { prop: 'size_bytes', label: t('albumManage.table.size'), width: 120, useSlot: true },
        {
          prop: 'created_at',
          label: t('albumManage.table.createdAt'),
          minWidth: 160,
          useSlot: true
        },
        {
          prop: 'operation',
          label: t('common.operation'),
          width: 160,
          fixed: 'right',
          useSlot: true
        }
      ]
    }
  })

  function handleSearch() {
    const pairId = Number(searchForm.value.pairId)
    replaceSearchParams({
      current: 1,
      keyword: searchForm.value.keyword,
      // 只有填了合法数字才带上 pair_id，避免 NaN 传给服务端
      ...(Number.isFinite(pairId) && pairId > 0 ? { pair_id: pairId } : {})
    })
  }

  function handleReset() {
    searchForm.value = { keyword: '', pairId: '' }
    resetSearchParams()
  }

  function openEdit(row: AlbumItem) {
    editingAlbum.value = row
    editForm.name = row.name
    editVisible.value = true
    nextTick(() => editFormRef.value?.clearValidate())
  }

  async function handleEditSubmit() {
    if (!editingAlbum.value || !editFormRef.value) return
    const valid = await editFormRef.value.validate().catch(() => false)
    if (!valid) return
    editSubmitting.value = true
    try {
      await updateAlbum(editingAlbum.value.id, { name: editForm.name.trim() })
      ElMessage.success(t('albumManage.editSuccess'))
      editVisible.value = false
      refreshData()
    } finally {
      editSubmitting.value = false
    }
  }

  async function handleDelete(row: AlbumItem) {
    // 说清后果：删相册是软删，照片不会跟着消失。
    // 不写清楚的话管理员会以为「删相册 = 删掉里面的照片」而不敢点。
    await ElMessageBox.confirm(
      t('albumManage.deleteConfirm', { name: row.name, count: row.photo_count }),
      t('albumManage.deleteTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel')
      }
    )
    await deleteAlbum(row.id)
    ElMessage.success(t('common.deleteSuccess'))
    refreshRemove()
  }
</script>

<style lang="scss" scoped>
  .album-manage-page {
    width: 100%;
  }
</style>
