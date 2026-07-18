export type RequisitionStatus = 'PENDING' | 'EXECUTED' | 'CANCELLED'
export type ResultStatus = 'PENDING' | 'COMPLETED'

export interface LabRequisition {
  id: number
  visitId: number
  patientId: number
  doctorId: number
  technicianId: number | null
  status: RequisitionStatus
  remark: string | null
  createdAt: string
  sampledAt: string | null
  reportedAt: string | null
}

export interface LabResultItem {
  id: number
  requisitionId: number
  orderId: number | null
  itemName: string
  resultValue: string | null
  unit: string | null
  refRange: string | null
  abnormalFlag: string | null
  status: ResultStatus
}

export interface LabRequisitionDetail {
  requisition: LabRequisition
  items: LabResultItem[]
}

export interface CreateRequisitionRequest {
  visitId: number
  doctorId: number
  orderIds?: number[]
}

export interface SubmitResultsRequest {
  technicianId: number
  items: { itemId: number; resultValue: string; unit?: string; refRange?: string; abnormalFlag?: string }[]
}
