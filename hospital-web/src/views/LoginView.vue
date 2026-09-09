<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { AxiosError } from 'axios'
import { ACCOUNT_LOCKED_MESSAGE, type LockedResponse } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useTabsStore } from '@/stores/tabs'

const router = useRouter()
const auth = useAuthStore()
const tabs = useTabsStore()

const form = reactive({ phone: '', password: '' })
const loading = ref(false)

async function handleLogin() {
  if (!form.phone.trim() || !form.password) {
    ElMessage.warning('请输入手机号和密码')
    return
  }
  loading.value = true
  try {
    // 复用 store 的 doLogin:它会完整写入 token/姓名/科室/角色/权限并 persist 到 localStorage
    await auth.doLogin(form.phone.trim(), form.password)
    tabs.reset()
    ElMessage.success('登录成功')

    // 清空表单,避免退出后下次登录残留密码
    form.phone = ''
    form.password = ''

    // R-10: 仍在用系统默认口令登录 → 强制先改密;否则直接进系统
    router.push(auth.mustChangePassword ? '/change-password' : '/dashboard')
  } catch (e: unknown) {
    // R-12: 连续失败 5 次后后端返回 429(账号锁定),需与 401 区分提示
    const status = e instanceof AxiosError ? e.response?.status : undefined
    if (status === 429) {
      const data = (e as AxiosError<LockedResponse>).response?.data
      ElMessage.error(data?.error || ACCOUNT_LOCKED_MESSAGE)
    } else if (status === 401) {
      ElMessage.error('手机号或密码错误')
    } else {
      ElMessage.error('登录失败,请稍后重试')
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-bg">
    <el-card class="login-card" shadow="always">
      <div class="brand">
        <svg class="brand-mark" viewBox="0 0 24 24" width="32" height="32" fill="none" aria-hidden="true">
          <rect x="2.5" y="2.5" width="19" height="19" rx="5" fill="currentColor" />
          <path d="M12 7v10M7 12h10" stroke="#fff" stroke-width="2" stroke-linecap="round" />
        </svg>
        <h1 class="title">医院信息系统</h1>
        <p class="subtitle">Hospital Information System</p>
      </div>
      <el-form @submit.prevent="handleLogin">
        <el-form-item>
          <el-input v-model="form.phone" placeholder="手机号" prefix-icon="Iphone" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            prefix-icon="Lock"
            size="large"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" :loading="loading" style="width: 100%" @click="handleLogin">
            登录
          </el-button>
        </el-form-item>
      </el-form>
      <p class="tip">默认密码 = 123456</p>
      <p class="forgot">忘记密码?请联系管理员重置为默认密码 123456</p>
    </el-card>
  </div>
</template>

<style scoped>
.login-bg {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: linear-gradient(135deg, var(--brand-700) 0%, var(--brand-900) 100%);
}
.login-card {
  width: 380px;
  padding: var(--sp-5);
  border-radius: var(--radius-lg);
}
.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: var(--sp-6);
}
.brand-mark {
  color: var(--brand);
  margin-bottom: var(--sp-3);
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
  letter-spacing: 0.04em;
  color: var(--text-placeholder);
}
.tip {
  text-align: center;
  color: var(--text-secondary);
  font-size: var(--fs-xs);
  margin: var(--sp-2) 0 0;
}
.forgot {
  text-align: center;
  color: var(--text-placeholder);
  font-size: var(--fs-xs);
  margin: var(--sp-2) 0 0;
}
</style>
