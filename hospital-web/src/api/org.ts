import http from './http'
import type { Department, DepartmentForm, Staff, StaffForm } from '@/types/org'

export function listDepartments() {
  return http.get<Department[]>('/core/org/departments/list').then((r) => r.data)
}

export function getDepartment(id: number) {
  return http.get<Department>(`/core/org/departments/${id}`).then((r) => r.data)
}

export function createDepartment(data: DepartmentForm) {
  return http.post<Department>('/core/org/departments', data).then((r) => r.data)
}

export function updateDepartment(id: number, data: Partial<DepartmentForm>) {
  return http.put<Department>(`/core/org/departments/${id}`, data).then((r) => r.data)
}

export function deleteDepartment(id: number) {
  return http.delete(`/core/org/departments/${id}`)
}

/** 当前登录用户对应的员工信息 */
export function getCurrentStaff() {
  return http.get<Staff>('/core/org/staff/me').then((r) => r.data)
}

export function listStaff(position?: string, deptId?: number) {
  const params: Record<string, string> = {}
  if (position) params.position = position
  if (deptId) params.deptId = String(deptId)
  return http.get<Staff[]>('/core/org/staff/list', { params }).then((r) => r.data)
}

export function getStaff(id: number) {
  return http.get<Staff>(`/core/org/staff/${id}`).then((r) => r.data)
}

export function createStaff(data: StaffForm) {
  return http.post<Staff>('/core/org/staff', data).then((r) => r.data)
}

export function updateStaff(id: number, data: Partial<StaffForm>) {
  return http.put<Staff>(`/core/org/staff/${id}`, data).then((r) => r.data)
}

export function deleteStaff(id: number) {
  return http.delete(`/core/org/staff/${id}`)
}
