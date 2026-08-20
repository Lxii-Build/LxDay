<!--
  内容审核 - 相册照片列表

  只显示元数据、**不显示缩略图**：服务端 GET /api/admin/photos 刻意不返回任何图片 URL
  （见 Store.ListPhotosAll 注释）。管理员没有用户 token，本就读不了 /media/<id> 鉴权代理，
  这一页的用途是违规内容处置，不是浏览情侣私密照片。
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
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchPhotoList, deletePhoto } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'
  import { formatFileSize } from '@/utils/format/filesize'
  import { ElButton, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'ContentAuditPhoto' })

  type PhotoItem = Api.Admin.PhotoItem

  const { t } = useI18n()

  /** pair_id 用字符串收集：ElInput 的 clearable 清空后是 ''，转数字才好判断「没填」 */
  const searchForm = ref({ keyword: '', pairId: '' })

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
          width: 100,
          fixed: 'right',
          formatter: (row) =>
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

  /** 后台删除是软删（进用户回收站，用户可自行恢复、不删磁盘文件），二次确认里必须说清 */
  const handleDelete = (row: PhotoItem) => {
    ElMessageBox.confirm(
      t('contentAudit.photo.deleteConfirm', { id: row.id }),
      t('contentAudit.photo.deleteTitle'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    ).then(async () => {
      await deletePhoto(row.id)
      ElMessage.success(t('contentAudit.photo.deleteSuccess'))
      // 删最后一页最后一条时回上一页，避免停在空页
      refreshRemove()
    })
  }
</script>
