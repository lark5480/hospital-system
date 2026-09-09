<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { AxiosError } from 'axios'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import * as authApi from '@/api/auth'
import { PASSWORD_MAX_LENGTH, PASSWORD_MIN_LENGTH, validateNewPassword } from '@/api/auth'
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
  // R-10: 新密码复杂度由后端强校验(8~64 位 + 字母数字混合),这里只做必填,
  //       详细规则用 validateNewPassword 在下方即时提示,文案与后端一致。
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请确认新密码', trigger: 'blur' }]
}

/** R-10: 新密码即时校验(镜像后端策略),避免提交后才被 400 拒绝。 */
const newPasswordError = computed(() => validateNewPassword(form.newPassword, form.oldPassword))
const confirmError = computed(() =>
  form.confirmPassword && form.confirmPassword !== form.newPassword ? '两次输入的密码不一致' : ''
)

function open() {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  visible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (newPasswordError.value) {
    ElMessage.warning(newPasswordError.value)
    return
  }
  if (confirmError.value) {
    ElMessage.warning(confirmError.value)
    return
  }
  loading.value = true
  try {
    await authApi.changePassword(form.oldPassword, form.newPassword)
    ElMessage.success('密码已修改,请重新登录')
    visible.value = false
    // 改密成功后强制重新登录(清除当前 token,跳登录页)
    auth.logout()
  } catch (e: unknown) {
    const data = e instanceof AxiosError ? e.response?.data : undefined
    const reason = typeof data === 'object' && data !== null ? (data as { error?: string }).error : undefined
    ElMessage.error(reason || '旧密码错误或修改失败')
  } finally {
    loading.value = false
  }
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
    <p class="policy">
      新密码要求:{{ PASSWORD_MIN_LENGTH }}~{{ PASSWORD_MAX_LENGTH }} 位,且同时包含字母和数字,
      不能与旧密码相同,不能是 123456 等常见弱口令。
    </p>
    <p v-if="newPasswordError" class="hint error">{{ newPasswordError }}</p>
    <p v-else-if="confirmError" class="hint error">{{ confirmError }}</p>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.policy {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-secondary);
}
.hint {
  margin: 0 0 var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--el-color-danger);
}
</style>
