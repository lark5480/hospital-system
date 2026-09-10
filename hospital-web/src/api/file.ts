import http from './http'

export interface UploadResult {
  objectName: string
  bucket: string
}

export interface FileMeta {
  objectName: string
  bizType?: string | null
  patientId?: number | null
  reportId?: number | null
  originalName?: string | null
  size: number
  createdAt: string
}

/**
 * 上传附件到文件服务(MinIO),返回对象名与桶。
 *
 * R-62:改为经 core 代理(`/api/core/files/upload`),不再直连网关的 `/api/files`。
 * 原因:file-service 没有用户体系,若由网关直接暴露,匿名调用者可以先不带参数
 * 拿到全部对象的 objectName + patientId,再带着该 patientId 下载 —— 归属校验形同虚设。
 * 现由 core 承担鉴权与归属判定,file-service 只在内网可达。
 * 本接口需要 visit:entry / visit:audit / system:admin 权限(患者无上传能力)。
 */
export function uploadFile(file: File) {
  const form = new FormData()
  form.append('file', file)
  return http
    .post<UploadResult>('/core/files/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    .then((r) => r.data)
}

/**
 * 文件列表:按业务类型 / 患者过滤(供文件管理查询)。
 *
 * R-62:不传 patientId 表示全量列举,该能力在服务端仅对医护 / 管理员开放;
 * 患者角色即使不传也会被强制收窄为本人文件。
 */
export function listFiles(params?: { bizType?: string; patientId?: number }) {
  return http.get<FileMeta[]>('/core/files', { params }).then((r) => r.data)
}

/**
 * 下载对象字节,返回 Blob。
 *
 * R-62:patientId 必传 —— 它是服务端做归属校验的依据(file-service 侧要求
 * 对象元数据里的 patientid 与之一致)。患者角色传自己的 id,服务端也会强制覆盖为本人。
 */
export function downloadFile(objectName: string, patientId: number) {
  return http
    .get<Blob>('/core/files/' + objectName, {
      params: { patientId },
      responseType: 'blob'
    })
    .then((r) => r.data)
}

/** 将 Blob 触发为浏览器下载。 */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
