import http from './http'
import type { LabRequisition, LabRequisitionDetail, CreateRequisitionRequest, SubmitResultsRequest } from '@/types/lab'

export function listRequisitions(status?: string) {
  const params = status ? { status } : {}
  return http.get<LabRequisition[]>('/lab/requisitions', { params }).then((r) => r.data)
}

export function getRequisition(id: number) {
  return http.get<LabRequisitionDetail>(`/lab/requisitions/${id}`).then((r) => r.data)
}

export function createRequisition(payload: CreateRequisitionRequest) {
  return http.post<LabRequisition>('/lab/requisitions', payload).then((r) => r.data)
}

export function submitResults(id: number, payload: SubmitResultsRequest) {
  return http.post<LabRequisition>(`/lab/requisitions/${id}/results`, payload).then((r) => r.data)
}

export function cancelRequisition(id: number) {
  return http.post<LabRequisition>(`/lab/requisitions/${id}/cancel`).then((r) => r.data)
}
