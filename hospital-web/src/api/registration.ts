import http from './http'

export interface Registration {
  id: number
  patientId: number
  deptId: number
  doctorId: number | null
  queueNo: number
  status: string
  visitId: number | null
  createdAt: string
  calledAt: string | null
}

export function register(data: { patientId: number; deptId: number; doctorId?: number }) {
  return http.post<Registration>('/core/registrations', data).then(r => r.data)
}

export function callNext(deptId: number) {
  return http.post<Registration>('/core/registrations/call-next', null, { params: { deptId } }).then(r => r.data)
}

export function cancelRegistration(id: number) {
  return http.post<Registration>(`/core/registrations/${id}/cancel`).then(r => r.data)
}

export function listRegistrations(deptId: number) {
  return http.get<Registration[]>('/core/registrations', { params: { deptId } }).then(r => r.data)
}

export function activeQueue(deptId: number) {
  return http.get<Registration[]>('/core/registrations/active', { params: { deptId } }).then(r => r.data)
}
