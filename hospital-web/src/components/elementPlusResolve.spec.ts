import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ElLoading } from 'element-plus'
import ElementPlusResolveFixture from './__fixtures__/ElementPlusResolveFixture.vue'

/**
 * R-39 回归测试:element-plus 按需引入后,组件与指令必须仍能正确解析。
 *
 * <p>为什么必须有这层测试:`vite build` 成功、`vue-tsc` 0 错误,
 * <b>都不能证明</b>按需引入是好的 ——
 * <ul>
 *   <li>组件未被 resolver 解析时,构建照样成功,只是浏览器里渲染成未知元素;</li>
 *   <li>`v-loading` 这类<b>指令</b>不走组件 resolver,尤其容易静默失效
 *       (表现为控制台 "Failed to resolve directive: loading" + 加载动画消失,
 *        而全仓有 20 处 v-loading)。</li>
 * </ul>
 * 只有真正挂载一次才能发现。
 */
describe('element-plus 按需引入解析(R-39)', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('模板里的 el-* 组件被解析为真实组件(而非未知元素)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const wrapper = mount(ElementPlusResolveFixture, {
      global: { plugins: [ElLoading] }
    })

    // 组件确实渲染成了 element-plus 的真实 DOM 结构(带 el-button / el-tag 类名)
    expect(wrapper.find('button.el-button').exists()).toBe(true)
    expect(wrapper.find('.el-tag').exists()).toBe(true)

    // 不能出现"组件未解析"的告警
    const resolvedWarnings = warn.mock.calls
      .map(args => String(args[0]))
      .filter(msg => msg.includes('Failed to resolve component'))
    expect(resolvedWarnings).toEqual([])
  })

  it('v-loading 指令被解析并暴露加载遮罩(R-39 的隐藏坑)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const wrapper = mount(ElementPlusResolveFixture, {
      global: { plugins: [ElLoading] }
    })

    // 指令生效时会在目标元素下插入 el-loading-mask
    expect(wrapper.find('.fixture-loading .el-loading-mask').exists()).toBe(true)

    const directiveWarnings = warn.mock.calls
      .map(args => String(args[0]))
      .filter(msg => msg.includes('Failed to resolve directive'))
    expect(directiveWarnings).toEqual([])
  })

  it('组件 props 真正生效(证明是真实组件而非降级占位)', () => {
    const wrapper = mount(ElementPlusResolveFixture, {
      global: { plugins: [ElLoading] }
    })

    // 若 <el-button> 未被解析成真实组件,不会有 element-plus 依 props 生成的修饰类。
    // 这比"DOM 里有个 button 标签"更强:它证明组件逻辑真的跑了。
    expect(wrapper.find('.el-button--primary').exists()).toBe(true)
    expect(wrapper.find('.el-tag').classes().length).toBeGreaterThan(1)
  })
})
