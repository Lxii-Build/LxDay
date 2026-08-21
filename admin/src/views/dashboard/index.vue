<!-- 数据看板 -->
<template>
  <div class="dashboard-page art-full-height">
    <ElRow :gutter="16">
      <ElCol v-for="card in statCards" :key="card.key" :xs="12" :sm="12" :md="8" :lg="6" :xl="4">
        <ElCard class="stat-card" shadow="never" v-loading="loading">
          <div class="stat-card__body">
            <div class="stat-card__icon" :style="{ backgroundColor: card.color }">
              <Icon :icon="card.icon" :width="24" :height="24" />
            </div>
            <div class="stat-card__info">
              <p class="stat-card__label">{{ card.label }}</p>
              <p class="stat-card__value">{{ card.value }}</p>
            </div>
          </div>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElCard class="chart-card" shadow="never">
      <template #header>
        <div class="chart-card__header">
          <span>{{ $t('dashboard.chart.title') }}</span>
          <ElButton text :loading="loading" @click="loadStats">{{ $t('common.refresh') }}</ElButton>
        </div>
      </template>
      <ArtLineChart
        :data="dailyCounts"
        :x-axis-data="dailyDates"
        :loading="loading"
        show-area-color
        height="360px"
      />
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { fetchDashboardStats } from '@/api/admin'
  import { Icon } from '@iconify/vue'

  defineOptions({ name: 'Dashboard' })

  const { t } = useI18n()

  const loading = ref(false)
  const stats = ref<Api.Admin.DashboardStats>({
    users: 0,
    pairs: 0,
    todos: 0,
    new_users_7d: 0,
    daily_new: []
  })

  const statCards = computed(() => [
    {
      key: 'users',
      label: t('dashboard.stats.users'),
      value: stats.value.users,
      color: '#5D87FF',
      icon: 'ri:user-3-line'
    },
    {
      key: 'pairs',
      label: t('dashboard.stats.pairs'),
      value: stats.value.pairs,
      color: '#FF80C8',
      icon: 'ri:heart-2-line'
    },
    {
      key: 'todos',
      label: t('dashboard.stats.todos'),
      value: stats.value.todos,
      color: '#FFAE1F',
      icon: 'ri:task-line'
    },
    {
      key: 'new_users_7d',
      label: t('dashboard.stats.newUsers7d'),
      value: stats.value.new_users_7d,
      color: '#539BFF',
      icon: 'ri:line-chart-line'
    }
  ])

  const dailyDates = computed(() => stats.value.daily_new.map((d) => d.date))
  const dailyCounts = computed(() => stats.value.daily_new.map((d) => d.count))

  const loadStats = async () => {
    loading.value = true
    try {
      stats.value = await fetchDashboardStats()
    } finally {
      loading.value = false
    }
  }

  onMounted(loadStats)
</script>

<style lang="scss" scoped>
  .dashboard-page {
    .stat-card {
      margin-bottom: 16px;
      border-radius: 12px;

      &__body {
        display: flex;
        align-items: center;
      }

      &__icon {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 48px;
        height: 48px;
        margin-right: 14px;
        color: #fff;
        border-radius: 10px;

        i {
          font-size: 24px;
        }
      }

      &__label {
        margin: 0;
        font-size: 13px;
        color: var(--art-gray-600);
      }

      &__value {
        margin: 4px 0 0;
        font-size: 24px;
        font-weight: 600;
      }
    }

    .chart-card {
      border-radius: 12px;

      &__header {
        display: flex;
        align-items: center;
        justify-content: space-between;
      }
    }
  }
</style>
