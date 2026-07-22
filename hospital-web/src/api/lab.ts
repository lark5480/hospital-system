import http from './http'
import type { LabRequisitionListItem, LabRequisitionDetail, CreateRequisitionRequest, SubmitResultsRequest } from '@/types/lab'

export function listRequisitions(status?: string, deptId?: number) {
  const params: Record<string, any> = {}
  if (status) params.status = status
  if (deptId) params.deptId = deptId
  return http.get<LabRequisitionListItem[]>('/lab/requisitions', { params }).then((r) => r.data)
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
