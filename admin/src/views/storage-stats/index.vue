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
          <span>{{ $t('storageStats.retention.title') }}</span>
          <ElButton link type="primary" @click="goSettings">{{
            $t('storageStats.retention.goSettings')
          }}</ElButton>
        </div>
      </template>
      <ElDescriptions :column="isNarrow ? 1 : 3" border>
        <ElDescriptionsItem :label="$t('storageStats.retention.recycleBin')">
          {{ retentionText(stats?.retention.recycle_bin_days) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem :label="$t('storageStats.retention.statusHistory')">
          {{ retentionText(stats?.retention.status_history_days) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem :label="$t('storageStats.retention.networkLog')">
          {{ retentionText(stats?.retention.network_log_days) }}
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>

    <ElCard class="pairs-card" shadow="never">
      <template #header>
        <div class="pairs-card__header">
          <span>{{ $t('storageStats.pairsTitle') }}</span>
          <ElButton :loading="loading" @click="load">{{ $t('common.refresh') }}</ElButton>
        </div>
      </template>

      <ElTable v-loading="loading" :data="stats?.pairs || []" :class="{ 'narrow-table': isNarrow }">
        <ElTableColumn prop="pair_id" :label="$t('storageStats.table.pairId')" width="100" />
        <ElTableColumn prop="couple" :label="$t('storageStats.table.couple')" min-width="150" />
        <ElTableColumn
          prop="photo_count"
          :label="$t('storageStats.table.photoCount')"
          width="100"
        />
        <ElTableColumn :label="$t('storageStats.table.storage')" width="120">
          <template #default="{ row }">{{ formatFileSize(row.size_bytes) }}</template>
        </ElTableColumn>
        <ElTableColumn :label="$t('storageStats.table.recycleBin')" width="160">
          <template #default="{ row }">
            {{ row.recycled_count }} 张 / {{ formatFileSize(row.recycled_bytes) }}
          </template>
        </ElTableColumn>
        <ElTableColumn :label="$t('common.operation')" width="140" fixed="right">
          <template #default="{ row }">
            <ElButton type="danger" link :disabled="!row.recycled_count" @click="handlePurge(row)">
              {{ $t('storageStats.purge') }}
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <ElEmpty
        v-if="!loading && !(stats?.pairs || []).length"
        :description="$t('storageStats.empty')"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { useI18n } from 'vue-i18n'
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
  const { t } = useI18n()
  const { width } = useWindowSize()
  const isNarrow = computed(() => width.value < 768)

  const stats = ref<Api.Admin.StorageStats | null>(null)
  const loading = ref(false)

  const summaryCards = computed(() => {
    const totalStats = stats.value?.total
    if (!totalStats) return []
    return [
      {
        label: t('storageStats.summary.photoCount'),
        value: `${totalStats.photo_count} ${t('storageStats.units.photos')}`,
        hint: ''
      },
      {
        label: t('storageStats.summary.originalStorage'),
        value: formatFileSize(totalStats.size_bytes),
        hint: t('storageStats.summary.dbOriginal')
      },
      {
        label: t('storageStats.summary.diskUsage'),
        value: formatFileSize(totalStats.disk_bytes),
        // 说清差距来源，否则管理员会以为数字对不上是 bug
        hint: t('storageStats.summary.diskHint', { count: totalStats.disk_file_count })
      },
      {
        label: t('storageStats.summary.recycled'),
        value: formatFileSize(totalStats.recycled_bytes),
        hint: t('storageStats.summary.pending', { count: totalStats.recycled_count })
      }
    ]
  })

  function retentionText(days?: number): string {
    if (days === undefined || days === null) return '-'
    return days <= 0
      ? t('storageStats.retention.forever')
      : t('storageStats.retention.days', { days })
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
        t('storageStats.purgeConfirm', {
          couple: row.couple,
          count: row.recycled_count,
          size: formatFileSize(row.recycled_bytes)
        }),
        t('storageStats.purgeTitle'),
        {
          type: 'warning',
          confirmButtonText: t('storageStats.purgeConfirmButton'),
          cancelButtonText: t('common.cancel')
        }
      )
      const res = await purgeRecycleBin(row.pair_id)
      ElMessage.success(
        t('storageStats.purgeSuccess', { count: res.purged, size: formatFileSize(res.freed_bytes) })
      )
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
