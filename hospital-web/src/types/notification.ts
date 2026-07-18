/** 通知服务留痕的领域事件记录(对应 notification-service 的 NotificationRecord)。 */
export interface NotificationRecord {
  id: number
  /** 事件类型:VISIT_CREATED=门诊就诊创建,PATIENT_CALLED=叫号通知 */
  type: string
  visitId: number
  patientId: number
  doctorId: number
  eventTime: string
  receivedAt: string
  channel: string
  content: string
}

/** GET /api/notify/events 的返回结构。 */
export interface NotificationListResult {
  total: number
  items: NotificationRecord[]
}
