<template>
  <div class="live2d-page art-full-height">
    <section class="live2d-hero">
      <div>
        <p class="eyebrow">COMPANION RESOURCES</p>
        <h1>Live2D 模型资源库</h1>
        <p>只管理经过校验的模型包；发布前必须完成 Cubism 原生渲染验收，不向客户端强制推送。</p>
      </div>
      <div class="hero-badge"><Icon icon="ri:shield-check-line" /> 仅超级管理员</div>
    </section>

    <ElAlert
      v-if="catalogUnavailable"
      class="catalog-alert"
      type="warning"
      :closable="false"
      show-icon
      title="服务端模型目录暂不可用"
    >
      请检查服务端连接后重试；页面不会用本地演示数据冒充线上资源。
    </ElAlert>

    <ElRow :gutter="18">
      <ElCol :xs="24" :lg="9">
        <ElCard class="import-card" shadow="never">
          <template #header>
            <div class="card-heading">
              <div>
                <h2>导入模型包</h2>
                <p>ZIP · 最大 50 MiB（解压后 300 MiB）· 服务端流式校验</p>
              </div>
              <Icon icon="ri:upload-cloud-2-line" :width="22" />
            </div>
          </template>
          <ElUpload
            class="model-upload"
            drag
            :auto-upload="false"
            :show-file-list="false"
            accept=".zip,application/zip"
            :on-change="handleFileChange"
          >
            <Icon icon="ri:file-zip-line" :width="42" />
            <div class="el-upload__text">拖入 ZIP，或 <em>选择文件</em></div>
            <div class="upload-tip">
              必须包含 model3.json、配套 moc3 和纹理 PNG；VTube Studio 的 .vtube.json
              只是元数据，不能替代 moc3
            </div>
          </ElUpload>
          <div v-if="stagedFile" class="staged-file">
            <div>
              <strong>{{ stagedFile.name }}</strong>
              <span>{{ formatBytes(stagedFile.size) }} · 等待提交</span>
            </div>
            <ElButton type="primary" :loading="uploading" @click="submitUpload">提交校验</ElButton>
          </div>
          <p class="safety-note">
            <Icon icon="ri:lock-line" /> 上传文件先进入隔离区，校验
            SHA-256、路径安全、纹理数量（单边 ≤ 4096px / 总像素 ≤ 16M）和真实渲染状态后才可发布。
          </p>
        </ElCard>
      </ElCol>

      <ElCol :xs="24" :lg="15">
        <ElCard class="catalog-card" shadow="never">
          <template #header>
            <div class="card-heading">
              <div>
                <h2>已登记模型</h2>
                <p>{{ models.length }} 个版本 · 草稿与发布状态分开管理</p>
              </div>
              <ElButton text :loading="loading" @click="loadModels">
                <Icon icon="ri:refresh-line" :width="18" />
                刷新
              </ElButton>
            </div>
          </template>
          <div v-if="loading" class="loading-state"><ElSkeleton :rows="4" animated /></div>
          <ElEmpty v-else-if="!models.length" description="暂无已登记模型" />
          <div v-else class="model-list">
            <article v-for="model in models" :key="model.id" class="model-item">
              <div class="model-avatar"><Icon icon="ri:user-smile-line" :width="26" /></div>
              <div class="model-copy">
                <div class="model-title-row">
                  <strong>{{ model.name }}</strong>
                  <ElTag :type="statusType(model.status)" size="small">{{
                    statusLabel(model.status)
                  }}</ElTag>
                </div>
                <p
                  >v{{ model.version }} · {{ formatBytes(model.bytes) }} ·
                  {{ model.texture_count }} 张纹理</p
                >
                <p class="hash"
                  >SHA-256 {{ model.sha256.slice(0, 16) }}… · 客户端 ≥
                  {{ model.client_min_version || '未限制' }}</p
                >
              </div>
              <div class="model-actions">
                <ElButton
                  v-if="model.status === 'draft' || model.status === 'withdrawn'"
                  type="primary"
                  plain
                  @click="changeStatus(model, 'publish')"
                  >发布</ElButton
                >
                <ElButton v-else plain @click="changeStatus(model, 'withdraw')">撤回</ElButton>
                <ElButton type="danger" plain @click="removeModel(model)">删除</ElButton>
              </div>
            </article>
          </div>
        </ElCard>
      </ElCol>
    </ElRow>
  </div>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox, UploadFile } from 'element-plus'
  import { Icon } from '@iconify/vue'
  import {
    deleteLive2DModel,
    fetchLive2DModels,
    publishLive2DModel,
    uploadLive2DModel,
    withdrawLive2DModel
  } from '@/api/admin'

  defineOptions({ name: 'Live2DManage' })

  const models = ref<Api.Admin.Live2DModel[]>([])
  const loading = ref(false)
  const uploading = ref(false)
  const catalogUnavailable = ref(false)
  const stagedFile = ref<File | null>(null)

  const loadModels = async () => {
    loading.value = true
    catalogUnavailable.value = false
    try {
      const result = await fetchLive2DModels()
      models.value = result.records
    } catch {
      catalogUnavailable.value = true
      models.value = []
    } finally {
      loading.value = false
    }
  }

  const handleFileChange = (file: UploadFile) => {
    stagedFile.value = file.raw || null
  }

  const submitUpload = async () => {
    if (!stagedFile.value) return
    uploading.value = true
    try {
      const created = await uploadLive2DModel(stagedFile.value)
      models.value = [created, ...models.value]
      stagedFile.value = null
      ElMessage.success('模型已进入校验队列')
    } finally {
      uploading.value = false
    }
  }

  const changeStatus = async (model: Api.Admin.Live2DModel, action: 'publish' | 'withdraw') => {
    if (action === 'publish') await publishLive2DModel(model.id)
    else await withdrawLive2DModel(model.id)
    await loadModels()
  }

  const removeModel = async (model: Api.Admin.Live2DModel) => {
    await ElMessageBox.confirm(
      `删除「${model.name}」后，已安装客户端仍保留本地副本。继续吗？`,
      '删除模型',
      {
        type: 'warning',
        confirmButtonText: '删除模型',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
        cancelButtonClass: 'el-button--default'
      }
    )
    await deleteLive2DModel(model.id)
    models.value = models.value.filter((item) => item.id !== model.id)
  }

  const formatBytes = (bytes: number) => {
    if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MiB`
    if (bytes >= 1024) return `${Math.round(bytes / 1024)} KiB`
    return `${bytes} B`
  }
  const statusLabel = (status: Api.Admin.Live2DModel['status']) =>
    ({ draft: '草稿', published: '已发布', withdrawn: '已撤回' })[status]
  const statusType = (status: Api.Admin.Live2DModel['status']) =>
    ({ draft: 'warning', published: 'success', withdrawn: 'info' })[status] as
      'warning' | 'success' | 'info'

  onMounted(loadModels)
</script>

<style lang="scss" scoped>
  .live2d-page {
    padding-bottom: 20px;
  }

  .live2d-hero {
    display: flex;
    gap: 24px;
    align-items: center;
    justify-content: space-between;
    padding: 24px 26px;
    margin-bottom: 16px;
    background: var(--lx-surface);
    border-radius: 18px;
    box-shadow:
      -10px -10px 22px var(--lx-highlight-raised),
      10px 10px 22px var(--lx-shadow-raised);

    .eyebrow {
      margin: 0 0 6px;
      font-size: 11px;
      font-weight: 700;
      color: var(--lx-link);
      letter-spacing: 0.16em;
    }

    h1 {
      margin: 0;
      font-size: clamp(22px, 3vw, 30px);
      color: var(--lx-text);
    }

    p:not(.eyebrow) {
      margin: 8px 0 0;
      font-size: 13px;
      color: var(--lx-text-secondary);
    }
  }

  .hero-badge,
  .safety-note {
    display: inline-flex;
    gap: 7px;
    align-items: center;
    font-size: 12px;
    color: var(--lx-text-secondary);
  }

  .catalog-alert {
    margin-bottom: 16px;
  }

  .card-heading,
  .model-title-row {
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
  }

  .card-heading h2 {
    margin: 0;
    font-size: 17px;
    color: var(--lx-text);
  }

  .card-heading p {
    margin: 4px 0 0;
    font-size: 12px;
    color: var(--lx-text-secondary);
  }

  .model-upload :deep(.el-upload-dragger) {
    min-height: 190px;
    background: var(--lx-surface);
    border: 0;
    border-radius: 16px;
    box-shadow:
      inset 4px 4px 9px var(--lx-shadow-inset),
      inset -4px -4px 9px var(--lx-highlight-inset);
  }

  .model-upload :deep(.el-upload-dragger:hover) {
    color: var(--lx-link);
  }

  .upload-tip,
  .hash {
    font-size: 11px;
    color: var(--lx-text-secondary);
  }

  .staged-file {
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
    padding: 12px;
    margin-top: 14px;
    background: var(--lx-elevated);
    border-radius: 12px;

    strong,
    span {
      display: block;
    }

    span {
      margin-top: 4px;
      font-size: 11px;
      color: var(--lx-text-secondary);
    }
  }

  .safety-note {
    margin: 16px 0 0;
    line-height: 1.5;
  }

  .model-list {
    display: grid;
    gap: 12px;
  }

  .model-item {
    display: flex;
    gap: 12px;
    align-items: center;
    padding: 14px;
    background: var(--lx-surface);
    border-radius: 14px;
    box-shadow:
      inset 2px 2px 5px var(--lx-shadow-inset),
      inset -2px -2px 5px var(--lx-highlight-inset);
  }

  .model-avatar {
    display: grid;
    flex: 0 0 52px;
    place-items: center;
    width: 52px;
    height: 52px;
    color: var(--lx-link);
    background: var(--lx-elevated);
    border-radius: 14px;
  }

  .model-copy {
    flex: 1;
    min-width: 0;

    strong {
      overflow: hidden;
      color: var(--lx-text);
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    p {
      margin: 4px 0 0;
      font-size: 12px;
      color: var(--lx-text-secondary);
    }
  }

  .model-actions {
    display: flex;
    flex-shrink: 0;
    gap: 8px;
  }

  @media (width <= 640px) {
    .live2d-hero {
      flex-direction: column;
      align-items: flex-start;
      padding: 20px;
    }

    .model-item {
      flex-wrap: wrap;
      align-items: flex-start;
    }

    .model-copy {
      width: calc(100% - 68px);
    }

    .model-actions {
      width: 100%;

      .el-button {
        flex: 1;
      }
    }
  }
</style>
