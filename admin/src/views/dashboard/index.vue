<!-- 数据看板 -->
<template>
  <div class="dashboard-page art-full-height">
    <section class="dashboard-hero" aria-labelledby="dashboard-title">
      <div class="dashboard-hero__copy">
        <p class="dashboard-hero__eyebrow">{{ $t('dashboard.eyebrow') }}</p>
        <h1 id="dashboard-title">{{ $t('dashboard.title') }}</h1>
        <p class="dashboard-hero__subtitle">{{ $t('dashboard.subtitle') }}</p>
      </div>
      <div class="dashboard-hero__actions">
        <p class="dashboard-status" aria-live="polite">
          <span
            class="dashboard-status__dot"
            :class="{ 'is-loading': loading, 'is-error': loadError }"
            aria-hidden="true"
          />
          {{ updateStatus }}
        </p>
        <ElButton
          class="dashboard-refresh"
          type="primary"
          plain
          :loading="loading"
          :aria-label="$t('dashboard.actions.refresh')"
          @click="loadStats"
        >
          <Icon
            v-if="!loading"
            icon="ri:refresh-line"
            :width="17"
            :height="17"
            aria-hidden="true"
          />
          <span>{{ $t('common.refresh') }}</span>
        </ElButton>
      </div>
    </section>

    <ElAlert
      v-if="loadError"
      class="dashboard-alert"
      type="error"
      :closable="false"
      show-icon
      :title="$t('dashboard.error.title')"
    >
      <template #default>
        <span>{{ $t('dashboard.error.description') }}</span>
        <ElButton link type="primary" :disabled="loading" @click="loadStats">
          {{ $t('dashboard.actions.retry') }}
        </ElButton>
      </template>
    </ElAlert>

    <section v-if="hasLoaded || !loadError" aria-labelledby="dashboard-stats-title">
      <h2 id="dashboard-stats-title" class="sr-only">{{ $t('dashboard.sections.stats') }}</h2>
      <ElRow :gutter="16">
        <ElCol v-for="card in statCards" :key="card.key" :xs="24" :sm="12" :md="12" :lg="6">
          <ElCard
            class="stat-card"
            shadow="never"
            v-loading="loading && !hasLoaded"
            :style="{ '--stat-accent': card.color }"
          >
            <div class="stat-card__body">
              <div class="stat-card__copy">
                <p class="stat-card__label">{{ card.label }}</p>
                <p class="stat-card__value">{{ card.value }}</p>
                <p class="stat-card__description">{{ card.description }}</p>
              </div>
              <div class="stat-card__icon" aria-hidden="true">
                <Icon :icon="card.icon" :width="24" :height="24" />
              </div>
            </div>
          </ElCard>
        </ElCol>
      </ElRow>
    </section>

    <section v-if="hasLoaded || !loadError" aria-labelledby="dashboard-chart-title">
      <ElCard class="chart-card" shadow="never">
        <template #header>
          <div class="chart-card__header">
            <div>
              <h2 id="dashboard-chart-title">{{ $t('dashboard.chart.title') }}</h2>
              <p>{{ $t('dashboard.chart.subtitle') }}</p>
            </div>
            <span class="chart-card__period">{{ $t('dashboard.chart.period') }}</span>
          </div>
        </template>
        <ArtLineChart
          v-if="loading || hasTrendData"
          :data="dailyCounts"
          :x-axis-data="dailyDates"
          :loading="loading"
          show-area-color
          height="360px"
        />
        <ElEmpty v-else :description="$t('dashboard.chart.empty')" :image-size="96" />
      </ElCard>
    </section>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { fetchDashboardStats } from '@/api/admin'
  import { Icon } from '@iconify/vue'

  defineOptions({ name: 'Dashboard' })

  const { t, locale } = useI18n()

  const loading = ref(false)
  const loadError = ref(false)
  const hasLoaded = ref(false)
  const lastUpdated = ref<Date | null>(null)
  const stats = ref<Api.Admin.DashboardStats>({
    users: 0,
    pairs: 0,
    todos: 0,
    new_users_7d: 0,
    daily_new: []
  })

  const numberFormatter = computed(() => new Intl.NumberFormat(locale.value))
  const formatNumber = (value: number) => numberFormatter.value.format(value)

  const statCards = computed(() => [
    {
      key: 'users',
      label: t('dashboard.stats.users'),
      description: t('dashboard.stats.usersDescription'),
      value: formatNumber(stats.value.users),
      color: '#3978F6',
      icon: 'ri:user-3-line'
    },
    {
      key: 'pairs',
      label: t('dashboard.stats.pairs'),
      description: t('dashboard.stats.pairsDescription'),
      value: formatNumber(stats.value.pairs),
      color: '#D95B9F',
      icon: 'ri:heart-2-line'
    },
    {
      key: 'todos',
      label: t('dashboard.stats.todos'),
      description: t('dashboard.stats.todosDescription'),
      value: formatNumber(stats.value.todos),
      color: '#B87400',
      icon: 'ri:task-line'
    },
    {
      key: 'new_users_7d',
      label: t('dashboard.stats.newUsers7d'),
      description: t('dashboard.stats.newUsersDescription'),
      value: formatNumber(stats.value.new_users_7d),
      color: '#16856B',
      icon: 'ri:line-chart-line'
    }
  ])

  const dailyDates = computed(() => stats.value.daily_new.map((d) => d.date))
  const dailyCounts = computed(() => stats.value.daily_new.map((d) => d.count))
  const hasTrendData = computed(() => dailyCounts.value.some((count) => count > 0))
  const updateStatus = computed(() => {
    if (loading.value && !hasLoaded.value) return t('dashboard.status.loading')
    if (!lastUpdated.value) return t('dashboard.status.waiting')
    const time = new Intl.DateTimeFormat(locale.value, {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    }).format(lastUpdated.value)
    return t('dashboard.status.updated', { time })
  })

  const loadStats = async () => {
    if (loading.value) return
    loading.value = true
    loadError.value = false
    try {
      stats.value = await fetchDashboardStats()
      lastUpdated.value = new Date()
      hasLoaded.value = true
    } catch {
      loadError.value = true
    } finally {
      loading.value = false
    }
  }

  onMounted(loadStats)
