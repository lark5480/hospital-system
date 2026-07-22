<script setup lang="ts">
import { onMounted, onUnmounted, onActivated, onDeactivated, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import * as regApi from '@/api/registration'
import type { Registration } from '@/api/registration'
import * as orgApi from '@/api/org'
import { listPatients } from '@/api/patient'

const route = useRoute()
const departments = ref<{ id: number; name: string }[]>([])
const patients = ref<{ id: number; name: string }[]>([])
const patientMap = computed(() => new Map(patients.value.map(p => [p.id, p.name])))

const deptId = ref<number>()
const queue = ref<Registration[]>([])
let timer: ReturnType<typeof setInterval> | null = null

const currentCalled = computed(() =>
  queue.value
    .filter(r => r.status === 'CALLED')
    .sort((a, b) => b.queueNo - a.queueNo)[0])
const waitingList = computed(() => queue.value.filter(r => r.status === 'WAITING'))
const deptName = computed(() => departments.value.find(d => d.id === deptId.value)?.name || '')

async function load() {
  if (!deptId.value) return
  try {
    queue.value = await regApi.activeQueue(deptId.value)
  } catch { /* ignore */ }
}

function startPolling() {
  stopPolling()
  timer = setInterval(load, 5000)
}

function stopPolling() {
  if (timer) { clearInterval(timer); timer = null }
}

function switchDept(id: number) {
  deptId.value = id
  load()
}

onMounted(async () => {
  try { departments.value = await orgApi.listDepartments() } catch { /* ignore */ }
  try { patients.value = await listPatients() } catch { /* ignore */ }
  const initDept = Number(route.query.dept)
  if (initDept && departments.value.some(d => d.id === initDept)) {
    deptId.value = initDept
  } else if (departments.value.length > 0) {
    deptId.value = departments.value[0].id
  }
  await load()
  startPolling()
})

// KeepAlive 缓存:关闭/切走 tab 只触发 deactivated 而非 unmounted。
// 停用即停轮询,切回再恢复,避免大屏关闭后仍在后台持续请求。
onActivated(() => {
  load()
  startPolling()
})
onDeactivated(stopPolling)

onUnmounted(stopPolling)
</script>

<template>
  <div class="screen">
    <!-- 科室选择 -->
    <div class="dept-bar">
      <button v-for="d in departments" :key="d.id"
        :class="['dept-btn', { active: d.id === deptId }]"
        @click="switchDept(d.id)">{{ d.name }}</button>
    </div>

    <h1 class="title">{{ deptName }} · 门诊叫号</h1>

    <!-- 当前就诊 -->
    <div class="current" v-if="currentCalled">
      <div class="current-label">当前就诊</div>
      <div class="current-name">{{ patientMap.get(currentCalled.patientId) || currentCalled.patientId }}</div>
      <div class="current-no">{{ currentCalled.queueNo }} 号</div>
    </div>
    <div class="current empty" v-else>
      <div class="current-label">当前无就诊</div>
    </div>

    <!-- 候诊队列 -->
    <div class="waiting">
      <div class="waiting-title">候诊队列（{{ waitingList.length }} 人）</div>
      <div class="waiting-list" v-if="waitingList.length > 0">
        <div v-for="r in waitingList" :key="r.id" class="waiting-item">
          <span class="waiting-no">{{ r.queueNo }}</span>
          <span class="waiting-name">{{ patientMap.get(r.patientId) || r.patientId }}</span>
        </div>
      </div>
      <div v-else class="waiting-empty">暂无候诊患者</div>
    </div>
  </div>
</template>

<style scoped>
.screen {
  min-height: 100vh;
  background: #1a1a2e;
  color: #eee;
  padding: 24px 40px;
  font-family: 'Microsoft YaHei', sans-serif;
}
.dept-bar { display: flex; gap: 8px; margin-bottom: 20px; flex-wrap: wrap; }
.dept-btn {
  padding: 6px 18px; border: 1px solid #555; border-radius: 6px;
  background: transparent; color: #ccc; cursor: pointer; font-size: 14px;
}
.dept-btn.active { background: #0f3460; border-color: #0f3460; color: #fff; }
.title { text-align: center; font-size: 28px; margin: 0 0 32px; color: #e94560; }
.current {
  text-align: center; padding: 40px; margin-bottom: 32px;
  background: #16213e; border-radius: 12px;
}
.current.empty { padding: 24px; }
.current-label { font-size: 16px; color: #999; margin-bottom: 12px; }
.current-name { font-size: 56px; font-weight: bold; color: #e94560; }
.current-no { font-size: 28px; color: #ccc; margin-top: 8px; }
.waiting { background: #16213e; border-radius: 12px; padding: 24px; }
.waiting-title { font-size: 18px; color: #999; margin-bottom: 16px; }
.waiting-list { display: flex; flex-wrap: wrap; gap: 12px; }
.waiting-item {
  display: flex; align-items: center; gap: 10px;
  background: #0f3460; padding: 10px 20px; border-radius: 8px; font-size: 18px;
}
.waiting-no { color: #e94560; font-weight: bold; font-size: 22px; }
.waiting-name { color: #eee; }
.waiting-empty { color: #666; font-size: 16px; text-align: center; padding: 20px; }
</style>
