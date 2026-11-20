import { create } from 'zustand'
import type { LinkedMessage, TranslationMessage } from '../i18n/index.js'
import { client } from '../runtime/client.js'

export const ACCEPTED_EXTS: string[] = client.acceptedFiles().split(',').map((ext: string) => ext.replace(/^\./, '').toLowerCase())
export const ACCEPT = ACCEPTED_EXTS.map(ext => `.${ext}`).join(',')
export const MAX_SIZE_MB = 64
export const MAX_COUNT = 50
const MAX_USER_DATA_SIZE_MB = 32
const MIME_BY_EXT: Record<string, string> = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp',
  tif: 'image/tiff', tiff: 'image/tiff', svg: 'image/svg+xml', avif: 'image/avif', pdf: 'application/pdf',
  txt: 'text/plain', md: 'text/markdown', csv: 'text/csv', json: 'application/json',
}
export const getFileExt = (name: string) => {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
}
export const getExtension = (name: string) => getFileExt(name).toUpperCase()
export const isAcceptedExt = (name: string) => ACCEPTED_EXTS.includes(getFileExt(name))
export const resolveMimeType = (file: File) => file.type || MIME_BY_EXT[getFileExt(file.name)] || 'application/octet-stream'
export const isImage = (mimeType: string) => mimeType.startsWith('image/')
export const getFileViewType = (name: string) => MIME_BY_EXT[getFileExt(name)] || ''
export const formatSize = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024, unit = 0
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++ }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`
}
export const validateFiles = (files: File[], currentCount: number): TranslationMessage | null => {
  if (files.some(file => !isAcceptedExt(file.name))) return { key: 'uploadUnsupportedFormat' }
  const oversized = files.find(file => file.size > (['pdf','doc','docx','ppt','pptx'].includes(getFileExt(file.name)) ? MAX_SIZE_MB : MAX_USER_DATA_SIZE_MB) * 1024 * 1024)
  if (oversized) return { key: 'uploadMaxMBSize', params: { size: MAX_SIZE_MB } }
  if (currentCount + files.length > MAX_COUNT) return { key: 'uploadMaxFiles', params: { count: MAX_COUNT } }
  return null
}
export type UploadTask = { status: 'uploading' } | { status: 'done'; serverId: string } | { status: 'error'; error: TranslationMessage; retryable: boolean }
export const useUploads = create<{ tasks: Record<string, UploadTask> }>(() => ({ tasks: {} }))
export const setUploadTask = (id: string, task: UploadTask | null) => useUploads.setState(state => {
  const tasks = { ...state.tasks }
  task ? tasks[id] = task : delete tasks[id]
  return { tasks }
})
export const cancelUpload = (id: string) => { setUploadTask(id, null); client.removeAttachment(id) }
export const uploadAttachment = async (_key: string, _id: string, file: Blob, _name: string, _isImage: boolean) => {
  client.addFiles([file instanceof File ? file : new File([file], _name)])
  return 'local'
}
export const onUploadNotice = (_listener: (notice: LinkedMessage) => void) => () => {}
