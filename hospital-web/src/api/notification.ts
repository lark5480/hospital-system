import http from './http'
import type { NotificationListResult } from '@/types/notification'

/** 拉取通知服务记录的最近领域事件(演示事件驱动链路)。 */
export function listEvents() {
  return http.get<NotificationListResult>('/notify/events').then((r) => r.data)
}
