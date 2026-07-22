import type { Order, Visit, VisitCreatePayload, VisitDetail } from '@/types/visit'
import http from './http'

export interface PageResult<T> {
  items: T[]
  total: number
  pageNum: number
  pageSize: number
}

export function listVisitsPage(keyword: string, pageNum: number, pageSize: number) {
  return http.get<PageResult<VisitDetail>>('/core/visits/page', {
    params: { keyword, pageNum, pageSize }
  }).then((r) => r.data)
}

export function createVisit(payload: VisitCreatePayload) {
  return http.post<Visit>('/core/visits', payload).then((r) => r.data)
}

export function getVisitDetail(id: number) {
  return http.get<VisitDetail>(`/core/visits/${id}/detail`).then((r) => r.data)
}

/** 追加医嘱(后端在同一事务生成对应收费,返回刷新后的就诊详情) */
export function addOrder(
  visitId: number,
  payload: Pick<Order, 'type' | 'itemName' | 'quantity' | 'unitPrice'>
) {
  return http.post<VisitDetail>(`/core/visits/${visitId}/orders`, payload).then((r) => r.data)
}

/** 修改一条未执行的医嘱(后端同步更新对应收费记录) */
export function updateOrder(
  visitId: number,
  orderId: number,
  payload: Pick<Order, 'type' | 'itemName' | 'quantity' | 'unitPrice'>
) {
  return http.put<VisitDetail>(`/core/visits/${visitId}/orders/${orderId}`, payload).then((r) => r.data)
}

/** 取消一条未执行的医嘱(后端同步删除对应未收费记录) */
export function cancelOrder(visitId: number, orderId: number) {
  return http.delete<VisitDetail>(`/core/visits/${visitId}/orders/${orderId}`).then((r) => r.data)
}

/** 退费:作废一条未执行的医嘱,已缴费用置为已退(REFUNDED),未缴费用直接删除 */
export function refundOrder(visitId: number, orderId: number) {
  return http.post<VisitDetail>(`/core/visits/${visitId}/orders/${orderId}/refund`).then((r) => r.data)
}

/** 结算该就诊全部未缴收费 */
export function payVisit(visitId: number) {
  return http.post<VisitDetail>(`/core/visits/${visitId}/pay`).then((r) => r.data)
}

/** 执行检查类医嘱(如 B 超 / CT),标记为已执行,记录检查所见 */
export function executeExamOrder(visitId: number, orderId: number, finding?: string) {
  return http.post<VisitDetail>(`/core/visits/${visitId}/exams/${orderId}/execute`,
    finding ? { finding } : undefined
  ).then((r) => r.data)
}

/** 确单:草稿 → 已确单,锁定后不可再追加/修改/取消医嘱 */
export function confirmVisit(visitId: number) {
  return http.post<VisitDetail>(`/core/visits/${visitId}/confirm`).then((r) => r.data)
}

/** 手动结束就诊:CONFIRMED/IN_PROGRESS → FINISHED;force=true 时强制作废未执行医嘱 */
export function finishVisit(visitId: number, force = false) {
  return http.post<VisitDetail>(`/core/visits/${visitId}/finish`, null, { params: { force } }).then((r) => r.data)
}

/** 删除草稿状态就诊单(级联删除医嘱 + 收费)。仅 CREATED 状态可删。 */
export function deleteVisit(visitId: number) {
  return http.delete(`/core/visits/${visitId}`).then((r) => r.data)
}

/** 患者历史就诊记录(含医嘱),供新建就诊时医生参考。 */
export interface PatientVisitHistory {
  visitId: number
  visitTime: string | null
  status: string
  chiefComplaint: string
  doctorName: string | null
  deptName: string | null
  orders: { orderId: number; type: string; itemName: string; status: string; finding: string | null }[]
}

export function getPatientHistory(patientId: number) {
  return http.get<PatientVisitHistory[]>('/core/visits/patient-history', { params: { patientId } }).then((r) => r.data)
}
