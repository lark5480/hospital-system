<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { usePatientStore } from '@/stores/patient'
import type { ExamPackage } from '@/types/patient'

const router = useRouter()
const store = usePatientStore()

const bookingVisible = ref(false)
const selectedPackage = ref<ExamPackage | null>(null)

const form = reactive<{ patientId: number; slotId: number | null }>({
  patientId: 0,
  slotId: null
})

function openBooking(pkg: ExamPackage) {
  selectedPackage.value = pkg
  form.slotId = null
  store.fetchSlots(pkg.id)
  bookingVisible.value = true
}

async function submitBooking() {
  if (!form.slotId) {
    ElMessage.warning('请选择体检时段')
    return
  }
  try {
    await store.book({
      patientId: form.patientId,
      packageId: selectedPackage.value!.id,
      slotId: form.slotId
    })
    ElMessage.success('预约成功')
    bookingVisible.value = false
    if (selectedPackage.value) store.fetchSlots(selectedPackage.value.id)
  } catch (e: any) {
    const msg = e?.response?.data?.error ?? '预约失败'
    ElMessage.error(msg)
  }
}

function goAppointments() {
  router.push('/patient/appointments')
}

onMounted(async () => {
  await store.fetchMe()
  if (store.currentPatient) {
    form.patientId = store.currentPatient.id
  }
  store.fetchPackages()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>体检套餐预约(C端)</h2>
      <el-button type="primary" @click="goAppointments">我的预约</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      title="演示说明"
      description="默认开发态已模拟 patient 角色,可直接预约。基础套餐「下午」号源 capacity=1,连约两次会触发后端『号源已满』(409),演示原子不超卖。"
      style="margin-bottom: 16px"
    />

    <el-table :data="store.packages" v-loading="store.loading" border stripe empty-text="暂无套餐">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="套餐名称" min-width="140" />
      <el-table-column prop="price" label="价格(元)" width="120">
        <template #default="{ row }">{{ row.price?.toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="200" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="openBooking(row)">预约</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 预约弹窗 -->
    <el-dialog v-model="bookingVisible" title="预约体检" width="520px">
      <template v-if="selectedPackage">
        <el-descriptions :column="1" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="套餐">{{ selectedPackage.name }}</el-descriptions-item>
          <el-descriptions-item label="价格">{{ selectedPackage.price?.toFixed(2) }} 元</el-descriptions-item>
          <el-descriptions-item label="患者" v-if="store.currentPatient">{{ store.currentPatient.name }}</el-descriptions-item>
        </el-descriptions>

        <el-form label-width="90px">
          <el-form-item label="体检时段">
            <el-select v-model="form.slotId" placeholder="选择号源" style="width: 100%">
              <el-option
                v-for="s in store.slots"
                :key="s.id"
                :value="s.id"
                :disabled="s.remaining <= 0"
                :label="`${s.examDate} ${s.period} · 剩余 ${s.remaining}/${s.capacity}`"
              />
            </el-select>
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="bookingVisible = false">取消</el-button>
        <el-button type="primary" :loading="store.loading" @click="submitBooking">确认预约</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.toolbar h2 {
  margin: 0;
  font-size: 18px;
}
</style>
