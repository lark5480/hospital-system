<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getBoard, getStations } from '@/api/dispatch'
import type { QueueBoardRow } from '@/types/dispatch'

const route = useRoute()

const stations = ref<string[]>([])
const selectedStation = ref<string>((route.query.station as string) || '')
const rows = ref<QueueBoardRow[]>([])
const loading = ref(false)
const isFullscreen = ref(false)

let pollTimer: ReturnType<typeof setInterval> | null = null

/** 当前正在检查的患者(该 station 下 IN_PROGRESS 的第一位)。 */
const current = computed<QueueBoardRow | undefined>(() =>
  rows.value.find((r) => r.status === 'IN_PROGRESS')
)

/** 等待队列(按 seq 升序)。 */
const waiting = computed<QueueBoardRow[]>(() =>
  rows.value
    .filter((r) => r.status === 'PENDING')
    .slice()
    .sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
)

/** 已过号(被重排到队尾)的患者。 */
const skipped = computed<QueueBoardRow[]>(() =>
  rows.value.filter((r) => r.status === 'SKIPPED')
)

async function loadStations() {
  try {
    stations.value = await getStations()
    if (!selectedStation.value && stations.value.length > 0) {
      selectedStation.value = stations.value[0]
    }
  } catch {
    stations.value = []
  }
}

async function loadBoard() {
  if (!selectedStation.value) return
  loading.value = true
  try {
    rows.value = await getBoard(selectedStation.value)
  } finally {
    loading.value = false
  }
}

function onStationChange() {
  loadBoard()
}

function toggleFullscreen() {
  const el = document.documentElement
  if (!document.fullscreenElement) {
    el.requestFullscreen?.().then(() => (isFullscreen.value = true)).catch(() => {})
  } else {
    document.exitFullscreen?.().then(() => (isFullscreen.value = false)).catch(() => {})
  }
}

onMounted(async () => {
  await loadStations()
  await loadBoard()
  // 每 10 秒轮询,大屏实时刷新
  pollTimer = setInterval(loadBoard, 10000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {})
})
</script>

<template>
  <div class="screen">
    <header class="topbar">
      <div class="station-name">
        {{ selectedStation || '请选择工位' }}
        <span class="sub">科室排队叫号</span>
      </div>
      <div class="top-actions">
        <el-select
          v-model="selectedStation"
          placeholder="选择工位"
          style="width: 180px"
          @change="onStationChange"
        >
          <el-option v-for="s in stations" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button @click="toggleFullscreen">{{ isFullscreen ? '退出全屏' : '全屏' }}</el-button>
      </div>
    </header>

    <section class="now">
      <div class="now-label">当前检查</div>
      <div v-if="current" class="now-name">{{ current.patientName || '—' }}</div>
      <div v-else class="now-name idle">请稍候</div>
      <div v-if="current" class="now-item">{{ current.itemName }}</div>
    </section>

    <section class="waiting">
      <div class="waiting-head">
        <span>等待队列</span>
        <span class="count">共 {{ waiting.length }} 人</span>
      </div>
      <div v-if="waiting.length === 0" class="empty">暂无等待</div>
      <ul v-else class="queue">
        <li v-for="t in waiting" :key="t.id" class="queue-item">
          <span class="q-seq">{{ t.seq }}</span>
          <span class="q-name">{{ t.patientName || '—' }}</span>
          <span class="q-item">{{ t.itemName }}</span>
        </li>
      </ul>
    </section>

    <section v-if="skipped.length" class="skipped">
      已过号(队尾): {{ skipped.map((s) => s.patientName || '—').join('、') }}
    </section>

    <footer class="foot">数据每 10 秒刷新 · {{ loading ? '刷新中…' : '已同步' }}</footer>
  </div>
</template>

<style scoped>
.screen {
  min-height: 100vh;
  background: #0c0c0e;
  color: #f5f5f7;
  display: flex;
  flex-direction: column;
  padding: 32px 48px;
  box-sizing: border-box;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #26262b;
  padding-bottom: 20px;
}
.station-name {
  font-size: 34px;
  font-weight: 700;
  letter-spacing: 1px;
}
.station-name .sub {
  font-size: 15px;
  font-weight: 400;
  color: #8a8a92;
  margin-left: 14px;
}
.top-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.now {
  margin: 48px 0;
  text-align: center;
}
.now-label {
  font-size: 20px;
  color: #8a8a92;
  letter-spacing: 4px;
}
.now-name {
  font-size: 96px;
  font-weight: 800;
  line-height: 1.2;
  margin-top: 12px;
}
.now-name.idle {
  font-size: 64px;
  font-weight: 500;
  color: #5a5a62;
}
.now-item {
  font-size: 28px;
  color: #b8b8c0;
  margin-top: 8px;
}
.waiting {
  flex: 1;
}
.waiting-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 22px;
  color: #c8c8d0;
  border-bottom: 1px solid #26262b;
  padding-bottom: 12px;
  margin-bottom: 16px;
}
.waiting-head .count {
  font-size: 16px;
  color: #8a8a92;
}
.empty {
  color: #5a5a62;
  font-size: 20px;
  padding: 24px 0;
}
.queue {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}
.queue-item {
  display: flex;
  align-items: center;
  gap: 16px;
  background: #16161a;
  border: 1px solid #26262b;
  border-radius: 12px;
  padding: 16px 20px;
}
.q-seq {
  font-size: 26px;
  font-weight: 700;
  color: #6a6a72;
  min-width: 42px;
}
.q-name {
  font-size: 30px;
  font-weight: 600;
}
.q-item {
  margin-left: auto;
  font-size: 16px;
  color: #8a8a92;
}
.skipped {
  margin-top: 24px;
  font-size: 15px;
  color: #6a6a72;
  border-top: 1px solid #26262b;
  padding-top: 16px;
}
.foot {
  margin-top: 20px;
  text-align: right;
  font-size: 13px;
  color: #5a5a62;
}
</style>
