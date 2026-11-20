import { ApiError } from './client.js'
export type ApiErrorCondition = 'unauthorized' | 'insufficientBalance' | 'tooLarge' | 'rateLimited' | 'serverBusy' | 'network' | 'other'
export const classifyStatus = (status: number | undefined): ApiErrorCondition =>
  status === 401 ? 'unauthorized' : status === 402 ? 'insufficientBalance' : status === 413 ? 'tooLarge' : status === 429 ? 'rateLimited' : (status ?? 0) >= 500 ? 'serverBusy' : 'other'
export const classifyApiError = (error: unknown): ApiErrorCondition => {
  if (!(error instanceof ApiError)) return 'other'
  if (error.status === undefined) return error.kind === 'network' ? 'network' : 'other'
  return classifyStatus(error.status)
}
