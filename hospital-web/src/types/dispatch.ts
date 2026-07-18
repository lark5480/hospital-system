export type TaskStatus = 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'SKIPPED'

/** 检查任务(写模型:dispatch.exam_task)。 */
export interface ExamTask {
  id: number
  appointmentId: number
  patientId: number
  packageId: number
  station: string
  itemName: string
  patientName: string | null
  status: TaskStatus
  seq: number
  startedAt: string | null
  doneAt: string | null
  createdAt: string | null
}

/** 排队看板读模型行(CQRS 投影:dispatch.queue_board)。 */
export interface QueueBoardRow {
  id: number
  station: string
  patientName: string | null
  itemName: string | null
  status: TaskStatus
  seq: number
  startedAt: string | null
  doneAt: string | null
  createdAt: string | null
}
