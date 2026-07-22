<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { FirstAidKit, Box, Monitor, DataBoard, Tickets, Calendar, List, Reading, OfficeBuilding, Menu as MenuIcon, Folder, User, Setting, Bell } from '@element-plus/icons-vue'
import { useVisitStore } from '@/stores/visit'
import { useNotificationStore } from '@/stores/notification'
import { useAuthStore } from '@/stores/auth'
import * as pharmacyApi from '@/api/pharmacy'
import * as dispatchApi from '@/api/dispatch'

const router = useRouter()
const visitStore = useVisitStore()
const notifyStore = useNotificationStore()
const auth = useAuthStore()

// 工作台卡片配置
interface DashboardCard {
  key: string
  title: string
  icon: any
  color: string
  bgColor: string
  authority: string
  path: string
  count: number
}

const allCards = ref<DashboardCard[]>([])

// 最近5条通知
const recentNotifications = computed(() => notifyStore.items.slice(0, 5))

// 通知类型标签
function getNotifyTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    'VISIT_CREATED': '就诊',
    'PATIENT_CALLED': '叫号',
    'VISIT_PAID': '缴费',
    'VISIT_FINISHED': '完成'
  }
  return labels[type] || '通知'
}

// 根据权限过滤卡片
const visibleCards = computed(() => {
  return allCards.value.filter(card => {
    // 无权限要求的卡片始终显示
    if (!card.authority) return true
    // 检查用户是否拥有该权限
    return auth.authorities.includes(card.authority)
  })
})

// 加载统计数据
async function loadStats() {
  const cards = allCards.value

  // 根据显示的卡片加载对应数据
  for (const card of cards) {
    if (card.key === 'visits' && auth.authorities.includes('visit:entry')) {
      await visitStore.fetchList()
      card.count = visitStore.visits.length
    } else if (card.key === 'pharmacy' && auth.authorities.includes('pharmacy:dispense')) {
      const rx = await pharmacyApi.listPrescriptions('PENDING')
      card.count = rx.length
    } else if (card.key === 'dispatch' && auth.authorities.includes('visit:entry')) {
      const board = await dispatchApi.getBoard()
      card.count = board.filter((r: any) => r.status === 'PENDING').length
    } else if (card.key === 'lab' && auth.authorities.includes('order:execute')) {
      // 检验申请数量（使用就诊列表中的待检数量）
      await visitStore.fetchList()
      card.count = visitStore.visits.length
    } else if (card.key === 'patient-booking' && auth.authorities.includes('patient:booking')) {
      // 患者端：我的预约数量（后续可接入API）
      card.count = 0
    } else if (card.key === 'patient-myqueue' && auth.authorities.includes('patient:booking')) {
      // 患者端：我的排队数量（后续可接入API）
      card.count = 0
    }
  }
}

