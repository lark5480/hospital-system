import http from './http'

export interface ExamTask {
  orderId: number
  visitId: number
  patientName: string
  doctorName: string
  itemName: string
}

export function listPendingExams() {
  return http.get<ExamTask[]>('/core/visits/exams/pending').then((r) => r.data)
}

export function executeExam(visitId: number, orderId: number) {
  return http.post(`/core/visits/${visitId}/exams/${orderId}/execute`).then((r) => r.data)
}
