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

/** 上传附件到文件服务(MinIO),返回对象名与桶。 */
export function uploadFile(file: File) {
  const form = new FormData()
  form.append('file', file)
  return http
    .post<UploadResult>('/files/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    .then((r) => r.data)
}

/** 文件列表:按业务类型 / 患者过滤(供文件管理查询)。 */
export function listFiles(params?: { bizType?: string; patientId?: number }) {
  return http.get<FileMeta[]>('/files', { params }).then((r) => r.data)
}

/** 下载对象字节(后端流式返回),返回 Blob。 */
export function downloadFile(objectName: string) {
  return http
    .get<Blob>('/files/' + objectName, { responseType: 'blob' })
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
