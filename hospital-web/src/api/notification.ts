import http from './http'
import type { NotificationListResult } from '@/types/notification'

/** 拉取通知服务记录的最近领域事件(演示事件驱动链路)。 */
export function listEvents() {
  return http.get<NotificationListResult>('/notify/events').then((r) => r.data)
}

/** 按角色拉取通知。 */
export function listEventsByRole(role: string) {
  return http.get<NotificationListResult>('/notify/events/by-role', { params: { role } }).then((r) => r.data)
}

/** 按科室拉取通知。 */
export function listEventsByDept(deptId: number) {
  return http.get<NotificationListResult>('/notify/events/by-dept', { params: { deptId } }).then((r) => r.data)
}
