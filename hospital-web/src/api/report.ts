import http from './http'
import type { Report, ReportDetail } from '@/types/report'

export function listReportsByType(type: string) {
  return http.get<Report[]>(`/reports/type/${type}`).then((r) => r.data)
}

export function listAllReports() {
  return http.get<Report[]>('/reports/list').then((r) => r.data)
}

/** 查询患者已发布的检验/检查报告(含解析名称),供新建就诊时查阅历史。 */
export function listReportsByPatient(patientId: number) {
  return http.get<ReportDetail[]>(`/reports/patient/${patientId}`).then((r) => r.data)
}

export function publishReport(id: number) {
  return http.post<Report>(`/reports/${id}/publish`).then((r) => r.data)
}
