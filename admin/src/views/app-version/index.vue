<!-- APP 版本发布 -->
<template>
  <div class="app-version-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElSelect
              v-model="platform"
              placeholder="全部平台"
              clearable
              style="width: 160px"
              @change="handleSearch"
            >
              <ElOption label="Android" value="android" />
              <ElOption label="iOS" value="ios" />
            </ElSelect>
            <ElButton type="primary" @click="dialogVisible = true">发布新版本</ElButton>
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

    <VersionDialog v-model="dialogVisible" @success="refreshData" />
  </div>
</template>

<script setup lang="ts">
  import { useTable } from '@/hooks/core/useTable'
  import {
    fetchAppVersionList,
    updateAppVersionStatus,
    deleteAppVersion
  } from '@/api/admin'
  import VersionDialog from './modules/version-dialog.vue'
  import { ElButton, ElLink, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'AppVersion' })

  type AppVersionItem = Api.Admin.AppVersionItem

  const platform = ref<string>('')
  const dialogVisible = ref(false)

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
      apiFn: fetchAppVersionList,
      apiParams: { current: 1, size: 20, platform: '' },
      columnsFactory: () => [
        { prop: 'id', label: 'ID', width: 80 },
        { prop: 'platform', label: '平台', width: 100 },
        { prop: 'version_name', label: '版本名', width: 120 },
        { prop: 'version_code', label: '版本号', width: 100 },
        {
          prop: 'apk_url',
          label: 'APK',
          minWidth: 200,
          formatter: (row) =>
            row.apk_url
              ? h(ElLink, { type: 'primary', href: row.apk_url, target: '_blank' }, () => '下载')
              : h('span', '-')
        },
        {
          prop: 'force_update',
          label: '强更',
          width: 90,
          formatter: (row) =>
            h(ElTag, { type: row.force_update ? 'danger' : 'info' }, () =>
              row.force_update ? '强制' : '否'
            )
        },
        {
          prop: 'status',
          label: '状态',
          width: 100,
          formatter: (row) =>
            h(ElTag, { type: row.status === 1 ? 'success' : 'info' }, () =>
              row.status === 1 ? '已发布' : '已下架'
            )
        },
        { prop: 'created_at', label: '发布时间', minWidth: 180 },
        {
          prop: 'operation',
          label: '操作',
          width: 160,
          fixed: 'right',
          formatter: (row) =>
            h('div', [
              h(
                ElButton,
                {
                  type: row.status === 1 ? 'warning' : 'success',
                  link: true,
                  onClick: () => toggleStatus(row)
                },
                () => (row.status === 1 ? '下架' : '上架')
              ),
              h(
                ElButton,
                { type: 'danger', link: true, onClick: () => handleDelete(row) },
                () => '删除'
              )
            ])
        }
      ]
    }
  })

  const handleSearch = () => {
    replaceSearchParams({ platform: platform.value })
    getData()
  }

  const toggleStatus = async (row: AppVersionItem) => {
    const next = row.status === 1 ? 0 : 1
    await updateAppVersionStatus(row.id, next)
    ElMessage.success(next === 1 ? '已上架' : '已下架')
    refreshData()
  }

  const handleDelete = (row: AppVersionItem) => {
    ElMessageBox.confirm(`确定要删除版本「${row.version_name}」吗？`, '删除版本', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(async () => {
      await deleteAppVersion(row.id)
      ElMessage.success('删除成功')
      refreshData()
    })
  }
</script>
