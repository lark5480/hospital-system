export type VisitStatus = 'CREATED' | 'CONFIRMED' | 'IN_PROGRESS' | 'FINISHED'

export interface Visit {
  id: number
  patientId: number
  doctorId: number
  deptId: number
  chiefComplaint: string
  status: VisitStatus
  visitTime: string
  createdAt: string
}

/** 新建就诊时由前端提交的字段;其余由后端生成。 */
export interface VisitCreatePayload {
  patientId: number
  doctorId: number
  deptId: number
  chiefComplaint: string
}

export type OrderType = 'MEDICATION' | 'EXAM' | 'LAB'
export type OrderStatus = 'CREATED' | 'EXECUTED' | 'CANCELLED'
export type PayStatus = 'UNPAID' | 'PAID' | 'REFUNDED'

/** 医嘱(一次就诊可包含多条:药品 / 检查 / 检验) */
export interface Order {
  id: number
  visitId: number
  type: OrderType
  itemName: string
  quantity: number
  unitPrice: number
  amount: number
  status: OrderStatus
  /** 执行科室ID（仅检查/检验医嘱） */
  executionDeptId?: number
  /** 检查所见/结果（仅 EXAM 类医嘱） */
  finding?: string
}

/** 收费记录(每笔医嘱生成一条,随就诊同事务落库) */
export interface Charge {
  id: number
  visitId: number
  orderId: number | null
  itemName: string
  amount: number
  payStatus: PayStatus
  payTime: string | null
  refundTime?: string | null
}

/** 就诊详情读模型:就诊 + 医嘱 + 收费 + 合计 + 名称 + 收费状态 */
export interface VisitDetail {
  visit: Visit
  orders: Order[]
  charges: Charge[]
  totalAmount: number
  patientName?: string
  doctorName?: string
  deptName?: string
  payStatus?: 'ALL_PAID' | 'HAS_UNPAID' | 'NO_CHARGES'
}
