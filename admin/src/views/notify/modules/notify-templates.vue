<!-- 通知 - 模板管理 -->
<template>
  <div class="notify-templates">
    <div class="mb-3 flex justify-end">
      <ElButton :loading="loading" @click="loadTemplates">刷新</ElButton>
      <ElButton type="primary" @click="openDialog()">新增模板</ElButton>
    </div>

    <ElTable v-loading="loading" :data="templates" border>
      <ElTableColumn prop="code" label="Code" width="180" />
      <ElTableColumn prop="title" label="标题" min-width="160" />
      <ElTableColumn prop="body" label="内容" min-width="240" show-overflow-tooltip />
      <ElTableColumn label="启用" width="90">
        <template #default="{ row }">
          <ElTag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="updated_at" label="更新时间" width="180" />
      <ElTableColumn label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <ElButton type="primary" link @click="openDialog(row)">编辑</ElButton>
        </template>
      </ElTableColumn>
    </ElTable>

    <ElDialog v-model="dialogVisible" :title="isEdit ? '编辑模板' : '新增模板'" width="520px" align-center>
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="80px">
        <ElFormItem label="Code" prop="code">
          <ElInput v-model.trim="formData.code" :disabled="isEdit" placeholder="模板唯一标识" />
        </ElFormItem>
        <ElFormItem label="标题" prop="title">
          <ElInput v-model.trim="formData.title" />
        </ElFormItem>
        <ElFormItem label="内容" prop="body">
          <ElInput v-model="formData.body" type="textarea" :rows="4" />
        </ElFormItem>
        <ElFormItem label="启用">
          <ElSwitch v-model="formData.enabled" :active-value="1" :inactive-value="0" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { fetchNotifyTemplates, upsertNotifyTemplate } from '@/api/admin'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'NotifyTemplates' })

  const templates = ref<Api.Admin.NotifyTemplate[]>([])
  const loading = ref(false)
  const dialogVisible = ref(false)
  const isEdit = ref(false)
  const submitting = ref(false)
  const formRef = ref<FormInstance>()

  const formData = reactive({
    code: '',
    title: '',
    body: '',
    enabled: 1
  })

  const rules = reactive<FormRules>({
    code: [{ required: true, message: '请输入 Code', trigger: 'blur' }],
    title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
    body: [{ required: true, message: '请输入内容', trigger: 'blur' }]
  })

  const loadTemplates = async () => {
    loading.value = true
    try {
      templates.value = await fetchNotifyTemplates()
    } finally {
      loading.value = false
    }
  }

  const openDialog = (row?: Api.Admin.NotifyTemplate) => {
    isEdit.value = !!row
    Object.assign(formData, {
      code: row?.code ?? '',
      title: row?.title ?? '',
      body: row?.body ?? '',
      enabled: row?.enabled ?? 1
    })
    dialogVisible.value = true
  }

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    submitting.value = true
    try {
      await upsertNotifyTemplate({ ...formData })
      ElMessage.success('保存成功')
      dialogVisible.value = false
      loadTemplates()
    } finally {
      submitting.value = false
    }
  }

  onMounted(loadTemplates)
</script>
