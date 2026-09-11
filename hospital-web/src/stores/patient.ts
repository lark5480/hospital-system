import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  getCurrentPatient,
  registerPatient,
  searchPatients,
  listPackages,
  listSlots,
  bookAppointment,
  listMyAppointments,
  listMyReports,
  cancelMyAppointment
} from '@/api/patient'
import type {
  Patient,
  PatientRegisterPayload,
  ExamPackage,
  Slot,
  AppointmentDetail,
  BookingPayload,
  ReportRecord
} from '@/types/patient'

export const usePatientStore = defineStore('patient', () => {
  const loading = ref(false)
  const currentPatient = ref<Patient | null>(null)
  const packages = ref<ExamPackage[]>([])
  const slots = ref<Slot[]>([])
  const appointments = ref<AppointmentDetail[]>([])
  const reports = ref<ReportRecord[]>([])

  async function fetchMe() {
    try {
      currentPatient.value = await getCurrentPatient()
    } catch (e: any) {
      // 404 = 后端未找到与当前用户绑定的患者记录(已登录但未建档)
      if (e?.response?.status === 404) {
        console.warn('[patient] 当前用户未绑定患者档案,请先建档')
      }
      currentPatient.value = null
    }
  }

  async function fetchPackages() {
    loading.value = true
    try {
      packages.value = await listPackages()
    } finally {
      loading.value = false
    }
  }

  async function fetchSlots(packageId: number) {
    loading.value = true
    try {
      slots.value = await listSlots(packageId)
    } finally {
      loading.value = false
    }
  }

  async function register(payload: PatientRegisterPayload) {
    return registerPatient(payload)
  }

  async function book(payload: BookingPayload) {
    const detail = await bookAppointment(payload)
    await fetchMyAppointments()
    return detail
  }

  /**
   * 取消预约。成功后重新拉列表:后端会同时释放号源、清空 dispatch 侧排队任务,
   * 列表里的状态与可操作项都必须以服务端最新结果为准,不做本地乐观改写。
   * 失败时把错误原样抛出,由视图层把后端返回的原因透给用户。
   */
  async function cancel(id: number) {
    await cancelMyAppointment(id)
    await fetchMyAppointments()
  }

  async function search(keyword: string) {
    return searchPatients(keyword)
  }

  async function fetchMyReports() {
    loading.value = true
    try {
      reports.value = await listMyReports()
    } finally {
      loading.value = false
    }
  }

  async function fetchMyAppointments() {
    loading.value = true
    try {
      appointments.value = await listMyAppointments()
    } finally {
      loading.value = false
    }
  }

  return {
    loading,
    currentPatient,
    packages,
    slots,
    appointments,
    reports,
    fetchMe,
    searchPatients: search,
    fetchPackages,
    fetchSlots,
    register,
    book,
    cancel,
    fetchMyAppointments,
    fetchMyReports
  }
})
