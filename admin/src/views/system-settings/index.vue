<!-- 系统设置（站点 / 存储 / SMTP） -->
<template>
  <div class="system-settings-page art-full-height" v-loading="loading">
    <ElForm ref="formRef" :model="form" :rules="rules" label-width="130px" style="max-width: 760px">
      <ElCard class="mb-4" shadow="never">
        <template #header>{{ $t('systemSettings.site.title') }}</template>
        <ElFormItem :label="$t('systemSettings.site.name')" prop="site.name">
          <ElInput
            v-model="form['site.name']"
            :placeholder="$t('systemSettings.site.namePlaceholder')"
            maxlength="50"
            show-word-limit
          />
        </ElFormItem>
        <ElFormItem :label="$t('systemSettings.site.url')" prop="site.url">
          <ElInput v-model.trim="form['site.url']" placeholder="https://love.lxii.cc" />
          <div class="settings-hint">{{ $t('systemSettings.site.urlHint') }}</div>
        </ElFormItem>
        <ElFormItem :label="$t('systemSettings.site.logo')" prop="site.logo">
          <ElInput
            v-model.trim="form['site.logo']"
            :placeholder="$t('systemSettings.site.logoPlaceholder')"
          />
        </ElFormItem>
        <ElFormItem :label="$t('systemSettings.site.description')" prop="site.description">
          <ElInput v-model="form['site.description']" type="textarea" :rows="2" />
        </ElFormItem>
      </ElCard>

      <ElCard class="mb-4" shadow="never">
        <template #header>{{ $t('systemSettings.storage.title') }}</template>
        <ElFormItem :label="$t('systemSettings.storage.driver')">
          <span class="settings-readonly">{{ $t('systemSettings.storage.local') }}</span>
          <div class="settings-hint">{{ $t('systemSettings.storage.hint') }}</div>
        </ElFormItem>
      </ElCard>

      <ElCard class="mb-4" shadow="never">
        <template #header>{{ $t('systemSettings.smtp.title') }}</template>
        <ElFormItem :label="$t('systemSettings.smtp.host')" prop="smtp.host">
          <ElInput v-model.trim="form['smtp.host']" placeholder="smtp.example.com" />
        </ElFormItem>
        <ElFormItem :label="$t('systemSettings.smtp.port')" prop="smtp.port">
          <ElInput v-model.trim="form['smtp.port']" placeholder="465 / 587" />
        </ElFormItem>
        <ElFormItem :label="$t('systemSettings.smtp.username')" prop="smtp.username">
          <ElInput v-model.trim="form['smtp.username']" />
        </ElFormItem>
        <ElFormItem :label="$t('systemSettings.smtp.password')" prop="smtp.password">
          <ElInput
            v-model="form['smtp.password']"
            type="password"
            show-password
            :placeholder="
              passwordSet
                ? $t('systemSettings.smtp.passwordSet')
                : $t('systemSettings.smtp.passwordUnset')
            "
          />
        </ElFormItem>
        <ElFormItem :label="$t('systemSettings.smtp.from')" prop="smtp.from">
          <ElInput
            v-model.trim="form['smtp.from']"
            :placeholder="$t('systemSettings.smtp.fromPlaceholder')"
          />
        </ElFormItem>
        <ElFormItem :label="$t('systemSettings.smtp.ssl')">
          <ElSwitch v-model="sslEnabled" />
        </ElFormItem>
        <ElFormItem>
          <ElButton :loading="testing" @click="openTestDialog">
            {{ $t('systemSettings.smtp.test') }}
          </ElButton>
          <span class="settings-hint ml-3">{{ $t('systemSettings.smtp.testHint') }}</span>
        </ElFormItem>
      </ElCard>

      <div class="settings-actions">
        <ElButton type="primary" :loading="saving" @click="handleSave">
          {{ $t('systemSettings.save') }}
        </ElButton>
        <ElButton @click="loadSettings">{{ $t('common.reset') }}</ElButton>
      </div>
    </ElForm>

    <!-- 测试发信 -->
    <ElDialog
      v-model="testVisible"
      :title="$t('systemSettings.smtp.testTitle')"
      width="440px"
      align-center
    >
      <ElForm ref="testFormRef" :model="testForm" :rules="testRules" label-width="90px">
        <ElFormItem :label="$t('systemSettings.smtp.testTo')" prop="to">
          <ElInput
            v-model.trim="testForm.to"
            :placeholder="$t('systemSettings.smtp.testToPlaceholder')"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="testVisible = false">{{ $t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="testing" @click="handleSmtpTest">
          {{ $t('systemSettings.smtp.testSend') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { fetchSettings, updateSettings, sendSmtpTest } from '@/api/admin'
  import { useSiteStore } from '@/store/modules/site'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'SystemSettings' })

  const { t } = useI18n()

  /**
   * 与服务端 settingKeys 白名单保持一致。
   * storage.local_dir / push.provider / OSS 相关键均已废弃（服务端不再读取），不出现在此表单。
   */
  const SETTING_KEYS = [
    'site.name',
    'site.url',
    'site.logo',
    'site.description',
    'storage.driver',
    'smtp.host',
    'smtp.port',
    'smtp.username',
    'smtp.password',
    'smtp.from',
    'smtp.ssl'
  ]

  /** 服务端对已设置的 SMTP 密码返回该占位符，回传同值表示不修改 */
  const PLACEHOLDER = '__set__'

  const loading = ref(false)
  const saving = ref(false)
  const testing = ref(false)
  const passwordSet = ref(false)
  const testVisible = ref(false)

  const formRef = ref<FormInstance>()
  const testFormRef = ref<FormInstance>()

  const createEmpty = (): Record<string, string> =>
    SETTING_KEYS.reduce((acc, k) => ((acc[k] = ''), acc), {} as Record<string, string>)

  const form = reactive<Record<string, string>>(createEmpty())
  const testForm = reactive({ to: '' })

  // SSL 以字符串 "true"/"false" 存储，界面用布尔开关
  const sslEnabled = computed({
    get: () => form['smtp.ssl'] === 'true',
    set: (v: boolean) => {
      form['smtp.ssl'] = v ? 'true' : 'false'
    }
  })

  /** http(s) URL 校验，允许留空 */
  const validateUrl = (_r: unknown, value: string, callback: (e?: Error) => void): void => {
    if (!value) return callback()
    try {
      const u = new URL(value)
      if (u.protocol !== 'http:' && u.protocol !== 'https:') {
        return callback(new Error(t('systemSettings.rules.url')))
      }
      callback()
    } catch {
      callback(new Error(t('systemSettings.rules.url')))
    }
  }

  /** SMTP 端口：1-65535 整数；主机填了则必填 */
  const validatePort = (_r: unknown, value: string, callback: (e?: Error) => void): void => {
    if (!value) {
      if (form['smtp.host']) return callback(new Error(t('systemSettings.rules.portRequired')))
      return callback()
    }
    if (!/^\d+$/.test(value)) return callback(new Error(t('systemSettings.rules.port')))
    const n = Number(value)
    if (n < 1 || n > 65535) return callback(new Error(t('systemSettings.rules.port')))
    callback()
  }

  /** 主机填了则用户名必填 */
  const validateUsername = (_r: unknown, value: string, callback: (e?: Error) => void): void => {
    if (!value && form['smtp.host']) {
      return callback(new Error(t('systemSettings.rules.usernameRequired')))
    }
    callback()
  }

  const rules = computed<FormRules>(() => ({
    'site.name': [
      { required: true, message: t('systemSettings.rules.siteName'), trigger: 'blur' },
      { max: 50, message: t('systemSettings.rules.siteNameMax'), trigger: 'blur' }
    ],
    'site.url': [{ validator: validateUrl, trigger: 'blur' }],
    'site.logo': [{ validator: validateUrl, trigger: 'blur' }],
    'smtp.port': [{ validator: validatePort, trigger: 'blur' }],
    'smtp.username': [{ validator: validateUsername, trigger: 'blur' }],
    'smtp.from': [{ type: 'email', message: t('systemSettings.rules.from'), trigger: 'blur' }]
  }))

  const testRules = computed<FormRules>(() => ({
    to: [
      { required: true, message: t('systemSettings.rules.testToRequired'), trigger: 'blur' },
      { type: 'email', message: t('systemSettings.rules.testTo'), trigger: 'blur' }
    ]
  }))

  const loadSettings = async () => {
    loading.value = true
    try {
      const data = await fetchSettings()
      Object.assign(form, createEmpty(), pickKnownKeys(data))
      // 后端对已设置的密码返回占位符，此处置空并记录状态
      passwordSet.value = form['smtp.password'] === PLACEHOLDER
      form['smtp.password'] = ''
      // 仅支持本地存储：强制归一化驱动，避免遗留的 oss/cos/kodo 值
      form['storage.driver'] = 'local'
      formRef.value?.clearValidate()
    } catch {
      ElMessage.error(t('systemSettings.loadFailed'))
    } finally {
      loading.value = false
    }
  }

  /** 只取本页管理的键，避免把服务端遗留的废弃键回写 */
  const pickKnownKeys = (data: Api.Admin.Settings): Record<string, string> => {
    const out: Record<string, string> = {}
    SETTING_KEYS.forEach((k) => {
      if (data && k in data) out[k] = data[k] ?? ''
    })
    return out
  }

  const handleSave = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return

    saving.value = true
    try {
      const payload: Api.Admin.Settings = { ...form, 'storage.driver': 'local' }
      // smtp.password：留空且原本已设置 -> 回传占位符，后端跳过修改
      if (!form['smtp.password']) {
        payload['smtp.password'] = passwordSet.value ? PLACEHOLDER : ''
      }
      await updateSettings(payload)
      ElMessage.success(t('common.saveSuccess'))
      // 同步站点信息
      useSiteStore().setSiteInfo({
        name: form['site.name'],
        logo: form['site.logo'],
        description: form['site.description']
      })
      loadSettings()
    } catch (error) {
      const msg = error instanceof Error ? error.message : ''
      ElMessage.error(msg || t('systemSettings.saveFailed'))
    } finally {
      saving.value = false
    }
  }

  const openTestDialog = () => {
    testForm.to = form['smtp.from'] || ''
    testVisible.value = true
    testFormRef.value?.clearValidate()
  }

  const handleSmtpTest = async () => {
    if (!testFormRef.value) return
    const valid = await testFormRef.value.validate().catch(() => false)
    if (!valid) return

    testing.value = true
    try {
      await sendSmtpTest(testForm.to)
      ElMessage.success(t('systemSettings.smtp.testSuccess'))
      testVisible.value = false
    } catch (error) {
      // 失败时把服务端返回的 message 原样呈现，便于排查 SMTP 配置
      const msg = error instanceof Error ? error.message : ''
      ElMessage.error(msg || t('systemSettings.smtp.testFailed'))
    } finally {
      testing.value = false
    }
  }

  onMounted(loadSettings)
</script>

<style lang="scss" scoped>
  .system-settings-page {
    .settings-actions {
      margin-left: 130px;
    }

    .settings-hint {
      font-size: 12px;
      line-height: 1.6;
      color: var(--art-gray-500);
    }

    .settings-readonly {
      font-size: 14px;
      color: var(--art-gray-800);
    }
  }
</style>
