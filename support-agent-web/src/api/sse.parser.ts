/** 尚未完成的 SSE 帧字段。 */
interface PendingSseFrame {
  event: string | null
  id: string | null
  data: string[]
}

/** 解析完成且包含业务数据的 SSE 帧。 */
export interface ParsedSseFrame {
  event: string | null
  id: string | null
  data: string
}

/**
 * 增量解析任意 Chunk 边界下的 SSE 文本。
 * 支持 CRLF、多行 data、注释心跳和最后一个无空行帧。
 */
export class SseParser {
  private buffer = ''
  private frame: PendingSseFrame = this.emptyFrame()

  /**
   * 输入一段已完成 UTF-8 解码的文本。
   *
   * @param chunk 当前网络文本片段
   * @returns 本次新完成的业务帧
   */
  push(chunk: string): ParsedSseFrame[] {
    this.buffer += chunk
    const frames: ParsedSseFrame[] = []
    let newline = this.buffer.indexOf('\n')
    while (newline >= 0) {
      const rawLine = this.buffer.slice(0, newline)
      this.buffer = this.buffer.slice(newline + 1)
      this.consumeLine(rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine, frames)
      newline = this.buffer.indexOf('\n')
    }
    return frames
  }

  /**
   * 在响应流正常结束时冲刷剩余文本和最后一帧。
   *
   * @returns 最后完成的业务帧
   */
  finish(): ParsedSseFrame[] {
    const frames: ParsedSseFrame[] = []
    if (this.buffer.length > 0) {
      const line = this.buffer.endsWith('\r') ? this.buffer.slice(0, -1) : this.buffer
      this.buffer = ''
      this.consumeLine(line, frames)
    }
    this.dispatch(frames)
    return frames
  }

  /** 解析一行 SSE 字段或空行分隔符。 */
  private consumeLine(line: string, frames: ParsedSseFrame[]): void {
    if (line === '') {
      this.dispatch(frames)
      return
    }
    if (line.startsWith(':')) {
      return
    }
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'event') this.frame.event = value
    else if (field === 'id' && !value.includes('\0')) this.frame.id = value
    else if (field === 'data') this.frame.data.push(value)
  }

  /** 只派发包含 data 的业务帧，注释心跳不会产生空事件。 */
  private dispatch(frames: ParsedSseFrame[]): void {
    if (this.frame.data.length > 0) {
      frames.push({
        event: this.frame.event,
        id: this.frame.id,
        data: this.frame.data.join('\n'),
      })
    }
    this.frame = this.emptyFrame()
  }

  /** 创建无跨帧状态污染的新帧。 */
  private emptyFrame(): PendingSseFrame {
    return { event: null, id: null, data: [] }
  }
}
