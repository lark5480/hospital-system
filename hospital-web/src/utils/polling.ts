/**
 * R-40: 页面可见性感知的轮询工具。
 *
 * 目的:大屏 / 标签页被切到后台或最小化后,`setInterval` 仍会在后台持续请求,
 * 白白消耗服务端与连接。这里把「定时轮询」与 Page Visibility API 绑定:
 *  - 页面隐藏(document.hidden)时暂停定时器;
 *  - 页面重新可见时立即拉取一次并恢复轮询;
 *  - 创建时若页面已处于隐藏态,则不启动定时器(但仍挂监听,恢复可见后自动开始)。
 *
 * 监听器与定时器统一由 `stop()` 清理,避免泄漏。
 */

/** 轮询器句柄。 */
export interface VisibilityAwarePoller {
  /**
   * 启动轮询。重复调用安全(内部先 `stop()` 再重建),
   * 也可用于 KeepAlive 页面 `onActivated` 时恢复。
   */
  start(): void
  /** 停止轮询并移除 visibilitychange 监听。幂等。 */
  stop(): void
}

/**
 * 基于 Page Visibility 的轮询器工厂。
 *
 * @param fn        每次轮询执行的回调(通常为数据拉取函数)
 * @param intervalMs 轮询间隔(毫秒)
 */
export function createVisibilityAwarePoller(
  fn: () => void,
  intervalMs: number
): VisibilityAwarePoller {
  let timer: ReturnType<typeof setInterval> | null = null
  let listening = false

  function clearTimer() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function onVisibilityChange() {
    if (document.hidden) {
      // 页面隐藏 → 暂停,避免后台空跑
      clearTimer()
    } else {
      // 页面重新可见 → 立即拉取一次并恢复轮询
      fn()
      if (timer === null) {
        timer = setInterval(fn, intervalMs)
      }
    }
  }

  function start() {
    stop()
    document.addEventListener('visibilitychange', onVisibilityChange)
    listening = true
    // 初始判断:仅当页面可见时才建立定时器
    if (!document.hidden) {
      timer = setInterval(fn, intervalMs)
    }
  }

  function stop() {
    clearTimer()
    if (listening) {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      listening = false
    }
  }

  return { start, stop }
}
