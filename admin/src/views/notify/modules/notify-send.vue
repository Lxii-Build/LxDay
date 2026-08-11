<!-- 通知 - 下发 -->
<template>
  <div class="notify-send">
    <ElForm ref="formRef" :model="formData" :rules="rules" label-width="100px" style="max-width: 640px">
      <ElFormItem label="使用模板">
        <ElSelect
          v-model="formData.template_code"
          placeholder="可选，选择后自动填充标题与内容"
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
      <ElFormItem label="标题" prop="title">
        <ElInput v-model.trim="formData.title" placeholder="通知标题" />
      </ElFormItem>
      <ElFormItem label="内容" prop="body">
        <ElInput v-model="formData.body" type="textarea" :rows="4" placeholder="通知内容" />
      </ElFormItem>
      <ElFormItem label="目标">
        <ElInput v-model.trim="formData.target" placeholder="留空默认全部用户（all）" />
      </ElFormItem>
      <ElFormItem>
        <ElButton type="primary" :loading="sending" @click="handleSend">下发通知</ElButton>
      </ElFormItem>
    </ElForm>
  </div>
</template>

<script setup lang="ts">
  import { fetchNotifyTemplates, sendNotify } from '@/api/admin'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'NotifySend' })

  const templates = ref<Api.Admin.NotifyTemplate[]>([])
  const formRef = ref<FormInstance>()
  const sending = ref(false)

  const formData = reactive<Api.Admin.NotifySendParams>({
    title: '',
    body: '',
    target: '',
    template_code: ''
  })

  const rules = reactive<FormRules>({
    title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
    body: [{ required: true, message: '请输入内容', trigger: 'blur' }]
  })

  const applyTemplate = (code: string) => {
    const tpl = templates.value.find((t) => t.code === code)
    if (tpl) {
      formData.title = tpl.title
      formData.body = tpl.body
    }
  }

  const handleSend = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    sending.value = true
    try {
      const res = await sendNotify({ ...formData })
      ElMessage.success(`下发成功，已推送 ${res.sent} 人`)
      formData.title = ''
      formData.body = ''
      formData.target = ''
      formData.template_code = ''
    } finally {
      sending.value = false
    }
  }

  onMounted(async () => {
    templates.value = await fetchNotifyTemplates()
  })
</script>
