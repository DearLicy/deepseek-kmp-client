import { create } from 'zustand'
import type { AssistantMessage, FileAttachment, Message, Session, SessionSummary } from './types.js'
import { client, latestState, onKmpState, type KmpBlock, type KmpMessage, type KmpSession, type KmpSource, type KmpState } from '../../runtime/client.js'

type ChatState = {
  ready: boolean
  initError: boolean
  importingHistory: boolean
  sessions: SessionSummary[]
  sessionCache: Record<string, Session>
  sessionLoadErrors: Record<string, boolean>
  sessionSaveErrors: Record<string, boolean>
  activeId: string | null
  streaming: Record<string, AbortController>
}

const initial: ChatState = {
  ready: false, initError: false, importingHistory: false, sessions: [], sessionCache: {},
  sessionLoadErrors: {}, sessionSaveErrors: {}, activeId: null, streaming: {},
}
export const useChatStore = create<ChatState>(() => initial)

const mapFile = (file: { id: string; name: string; mediaType: string; sizeBytes: number }): FileAttachment => ({
  id: file.id, serverId: null, name: file.name, size: file.sizeBytes,
  isImage: file.mediaType.startsWith('image/'), mimeType: file.mediaType,
})
const mapSource = (source: KmpSource) => ({
  url: source.url, title: source.title || source.url, siteName: source.siteName || '',
  publishedAt: source.publishedAt ?? null, snippet: source.snippet ?? null,
  siteIcon: source.siteIcon ?? null, index: source.index,
})
const status = (value?: string | null) => value === 'Wip' ? 'wip' : value === 'Failed' ? 'failed' : 'done'
const mapBlocks = (message: KmpMessage): AssistantMessage['blocks'] => {
  const sources = message.sources.map(mapSource)
  return message.blocks.flatMap((block: KmpBlock): AssistantMessage['blocks'] => {
    if (block.kind === 'text') return [{ type: 'text', text: block.text, citations: block.citations?.map(c => ({
      type: 'web_search_result_location' as const, url: c.url, title: c.title ?? null, cited_text: c.citedText, encrypted_index: c.encryptedIndex ?? '',
    })) }]
    if (block.kind === 'thinking') return [{ type: 'thinking', thinking: block.text, startedAt: block.startedAt ?? undefined, endedAt: block.endedAt ?? undefined }]
    const toolId = `tool-${block.id}`
    if (block.kind === 'search') {
      const tool = { type: 'server_tool_use' as const, id: toolId, name: 'deepseek_web_search' as const,
        input: { action: 'search' as const, queries: block.text ? block.text.split('、') : [] }, caller: { type: 'direct' as const }, kind: 'search' as const }
      const results = sources.map(source => ({ type: 'web_search_result' as const, title: source.title, url: source.url,
        snippet: source.snippet ?? '', site_icon: source.siteIcon, site_name: source.siteName,
        encrypted_content: '', page_age: source.publishedAt }))
      return [tool, { type: 'web_search_tool_result', tool_use_id: toolId, content: results, kind: 'search' }]
    }
    const url = block.text || sources[0]?.url || ''
    const tool = { type: 'server_tool_use' as const, id: toolId, name: 'deepseek_read_url' as const,
      input: { action: 'read_link' as const, url }, caller: { type: 'direct' as const }, kind: 'read_link' as const }
    return [tool, { type: 'web_fetch_tool_result', tool_use_id: toolId, kind: 'read_link',
      content: { type: 'web_fetch_result', url, content: { type: 'document', source: { type: 'text', media_type: 'text/plain', data: '' }, title: sources[0]?.title ?? null }, published_at: sources[0]?.publishedAt ?? null } }]
  })
}
const mapMessage = (message: KmpMessage, previous: string | null): Message => {
  if (message.role === 'User') return {
    id: message.id, role: 'user', content: message.blocks.filter(block => block.kind === 'text').map(block => block.text).join('\n'),
    files: message.attachments.map(mapFile), createdAt: message.createdAt, parentId: previous, selectedChildId: undefined,
  }
  const replyStatus = message.status === 'Streaming' ? 'streaming' : message.status === 'Completed' ? 'done' : message.status === 'Cancelled' ? 'stopped' : message.status === 'Failed' ? 'error' : 'pending'
  return {
    id: message.id, role: 'assistant', createdAt: message.createdAt, model: 'deepseek-flash',
    thinking: message.blocks.some(block => block.kind === 'thinking'), search: message.blocks.some(block => block.kind === 'search'),
    prefix: false, status: replyStatus, blocks: mapBlocks(message), stopReason: message.error && replyStatus === 'error' ? undefined : undefined,
    error: message.error ? { kind: 'stream', message: message.error, retryable: true } : undefined,
    parentId: previous, selectedChildId: undefined,
  }
}
const mapSession = (session: KmpSession): Session => {
  let previous: string | null = null
  const messages = session.messages.map((message, index) => {
    const mapped = mapMessage(message, previous)
    previous = message.id
    // chat-demo renders the selected branch by following selectedChildId from rootMessageId.
    // KMP stores a linear message list, so rebuild that display chain while projecting it.
    return { ...mapped, selectedChildId: session.messages[index + 1]?.id }
  })
  return { id: session.id, title: session.title, createdAt: session.messages[0]?.createdAt ?? session.updatedAt,
    updatedAt: session.updatedAt, messages, rootMessageId: messages[0]?.id ?? null }
}
export const syncFromKmp = (state: KmpState) => {
  const sessionCache: Record<string, Session> = {}
  for (const session of state.sessions) sessionCache[session.id] = mapSession(session)
  const sessions = state.sessions.map(session => ({ id: session.id, title: session.title,
    createdAt: session.messages[0]?.createdAt ?? session.updatedAt, updatedAt: session.updatedAt }))
    .sort((a, b) => b.updatedAt - a.updatedAt)
  const activeId = state.selectedId
  useChatStore.setState({ ready: state.ready, initError: !!state.storageError, sessions, sessionCache,
    activeId, streaming: activeId && state.activeId ? { [activeId]: {} as AbortController } : {} })
}
onKmpState(syncFromKmp)
if (latestState) syncFromKmp(latestState)

export const initChat = async () => { if (latestState) syncFromKmp(latestState) }
export const bindChatPersistence = () => {}
export const retrySaveSessions = () => {}
export const newChat = () => client.newSession()
export const selectSession = (id: string) => client.selectSession(id)
export const renameSession = (id: string, title: string) => client.renameSession(id, title)
export const removeSession = async (id: string) => { client.deleteSession(id); return true }
export const sendMessage = (_sessionId: string | null) => { client.send(); return true }
export const stopReply = (_sessionId?: string) => client.stop()
export const selectMessageVersion = (_sessionId: string, _messageId: string) => {}
export const regenerateReply = (_sessionId: string, messageId?: string) => {
  if (messageId) { client.editForResend(messageId); window.setTimeout(() => client.send(), 180) }
  return !!messageId
}
const retry = (messageId: string) => { client.editForResend(messageId); window.setTimeout(() => client.send(), 180) }
export const continueReply = (_sessionId: string, messageId: string) => { retry(messageId) }
export const retryUpload = (_sessionId: string, messageId: string) => { retry(messageId) }
export const editMessage = (sessionId: string, _messageId: string, content: string) => {
  client.selectSession(sessionId)
  client.input(content)
  window.setTimeout(() => client.send(), 60)
  return true
}
export const exportSnapshot = () => ({ sessions: useChatStore.getState().sessionCache })
