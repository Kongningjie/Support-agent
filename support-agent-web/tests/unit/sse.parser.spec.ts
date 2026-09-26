import { describe, expect, it } from 'vitest'
import { SseParser } from '@/api/sse.parser'

describe('SseParser', () => {
  it('能够跨 Chunk 和 UTF-8 解码边界解析完整事件', () => {
    const parser = new SseParser()
    expect(parser.push('id: e1\nevent: answer.delta\nda')).toEqual([])
    expect(parser.push('ta: {"text":"你')).toEqual([])
    expect(parser.push('好"}\n\n')).toEqual([
      { id: 'e1', event: 'answer.delta', data: '{"text":"你好"}' },
    ])
  })

  it('合并多行 data 并忽略注释心跳和未知字段', () => {
    const parser = new SseParser()
    expect(
      parser.push(
        ': heartbeat\r\nretry: 1000\r\nevent: sample\r\ndata: first\r\ndata: second\r\n\r\n',
      ),
    ).toEqual([{ id: null, event: 'sample', data: 'first\nsecond' }])
  })

  it('在流结束时冲刷没有空行结尾的最后一帧', () => {
    const parser = new SseParser()
    parser.push('event: error\ndata: {"code":"FAILED"}')
    expect(parser.finish()).toEqual([{ id: null, event: 'error', data: '{"code":"FAILED"}' }])
  })
})
