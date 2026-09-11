<script setup lang="ts">
import { onMounted, onActivated, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as regApi from '@/api/registration'
import type { Registration } from '@/api/registration'
import * as orgApi from '@/api/org'
import { listPatients } from '@/api/patient'

const router = useRouter()

// --- 基础数据 ---
const departments = ref<{ id: number; name: string }[]>([])
const patients = ref<{ id: number; name: string }[]>([])
const patientMap = computed(() => new Map(patients.value.map(p => [p.id, p.name])))

// --- 挂号表单 ---
const formDeptId = ref<number>()
const formPatientId = ref<number>()
const registering = ref(false)

async function doRegister() {
  if (!formPatientId.value || !formDeptId.value) {
    ElMessage.warning('请选择患者和科室')
    return
  }
  registering.value = true
  try {
    const reg = await regApi.register({ patientId: formPatientId.value, deptId: formDeptId.value })
    ElMessage.success(`挂号成功，排队号 ${reg.queueNo}`)
    formPatientId.value = undefined
    await loadQueue()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '挂号失败')
  } finally {
    registering.value = false
  }
}

// --- 排队管理 ---
const queueDeptId = ref<number>()
const queue = ref<Registration[]>([])
const calling = ref(false)
const refreshing = ref(false)

async function doRefresh() {
  refreshing.value = true
  try {
    await loadQueue()
  } finally {
    refreshing.value = false
  }
}

const waitingCount = computed(() => queue.value.filter(r => r.status === 'WAITING').length)
const currentCalled = computed(() =>
  queue.value
    .filter(r => r.status === 'CALLED')
    .sort((a, b) => b.queueNo - a.queueNo)[0])

async function loadQueue() {
  if (!queueDeptId.value) { queue.value = []; return }
  try {
    queue.value = await regApi.listRegistrations(queueDeptId.value)
  } catch { /* ignore */ }
}

async function doCallNext() {
  if (!queueDeptId.value) return
  calling.value = true
  try {
    const reg = await regApi.callNext(queueDeptId.value)
    ElMessage.success(`已叫号：${patientMap.value.get(reg.patientId) || reg.patientId}（${reg.queueNo}号），就诊单 #${reg.visitId} 已创建`)
    await loadQueue()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '叫号失败')
  } finally {
    calling.value = false
  }
}

async function doCancel(reg: Registration) {
  try {
    await ElMessageBox.confirm(
      `确认取消 ${patientMap.value.get(reg.patientId) || reg.patientId} 的挂号（${reg.queueNo}号）？`,
      '取消挂号', { confirmButtonText: '确认', cancelButtonText: '关闭', type: 'warning' })
  } catch { return }
  try {
    await regApi.cancelRegistration(reg.id)
    ElMessage.success('已取消')
    await loadQueue()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '取消失败')
  }
}

function openScreen() {
  if (queueDeptId.value) {
    router.push({ path: '/registration/screen', query: { dept: queueDeptId.value } })
  }
}

function statusTag(status: string) {
  const map: Record<string, { text: string; type: '' | 'success' | 'warning' | 'info' | 'danger' }> = {
    WAITING: { text: '候诊', type: 'warning' },
    CALLED: { text: '已叫号', type: 'success' },
    COMPLETED: { text: '就诊完成', type: 'info' },
    CANCELLED: { text: '已取消', type: 'info' }
  }
  return map[status] || { text: status, type: 'info' as const }
}

onMounted(async () => {
  try { departments.value = await orgApi.listDepartments() } catch { /* ignore */ }
  // R-07: 后端患者列表已改为分页(pageSize 上限 500),这里显式取满一页
  // TODO(P1): 改用 /api/patient/names?ids= 按需取姓名,避免挂号页全量拉取
  try { patients.value = await listPatients({ pageNum: 1, pageSize: 500 }) } catch { /* ignore */ }
  if (departments.value.length > 0) {
    queueDeptId.value = departments.value[0].id
    await loadQueue()
  }
})

// KeepAlive 缓存的 tab 切回时重新拉队列,确保就诊完结后"当前患者"及时清空
onActivated(() => {
  if (queueDeptId.value) {
    loadQueue()
  }
})
</script>

<template>
  <div>
    <h2 style="margin: 0 0 16px">门诊挂号</h2>

    <!-- 挂号表单 -->
    <el-card shadow="never" class="block">
      <el-form inline>
        <el-form-item label="患者">
          <el-select v-model="formPatientId" filterable placeholder="选择患者" style="width: 200px">
            <el-option v-for="p in patients" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="科室">
          <el-select v-model="formDeptId" placeholder="选择科室" style="width: 200px">
            <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="registering" @click="doRegister">挂号</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 排队管理 -->
    <el-card shadow="never" class="block">
      <div class="queue-toolbar">
        <div class="queue-left">
          <el-select v-model="queueDeptId" placeholder="选择科室" style="width: 200px" @change="loadQueue">
            <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-tag v-if="waitingCount > 0" type="warning" size="large">{{ waitingCount }} 人候诊</el-tag>
          <el-tag v-if="currentCalled" type="success" size="large">
            当前：{{ patientMap.get(currentCalled.patientId) || currentCalled.patientId }}（{{ currentCalled.queueNo }}号）
          </el-tag>
        </div>
        <div class="queue-right">
          <el-button type="success" size="large" :loading="calling" :disabled="waitingCount === 0"
            @click="doCallNext">叫下一位</el-button>
          <el-button :loading="refreshing" :disabled="!queueDeptId" @click="doRefresh">刷新</el-button>
          <el-button @click="openScreen" :disabled="!queueDeptId">门诊大屏</el-button>
        </div>
      </div>

      <el-table :data="queue" border stripe empty-text="暂无排队记录" style="margin-top: 12px">
        <el-table-column prop="queueNo" label="排队号" width="80" />
        <el-table-column label="患者" min-width="120">
          <template #default="{ row }">{{ patientMap.get(row.patientId) || row.patientId }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status).type" size="small">{{ statusTag(row.status).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="就诊单" width="100">
          <template #default="{ row }">
            <el-link v-if="row.visitId" type="primary" @click="$router.push(`/visits/${row.visitId}`)">
              #{{ row.visitId }}
            </el-link>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="挂号时间" width="180" />
        <el-table-column prop="calledAt" label="叫号时间" width="180">
          <template #default="{ row }">{{ row.calledAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="row.status === 'WAITING'" size="small" type="danger" link
              @click="doCancel(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.block { margin-bottom: 16px; }
.queue-toolbar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.queue-left { display: flex; align-items: center; gap: 12px; }
.queue-right { display: flex; gap: 8px; }
</style>