onMounted(function () {
  // 初始化卡片配置
  allCards.value = [
    // 医护人员卡片
    {
      key: 'visits',
      title: '门诊就诊',
      icon: FirstAidKit,
      color: 'var(--brand)',
      bgColor: 'var(--brand-subtle)',
      authority: 'visit:entry',
      path: '/visits',
      count: 0
    },
    {
      key: 'pharmacy',
      title: '待发药',
      icon: Box,
      color: 'var(--warning)',
      bgColor: 'var(--warning-subtle)',
      authority: 'pharmacy:dispense',
      path: '/pharmacy/prescriptions',
      count: 0
    },
    {
      key: 'dispatch',
      title: '排队待检',
      icon: Monitor,
      color: 'var(--success)',
      bgColor: 'var(--success-subtle)',
      authority: 'visit:entry',
      path: '/dispatch',
      count: 0
    },
    {
      key: 'lab',
      title: '检验申请',
      icon: DataBoard,
      color: 'var(--brand-500)',
      bgColor: 'var(--brand-subtle)',
      authority: 'order:execute',
      path: '/lab/requisitions',
      count: 0
    },
    // 患者端卡片
    {
      key: 'patient-booking',
      title: '套餐预约',
      icon: Tickets,
      color: 'var(--warning)',
      bgColor: 'var(--warning-subtle)',
      authority: 'patient:booking',
      path: '/patient/booking',
      count: 0
    },
    {
      key: 'patient-appointments',
      title: '我的预约',
      icon: Calendar,
      color: 'var(--brand-500)',
      bgColor: 'var(--brand-subtle)',
      authority: 'patient:booking',
      path: '/patient/appointments',
      count: 0
    },
    {
      key: 'patient-myqueue',
      title: '我的排队',
      icon: List,
      color: 'var(--success)',
      bgColor: 'var(--success-subtle)',
      authority: 'patient:booking',
      path: '/patient/my-queue',
      count: 0
    },
    {
      key: 'patient-my-reports',
      title: '我的报告',
      icon: Reading,
      color: 'var(--brand)',
      bgColor: 'var(--brand-subtle)',
      authority: 'patient:booking',
      path: '/patient/my-reports',
      count: 0
    },
    // 管理员卡片
    {
      key: 'org',
      title: '组织架构',
      icon: OfficeBuilding,
      color: 'var(--info)',
      bgColor: 'var(--info-subtle)',
      authority: 'system:admin',
      path: '/org/departments',
      count: 0
    },
    {
      key: 'menu-manage',
      title: '菜单管理',
      icon: MenuIcon,
      color: 'var(--info)',
      bgColor: 'var(--info-subtle)',
      authority: 'system:admin',
      path: '/menu-manage',
      count: 0
    },
    {
      key: 'files',
      title: '文件管理',
      icon: Folder,
      color: 'var(--info)',
      bgColor: 'var(--info-subtle)',
      authority: 'system:admin',
      path: '/files',
      count: 0
    }
  ]

  // 加载通知
  notifyStore.fetchEvents()
  // 加载统计数据
  loadStats()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">工作台</h2>
    </div>

    <!-- 快捷入口卡片 -->
    <div class="kpi-grid">
      <div
        class="kpi-card"
        v-for="card in visibleCards"
        :key="card.key"
        @click="router.push(card.path)"
      >
        <div class="kpi-icon" :style="{ background: card.bgColor, color: card.color }">
          <el-icon :size="22"><component :is="card.icon" /></el-icon>
        </div>
        <div>
          <div class="kpi-value">{{ card.count }} <small>个</small></div>
          <div class="kpi-label">{{ card.title }}</div>
        </div>
      </div>
    </div>

    <!-- 用户信息和系统概览 -->
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><div class="card-title"><el-icon><User /></el-icon><span>当前用户</span></div></template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="手机号">{{ auth.username || '-' }}</el-descriptions-item>
            <el-descriptions-item label="姓名">{{ auth.name || '-' }}</el-descriptions-item>
            <el-descriptions-item label="所属科室">
              <el-tag v-if="auth.department" size="small" type="warning">{{ auth.department }}</el-tag>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="角色">
              <el-tag v-for="role in auth.roles" :key="role" size="small" style="margin-right: 4px">
                {{ role }}
              </el-tag>
              <span v-if="!auth.roles?.length">-</span>
            </el-descriptions-item>
            <el-descriptions-item label="权限数">{{ auth.authorities?.length || 0 }} 项</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><div class="card-title"><el-icon><Setting /></el-icon><span>系统概览</span></div></template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="架构形态">模块化单体 + 通知/文件抽出服务</el-descriptions-item>
            <el-descriptions-item label="业务模块">9 个</el-descriptions-item>
            <el-descriptions-item label="中间件">PostgreSQL / MinIO / RabbitMQ</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="notify-card">
          <template #header>
            <div class="card-title-header">
              <span class="card-title"><el-icon><Bell /></el-icon><span>消息通知</span></span>
              <el-badge :value="notifyStore.unreadCount" :hidden="notifyStore.unreadCount === 0" :max="99">
                <el-button v-if="auth.authorities?.includes('visit:entry')" size="small" text @click="router.push('/notifications')">查看全部</el-button>
              </el-badge>
            </div>
          </template>
          <div v-if="recentNotifications.length === 0" class="empty-notify">暂无通知</div>
          <div v-else class="notify-list">
            <div v-for="item in recentNotifications" :key="item.id" class="notify-item">
              <el-tag :type="item.type === 'PATIENT_CALLED' ? 'success' : 'info'" size="small">
                {{ getNotifyTypeLabel(item.type) }}
              </el-tag>
              <span class="notify-content">{{ item.content }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.card-title { display: flex; align-items: center; gap: var(--sp-2); font-weight: var(--fw-semibold); font-size: var(--fs-base); }
.card-title-header { display: flex; justify-content: space-between; align-items: center; }
.notify-card :deep(.el-card__body) { padding: 0 var(--sp-5); }
.empty-notify { color: var(--text-secondary); font-size: var(--fs-base); padding: var(--sp-5) 0; text-align: center; }
.notify-list { max-height: 200px; overflow-y: auto; }
.notify-item { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-2) 0; border-bottom: 1px solid var(--border-light); }
.notify-item:last-child { border-bottom: none; }
.notify-content { font-size: var(--fs-sm); color: var(--text-regular); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
