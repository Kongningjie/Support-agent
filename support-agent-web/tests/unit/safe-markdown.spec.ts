import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SafeMarkdown from '@/components/content/SafeMarkdown.vue'

describe('SafeMarkdown', () => {
  it('禁用原始 HTML 并阻止 javascript 链接', () => {
    const wrapper = mount(SafeMarkdown, {
      props: {
        content: '<img src=x onerror=alert(1)> [危险](javascript:alert(1)) **安全文本**',
      },
    })
    expect(wrapper.html()).not.toContain('<img')
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('href="javascript:')
    expect(wrapper.text()).toContain('安全文本')
  })

  it('为普通外部链接添加隔离属性', () => {
    const wrapper = mount(SafeMarkdown, {
      props: { content: '[公开资料](https://example.com/docs)' },
    })
    const link = wrapper.get('a')
    expect(link.attributes('href')).toBe('https://example.com/docs')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
  })
})
