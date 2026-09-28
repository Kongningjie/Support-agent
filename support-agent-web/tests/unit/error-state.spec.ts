import { mount } from '@vue/test-utils'
import { ElButton } from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import ErrorState from '@/components/feedback/ErrorState.vue'

describe('统一错误状态', () => {
  it('可重试错误提供键盘可用的重试动作', async () => {
    const wrapper = mount(ErrorState, {
      global: { components: { ElButton } },
      props: {
        message: '依赖服务暂不可用',
        retryable: true,
      },
    })

    await wrapper.get('button').trigger('click')

    expect(wrapper.attributes('role')).toBe('alert')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('只复制公开追踪标识且不渲染重试按钮', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    const wrapper = mount(ErrorState, {
      global: { components: { ElButton } },
      props: {
        message: '请求参数不符合要求',
        traceId: 'trace-public-400',
        retryable: false,
      },
    })

    await wrapper.get('button[aria-label="复制追踪标识"]').trigger('click')

    expect(writeText).toHaveBeenCalledWith('trace-public-400')
    expect(wrapper.text()).not.toContain('重新尝试')
  })
})
