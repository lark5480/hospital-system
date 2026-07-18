import http from './http'
import type { Prescription, PrescriptionDetail, CreatePrescriptionRequest } from '@/types/pharmacy'

export function listPrescriptions(status?: string) {
  const params = status ? { status } : {}
  return http.get<Prescription[]>('/pharmacy/prescriptions', { params }).then((r) => r.data)
}

export function listPrescriptionsWithDetail(status?: string, keyword?: string) {
  const params: Record<string, string> = {}
  if (status) params.status = status
  if (keyword) params.keyword = keyword
  return http.get<PrescriptionDetail[]>('/pharmacy/prescriptions/list/detail', { params }).then((r) => r.data)
}

export function getPrescription(id: number) {
  return http.get<PrescriptionDetail>(`/pharmacy/prescriptions/${id}`).then((r) => r.data)
}

export function createPrescription(payload: CreatePrescriptionRequest) {
  return http.post<Prescription>('/pharmacy/prescriptions', payload).then((r) => r.data)
}

export function dispensePrescription(id: number) {
  // 药师身份由服务端从 JWT 派发,无需客户端传。
  return http.post<Prescription>(`/pharmacy/prescriptions/${id}/dispense`, {}).then((r) => r.data)
}

export function cancelPrescription(id: number) {
  return http.post<Prescription>(`/pharmacy/prescriptions/${id}/cancel`).then((r) => r.data)
}