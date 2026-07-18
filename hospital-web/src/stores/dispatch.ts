import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getBoard, startTask, completeTask } from '@/api/dispatch'
import type { QueueBoardRow } from '@/types/dispatch'

export const useDispatchStore = defineStore('dispatch', () => {
  const rows = ref<QueueBoardRow[]>([])
  const loading = ref(false)
  const stationFilter = ref<string>('')

  async function fetchBoard() {
    loading.value = true
    try {
      rows.value = await getBoard(stationFilter.value || undefined)
    } finally {
      loading.value = false
    }
  }

  async function start(id: number) {
    await startTask(id)
    await fetchBoard()
  }

  async function complete(id: number) {
    await completeTask(id)
    await fetchBoard()
  }

  return { rows, loading, stationFilter, fetchBoard, start, complete }
})
