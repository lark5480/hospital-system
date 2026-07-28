<script setup lang="ts">
import { computed, onActivated, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useDispatchStore } from '@/stores/dispatch'
import { callNext, reorderTail, requeueTask, skipTask } from '@/api/dispatch'
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
  try {
    const targets = store.stationFilter ? [store.stationFilter] : stations.value
    await Promise.all(targets.map((s) => callNext(s)))
    await store.fetchBoard()
    ElMessage.success('叫号成功')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || '叫号失败')
  }
}

async function handleReorder(id: number) {
  try {
    await reorderTail(id)
    await store.fetchBoard()
    ElMessage.success('已标记过号')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || '过号失败')
  }
}

async function handleSkip(id: number) {
  try {
    await skipTask(id)
    await store.fetchBoard()
    ElMessage.success('已跳过')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || '跳过失败')
  }
}

async function handleStart(id: number) {
  try {
    await store.start(id)
    ElMessage.success('已开始')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || '开始任务失败')
  }
}

async function handleComplete(id: number) {
  try {
    await store.complete(id)
    ElMessage.success('已完成')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.error || '完成任务失败')
  }
}

/** 已跳过 → 重新排队(患者去而复返);预约已出报告时后端拒绝,展示原因。 */
async function handleRequeue(id: number) {
  try {
    await requeueTask(id)
    ElMessage.success('已重新排队')
  } catch (e: any) {
    ElMessage.warning(e?.response?.data?.error || '重新排队失败')
  }
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
        <div v-for="t in g.tasks" :key="t.id" class="task" :class="`task--${t.status.toLowerCase()}`">
          <div class="task-head">
            <span class="seq">#{{ t.seq }}</span>
            <span class="item">{{ t.itemName }}</span>
            <el-tag :type="statusMeta[t.status].type" size="small" effect="light">
              {{ statusMeta[t.status].text }}
            </el-tag>
          </div>
          <div class="task-foot">
            <span class="patient">{{ t.patientName || '—' }}</span>
            <div class="task-actions">
              <el-button v-if="t.status === 'PENDING'" type="primary" size="small" @click="handleStart(t.id)">
                开始
              </el-button>
              <el-button v-if="t.status === 'IN_PROGRESS'" type="success" size="small" @click="handleComplete(t.id)">
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
              <el-button v-if="t.status === 'SKIPPED'" size="small" plain type="warning" @click="handleRequeue(t.id)">
                重新排队
              </el-button>
            </div>
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
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--sp-4);
  align-items: start;
}
.station-title {
  font-weight: var(--fw-semibold);
  margin-right: var(--sp-2);
}
.task {
  padding: 10px 12px;
  margin: 0 -12px;
  border-bottom: 1px solid var(--border-light);
  border-left: 3px solid transparent;
}
.task:last-child {
  border-bottom: none;
}
.task--in_progress {
  border-left-color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
}
.task--done {
  opacity: 0.55;
}
.task--skipped {
  opacity: 0.55;
}
.task-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.seq {
  font-weight: var(--fw-bold);
  color: var(--brand);
  min-width: 26px;
}
.item {
  flex: 1;
  font-weight: var(--fw-medium);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 6px;
  padding-left: calc(26px + var(--sp-2));
}
.patient {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-right: var(--sp-2);
}
.task-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.task-actions .el-button + .el-button {
  margin-left: 0;
}
</style>
