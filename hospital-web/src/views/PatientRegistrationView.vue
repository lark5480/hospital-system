<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import * as regApi from '@/api/registration'
import type { Registration } from '@/api/registration'
import * as orgApi from '@/api/org'
import { getCurrentPatient } from '@/api/patient'

const departments = ref<{ id: number; name: string }[]>([])
const patientId = ref<number>()
const patientName = ref('')
const myQueue = ref<Registration[]>([])
const registering = ref(false)

const myWaiting = computed(() => myQueue.value.filter(r => r.status === 'WAITING'))
const myCalled = computed(() => myQueue.value.find(r => r.status === 'CALLED'))

async function loadMyQueue() {
  if (!patientId.value) return
  // 遍历所有科室查自己的排队(轻量实现)
  const all: Registration[] = []
  for (const d of departments.value) {
    try {
      const list = await regApi.listRegistrations(d.id)
      all.push(...list.filter(r => r.patientId === patientId.value))
    } catch { /* ignore */ }
  }
  myQueue.value = all.sort((a, b) => b.queueNo - a.queueNo)
}

async function doRegister(deptId: number) {
  if (!patientId.value) {
    ElMessage.warning('未获取到患者身份，请确认已登录患者账号')
    return
  }
  registering.value = true
  try {
    const reg = await regApi.register({ patientId: patientId.value, deptId })
    const deptName = departments.value.find(d => d.id === deptId)?.name || ''
    ElMessage.success(`挂号成功！${deptName} ${reg.queueNo} 号，请候诊等待叫号`)
    await loadMyQueue()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '挂号失败')
  } finally {
    registering.value = false
  }
}

onMounted(async () => {
  try { departments.value = await orgApi.listDepartments() } catch { /* ignore */ }
  try {
    const p = await getCurrentPatient()
    patientId.value = p.id
    patientName.value = p.name
  } catch { /* dev 模式可能无患者身份 */ }
  await loadMyQueue()
})
</script>

<template>
  <div>
    <h2 style="margin: 0 0 16px">自助挂号</h2>

    <!-- 当前排队状态 -->
    <el-card v-if="myCalled" shadow="never" class="block status-card called">
      <el-tag type="success" size="large">已叫号</el-tag>
      <span class="status-text">请前往就诊（就诊单 #{{ myCalled.visitId }}）</span>
    </el-card>
    <el-card v-else-if="myWaiting.length > 0" shadow="never" class="block status-card">
      <el-tag type="warning" size="large">候诊中</el-tag>
      <span class="status-text">前方还有 {{ myWaiting.length - 1 }} 人，请耐心等待叫号</span>
    </el-card>

    <!-- 选择科室挂号 -->
    <el-card shadow="never" class="block">
      <template #header><span>选择科室挂号</span></template>
      <div class="dept-grid">
        <el-button v-for="d in departments" :key="d.id" size="large"
          :loading="registering" @click="doRegister(d.id)">
          {{ d.name }}
        </el-button>
      </div>
      <p v-if="!patientId" class="hint">提示：未检测到患者身份。请用患者账号（如 13700000000）登录后再挂号，或由护士在 B 端“门诊挂号”页面代挂。</p>
    </el-card>

    <!-- 我的挂号记录 -->
    <el-card v-if="myQueue.length > 0" shadow="never" class="block">
      <template #header><span>今日挂号记录</span></template>
      <el-table :data="myQueue" border stripe>
        <el-table-column prop="queueNo" label="排队号" width="80" />
        <el-table-column label="科室" min-width="120">
          <template #default="{ row }">
            {{ departments.find(d => d.id === row.deptId)?.name || row.deptId }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'WAITING' ? 'warning' : row.status === 'CALLED' ? 'success' : 'info'" size="small">
              {{ row.status === 'WAITING' ? '候诊' : row.status === 'CALLED' ? '已叫号' : '已取消' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="挂号时间" width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.block { margin-bottom: 16px; }
.dept-grid { display: flex; flex-wrap: wrap; gap: 12px; }
.dept-grid .el-button { min-width: 120px; }
.status-card { display: flex; align-items: center; gap: 12px; }
.status-text { font-size: 16px; }
.hint { color: #999; font-size: 13px; margin-top: 12px; }
</style>
