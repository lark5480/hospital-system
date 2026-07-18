import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RouteLocationNormalized } from 'vue-router'

/**
 * 多标签页导航状态(Pinia store)。
 * - 监听路由变化(open)自动维护已打开的标签;列表页按 path 去重(重复点击聚焦),
 *   详情页 path 含 id,天然互不相同 → 打开多个详情互不覆盖。
 * - KeepAlive 缓存各标签组件实例,切换标签保留页面状态(滚动/表单/查询)。
 * - sessionStorage 轻量持久化,刷新后恢复标签列表(组件状态仍由 KeepAlive 重新拉取)。
 */
export interface TabItem {
  path: string
  title: string
}

function computeTitle(route: RouteLocationNormalized): string {
  const base = (route.meta?.title as string) || '未命名页面'
  const id = route.params?.id
  return id ? `${base} #${id}` : base
}

const STORAGE_KEY = 'hospital-tabs'

export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<TabItem[]>([])
  const active = ref<string>('')
  // 刷新计数:改变组件的 key 强制重挂载(重新拉取数据),用于"刷新当前"操作
  const reloadTick = ref<Record<string, number>>({})

  function persist() {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ tabs: tabs.value, active: active.value }))
    } catch {
      /* sessionStorage 不可用时忽略 */
    }
  }

  function load() {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY)
      if (raw) {
        const data = JSON.parse(raw)
        tabs.value = data.tabs ?? []
        active.value = data.active ?? ''
      }
    } catch {
      /* 解析失败忽略,从头开始 */
    }
  }

  /** 导航到某路由时调用:打开(或聚焦)对应标签。 */
  function open(route: RouteLocationNormalized) {
    // 无标题的路由(redirect / 404)不计入标签
    if (!route.meta?.title) return
    const path = route.path
    const title = computeTitle(route)
    const existing = tabs.value.find((t) => t.path === path)
    if (existing) {
      if (existing.title !== title) existing.title = title
    } else {
      tabs.value.push({ path, title })
    }
    active.value = path
    persist()
  }

  /** 点击标签切换时调用:仅更新激活态(实际跳转由调用方 router.push)。 */
  function activate(path: string) {
    active.value = path
    persist()
  }

  /**
   * 关闭标签。返回关闭后应跳转的 path(可能为空字符串表示无标签可跳)。
   * 调用方据此执行 router.push。
   */
  function close(path: string): string {
    const idx = tabs.value.findIndex((t) => t.path === path)
    if (idx === -1) return active.value
    tabs.value.splice(idx, 1)
    delete reloadTick.value[path]
    let target = ''
    if (active.value === path) {
      const neighbor = tabs.value[idx] ?? tabs.value[idx - 1] ?? null
      target = neighbor ? neighbor.path : ''
      active.value = target
    }
    persist()
    return target
  }

  /** 关闭除指定标签外的全部。返回 null 表示无需跳转(保持当前激活)。 */
  function closeOthers(path: string): string | null {
    const keep = tabs.value.find((t) => t.path === path)
    if (!keep) return active.value
    tabs.value = [keep]
    const keptTick = reloadTick.value[path]
    reloadTick.value = keptTick ? { [path]: keptTick } : {}
    active.value = path
    persist()
    return null
  }

  /** 关闭全部标签,返回首页路径(调用方跳转后 afterEach 会重新打开 dashboard 标签)。 */
  function closeAll(): string {
    tabs.value = []
    reloadTick.value = {}
    active.value = ''
    persist()
    return '/dashboard'
  }

  /** 刷新某标签:递增 reloadTick,使组件 key 变化 → 强制重挂载重新拉取。 */
  function reload(path: string) {
    reloadTick.value[path] = (reloadTick.value[path] ?? 0) + 1
  }

  /**
   * 清空全部标签状态(退出登录时调用)。
   * sessionStorage 会跨"同标签页的登录态切换"保留,必须显式清除,
   * 否则重新登录后会从持久化里读回上一会话的标签(隐私/会话隔离问题)。
   */
  function reset() {
    tabs.value = []
    active.value = ''
    reloadTick.value = {}
    try {
      sessionStorage.removeItem(STORAGE_KEY)
    } catch {
      /* 忽略 */
    }
  }

  return { tabs, active, reloadTick, load, open, activate, close, closeOthers, closeAll, reload, reset }
})
