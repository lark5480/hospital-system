// C 端(患者域 / 体检预约)前端类型

export interface Patient {
  id: number
  name: string
  username: string | null
  gender: string | null
  birthday: string | null
  phone: string
  idCard: string | null
  userId: number | null
  createdAt: string
}

/**
 * R-07: 患者姓名投影(GET /api/patient/names)。
 * 仅含 id + name,不含身份证 / 手机号等 PII,供门诊大屏与下拉框按需取姓名。
 */
export interface PatientNameView {
  id: number
  name: string
}

/** R-07: 患者列表分页参数(后端默认 pageNum=1 / pageSize=200,上限 500)。 */
export interface PatientPageQuery {
  pageNum?: number
  pageSize?: number
}

export interface PatientRegisterPayload {
  name: string
  gender?: string
  birthday?: string
  phone: string
  idCard?: string
  username?: string
}

/** 建档响应:患者记录 + 自动开通的 C 端登录凭据(账号=手机号, 临时密码) */
export interface PatientRegisterResponse {
  patient: Patient
  username: string
  /** 新患者返回初始临时密码;已存在患者返回 null */
  tempPassword: string | null
}

export interface ExamPackage {
  id: number
  name: string
  price: number
  description: string | null
}

export interface Slot {
  id: number
  packageId: number
  examDate: string
  period: string
  capacity: number
  booked: number
  /** 前端计算:capacity - booked */
  remaining: number
}

export interface Appointment {
  id: number
  patientId: number
  packageId: number
  slotId: number
  status: string
  createdAt: string
}

export interface AppointmentDetail extends Appointment {
  packageName: string | null
  examDate: string | null
  period: string | null
  payStatus: string | null
  payAmount: number | null
}

/** C 端报告读模型 */
export interface ReportRecord {
  id: number
  visitId: number | null
  patientId: number | null
  type: string
  title: string
  content: string
  doctorId: number
  status: string
  createdAt: string
  publishedAt: string | null
  /** PDF 在 MinIO 的对象名;为空表示尚未生成 */
  fileId?: string | null
  /** PDF 生成状态: PENDING / READY / FAILED */
  pdfStatus?: string | null
}

export interface BookingPayload {
  patientId: number
  packageId: number
  slotId: number
}