</script>

<style lang="scss" scoped>
  .dashboard-page {
    padding-bottom: 20px;

    .dashboard-hero {
      position: relative;
      display: flex;
      gap: 24px;
      align-items: center;
      justify-content: space-between;
      padding: 24px 26px;
      margin-bottom: 16px;
      overflow: hidden;
      background: var(--default-box-color);
      border: 1px solid var(--art-card-border);
      border-radius: 16px;

      &::before {
        position: absolute;
        inset: 18px auto 18px 0;
        width: 4px;
        content: '';
        background: var(--main-color);
        border-radius: 0 4px 4px 0;
      }

      &__copy {
        min-width: 0;
      }

      &__eyebrow {
        margin: 0 0 6px;
        font-size: 12px;
        font-weight: 700;
        color: var(--main-color);
        letter-spacing: 0.12em;
      }

      h1 {
        margin: 0;
        font-size: clamp(24px, 3vw, 32px);
        font-weight: 700;
        line-height: 1.25;
        color: var(--art-gray-900);
        letter-spacing: -0.02em;
      }

      &__subtitle {
        margin: 8px 0 0;
        font-size: 14px;
        line-height: 1.65;
        color: var(--art-gray-600);
      }

      &__actions {
        display: flex;
        flex-shrink: 0;
        gap: 14px;
        align-items: center;
      }
    }

    .dashboard-status {
      display: flex;
      gap: 7px;
      align-items: center;
      margin: 0;
      font-size: 12px;
      color: var(--art-gray-600);
      white-space: nowrap;

      &__dot {
        width: 7px;
        height: 7px;
        background: #16856b;
        border-radius: 50%;

        &.is-loading {
          background: #b87400;
          animation: status-pulse 1.2s ease-in-out infinite;
        }

        &.is-error {
          background: var(--el-color-danger);
          animation: none;
        }
      }
    }

    .dashboard-refresh {
      min-width: 94px;
      min-height: 40px;

      :deep(.el-button__text) {
        display: inline-flex;
        gap: 7px;
        align-items: center;
      }
    }

    .dashboard-alert {
      margin-bottom: 16px;

      :deep(.el-alert__description) {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 10px;
        align-items: center;
        margin-top: 4px;
      }
    }

    .stat-card {
      margin-bottom: 16px;
      border: 1px solid var(--art-card-border);
      border-radius: 16px;
      transition:
        transform 180ms ease,
        border-color 180ms ease;

      &:hover {
        border-color: color-mix(in srgb, var(--stat-accent) 38%, var(--art-card-border));
        transform: translateY(-2px);
      }

      &__body {
        display: flex;
        gap: 16px;
        align-items: flex-start;
        justify-content: space-between;
        min-height: 104px;
      }

      &__copy {
        min-width: 0;
      }

      &__icon {
        display: flex;
        flex: 0 0 48px;
        align-items: center;
        justify-content: center;
        width: 48px;
        height: 48px;
        color: var(--stat-accent);
        background: color-mix(in srgb, var(--stat-accent) 13%, transparent);
        border-radius: 14px;
      }

      &__label {
        margin: 0;
        font-size: 13px;
        font-weight: 600;
        color: var(--art-gray-600);
      }

      &__value {
        margin: 5px 0 4px;
        font-size: 30px;
        font-weight: 700;
        font-variant-numeric: tabular-nums;
        line-height: 1.2;
        color: var(--art-gray-900);
        letter-spacing: -0.03em;
      }

      &__description {
        margin: 0;
        font-size: 12px;
        line-height: 1.45;
        color: var(--art-gray-500);
      }
    }

    .chart-card {
      border: 1px solid var(--art-card-border);
      border-radius: 16px;

      &__header {
        display: flex;
        gap: 16px;
        align-items: center;
        justify-content: space-between;

        h2 {
          margin: 0;
          font-size: 17px;
          font-weight: 650;
          color: var(--art-gray-900);
        }

        p {
          margin: 4px 0 0;
          font-size: 12px;
          color: var(--art-gray-500);
        }
      }

      &__period {
        padding: 6px 10px;
        font-size: 12px;
        font-weight: 600;
        color: var(--main-color);
        white-space: nowrap;
        background: color-mix(in srgb, var(--main-color) 10%, transparent);
        border-radius: 10px;
      }
    }

    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
      border: 0;
    }
  }

  @keyframes status-pulse {
    50% {
      opacity: 0.35;
    }
  }

  @media (width <= 768px) {
    .dashboard-page {
      .dashboard-hero {
        align-items: flex-start;
        padding: 20px;

        &__actions {
          flex-direction: column-reverse;
          align-items: flex-end;
        }
      }
    }
  }

  @media (width <= 480px) {
    .dashboard-page {
      .dashboard-hero {
        flex-direction: column;
        gap: 18px;

        &__actions {
          flex-direction: row;
          justify-content: space-between;
          width: 100%;
        }
      }

      .dashboard-status {
        white-space: normal;
      }

      .stat-card__body {
        min-height: 92px;
      }

      .chart-card__header {
        align-items: flex-start;
      }
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .dashboard-page {
      .dashboard-status__dot,
      .stat-card {
        transition: none;
        animation: none;
      }

      .stat-card:hover {
        transform: none;
      }
    }
  }
</style>
