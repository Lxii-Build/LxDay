<!--
  磁盘占用统计（0821 新增，管理员 Q28=D）

  为什么需要：管理员此前**完全不知道服务器磁盘被谁占了多少**，直到它满。
  而 0821 之前有三处"只涨不跌"：相册软删后文件永久保留、状态历史永久保留、
  网络日志保留天数写死。这一页把容量摊开，配合「数据保留」配置才能真正管住磁盘。

  库里的 size_bytes 只统计原图；真实磁盘占用（含缩略图 384 与预览图 1080）
  由服务端 walk uploads 目录得出，两者会有差距，这是正常的。
-->
<template>
  <div class="storage-stats-page">
    <ElRow :gutter="12">
      <ElCol v-for="card in summaryCards" :key="card.label" :xs="12" :sm="12" :md="8" :lg="6">
        <ElCard class="stat-card" shadow="never">
          <div class="stat-card__label">{{ card.label }}</div>
          <div class="stat-card__value">{{ card.value }}</div>
          <div v-if="card.hint" class="stat-card__hint">{{ card.hint }}</div>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElCard class="retention-card" shadow="never">
      <template #header>
        <div class="retention-card__header">
          <span>数据保留策略</span>
          <ElButton link type="primary" @click="goSettings">去「系统设置」调整</ElButton>
        </div>
      </template>
      <ElDescriptions :column="isNarrow ? 1 : 3" border>
        <ElDescriptionsItem label="回收站保留">
          {{ retentionText(stats?.retention.recycle_bin_days) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="状态历史保留">
          {{ retentionText(stats?.retention.status_history_days) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="网络日志保留">
          {{ retentionText(stats?.retention.network_log_days) }}
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <ElCard class="pairs-card" shadow="never">
      <template #header>
        <div class="pairs-card__header">
          <span>各情侣占用（按占用降序）</span>
          <ElButton :loading="loading" @click="load">刷新</ElButton>
        </div>
      </template>

      <ElTable v-loading="loading" :data="stats?.pairs || []" :class="{ 'narrow-table': isNarrow }">
        <ElTableColumn prop="pair_id" label="情侣 ID" width="100" />
        <ElTableColumn prop="couple" label="情侣" min-width="150" />
        <ElTableColumn prop="photo_count" label="照片数" width="100" />
        <ElTableColumn label="占用" width="120">
          <template #default="{ row }">{{ formatFileSize(row.size_bytes) }}</template>
        </ElTableColumn>
        <ElTableColumn label="回收站" width="160">
          <template #default="{ row }">
            {{ row.recycled_count }} 张 / {{ formatFileSize(row.recycled_bytes) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <ElButton type="danger" link :disabled="!row.recycled_count" @click="handlePurge(row)">
              清空回收站
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <ElEmpty v-if="!loading && !(stats?.pairs || []).length" description="还没有照片" />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { useRouter } from 'vue-router'
  import { useWindowSize } from '@vueuse/core'
  import { fetchStorageStats, purgeRecycleBin } from '@/api/admin'
  import { formatFileSize } from '@/utils/format/filesize'
  import {
    ElButton,
    ElCard,
    ElCol,
    ElDescriptions,
    ElDescriptionsItem,
    ElEmpty,
    ElMessage,
    ElMessageBox,
    ElRow,
    ElTable,
    ElTableColumn
  } from 'element-plus'

  defineOptions({ name: 'StorageStats' })

  const router = useRouter()
  const { width } = useWindowSize()
  const isNarrow = computed(() => width.value < 768)

  const stats = ref<Api.Admin.StorageStats | null>(null)
  const loading = ref(false)

  const summaryCards = computed(() => {
    const t = stats.value?.total
    if (!t) return []
    return [
      { label: '照片总数', value: `${t.photo_count} 张`, hint: '' },
      { label: '原图占用', value: formatFileSize(t.size_bytes), hint: '库中记录的原图字节数' },
      {
        label: '磁盘实际占用',
        value: formatFileSize(t.disk_bytes),
        // 说清差距来源，否则管理员会以为数字对不上是 bug
        hint: `${t.disk_file_count} 个文件，含缩略图与预览图`
      },
      {
        label: '回收站占用',
        value: formatFileSize(t.recycled_bytes),
        hint: `${t.recycled_count} 张待清理`
      }
    ]
  })

  function retentionText(days?: number): string {
    if (days === undefined || days === null) return '-'
    return days <= 0 ? '永久保留' : `${days} 天`
  }

  function goSettings() {
    router.push('/system-settings')
  }

  async function load() {
    loading.value = true
    try {
      stats.value = await fetchStorageStats()
    } finally {
      loading.value = false
    }
  }

  async function handlePurge(row: Api.Admin.StorageUsageItem) {
    try {
      // 真删磁盘文件，不可恢复 —— 必须把后果写在确认框里，且不能是一句"确定吗"
      await ElMessageBox.confirm(
        `将永久删除「${row.couple}」回收站里的 ${row.recycled_count} 张照片` +
          `（约 ${formatFileSize(row.recycled_bytes)}），服务器上的文件会一并清除，之后无法恢复。`,
        '清空回收站',
        { type: 'warning', confirmButtonText: '永久删除', cancelButtonText: '取消' }
      )
      const res = await purgeRecycleBin(row.pair_id)
      ElMessage.success(`已清理 ${res.purged} 张，释放 ${formatFileSize(res.freed_bytes)}`)
      await load()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }

  onMounted(load)
</script>

<style lang="scss" scoped>
  .storage-stats-page {
    width: 100%;
  }

  .stat-card {
    margin-bottom: 12px;

    &__label {
      font-size: 13px;
      color: var(--art-text-gray-600);
    }

    &__value {
      margin-top: 6px;
      font-size: 20px;
      font-weight: 600;
      color: var(--art-text-gray-900);
    }

    &__hint {
      margin-top: 4px;
      font-size: 12px;
      color: var(--art-text-gray-500);
    }
  }

  .retention-card,
  .pairs-card {
    margin-bottom: 12px;
  }

  .retention-card__header,
  .pairs-card__header {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    justify-content: space-between;
  }

  /* 窄屏下这张表列较多，允许横向滚动而不是把列压成一团 */
  .narrow-table {
    :deep(.el-table__inner-wrapper) {
      overflow-x: auto;
    }

    :deep(.el-table__body),
    :deep(.el-table__header) {
      min-width: 680px;
    }
  }
</style>
