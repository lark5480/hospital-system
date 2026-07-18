import http from './http'
import type { QueueBoardRow } from '@/types/dispatch'
import type { ExamTask } from '@/types/dispatch'

export function getMyQueue() {
  return http.get<ExamTask[]>('/core/dispatch/my-queue').then((r) => r.data)
}

export function getBoard(station?: string) {
  return http
    .get<QueueBoardRow[]>('/core/dispatch/board', { params: station ? { station } : {} })
    .then((r) => r.data)
}

export function startTask(id: number) {
  return http.post<void>(`/core/dispatch/${id}/start`).then((r) => r.data)
}

export function completeTask(id: number) {
  return http.post<void>(`/core/dispatch/${id}/complete`).then((r) => r.data)
}

/** 活跃工位列表(有待检/检查中任务),供大屏选择。 */
export function getStations() {
  return http.get<string[]>('/core/dispatch/stations').then((r) => r.data)
}

/** 自动叫号:取该 station 下一个待检任务置为检查中,返回被叫到的任务(无则 null)。 */
export function callNext(station: string) {
  return http
    .post<ExamTask | null>('/core/dispatch/call-next', null, { params: { station } })
    .then((r) => r.data)
}

/** 过号重排:把某待检任务排到该 station 队尾。 */
export function reorderTail(id: number) {
  return http.post<void>(`/core/dispatch/tasks/${id}/reorder-tail`).then((r) => r.data)
}

/** 跳过:放弃某任务置 SKIPPED。 */
export function skipTask(id: number) {
  return http.post<void>(`/core/dispatch/tasks/${id}/skip`).then((r) => r.data)
}
