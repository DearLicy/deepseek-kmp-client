import { create } from 'zustand'
import type { TranslationMessage } from '../../i18n/index.js'
import { isImage, resolveMimeType, setUploadTask, validateFiles } from '../attachments.js'
import { client, onKmpState, type KmpAttachment } from '../../runtime/client.js'
import type { FileAttachment } from './types.js'

export type DraftAttachment = { id: string; file: File; isImage: boolean }
type Draft = { text: string; attachments: DraftAttachment[] }
export const EMPTY_DRAFT: Draft = { text: '', attachments: [] }
export const useDrafts = create<{ drafts: Map<string | null, Draft> }>(() => ({ drafts: new Map() }))
export const getDraft = (sessionId: string | null): Draft => useDrafts.getState().drafts.get(sessionId) ?? EMPTY_DRAFT
const updateDraft = (sessionId: string | null, update: (draft: Draft) => Draft) => useDrafts.setState(state => {
  const draft = update(state.drafts.get(sessionId) ?? EMPTY_DRAFT)
  const drafts = new Map(state.drafts)
  draft.text || draft.attachments.length ? drafts.set(sessionId, draft) : drafts.delete(sessionId)
  return { drafts }
})

const pendingFiles = new Map<string, File[]>()
const fileCache = new Map<string, File>()
const fileKey = (name: string, size: number) => `${name}:${size}`
const pendingFor = (sessionId: string | null, attachment: KmpAttachment) => {
  const key = sessionId ?? 'new'
  const list = pendingFiles.get(key) ?? []
  const index = list.findIndex(file => file.name === attachment.name && file.size === attachment.sizeBytes)
  if (index < 0) return null
  return list.splice(index, 1)[0]
}
const hydrate = async (attachment: KmpAttachment, file: File) => {
  if (file.size) return
  const blob = await client.localFile(attachment.id)
  if (!blob) return
  const loaded = new File([blob], attachment.name, { type: attachment.mediaType })
  fileCache.set(attachment.id, loaded)
  const current = useDrafts.getState().drafts
  const drafts = new Map(current)
  for (const [session, draft] of drafts) {
    if (draft.attachments.some(item => item.id === attachment.id)) {
      drafts.set(session, { ...draft, attachments: draft.attachments.map(item => item.id === attachment.id ? { ...item, file: loaded } : item) })
    }
  }
  useDrafts.setState({ drafts })
}

let syncing = false
onKmpState(state => {
  if (syncing) return
  syncing = true
  try {
    const keys = new Set<string | null>([null, ...(state.selectedId ? [state.selectedId] : []), ...Object.keys(state.drafts), ...Object.keys(state.draftAttachments)])
    const drafts = new Map<string | null, Draft>()
    for (const rawKey of keys) {
      const key = rawKey === null ? 'new' : rawKey
      if (key === 'new' && rawKey !== null) continue
      const text = state.drafts[key] ?? (rawKey === null ? state.draft : '') ?? ''
      const attachments = (state.draftAttachments[key] ?? (rawKey === null ? state.attachments : []) ?? []).map((attachment): DraftAttachment => {
        const file = fileCache.get(attachment.id) ?? pendingFor(rawKey, attachment) ?? new File([], attachment.name, { type: attachment.mediaType })
        if (file.size) fileCache.set(attachment.id, file)
        setUploadTask(attachment.id, { status: 'done', serverId: 'local' })
        if (!file.size) void hydrate(attachment, file)
        return { id: attachment.id, file, isImage: isImage(attachment.mediaType) }
      })
      if (text || attachments.length) drafts.set(rawKey, { text, attachments })
    }
    useDrafts.setState({ drafts })
  } finally { syncing = false }
})

export const setDraftText = (sessionId: string | null, text: string) => {
  updateDraft(sessionId, draft => ({ ...draft, text }))
  client.input(text)
}
export const addDraftFiles = (sessionId: string | null, files: File[], _apiKey: string): TranslationMessage | null => {
  if (!files.length) return null
  const error = validateFiles(files, getDraft(sessionId).attachments.length)
  if (error) return error
  const key = sessionId ?? 'new'
  pendingFiles.set(key, [...(pendingFiles.get(key) ?? []), ...files])
  client.addFiles(files)
  return null
}
export const retryDraftAttachment = (_sessionId: string | null, _id: string, _apiKey: string) => {}
export const prepareDraftMessage = (draft: Draft, _tasks: Record<string, unknown>) => {
  const text = draft.text.trim()
  if (!text && draft.attachments.length === 0) return null
  const files: (FileAttachment & { serverId: string; blob: Blob })[] = draft.attachments.map(attachment => ({
    id: attachment.id, serverId: 'local', name: attachment.file.name, size: attachment.file.size,
    isImage: attachment.isImage, mimeType: resolveMimeType(attachment.file), blob: attachment.file,
  }))
  return { text, files }
}
export const removeDraftAttachment = (sessionId: string | null, id: string) => {
  client.removeAttachment(id)
  updateDraft(sessionId, draft => ({ ...draft, attachments: draft.attachments.filter(item => item.id !== id) }))
}
export const clearSentDraft = (sessionId: string | null) => updateDraft(sessionId, () => EMPTY_DRAFT)
export const clearDraft = (sessionId: string | null) => updateDraft(sessionId, () => EMPTY_DRAFT)
export const clearAllDrafts = () => useDrafts.setState({ drafts: new Map() })
