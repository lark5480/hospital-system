<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as dispatchApi from '@/api/dispatch'
import type { ExamTask } from '@/types/dispatch'
import { usePatientStore } from '@/stores/patient'
import { createVisibilityAwarePoller } from '@/utils/polling'

const patientStore = usePatientStore()
const tasks = ref<ExamTask[]>([])
const loading = ref(false)
// 叫号横幅:当前正在被叫号(检查中)的患者自身任务
const calledBanner = ref<ExamTask | null>(null)
// 记录上一次轮询各任务状态,用于检测「刚被叫号」的瞬间
const prevStatus = new Map<number, string>()

const statusMeta: Record<string, { text: string; type: string }> = {
  PENDING: { text: '等待中', type: 'info' },
  IN_PROGRESS: { text: '检查中', type: 'warning' },
  DONE: { text: '已完成', type: 'success' },
  SKIPPED: { text: '已跳过', type: 'danger' }
}

async function fetchQueue() {
  loading.value = true
  try {
    const next = await dispatchApi.getMyQueue()
    // 检测本患者任务是否「刚被叫号」(状态由非检查中变为检查中)→ 弹叫号提示
    for (const t of next) {
      const before = prevStatus.get(t.id)
      if (t.status === 'IN_PROGRESS' && before && before !== 'IN_PROGRESS') {
        ElMessage.success(`您已被叫号,请前往【${t.station}】进行【${t.itemName}】检查`)
      }
      prevStatus.set(t.id, t.status)
    }
    // 横幅:展示当前正在检查中的自身任务
    calledBanner.value = next.find((t) => t.status === 'IN_PROGRESS') ?? null
    tasks.value = next
  } catch { ElMessage.error('加载排队失败') }
  finally { loading.value = false }
}

// R-40: 10s 轮询改由可见性感知轮询器驱动 —— 页面隐藏(切后台/最小化)自动暂停,
// 重新可见时立即拉一次并恢复,避免患者把页面挂后台后仍在持续请求。
const poller = createVisibilityAwarePoller(fetchQueue, 10000)

onMounted(async () => {
  await patientStore.fetchMe()
  fetchQueue()
  poller.start()
})

onUnmounted(() => {
  poller.stop()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>我的排队</h2>
      <el-button @click="fetchQueue">刷新</el-button>
    </div>
    <el-alert
      v-if="calledBanner"
      class="call-banner"
      type="warning"
      :closable="false"
      show-icon
      :title="`叫号通知:您正在【${calledBanner.station}】进行【${calledBanner.itemName}】检查`"
      description="请按指引前往对应科室,完成本项后再进行下一顺序项目。"
    />
    <el-table :data="tasks" v-loading="loading" border stripe empty-text="暂无排队项目">
      <el-table-column prop="seq" label="序号" width="70" />
      <el-table-column prop="station" label="工位" width="120" />
      <el-table-column prop="itemName" label="检查项目" min-width="140" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status]?.type || ''">{{ statusMeta[row.status]?.text || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
    </el-table>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.toolbar h2 { margin: 0; font-size: 18px; }
.call-banner { margin-bottom: 16px; }
</style>
