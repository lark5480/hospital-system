/** 通知服务留痕的领域事件记录(对应 notification-service 的 NotificationRecord)。 */
export interface NotificationRecord {
  id: number
  /** 事件类型:ORDER_EXAM=检查医嘱,ORDER_LAB=检验医嘱,ORDER_MEDICATION=药品医嘱,VISIT_PAID=缴费完成 */
  type: string
  visitId: number
  patientId: number
  doctorId: number
  eventTime: string
  receivedAt: string
  channel: string
  content: string
  /** 目标角色:DOCTOR/PATIENT/PHARMACIST/CASHIER */
  targetRole: string
  /** 目标科室ID(检查/检验医嘱精确投递) */
  targetDeptId?: number | null
}

/** GET /api/notify/events 的返回结构。 */
export interface NotificationListResult {
  total: number
  items: NotificationRecord[]
}
