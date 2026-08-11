<!-- 管理员管理（仅超级管理员） -->
<template>
  <div class="admin-manage-page art-full-height">
    <ElCard class="art-table-card" shadow="never">
      <div class="mb-3 flex justify-end">
        <ElButton :loading="loading" @click="loadAdmins">刷新</ElButton>
        <ElButton type="primary" @click="openDialog">新增管理员</ElButton>
      </div>

      <ElTable v-loading="loading" :data="admins" border>
        <ElTableColumn prop="id" label="ID" width="80" />
        <ElTableColumn prop="username" label="用户名" min-width="160" />
        <ElTableColumn prop="email" label="邮箱" min-width="180">
          <template #default="{ row }">{{ row.email || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="角色" width="140">
          <template #default="{ row }">
            <ElTag :type="row.role === 'super' ? 'danger' : 'primary'">
              {{ row.role === 'super' ? '超级管理员' : '管理员' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '正常' : '禁用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="首次改密" width="110">
          <template #default="{ row }">
            <ElTag v-if="row.must_change" type="warning">待修改</ElTag>
            <span v-else>-</span>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="dialogVisible" title="新增管理员" width="480px" align-center>
      <ElForm ref="formRef" :model="formData" :rules="rules" label-width="80px">
        <ElFormItem label="用户名" prop="username">
          <ElInput v-model.trim="formData.username" />
        </ElFormItem>
        <ElFormItem label="密码" prop="password">
          <ElInput v-model.trim="formData.password" type="password" show-password placeholder="至少 6 位" />
        </ElFormItem>
        <ElFormItem label="角色" prop="role">
          <ElSelect v-model="formData.role" style="width: 100%">
            <ElOption label="管理员" value="admin" />
            <ElOption label="超级管理员" value="super" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="邮箱" prop="email">
          <ElInput v-model.trim="formData.email" placeholder="选填" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit">创建</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { fetchAdmins, createAdmin } from '@/api/admin'
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

  defineOptions({ name: 'AdminManage' })

  const admins = ref<Api.Admin.AdminItem[]>([])
  const loading = ref(false)
  const dialogVisible = ref(false)
  const submitting = ref(false)
  const formRef = ref<FormInstance>()

  const formData = reactive<Api.Admin.AdminCreateParams>({
    username: '',
    password: '',
    role: 'admin',
    email: ''
  })

  const rules = reactive<FormRules>({
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    password: [
      { required: true, message: '请输入密码', trigger: 'blur' },
      { min: 6, message: '密码至少 6 位', trigger: 'blur' }
    ],
    role: [{ required: true, message: '请选择角色', trigger: 'change' }]
  })

  const loadAdmins = async () => {
    loading.value = true
    try {
      admins.value = await fetchAdmins()
    } finally {
      loading.value = false
    }
  }

  const openDialog = () => {
    Object.assign(formData, { username: '', password: '', role: 'admin', email: '' })
    dialogVisible.value = true
  }

  const handleSubmit = async () => {
    if (!formRef.value) return
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    submitting.value = true
    try {
      await createAdmin({ ...formData })
      ElMessage.success('创建成功')
      dialogVisible.value = false
      loadAdmins()
    } finally {
      submitting.value = false
    }
  }

  onMounted(loadAdmins)
</script>
