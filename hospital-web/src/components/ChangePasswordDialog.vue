<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import * as authApi from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const visible = ref(false)
const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 4, message: '密码长度至少 4 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    {
      validator: (_rule: any, value: string, callback: any) => {
        if (value !== form.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}

function open() {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  visible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await authApi.changePassword(form.oldPassword, form.newPassword)
      ElMessage.success('密码已修改,请重新登录')
      visible.value = false
      // 改密成功后强制重新登录(清除当前 token,跳登录页)
      auth.logout()
    } catch {
      ElMessage.error('旧密码错误或修改失败')
    } finally {
      loading.value = false
    }
  })
}

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" title="修改密码" width="400px" :close-on-click-modal="false">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="旧密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" show-password />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>
