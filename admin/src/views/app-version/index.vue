<template>
  <div class="app-release-page art-full-height">
    <ElCard shadow="never" class="mb-4">
      <div class="flex-bt">
        <div>
          <h2 class="m-0 text-lg font-semibold">APP 更新中心</h2>
          <p class="mt-2 mb-0 art-text-gray-500">
            版本源：GitHub Releases。后台不再维护重复的版本数据库。
          </p>
        </div>
        <ElButton type="primary" :loading="loading" @click="load">刷新 GitHub</ElButton>
      </div>
      <ElAlert
        v-if="error"
        class="mt-4"
        type="error"
        :title="error"
        show-icon
        :closable="false"
      />
      <div v-if="serverInfo" class="mt-4 flex flex-wrap gap-3">
        <ElTag type="success">服务端 {{ serverInfo.version }}</ElTag>
        <ElTag type="info">commit {{ serverInfo.commit }}</ElTag>
        <ElTag type="info">Go {{ serverInfo.go }}</ElTag>
        <ElLink :href="repository" target="_blank" type="primary">打开仓库</ElLink>
      </div>
    </ElCard>

    <ElCard shadow="never" class="art-table-card">
      <ElTable v-loading="loading" :data="releases" stripe>
        <ElTableColumn prop="version_name" label="版本" width="130" />
        <ElTableColumn label="渠道" width="110">
          <template #default="{ row }">
            <ElTag :type="row.prerelease ? 'warning' : 'success'">
              {{ row.prerelease ? '测试版' : '正式版' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="version_code" label="versionCode" width="120" />
        <ElTableColumn label="发布时间" width="190">
          <template #default="{ row }">{{ formatDateTime(row.published_at) }}</template>
        </ElTableColumn>
        <ElTableColumn label="APK" min-width="150">
          <template #default="{ row }">
            <ElLink v-if="row.apk_url" :href="row.apk_url" target="_blank" type="primary">
              下载 {{ row.version_name }}
            </ElLink>
            <span v-else class="art-text-gray-400">未附 APK</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="更新说明" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ row.notes || '未填写' }}</template>
        </ElTableColumn>
        <ElTableColumn label="Release" width="100" fixed="right">
          <template #default="{ row }">
            <ElLink :href="row.html_url" target="_blank" type="primary">查看</ElLink>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-if="!loading && releases.length === 0" description="GitHub 暂无可用 Release" />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import {
    ElAlert,
    ElButton,
    ElCard,
    ElEmpty,
    ElLink,
    ElTable,
    ElTableColumn,
    ElTag
  } from 'element-plus'
  import { fetchAppReleases } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'

  defineOptions({ name: 'AppVersion' })

  const loading = ref(false)
  const error = ref('')
  const releases = ref<Api.Admin.AppReleaseItem[]>([])
  const repository = ref('https://github.com/Lxii-Build/LxDay')
  const serverInfo = ref<Api.Admin.ServerInfo | null>(null)

  const load = async () => {
    loading.value = true
    error.value = ''
    try {
      const data = await fetchAppReleases()
      releases.value = data.releases || []
      repository.value = data.repository || repository.value
      serverInfo.value = {
        version: data.server_version,
        commit: data.server_commit,
        go: '1.25'
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : '读取 GitHub Release 失败，请重试'
    } finally {
      loading.value = false
    }
  }

  onMounted(load)
</script>
