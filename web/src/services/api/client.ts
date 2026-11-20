import type { TranslationMessage } from '../../i18n/index.js'
import { client, waitForKmpState } from '../../runtime/client.js'

export type ApiErrorKind = 'http' | 'stream' | 'network' | 'interrupted' | 'protocol'
export class ApiError extends Error {
  readonly localMessage?: TranslationMessage
  constructor(readonly kind: ApiErrorKind, message: string | TranslationMessage, readonly status?: number) {
    super(typeof message === 'string' ? message : message.key)
    this.name = 'ApiError'
    this.localMessage = typeof message === 'string' ? undefined : message
  }
}

/** Uses the same KMP BrowserClient key verification and session-only persistence. */
export const verifyApiKey = async (apiKey: string): Promise<void> => {
  client.validateAndSaveKey(apiKey)
  await waitForKmpState(state => state.keyChecking || state.configured || state.keyError !== null)
  const state = await waitForKmpState(state => !state.keyChecking)
  if (!state.configured) throw new ApiError('http', state.keyError || 'API Key 校验失败', 401)
}
