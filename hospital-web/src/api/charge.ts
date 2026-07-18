import http from './http'

export interface ChargeVO {
  charge: {
    id: number
    visitId: number
    orderId: number | null
    itemName: string
    amount: number
    payStatus: string
    payTime: string | null
  }
  patientName: string | null
}

export function listUnpaidCharges() {
  return http.get<ChargeVO[]>('/core/charges/unpaid').then((r) => r.data)
}

export function listPaidCharges() {
  return http.get<ChargeVO[]>('/core/charges/paid').then((r) => r.data)
}

export function payVisitCharges(visitId: number) {
  return http.post(`/core/charges/${visitId}/pay`).then((r) => r.data)
}

/** 批量查询就诊单缴费状态(只读,不暴露金额):true=已缴清 / false=有未缴。 */
export function batchVisitPaymentStatus(visitIds: number[]) {
  return http.post<Record<number, boolean>>('/core/visits/payment-status', visitIds).then((r) => r.data)
}
