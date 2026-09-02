<!--
  内容审核 - 相册照片列表

  列表只返回元数据；需要审核时通过独立接口查看 384px 审核缩略图，永远不回传原图。
  缩略图查看会写审计，且响应带 no-store，避免私密内容进入浏览器缓存。
-->
<template>
  <div>
    <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
      <template #left>
        <ElSpace wrap>
          <ElInput
            v-model="searchForm.keyword"
            :placeholder="$t('contentAudit.photo.searchPlaceholder')"
            clearable
            style="width: 240px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <ElInput
            v-model="searchForm.pairId"
            :placeholder="$t('contentAudit.photo.pairIdPlaceholder')"
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
      :empty-text="$t('contentAudit.photo.empty')"
      @pagination:size-change="handleSizeChange"
      @pagination:current-change="handleCurrentChange"
    >
    </ArtTable>

    <ElDialog
      v-model="thumbVisible"
      :title="$t('contentAudit.photo.thumbTitle')"
      width="min(92vw, 520px)"
      align-center
      @closed="closeThumb"
    >
      <div v-loading="thumbLoading" class="photo-thumb-preview">
        <ElImage v-if="thumbUrl" :src="thumbUrl" fit="contain" class="photo-thumb-image" />
        <ElEmpty v-else-if="!thumbLoading" :description="$t('contentAudit.photo.thumbEmpty')" />
      </div>
      <p class="mt-3 mb-0 text-xs art-text-gray-500">
        {{ $t('contentAudit.photo.thumbHint', { id: thumbPhoto?.id || '-' }) }}
      </p>
      <template #footer>
        <ElButton @click="thumbVisible = false">{{ $t('common.close') }}</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="editVisible"
      :title="$t('contentAudit.photo.editTitle')"
      width="520px"
      align-center
    >
      <ElForm ref="editFormRef" :model="editForm" :rules="editRules" label-width="76px">
        <ElFormItem :label="$t('contentAudit.photo.form.caption')" prop="caption">
          <ElInput
            v-model="editForm.caption"
            type="textarea"
            :rows="5"
            maxlength="500"
            show-word-limit
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
  import { onUnmounted } from 'vue'
  import { useI18n } from 'vue-i18n'
  import { useTable } from '@/hooks/core/useTable'
  import { deletePhoto, fetchPhotoList, fetchPhotoThumbnail, updatePhoto } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'
  import { formatFileSize } from '@/utils/format/filesize'
  import {
    ElButton,
    ElForm,
    ElInput,
    ElMessage,
    ElMessageBox,
    ElTag,
    type FormInstance,
    type FormRules
  } from 'element-plus'

  defineOptions({ name: 'ContentAuditPhoto' })

  type PhotoItem = Api.Admin.PhotoItem

  const { t } = useI18n()

  /** pair_id 用字符串收集：ElInput 的 clearable 清空后是 ''，转数字才好判断「没填」 */
  const searchForm = ref({ keyword: '', pairId: '' })
  const editVisible = ref(false)
  const editSubmitting = ref(false)
  const editingPhoto = ref<PhotoItem | null>(null)
  const editFormRef = ref<FormInstance>()
  const editForm = reactive({ caption: '' })
  const thumbVisible = ref(false)
  const thumbLoading = ref(false)
  const thumbUrl = ref('')
  const thumbPhoto = ref<PhotoItem | null>(null)
  let thumbRequestId = 0

  const clearThumbUrl = () => {
    if (thumbUrl.value) URL.revokeObjectURL(thumbUrl.value)
    thumbUrl.value = ''
  }

  const closeThumb = () => {
    thumbRequestId += 1
    clearThumbUrl()
    thumbPhoto.value = null
    thumbLoading.value = false
  }

  const openThumb = async (row: PhotoItem) => {
    const requestId = ++thumbRequestId
    clearThumbUrl()
    thumbPhoto.value = row
    thumbVisible.value = true
    thumbLoading.value = true
    try {
      const url = await fetchPhotoThumbnail(row.id)
      if (requestId !== thumbRequestId) {
        URL.revokeObjectURL(url)
        return
      }
      thumbUrl.value = url
    } catch (error) {
      if (requestId === thumbRequestId) {
        ElMessage.error(error instanceof Error ? error.message : t('contentAudit.photo.thumbError'))
        thumbVisible.value = false
      }
    } finally {
      if (requestId === thumbRequestId) thumbLoading.value = false
    }
  }

  onUnmounted(closeThumb)

  const editRules = computed<FormRules>(() => ({
    caption: [{ max: 500, message: t('contentAudit.photo.rules.caption'), trigger: 'blur' }]
  }))

  /**
   * 状态映射，取值与服务端 album_store.go 一致：
   * 1 = 正常（CreatePhoto 写入 1）、2 = 回收站（SetPhotoStatus / AdminDeletePhoto 置 2）
   */
  const statusMap: Record<number, { key: string; type: 'success' | 'info' }> = {
    1: { key: 'contentAudit.photo.status.normal', type: 'success' },
    2: { key: 'contentAudit.photo.status.recycled', type: 'info' }
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
    refreshRemove,
    refreshData
  } = useTable({
    core: {
      apiFn: fetchPhotoList,
      apiParams: { current: 1, size: 20, keyword: '' },
      columnsFactory: () => [
        { prop: 'id', label: t('contentAudit.photo.table.id'), width: 80 },
        {
          prop: 'pair_id',
          label: t('contentAudit.photo.table.pairId'),
          width: 110,
          formatter: (row) =>
            row.album_id > 0
              ? `#${row.pair_id} / ${t('contentAudit.photo.albumPrefix')}${row.album_id}`
              : `#${row.pair_id} / ${t('contentAudit.photo.unfiled')}`
        },
        {
          prop: 'uploader_name',
          label: t('contentAudit.photo.table.uploader'),
          minWidth: 140,
          formatter: (row) => `${row.uploader_name || '-'} (#${row.uploader_id})`
        },
        {
          prop: 'width',
          label: t('contentAudit.photo.table.dimension'),
          width: 120,
          formatter: (row) => (row.width > 0 && row.height > 0 ? `${row.width}×${row.height}` : '-')
        },
        {
          prop: 'size_bytes',
          label: t('contentAudit.photo.table.size'),
          width: 110,
          formatter: (row) => formatFileSize(row.size_bytes)
        },
        {
          prop: 'mime',
          label: t('contentAudit.photo.table.mime'),
          width: 120,
          formatter: (row) => row.mime || '-'
        },
        {
          prop: 'taken_at',
          label: t('contentAudit.photo.table.takenAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.taken_at)
        },
        {
          prop: 'created_at',
          label: t('contentAudit.photo.table.createdAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.created_at)
        },
        {
          prop: 'caption',
          label: t('contentAudit.photo.table.caption'),
          minWidth: 200,
          showOverflowTooltip: true,
          formatter: (row) => row.caption || '-'
        },
        {
          prop: 'status',
          label: t('contentAudit.photo.table.status'),
          width: 110,
          formatter: (row) => {
            const s = statusMap[row.status]
            return s
              ? h(ElTag, { type: s.type }, () => t(s.key))
              : h(ElTag, { type: 'info' }, () => String(row.status))
          }
        },
        {
          prop: 'operation',
          label: t('common.operation'),
          width: 250,
          fixed: 'right',
          formatter: (row) =>
            h('div', [
              h(ElButton, { type: 'info', link: true, onClick: () => openThumb(row) }, () =>
                t('contentAudit.photo.view')
              ),
              row.status === 1
                ? h(ElButton, { type: 'primary', link: true, onClick: () => openEdit(row) }, () =>
                    t('common.edit')
                  )
                : null,
              h(
                ElButton,
                {
                  type: 'danger',
                  link: true,
                  // 已在回收站的照片再删一次无意义（服务端同样只会把 status 置 2）
                  disabled: row.status === 2,
                  onClick: () => handleDelete(row)
                },
                () => t('common.delete')
              )
            ])
        }
      ]
    }
  })

  const handleSearch = () => {
    const pairId = Number(searchForm.value.pairId)
    replaceSearchParams({
      keyword: searchForm.value.keyword.trim(),
      // 非法输入（含中文、负数）一律当作不筛选，避免服务端拿到 NaN 后按 0 处理却让人以为筛过了
      pair_id: Number.isInteger(pairId) && pairId > 0 ? pairId : 0
    })
    getData()
  }

  const handleReset = () => {
    searchForm.value.keyword = ''
    searchForm.value.pairId = ''
    resetSearchParams()
  }

  const openEdit = (row: PhotoItem) => {
    editingPhoto.value = row
    editForm.caption = row.caption || ''
    editVisible.value = true
    nextTick(() => editFormRef.value?.clearValidate())
  }

  const handleEditSubmit = async () => {
    if (!editingPhoto.value || !editFormRef.value) return
    const valid = await editFormRef.value.validate().catch(() => false)
    if (!valid) return
    editSubmitting.value = true
    try {
      await updatePhoto(editingPhoto.value.id, { caption: editForm.caption.trim() })
      ElMessage.success(t('contentAudit.photo.editSuccess'))
      editVisible.value = false
      await refreshData()
    } finally {
      editSubmitting.value = false
    }
  }

  /** 后台删除是软删（进用户回收站，用户可自行恢复、不删磁盘文件），二次确认里必须说清 */
  const handleDelete = async (row: PhotoItem) => {
    try {
      await ElMessageBox.confirm(
        t('contentAudit.photo.deleteConfirm', { id: row.id }),
        t('contentAudit.photo.deleteTitle'),
        {
          confirmButtonText: t('common.confirm'),
          cancelButtonText: t('common.cancel'),
          type: 'warning'
        }
      )
      await deletePhoto(row.id)
      ElMessage.success(t('contentAudit.photo.deleteSuccess'))
      // 删最后一页最后一条时回上一页，避免停在空页
      await refreshRemove()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }
</script>

<style scoped>
  .photo-thumb-preview {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 300px;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 12px;
  }

  .photo-thumb-image {
    width: 100%;
    max-height: 60vh;
  }
</style>
