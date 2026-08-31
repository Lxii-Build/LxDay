<!-- APP 版本发布 -->
<template>
  <div class="app-version-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <ArtTableHeader v-model:columns="columnChecks" :loading="loading" @refresh="refreshData">
        <template #left>
          <ElSpace wrap>
            <ElSelect
              v-model="platform"
              :placeholder="$t('appVersion.filter.allPlatforms')"
              clearable
              style="width: 160px"
              @change="handleSearch"
            >
              <ElOption :label="$t('appVersion.platform.android')" value="android" />
              <ElOption :label="$t('appVersion.platform.ios')" value="ios" />
            </ElSelect>
            <ElButton type="primary" @click="openCreate">{{ $t('appVersion.publish') }}</ElButton>
          </ElSpace>
        </template>
      </ArtTableHeader>

      <ArtTable
        :loading="loading"
        :data="data"
        :columns="columns"
        :pagination="pagination"
        :empty-text="$t('appVersion.empty')"
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      >
      </ArtTable>
    </ElCard>

    <VersionDialog
      v-model="dialogVisible"
      :existing="existing"
      :editing="editingVersion"
      @success="refreshData"
    />
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { useTable } from '@/hooks/core/useTable'
  import { deleteAppVersion, fetchAppVersionList, updateAppVersionStatus } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'
  import VersionDialog from './modules/version-dialog.vue'
  import { ElButton, ElLink, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'AppVersion' })

  type AppVersionItem = Api.Admin.AppVersionItem

  const { t } = useI18n()

  const platform = ref<string>('')
  const dialogVisible = ref(false)
  const editingVersion = ref<AppVersionItem | null>(null)

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
        { prop: 'id', label: t('appVersion.table.id'), width: 80 },
        { prop: 'platform', label: t('appVersion.table.platform'), width: 100 },
        { prop: 'version_name', label: t('appVersion.table.versionName'), width: 120 },
        { prop: 'version_code', label: t('appVersion.table.versionCode'), width: 110 },
        {
          prop: 'apk_url',
          label: t('appVersion.table.apk'),
          minWidth: 200,
          formatter: (row) =>
            row.apk_url
              ? h(ElLink, { type: 'primary', href: row.apk_url, target: '_blank' }, () =>
                  t('appVersion.download')
                )
              : h('span', '-')
        },
        {
          prop: 'force_update',
          label: t('appVersion.table.forceUpdate'),
          width: 100,
          formatter: (row) =>
            h(ElTag, { type: row.force_update ? 'danger' : 'info' }, () =>
              row.force_update ? t('appVersion.force.yes') : t('appVersion.force.no')
            )
        },
        {
          prop: 'status',
          label: t('appVersion.table.status'),
          width: 100,
          formatter: (row) =>
            h(ElTag, { type: row.status === 1 ? 'success' : 'info' }, () =>
              row.status === 1 ? t('appVersion.status.online') : t('appVersion.status.offline')
            )
        },
        {
          prop: 'created_at',
          label: t('appVersion.table.createdAt'),
          minWidth: 180,
          formatter: (row) => formatDateTime(row.created_at)
        },
        {
          prop: 'operation',
          label: t('common.operation'),
          width: 220,
          fixed: 'right',
          formatter: (row) =>
            h('div', [
              h(ElButton, { type: 'primary', link: true, onClick: () => openEdit(row) }, () =>
                t('common.edit')
              ),
              h(
                ElButton,
                {
                  type: row.status === 1 ? 'warning' : 'success',
                  link: true,
                  onClick: () => toggleStatus(row)
                },
                () => (row.status === 1 ? t('appVersion.offline') : t('appVersion.online'))
              ),
              h(ElButton, { type: 'danger', link: true, onClick: () => handleDelete(row) }, () =>
                t('common.delete')
              )
            ])
        }
      ]
    }
  })

  /** 当前列表已有的版本，交给弹窗做 version_code 重复提示 */
  const existing = computed(() =>
    (data.value || []).map((item) => ({
      platform: item.platform,
      version_code: item.version_code,
      version_name: item.version_name
    }))
  )

  const openCreate = () => {
    editingVersion.value = null
    dialogVisible.value = true
  }

  const openEdit = (row: AppVersionItem) => {
    editingVersion.value = row
    dialogVisible.value = true
  }

  const handleSearch = () => {
    replaceSearchParams({ platform: platform.value })
    getData()
  }

  const toggleStatus = async (row: AppVersionItem) => {
    const next = row.status === 1 ? 0 : 1
    await updateAppVersionStatus(row.id, next)
    ElMessage.success(next === 1 ? t('appVersion.onlineSuccess') : t('appVersion.offlineSuccess'))
    refreshData()
  }

  const handleDelete = (row: AppVersionItem) => {
    ElMessageBox.confirm(
      t('appVersion.deleteConfirm', { name: row.version_name }),
      t('appVersion.deleteTitle'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    ).then(async () => {
      await deleteAppVersion(row.id)
      ElMessage.success(t('common.deleteSuccess'))
      refreshData()
    })
  }
</script>
