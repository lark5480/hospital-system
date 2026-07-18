<script setup lang="ts">
import { onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useNotificationStore } from '@/stores/notification'

const store = useNotificationStore()

async function load() {
  try {
    await store.fetchEvents()
  } catch {
    ElMessage.error('加载通知事件失败')
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="store.loading">
    <div class="toolbar">
      <h2>消息通知</h2>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>
    <el-alert
      class="hint"
      type="info"
      :closable="false"
      title="事件驱动演示:① 在「门诊就诊」新建一条就诊,core 发布 VisitCreatedEvent;② B 端分诊大屏「叫号下一号」,core 发布 PatientCalledEvent。二者均经 RabbitMQ → 通知服务落痕,此处即可看到。"
    />
    <el-table :data="store.items" border stripe empty-text="暂无事件" style="margin-top: 12px">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.type === 'PATIENT_CALLED'" type="warning">叫号通知</el-tag>
          <el-tag v-else type="info">就诊创建</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="visitId" label="预约/就诊号" width="120" />
      <el-table-column prop="patientId" label="患者ID" width="100" />
      <el-table-column prop="channel" label="渠道" width="110" />
      <el-table-column prop="content" label="内容" min-width="260" />
      <el-table-column prop="receivedAt" label="接收时间" min-width="200" />
    </el-table>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.toolbar h2 { margin: 0; font-size: 18px; }
.hint { margin-bottom: 4px; }
</style>
