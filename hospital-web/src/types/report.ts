export type ReportType = 'LAB' | 'EXAM' | 'CLINICAL'
export type ReportStatus = 'DRAFT' | 'PUBLISHED'

export interface Report {
  id: number
  visitId: number
  patientId: number
  type: ReportType
  title: string
  content: string
  doctorId: number
  status: ReportStatus
  createdAt: string
  publishedAt: string | null
}

/** 报告详情读模型:实体 + 解析后的患者/就诊单名称。 */
export interface ReportDetail extends Report {
  patientName?: string
  patientGender?: string
  patientPhone?: string
  doctorName?: string
  deptName?: string
  visitChiefComplaint?: string
}
