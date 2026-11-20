import { createRuntime } from '../bootstrap.js'

export interface KmpAttachment {
  id: string
  name: string
  mediaType: string
  sizeBytes: number
}

export interface KmpBlock {
  id: string
  kind: 'text' | 'thinking' | 'search' | 'readLink'
  text: string
  collapsed?: boolean
  startedAt?: number | null
  endedAt?: number | null
  status?: string | null
  citations?: Array<{ type?: string; url: string; title?: string | null; citedText: string; encryptedIndex?: string }>
}

export interface KmpSource {
  url: string
  title: string
  siteName: string
  publishedAt?: string | null
  snippet?: string | null
  siteIcon?: string | null
  index: number
}

export interface KmpMessage {
  id: string
  role: 'User' | 'Assistant'
  status: 'Draft' | 'Streaming' | 'Completed' | 'Failed' | 'Cancelled'
  error?: string | null
  blocks: KmpBlock[]
  sources: KmpSource[]
  createdAt: number
  attachments: KmpAttachment[]
}

export interface KmpSession {
  id: string
  title: string
  pinned: boolean
  updatedAt: number
  messages: KmpMessage[]
}

export interface KmpState {
  ready: boolean
  configured: boolean
  keyMasked: string | null
  keyChecking: boolean
  keyError: string | null
  storageError: string | null
  draft: string
  thinking: boolean
  search: boolean
  theme: string
  fontScale: number
  selectedId: string | null
  activeId: string | null
  notice: string | null
  sessions: KmpSession[]
  attachments: KmpAttachment[]
  preparingAttachments: boolean
  drafts: Record<string, string>
  draftAttachments: Record<string, KmpAttachment[]>
}

export const runtime = createRuntime()
export const client = runtime.client
export let latestState: KmpState | null = null

export const startRuntime = () => {
  client.start((raw: string) => {
    try {
      latestState = JSON.parse(raw) as KmpState
      window.dispatchEvent(new CustomEvent('deepseek:kmp-state', { detail: latestState }))
    } catch (error) {
      console.error('共享对话状态读取失败', error)
    }
  })
}

export const onKmpState = (listener: (state: KmpState) => void) => {
  const handler = (event: Event) => listener((event as CustomEvent<KmpState>).detail)
  window.addEventListener('deepseek:kmp-state', handler)
  if (latestState) listener(latestState)
  return () => window.removeEventListener('deepseek:kmp-state', handler)
}

export const waitForKmpState = async (
  predicate: (state: KmpState) => boolean,
  timeout = 20_000
): Promise<KmpState> => {
  if (latestState && predicate(latestState)) return latestState
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { off(); reject(new Error('等待共享客户端状态超时')) }, timeout)
    const off = onKmpState(state => {
      if (predicate(state)) { clearTimeout(timer); off(); resolve(state) }
    })
  })
}
