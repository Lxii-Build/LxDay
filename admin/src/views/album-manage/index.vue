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
            placeholder="搜索相册名"
            clearable
            style="width: 240px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <ElInput
            v-model="searchForm.pairId"
            placeholder="情侣 ID"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <ElButton type="primary" @click="handleSearch">搜索</ElButton>
          <ElButton @click="handleReset">重置</ElButton>
        </ElSpace>
      </template>
    </ArtTableHeader>

    <ArtTable
      :loading="loading"
      :data="data"
      :columns="columns"
      :pagination="pagination"
      empty-text="暂无相册"
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
        <ElButton type="danger" link @click="handleDelete(row)">删除</ElButton>
      </template>
    </ArtTable>
  </div>
</template>

<script setup lang="ts">
  import { ref } from 'vue'
  import { useTable } from '@/hooks/core/useTable'
  import { fetchAlbumList, deleteAlbum } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'
  import { formatFileSize } from '@/utils/format/filesize'
  import { ElButton, ElInput, ElMessage, ElMessageBox, ElSpace } from 'element-plus'

  defineOptions({ name: 'AlbumManage' })

  type AlbumItem = Api.Admin.AlbumItem

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
        { prop: 'name', label: '相册名', minWidth: 140 },
        { prop: 'couple', label: '所属情侣', minWidth: 160 },
        { prop: 'pair_id', label: '情侣 ID', width: 100 },
        { prop: 'photo_count', label: '照片数', width: 100 },
        { prop: 'size_bytes', label: '占用空间', width: 120, useSlot: true },
        { prop: 'created_at', label: '创建时间', minWidth: 160, useSlot: true },
        { prop: 'operation', label: '操作', width: 100, fixed: 'right', useSlot: true }
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

  async function handleDelete(row: AlbumItem) {
    // 说清后果：删相册是软删，照片不会跟着消失。
    // 不写清楚的话管理员会以为「删相册 = 删掉里面的照片」而不敢点。
    await ElMessageBox.confirm(
      `确定删除相册「${row.name}」？其中 ${row.photo_count} 张照片不会被删除，会退回该情侣的「未归类」。`,
      '删除相册',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await deleteAlbum(row.id)
    ElMessage.success('已删除')
    refreshRemove()
  }
</script>

<style lang="scss" scoped>
  .album-manage-page {
    width: 100%;
  }
</style>
