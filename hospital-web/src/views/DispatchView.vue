<script setup lang="ts">
import { computed, onActivated, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { useDispatchStore } from '@/stores/dispatch'
import { callNext, reorderTail, skipTask } from '@/api/dispatch'
import { subscribeBoardUpdates, unsubscribe } from '@/api/dispatchSSE'
import type { TaskStatus } from '@/types/dispatch'

const store = useDispatchStore()
const router = useRouter()

const statusMeta: Record<TaskStatus, { text: string; type: '' | 'success' | 'warning' | 'info' | 'danger' }> = {
  PENDING: { text: '待检', type: 'info' },
  IN_PROGRESS: { text: '检查中', type: 'warning' },
  DONE: { text: '已完成', type: 'success' },
  SKIPPED: { text: '已跳过', type: 'danger' }
}

const stations = computed(() => Array.from(new Set(store.rows.map((r) => r.station))))
const grouped = computed(() =>
  stations.value.map((s) => ({ station: s, tasks: store.rows.filter((r) => r.station === s) }))
)

function onStationChange() {
  store.fetchBoard()
  setupSSE()
}

/** 自动叫号:若已选工位则对该工位叫号,否则对所有工位各叫一个。 */
async function handleCallNext() {
  const targets = store.stationFilter ? [store.stationFilter] : stations.value
  await Promise.all(targets.map((s) => callNext(s)))
  await store.fetchBoard()
}

async function handleReorder(id: number) {
  await reorderTail(id)
  await store.fetchBoard()
}

async function handleSkip(id: number) {
  await skipTask(id)
  await store.fetchBoard()
}

function openScreen() {
  router.push('/dispatch/screen')
}

onMounted(() => {
  store.fetchBoard()
  setupSSE()
})

// KeepAlive 激活时立刻刷新一帧 + 重新订阅SSE
onActivated(() => {
  store.fetchBoard()
  setupSSE()
})

onUnmounted(() => {
  unsubscribe()
})

function setupSSE() {
  unsubscribe()
  subscribeBoardUpdates(() => {
    store.fetchBoard()
  })
}
</script>

<template>
  <div>
    <div class="toolbar">
      <el-select
        v-model="store.stationFilter"
        placeholder="全部工位"
        clearable
        style="width: 200px"
        @change="onStationChange"
      >
        <el-option v-for="s in stations" :key="s" :label="s" :value="s" />
      </el-select>
      <el-button type="primary" :loading="store.loading" @click="handleCallNext">叫号下一号</el-button>
      <el-button :icon="Refresh" :loading="store.loading" @click="store.fetchBoard()">刷新</el-button>
      <el-button @click="openScreen">科室大屏</el-button>
      <span class="hint">状态推进:待检 → 检查中 → 已完成(写模型 ExamTask + 读模型 queue_board 同步)</span>
    </div>

    <el-empty
      v-if="!store.loading && store.rows.length === 0"
      description="暂无排队任务。请先在「套餐预约」完成一次预约,任务会自动生成。"
    />

    <div v-else class="board">
      <el-card v-for="g in grouped" :key="g.station" class="station" shadow="never">
        <template #header>
          <span class="station-title">{{ g.station }}</span>
          <el-tag size="small" effect="plain">{{ g.tasks.length }} 项</el-tag>
        </template>
        <div v-for="t in g.tasks" :key="t.id" class="task">
          <div class="task-main">
            <span class="seq">#{{ t.seq }}</span>
            <div>
              <div class="item">{{ t.itemName }}</div>
              <div class="patient">{{ t.patientName }} · {{ statusMeta[t.status].text }}</div>
            </div>
          </div>
          <div class="task-actions">
            <el-tag :type="statusMeta[t.status].type" size="small">{{ statusMeta[t.status].text }}</el-tag>
            <el-button v-if="t.status === 'PENDING'" type="primary" size="small" @click="store.start(t.id)">
              开始
            </el-button>
            <el-button v-if="t.status === 'IN_PROGRESS'" type="success" size="small" @click="store.complete(t.id)">
              完成
            </el-button>
            <el-button v-if="t.status === 'PENDING'" size="small" plain @click="handleReorder(t.id)">
              过号
            </el-button>
            <el-button
              v-if="t.status === 'PENDING' || t.status === 'IN_PROGRESS'"
              size="small"
              plain
              type="danger"
              @click="handleSkip(t.id)"
            >
              跳过
            </el-button>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-4);
  flex-wrap: wrap;
}
.hint {
  color: var(--text-secondary);
  font-size: var(--fs-xs);
}
.board {
  display: flex;
  gap: var(--sp-4);
  flex-wrap: wrap;
  align-items: flex-start;
}
.station {
  width: 280px;
}
.station-title {
  font-weight: var(--fw-semibold);
  margin-right: var(--sp-2);
}
.task {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-light);
}
.task:last-child {
  border-bottom: none;
}
.task-main {
  display: flex;
  align-items: center;
  gap: 10px;
}
.seq {
  font-weight: var(--fw-bold);
  color: var(--brand);
}
.item {
  font-weight: var(--fw-medium);
}
.patient {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}
.task-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
</style>
