export type PrescriptionStatus = 'PENDING' | 'DISPENSING' | 'DISPENSED' | 'CANCELLED'
export type ItemStatus = 'PENDING' | 'DISPENSED' | 'CANCELLED'

export interface Prescription {
  id: number
  visitId: number
  patientId: number
  doctorId: number
  pharmacistId: number | null
  status: PrescriptionStatus
  remark: string | null
  createdAt: string
  dispensedAt: string | null
}

export interface PrescriptionItem {
  id: number
  prescriptionId: number
  orderId: number | null
  itemName: string
  quantity: number
  unitPrice: number
  status: ItemStatus
}

export interface PrescriptionDetail {
  prescription: Prescription
  items: PrescriptionItem[]
  patientName?: string
  doctorName?: string
  pharmacistName?: string
}

export interface CreatePrescriptionRequest {
  visitId: number
  doctorId: number
}
