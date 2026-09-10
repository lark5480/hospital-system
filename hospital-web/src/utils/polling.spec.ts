import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createVisibilityAwarePoller } from '@/utils/polling'

/** jsdom 默认 document.hidden 为 false(可见);此处可覆写以模拟切后台。 */
function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, value: hidden })
}

function emitVisibilityChange() {
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('createVisibilityAwarePoller (R-40)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    setHidden(false)
  })

  afterEach(() => {
    vi.useRealTimers()
    setHidden(false)
  })

  it('页面可见时按 interval 周期执行', () => {
    const fn = vi.fn()
    const poller = createVisibilityAwarePoller(fn, 5000)
    poller.start()

    vi.advanceTimersByTime(5000)
    expect(fn).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(5000)
    expect(fn).toHaveBeenCalledTimes(2)

    poller.stop()
  })

  it('页面隐藏时暂停轮询,不再触发', () => {
    const fn = vi.fn()
    const poller = createVisibilityAwarePoller(fn, 5000)
    poller.start()
    vi.advanceTimersByTime(5000)
    expect(fn).toHaveBeenCalledTimes(1)

    setHidden(true)
    emitVisibilityChange()
    vi.advanceTimersByTime(20000)
    // 隐藏期间不应再有请求
    expect(fn).toHaveBeenCalledTimes(1)

    poller.stop()
  })

  it('页面重新可见时立即拉取一次并恢复轮询', () => {
    const fn = vi.fn()
    const poller = createVisibilityAwarePoller(fn, 5000)
    poller.start()

    setHidden(true)
    emitVisibilityChange()
    vi.advanceTimersByTime(5000)
    expect(fn).toHaveBeenCalledTimes(0)

    setHidden(false)
    emitVisibilityChange()
    // 恢复可见 → 立即执行一次
    expect(fn).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(5000)
    // 随后恢复周期轮询
    expect(fn).toHaveBeenCalledTimes(2)

    poller.stop()
  })

  it('初始即处于隐藏态时不建立定时器', () => {
    setHidden(true)
    const fn = vi.fn()
    const poller = createVisibilityAwarePoller(fn, 5000)
    poller.start()

    vi.advanceTimersByTime(20000)
    expect(fn).toHaveBeenCalledTimes(0)

    poller.stop()
  })

  it('stop() 后既不再触发,也移除了 visibilitychange 监听', () => {
    const fn = vi.fn()
    const poller = createVisibilityAwarePoller(fn, 5000)
    poller.start()
    poller.stop()

    vi.advanceTimersByTime(20000)
    expect(fn).toHaveBeenCalledTimes(0)

    // 监听已移除:即便随后可见性变化也不会重新拉起轮询
    setHidden(false)
    emitVisibilityChange()
    vi.advanceTimersByTime(20000)
    expect(fn).toHaveBeenCalledTimes(0)
  })
})
