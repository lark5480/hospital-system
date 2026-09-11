<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AxiosError } from 'axios'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  changePassword,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  validateNewPassword
} from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }]
}

/** 即时校验:新密码不符合策略时立即给出中文原因,避免提交后才被后端 400 拒绝。 */
const newPasswordError = computed(() => validateNewPassword(form.newPassword, form.oldPassword))
const confirmError = computed(() =>
  form.confirmPassword && form.confirmPassword !== form.newPassword ? '两次输入的密码不一致' : ''
)

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
    await changePassword(form.oldPassword, form.newPassword)
    // R-10: 解除"强制改密"拦截(同时写回持久化缓存,刷新后不再被挡)
    auth.setMustChangePassword(false)
    // R-34: 后端改密后会吊销该用户全部旧 token,当前会话立即失效,必须重新登录
    ElMessage.success('密码修改成功,请使用新密码重新登录')
    auth.logout()
    await router.push('/login')
  } catch (e: unknown) {
    const data = e instanceof AxiosError ? e.response?.data : undefined
    const reason = typeof data === 'object' && data !== null ? (data as { error?: string }).error : undefined
    ElMessage.error(reason || '密码修改失败,请稍后重试')
  } finally {
    loading.value = false
  }
}

function handleLogout() {
  auth.logout()
}
</script>

<template>
  <div class="cp-bg">
    <el-card class="cp-card" shadow="always">
      <div class="brand">
        <h1 class="title">修改密码</h1>
        <p class="subtitle">当前账号仍在使用系统初始密码,请先设置新密码</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" @submit.prevent="handleSubmit">
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="请输入新密码" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="请再次输入新密码" />
        </el-form-item>
      </el-form>

      <ul class="policy">
        <li>长度 {{ PASSWORD_MIN_LENGTH }}~{{ PASSWORD_MAX_LENGTH }} 位;</li>
        <li>必须同时包含字母和数字;</li>
        <li>不能与旧密码相同;</li>
        <li>不能是 123456 等常见弱口令。</li>
      </ul>

      <p v-if="newPasswordError" class="hint error">{{ newPasswordError }}</p>
      <p v-else-if="confirmError" class="hint error">{{ confirmError }}</p>
      <p v-else-if="form.newPassword" class="hint ok">新密码符合安全要求</p>

      <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="handleSubmit">
        确认修改
      </el-button>
      <p class="logout" @click="handleLogout">退出登录</p>
    </el-card>
  </div>
</template>

<style scoped>
.cp-bg {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: linear-gradient(135deg, var(--brand-700) 0%, var(--brand-900) 100%);
}
.cp-card {
  width: 420px;
  padding: var(--sp-5);
  border-radius: var(--radius-lg);
}
.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: var(--sp-5);
}
.title {
  text-align: center;
  margin: 0;
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  color: var(--text-primary);
}
.subtitle {
  text-align: center;
  margin: var(--sp-1) 0 0;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}
.policy {
  margin: 0 0 var(--sp-3);
  padding-left: var(--sp-5);
  font-size: var(--fs-xs);
  line-height: 1.8;
  color: var(--text-secondary);
}
.hint {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-xs);
  text-align: center;
}
.hint.error {
  color: var(--el-color-danger);
}
.hint.ok {
  color: var(--el-color-success);
}
.logout {
  text-align: center;
  color: var(--text-placeholder);
  font-size: var(--fs-xs);
  margin: var(--sp-3) 0 0;
  cursor: pointer;
}
</style>
