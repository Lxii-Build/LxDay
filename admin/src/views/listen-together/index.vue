<template>
  <div class="listen-together-page art-full-height">
    <ElCard class="listen-hero" shadow="never">
      <div class="listen-hero__copy">
        <span class="listen-hero__eyebrow">{{ $t('listenTogether.eyebrow') }}</span>
        <h2>{{ $t('listenTogether.title') }}</h2>
        <p>{{ $t('listenTogether.description') }}</p>
      </div>
      <ElButton :loading="loading" @click="load">{{ $t('common.refresh') }}</ElButton>
    </ElCard>

    <ElAlert v-if="loadError" type="error" :closable="false" show-icon>
      <span>{{ $t('listenTogether.loadFailed') }}</span>
      <ElButton link type="primary" :disabled="loading" @click="load">
        {{ $t('listenTogether.retry') }}
      </ElButton>
    </ElAlert>

    <ElCard class="listen-room-card" shadow="never">
      <template #header>
        <div class="listen-room-card__header">
          <span>{{ $t('listenTogether.roomsTitle') }}</span>
          <ElTag type="info">{{ rooms.length }}</ElTag>
        </div>
      </template>
      <div v-if="!loading && !loadError && rooms.length" class="room-list">
        <article v-for="room in rooms" :key="room.room_id" class="room-item">
          <div class="room-item__main">
            <div class="room-item__title">
              <strong>{{ room.room_id }}</strong>
              <ElTag :type="room.state.playing ? 'success' : 'info'">
                {{
                  room.state.playing ? $t('listenTogether.playing') : $t('listenTogether.paused')
                }}
              </ElTag>
            </div>
            <div class="room-item__track">
              {{ room.state.title || $t('listenTogether.noTrack') }}
              <span v-if="room.state.artist"> · {{ room.state.artist }}</span>
            </div>
            <div
              v-if="room.state.source === 'netease' && room.state.song_id"
              class="room-item__source"
            >
              网易云 · {{ room.state.song_id }}
            </div>
            <div class="room-item__meta">
              {{ $t('listenTogether.pair', { id: room.pair_id }) }} ·
              {{ $t('listenTogether.host', { id: room.host_user_id }) }} ·
              {{ $t('listenTogether.members', { count: room.members }) }} ·
              {{ formatDate(room.updated_at) }}
            </div>
          </div>
          <ElButton type="danger" plain @click="closeRoom(room)">
            {{ $t('listenTogether.close') }}
          </ElButton>
        </article>
      </div>
      <ElEmpty v-else-if="!loading && !loadError" :description="$t('listenTogether.empty')" />
      <div v-else class="room-loading" aria-live="polite">{{ $t('listenTogether.loading') }}</div>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { onMounted, ref } from 'vue'
  import { useI18n } from 'vue-i18n'
  import { closeListenRoom, fetchListenRooms } from '@/api/admin'
  import { formatDateTime } from '@/utils/format/datetime'
  import { ElAlert, ElButton, ElCard, ElEmpty, ElMessage, ElMessageBox, ElTag } from 'element-plus'

  defineOptions({ name: 'ListenTogether' })
  const { t } = useI18n()
  const rooms = ref<Api.Admin.ListenRoom[]>([])
  const loading = ref(false)
  const loadError = ref(false)

  function formatDate(value: string) {
    return formatDateTime(value)
  }

  async function load() {
    loading.value = true
    loadError.value = false
    try {
      rooms.value = (await fetchListenRooms()) || []
    } catch {
      rooms.value = []
      loadError.value = true
    } finally {
      loading.value = false
    }
  }

  async function closeRoom(room: Api.Admin.ListenRoom) {
    try {
      await ElMessageBox.confirm(
        t('listenTogether.closeConfirm', { room: room.room_id }),
        t('listenTogether.closeTitle'),
        {
          type: 'warning',
          confirmButtonText: t('listenTogether.close'),
          cancelButtonText: t('common.cancel')
        }
      )
      await closeListenRoom(room.room_id)
      ElMessage.success(t('listenTogether.closeSuccess'))
      await load()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
    }
  }

  onMounted(load)
</script>

<style scoped lang="scss">
  .listen-together-page {
    gap: 14px;
  }

  .listen-hero,
  .listen-room-card {
    border-radius: 20px;
  }

  .listen-hero :deep(.el-card__body),
  .listen-room-card :deep(.el-card__body) {
    padding: 22px;
  }

  .listen-hero {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }

  .listen-hero__eyebrow {
    color: var(--theme-color);
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.12em;
    text-transform: uppercase;
  }

  h2 {
    margin: 5px 0;
    color: var(--art-gray-900);
    font-size: 22px;
  }

  p {
    margin: 0;
    color: var(--art-gray-600);
  }

  .listen-room-card__header,
  .room-item__title {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .room-list {
    display: grid;
    gap: 12px;
  }

  .room-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 16px;
    border-radius: 16px;
    background: var(--default-box-color);
    box-shadow:
      inset 3px 3px 7px var(--lx-neo-dark),
      inset -3px -3px 7px var(--lx-neo-light);
  }

  .room-item__title strong {
    color: var(--theme-color);
    font-size: 18px;
    letter-spacing: 0.08em;
  }

  .room-item__track {
    margin-top: 7px;
    color: var(--art-gray-900);
    font-weight: 600;
  }

  .room-item__meta {
    margin-top: 6px;
    color: var(--art-gray-500);
    font-size: 12px;
  }

  .room-loading {
    padding: 28px;
    color: var(--art-gray-500);
    text-align: center;
  }

  @media (max-width: 640px) {
    .listen-hero,
    .room-item {
      align-items: stretch;
      flex-direction: column;
    }

    .room-item :deep(.el-button) {
      width: 100%;
    }
  }
</style>
