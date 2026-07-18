<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { FirstAidKit, Box, Monitor, DataBoard } from '@element-plus/icons-vue'
import { useVisitStore } from '@/stores/visit'
import { useNotificationStore } from '@/stores/notification'
import { useAuthStore } from '@/stores/auth'
import * as pharmacyApi from '@/api/pharmacy'
import * as dispatchApi from '@/api/dispatch'

const router = useRouter()
const visitStore = useVisitStore()
const notifyStore = useNotificationStore()
const auth = useAuthStore()

const pendingRxCount = ref(0)
const queueCount = ref(0)

onMounted(function () {
  void Promise.allSettled([
    visitStore.fetchList(),
    notifyStore.fetchEvents(),
    pharmacyApi.listPrescriptions('PENDING').then(function (r) { pendingRxCount.value = r.length }),
    dispatchApi.getBoard().then(function (r) { queueCount.value = r.length })
  ])
});</script>

<template>
  <div>
    <h2 class="page-title">工作台</h2>
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card" @click="router.push('/visits')">
          <div class="stat-icon"><el-icon :size="22"><component :is="FirstAidKit" /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ visitStore.visits.length }} <small>条</small></div>
            <div class="stat-title">门诊就诊</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card" @click="router.push('/pharmacy/prescriptions')">
          <div class="stat-icon" style="background:#fef3e2;color:#e6a23c"><el-icon :size="22"><component :is="Box" /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ pendingRxCount }} <small>个</small></div>
            <div class="stat-title">待发药</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card" @click="router.push('/dispatch')">
          <div class="stat-icon" style="background:#e8f5e9;color:#67c23a"><el-icon :size="22"><component :is="Monitor" /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ queueCount }} <small>个</small></div>
            <div class="stat-title">排队待检</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card" @click="router.push('/lab/requisitions')">
          <div class="stat-icon" style="background:#eaf1ff;color:#409eff"><el-icon :size="22"><component :is="DataBoard" /></el-icon></div>
          <div class="stat-body">
            <div class="stat-value">{{ visitStore.visits.length }} <small>条</small></div>
            <div class="stat-title">检验申请</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><div class="card-title">👤 当前用户</div></template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="用户名">{{ auth.username || '-' }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ (auth.roles || []).join(' | ') || '-' }}</el-descriptions-item>
            <el-descriptions-item label="认证方式">{{ auth.enabled ? '自管 JWT' : '本地开发(模拟)' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><div class="card-title">⚙ 系统概览</div></template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="架构形态">模块化单体 + 通知/文件抽出服务</el-descriptions-item>
            <el-descriptions-item label="业务模块">9 个(临床/药事/医技/报告/预约/排队/患者/平台/IAM)</el-descriptions-item>
            <el-descriptions-item label="前端页面">11 个(Vue3 + Element Plus)</el-descriptions-item>
            <el-descriptions-item label="中间件">PostgreSQL / MinIO / RabbitMQ / Redis</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.page-title { margin: 0 0 16px; font-size: 18px; }
.stat-card { cursor: pointer; }
.stat-card :deep(.el-card__body) { display: flex; align-items: center; gap: 14px; }
.stat-icon {
  width: 44px; height: 44px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  background: #eaf1ff; color: #2c6bed;
}
.stat-value { font-size: 22px; font-weight: 700; }
.stat-value small { font-size: 12px; font-weight: 400; color: #888; }
.stat-title { font-size: 13px; color: #888; }
.card-title { font-weight: 600; font-size: 14px; }
</style>
