<!-- 系统设置（站点 / 存储 / SMTP / 推送） -->
<template>
  <div class="system-settings-page art-full-height" v-loading="loading">
    <ElForm label-width="120px" style="max-width: 760px">
      <ElCard class="mb-4" shadow="never">
        <template #header>站点信息</template>
        <ElFormItem label="站点名称">
          <ElInput v-model="form['site.name']" placeholder="林曦日记" />
        </ElFormItem>
        <ElFormItem label="站点 LOGO">
          <ElInput v-model="form['site.logo']" placeholder="LOGO 图片地址" />
        </ElFormItem>
        <ElFormItem label="站点描述">
          <ElInput v-model="form['site.description']" type="textarea" :rows="2" />
        </ElFormItem>
      </ElCard>

      <ElCard class="mb-4" shadow="never">
        <template #header>存储配置</template>
        <ElFormItem label="存储驱动">
          <ElSelect v-model="form['storage.driver']" style="width: 220px" disabled>
            <ElOption label="本地存储 (local)" value="local" />
          </ElSelect>
          <span class="storage-tip">当前仅支持本地存储</span>
        </ElFormItem>
        <ElFormItem label="本地目录">
          <ElInput v-model="form['storage.local_dir']" placeholder="如 ./uploads" />
        </ElFormItem>
      </ElCard>

      <ElCard class="mb-4" shadow="never">
        <template #header>SMTP 邮件</template>
        <ElFormItem label="主机">
          <ElInput v-model="form['smtp.host']" />
        </ElFormItem>
        <ElFormItem label="端口">
          <ElInput v-model="form['smtp.port']" placeholder="如 465 / 587" />
        </ElFormItem>
        <ElFormItem label="用户名">
          <ElInput v-model="form['smtp.username']" />
        </ElFormItem>
        <ElFormItem label="密码">
          <ElInput
            v-model="form['smtp.password']"
            type="password"
            show-password
            :placeholder="passwordSet ? '已设置，留空则不修改' : '未设置'"
          />
        </ElFormItem>
        <ElFormItem label="发件人">
          <ElInput v-model="form['smtp.from']" placeholder="发件邮箱地址" />
        </ElFormItem>
        <ElFormItem label="SSL">
          <ElSwitch v-model="sslEnabled" />
        </ElFormItem>
      </ElCard>

      <ElCard class="mb-4" shadow="never">
        <template #header>推送配置</template>
        <ElFormItem label="推送服务商">
          <ElInput v-model="form['push.provider']" placeholder="如 jpush / getui / none" />
        </ElFormItem>
      </ElCard>

      <div class="settings-actions">
        <ElButton type="primary" :loading="saving" @click="handleSave">保存设置</ElButton>
        <ElButton @click="loadSettings">重置</ElButton>
      </div>
    </ElForm>
  </div>
</template>

<!-- SCRIPT-PLACEHOLDER -->

<script setup lang="ts">
  import { fetchSettings, updateSettings } from '@/api/admin'
  import { useSiteStore } from '@/store/modules/site'
  import { ElMessage } from 'element-plus'

  defineOptions({ name: 'SystemSettings' })

  const SETTING_KEYS = [
    'site.name',
    'site.logo',
    'site.description',
    'storage.driver',
    'storage.local_dir',
    'smtp.host',
    'smtp.port',
    'smtp.username',
    'smtp.password',
    'smtp.from',
    'smtp.ssl',
    'push.provider'
  ]

  const PLACEHOLDER = '__set__'

  const loading = ref(false)
  const saving = ref(false)
  const passwordSet = ref(false)

  const createEmpty = (): Record<string, string> =>
    SETTING_KEYS.reduce((acc, k) => ((acc[k] = ''), acc), {} as Record<string, string>)

  const form = reactive<Record<string, string>>(createEmpty())

  // SSL 以字符串 "true"/"false" 存储，界面用布尔开关
  const sslEnabled = computed({
    get: () => form['smtp.ssl'] === 'true',
    set: (v: boolean) => {
      form['smtp.ssl'] = v ? 'true' : 'false'
    }
  })

  const loadSettings = async () => {
    loading.value = true
    try {
      const data = await fetchSettings()
      Object.assign(form, createEmpty(), data)
      // 后端对已设置的密码返回占位符，此处置空并记录状态
      passwordSet.value = form['smtp.password'] === PLACEHOLDER
      form['smtp.password'] = ''
      // 仅支持本地存储：强制归一化驱动，避免遗留的 oss/cos/kodo 值导致下拉为空
      form['storage.driver'] = 'local'
    } finally {
      loading.value = false
    }
  }

  const handleSave = async () => {
    saving.value = true
    try {
      const payload: Api.Admin.Settings = { ...form }
      // smtp.password：留空且原本已设置 -> 回传占位符，后端跳过修改
      if (!form['smtp.password']) {
        payload['smtp.password'] = passwordSet.value ? PLACEHOLDER : ''
      }
      await updateSettings(payload)
      ElMessage.success('保存成功')
      // 同步站点信息
      useSiteStore().setSiteInfo({
        name: form['site.name'],
        logo: form['site.logo'],
        description: form['site.description']
      })
      loadSettings()
    } finally {
      saving.value = false
    }
  }

  onMounted(loadSettings)
</script>

<style lang="scss" scoped>
  .system-settings-page {
    .settings-actions {
      margin-left: 120px;
    }

    .storage-tip {
      margin-left: 12px;
      font-size: 12px;
      color: var(--art-gray-500);
    }
  }
</style>
