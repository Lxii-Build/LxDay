<!-- 通知 - 下发 -->
<template>
  <div class="notify-send">
    <ElForm
      ref="formRef"
      :model="formData"
      :rules="rules"
      label-width="100px"
      style="max-width: 640px"
    >
      <ElFormItem :label="$t('notify.send.template')">
        <ElSelect
          v-model="templateCode"
          :placeholder="$t('notify.send.templatePlaceholder')"
          clearable
          style="width: 100%"
          @change="applyTemplate"
        >
          <ElOption
            v-for="tpl in templates"
            :key="tpl.code"
            :label="`${tpl.title}（${tpl.code}）`"
            :value="tpl.code"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem :label="$t('notify.send.title')" prop="title">
        <ElInput v-model.trim="formData.title" :placeholder="$t('notify.send.titlePlaceholder')" />
      </ElFormItem>
      <ElFormItem :label="$t('notify.send.body')" prop="body">
        <ElInput
          v-model="formData.body"
          type="textarea"
          :rows="4"
          :placeholder="$t('notify.send.bodyPlaceholder')"
        />
      </ElFormItem>
      <ElFormItem :label="$t('notify.send.target')">
        <ElSelect v-model="targetMode" style="width: 100%">
          <ElOption :label="$t('notify.send.targetAll')" value="all" />
          <ElOption :label="$t('notify.send.targetUsers')" value="uid" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem v-if="targetMode === 'uid'" :label="$t('notify.send.uids')" prop="uids">
        <ElInput v-model.trim="formData.uids" :placeholder="$t('notify.send.uidsPlaceholder')" />
      </ElFormItem>
      <ElFormItem>
        <ElButton type="primary" :loading="sending" @click="handleSend">
          {{ $t('notify.send.submit') }}
        </ElButton>
      </ElFormItem>
    </ElForm>
  </div>
</template>

<script setup lang="ts">
  import { useI18n } from 'vue-i18n'
  import { fetchNotifyTemplates, sendNotify } from '@/api/admin'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'NotifySend' })

  const { t } = useI18n()

  const templates = ref<Api.Admin.NotifyTemplate[]>([])
  const formRef = ref<FormInstance>()
  const sending = ref(false)

  /** 目标模式：all 全站广播 / uid 指定用户 */
  const targetMode = ref<'all' | 'uid'>('all')
  const templateCode = ref('')

  const formData = reactive({
    title: '',
    body: '',
    /** 指定用户时的 ID 列表，逗号分隔 */
    uids: ''
  })

  /** 指定用户时至少要有一个合法的正整数 ID */
  const validateUids = (_rule: unknown, value: string, callback: (e?: Error) => void): void => {
    if (targetMode.value !== 'uid') return callback()

    const parts = (value || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)

    if (!parts.length) return callback(new Error(t('notify.rules.uidsRequired')))
    if (parts.some((p) => !/^\d+$/.test(p) || Number(p) <= 0)) {
      return callback(new Error(t('notify.rules.uidsInvalid')))
    }
    callback()
  }

  const rules = computed<FormRules>(() => ({
    title: [{ required: true, message: t('notify.rules.title'), trigger: 'blur' }],
    body: [{ required: true, message: t('notify.rules.body'), trigger: 'blur' }],
    uids: [{ validator: validateUids, trigger: 'blur' }]
  }))

  const applyTemplate = (code: string) => {
    const tpl = templates.value.find((item) => item.code === code)
    if (tpl) {
      formData.title = tpl.title
      formData.body = tpl.body
    }
  }

  /** 组装服务端约定的 target：all 或 uid:1,2,3 */
  const buildTarget = (): string => {
    if (targetMode.value === 'all') return 'all'
    const ids = formData.uids
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .join(',')
    return `uid:${ids}`
  }

  const handleSend = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return

    sending.value = true
    try {
      const res = await sendNotify({
        title: formData.title,
        body: formData.body,
        target: buildTarget(),
        template_code: templateCode.value || undefined
      })
      ElMessage.success(t('notify.send.success', { count: res?.sent ?? 0 }))
      formData.title = ''
      formData.body = ''
      formData.uids = ''
      templateCode.value = ''
      targetMode.value = 'all'
      formRef.value.clearValidate()
    } finally {
      sending.value = false
    }
  }

  onMounted(async () => {
    templates.value = await fetchNotifyTemplates()
  })
</script>
