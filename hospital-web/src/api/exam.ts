import http from './http'

export interface ExamTask {
  orderId: number
  visitId: number
  patientName: string
  doctorName: string
  itemName: string
  status: string
  finding: string | null
}

export function listPendingExams() {
  return http.get<ExamTask[]>('/core/visits/exams/pending').then((r) => r.data)
}

export function listExams(status?: string) {
  return http.get<ExamTask[]>('/core/visits/exams', { params: { status } }).then((r) => r.data)
}

export function executeExam(visitId: number, orderId: number, finding?: string) {
  return http.post(`/core/visits/${visitId}/exams/${orderId}/execute`,
    finding ? { finding } : undefined
  ).then((r) => r.data)
}
