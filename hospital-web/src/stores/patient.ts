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
  listMyReports
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
    await fetchMyAppointments(payload.patientId)
    return detail
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

  async function fetchMyAppointments(patientId: number) {
    loading.value = true
    try {
      appointments.value = await listMyAppointments(patientId)
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
    fetchMyAppointments,
    fetchMyReports
  }
})
