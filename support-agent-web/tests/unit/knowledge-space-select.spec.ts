import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import KnowledgeSpaceSelect from '@/components/knowledge-space/KnowledgeSpaceSelect.vue'
import * as knowledgeSpaceApi from '@/api/knowledge-space.api'
import type { KnowledgeSpaceView } from '@/types/knowledge-space.types'

afterEach(() => vi.restoreAllMocks())

describe('KnowledgeSpaceSelect', () => {
  it('只展示后端返回空间且不会自动选择 GLOBAL', async () => {
    vi.spyOn(knowledgeSpaceApi, 'listAllReadableKnowledgeSpaces').mockResolvedValue([sampleSpace()])
    const wrapper = mount(KnowledgeSpaceSelect, {
      props: { modelValue: null, inputId: 'space-test' },
    })
    await flushPromises()

    const select = wrapper.get('select')
    expect(select.element.value).toBe('')
    expect(wrapper.text()).toContain('企业公共空间（GLOBAL）')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    await select.setValue('00000000-0000-0000-0000-000000000001')
    expect(wrapper.emitted('update:modelValue')).toEqual([['00000000-0000-0000-0000-000000000001']])
  })
})

/** 创建空间选择组件测试使用的 GLOBAL 空间。 */
function sampleSpace(): KnowledgeSpaceView {
  return {
    spaceId: '00000000-0000-0000-0000-000000000001',
    code: 'GLOBAL',
    name: '企业公共空间',
    description: '企业公共知识',
    visibility: 'ENTERPRISE',
    status: 'ACTIVE',
    systemSpace: true,
    version: 0,
    currentUserRole: null,
    createdAt: '2026-09-27T00:00:00Z',
    updatedAt: '2026-09-27T00:00:00Z',
  }
}
