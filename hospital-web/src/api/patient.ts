import http from './http'
import type {
  Patient,
  PatientRegisterPayload,
  PatientRegisterResponse,
  ExamPackage,
  Slot,
  AppointmentDetail,
  BookingPayload,
  ReportRecord,
  PatientNameView,
  PatientPageQuery
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

/**
 * 取消预约(仅限本人,后端做归属校验)。
 * 成功后该号源会被释放(可被他人重新预约),同时 dispatch 侧已生成的排队任务会被撤出队列。
 */
export function cancelMyAppointment(id: number) {
  return http.post<void>(`/patient/appointments/${id}/cancel`).then((r) => r.data)
}

export function getCurrentPatient() {
  return http.get<Patient>('/patient/me').then((r) => r.data)
}

/** R-07: 分页查询患者列表。后端默认 1 / 200,pageSize 上限 500。 */
export function listPatients(query?: PatientPageQuery) {
  return http.get<Patient[]>('/patient', { params: query }).then((r) => r.data)
}

/**
 * R-07: 按 ID 批量查询患者姓名(仅 id + name,不含 PII)。
 * 用于替代大屏 / 下拉框的全量患者拉取,和 listPatients 的分页上限配套。
 */
export function fetchPatientNames(ids: number[]) {
  return http
    .get<PatientNameView[]>('/patient/names', { params: { ids: ids.join(',') } })
    .then((r) => r.data)
}

export function updatePatient(id: number, payload: PatientRegisterPayload) {
  return http.put<Patient>(`/patient/${id}`, payload).then((r) => r.data)
}

export function searchPatients(keyword: string) {
  return http.get<Patient[]>('/patient/search', { params: { keyword } }).then((r) => r.data)
}

/** 我的预约列表(后端按当前登录用户解析,无需传患者ID) */
export function listMyAppointments() {
  return http.get<AppointmentDetail[]>('/patient/appointments').then((r) => r.data)
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

/** 重置患者密码为默认值(123456) */
export function resetPatientPassword(userId: number) {
  return http.post<{ message: string }>(`/core/iam/users/${userId}/reset-password`).then((r) => r.data)
}
