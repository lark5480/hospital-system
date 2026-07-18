<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as authApi from '@/api/auth'
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
    const res = await authApi.login(form.phone.trim(), form.password)
    auth.token = res.token
    auth.username = res.username
    auth.name = res.name
    auth.roles = res.roles
    auth.authorities = res.authorities
    auth.authenticated = true
    tabs.reset()
    ElMessage.success('登录成功')

    // 清空表单,避免退出后下次登录残留密码
    form.phone = ''
    form.password = ''

    // 直接进系统,不强制改密(用户可从右上角主动改)
    router.push('/dashboard')
  } catch {
    ElMessage.error('用户名或密码错误')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-bg">
    <el-card class="login-card" shadow="always">
      <h1 class="title">医院信息系统</h1>
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
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  width: 380px;
  padding: 20px;
  border-radius: 8px;
}
.title {
  text-align: center;
  margin: 0 0 24px;
  font-size: 22px;
  color: #303133;
}
.tip {
  text-align: center;
  color: #909399;
  font-size: 12px;
  margin: 8px 0 0;
}
.forgot {
  text-align: center;
  color: #c0c4cc;
  font-size: 12px;
  margin: 8px 0 0;
}
</style>
