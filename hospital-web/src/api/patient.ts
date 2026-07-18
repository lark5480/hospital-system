import http from './http'
import type {
  Patient,
  PatientRegisterPayload,
  PatientRegisterResponse,
  ExamPackage,
  Slot,
  AppointmentDetail,
  BookingPayload,
  ReportRecord
} from '@/types/patient'

export function registerPatient(payload: PatientRegisterPayload) {
  return http.post<PatientRegisterResponse>('/patient/register', payload).then((r) => r.data)
}

export function listPackages() {
  return http.get<ExamPackage[]>('/patient/packages').then((r) => r.data)
}

export function listSlots(packageId: number) {
  return http
    .get<Slot[]>('/patient/slots', { params: { packageId } })
    .then((r) =>
      // 前端补 remaining,便于 UI 禁用已满时段
      r.data.map((s) => ({ ...s, remaining: s.capacity - s.booked }))
    )
}

export function bookAppointment(payload: BookingPayload) {
  return http.post<AppointmentDetail>('/patient/appointments', payload).then((r) => r.data)
}

export function getCurrentPatient() {
  return http.get<Patient>('/patient/me').then((r) => r.data)
}

export function listPatients() {
  return http.get<Patient[]>('/patient').then((r) => r.data)
}

export function updatePatient(id: number, payload: PatientRegisterPayload) {
  return http.put<Patient>(`/patient/${id}`, payload).then((r) => r.data)
}

export function searchPatients(keyword: string) {
  return http.get<Patient[]>('/patient/search', { params: { keyword } }).then((r) => r.data)
}

export function listMyAppointments(patientId: number) {
  return http
    .get<AppointmentDetail[]>('/patient/appointments', { params: { patientId } })
    .then((r) => r.data)
}

export function listMyReports() {
  return http.get<ReportRecord[]>('/patient/reports').then((r) => r.data)
}

/** 下载报告 PDF(后端流式代理,自动带 Bearer 令牌)。返回 Blob 供前端触发下载。 */
export function downloadReport(id: number) {
  return http
    .get<Blob>('/patient/reports/' + id + '/download', {
      responseType: 'blob'
    })
    .then((r) => r.data)
}
