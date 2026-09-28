import { httpClient } from './http.client'
import type { ApiResult, PageResult } from '@/types/api.types'
import type {
  ManagedDocumentAction,
  ManagedDocumentDetails,
  ManagedDocumentStatus,
  ManagedDocumentSummary,
} from '@/types/knowledge.types'

/** 按空间、状态和关键词分页读取托管知识。 */
export async function listDocuments(filters: {
  status: ManagedDocumentStatus | null
  keyword: string
  spaceId: string | null
  page: number
  size: number
}): Promise<PageResult<ManagedDocumentSummary>> {
  const response = await httpClient.get<ApiResult<PageResult<ManagedDocumentSummary>>>(
    '/knowledge/documents',
    { params: filters },
  )
  return requireData(response.data.data)
}

/** 读取知识文档完整正文和治理状态。 */
export async function getDocument(documentId: string): Promise<ManagedDocumentDetails> {
  const response = await httpClient.get<ApiResult<ManagedDocumentDetails>>(
    `/knowledge/documents/${documentId}`,
  )
  return requireData(response.data.data)
}

/** 由直接文本在指定空间创建草稿。 */
export async function createTextDocument(input: {
  spaceId: string
  title: string
  content: string
  idempotencyKey: string
}): Promise<ManagedDocumentDetails> {
  const response = await httpClient.post<ApiResult<ManagedDocumentDetails>>(
    '/knowledge/documents/text',
    input,
  )
  return requireData(response.data.data)
}

/** 上传 Markdown 或 TXT 文件并在指定空间创建草稿。 */
export async function uploadDocument(input: {
  spaceId: string
  title: string
  file: File
  idempotencyKey: string
}): Promise<ManagedDocumentDetails> {
  const form = new FormData()
  form.append('spaceId', input.spaceId)
  if (input.title.trim()) form.append('title', input.title.trim())
  form.append('file', input.file)
  form.append('idempotencyKey', input.idempotencyKey)
  const response = await httpClient.post<ApiResult<ManagedDocumentDetails>>(
    '/knowledge/documents/files',
    form,
  )
  return requireData(response.data.data)
}

/** 按当前版本保存草稿完整内容。 */
export async function reviseDocument(
  documentId: string,
  title: string,
  content: string,
  version: number,
): Promise<ManagedDocumentDetails> {
  const response = await httpClient.put<ApiResult<ManagedDocumentDetails>>(
    `/knowledge/documents/${documentId}/draft`,
    { title, content, version },
  )
  return requireData(response.data.data)
}

/** 发起文档异步发布。 */
export async function publishDocument(
  documentId: string,
  version: number,
  idempotencyKey: string,
): Promise<ManagedDocumentAction> {
  const response = await httpClient.post<ApiResult<ManagedDocumentAction>>(
    `/knowledge/documents/${documentId}/publish`,
    { version, idempotencyKey },
  )
  return requireData(response.data.data)
}

/** 归档已发布文档并发起索引删除。 */
export async function archiveDocument(
  documentId: string,
  version: number,
  archiveReason: string,
  idempotencyKey: string,
): Promise<ManagedDocumentAction> {
  const response = await httpClient.post<ApiResult<ManagedDocumentAction>>(
    `/knowledge/documents/${documentId}/archive`,
    { version, archiveReason, idempotencyKey },
  )
  return requireData(response.data.data)
}

/** 删除从未发布且版本匹配的草稿。 */
export async function deleteDocumentDraft(documentId: string, version: number): Promise<void> {
  await httpClient.delete<ApiResult<null>>(`/knowledge/documents/${documentId}`, {
    params: { version },
  })
}

/** 拒绝缺失统一响应数据的异常成功响应。 */
function requireData<T>(value: T | null): T {
  if (value === null) throw new Error('知识治理接口未返回预期数据')
  return value
}
