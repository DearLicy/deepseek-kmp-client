import type { TranslationKey } from '../../i18n/index.js'
import { client } from '../../runtime/client.js'
import { exportSnapshot } from './store.js'
export class BackupError extends Error { constructor(readonly key: TranslationKey) { super(key) } }
export const exportHistory = async () => ({
  name: `deepseek-local-${new Date().toISOString().slice(0, 10)}.json`,
  blob: new Blob([JSON.stringify({ format: 'deepseek-kmp-client', version: 1, ...exportSnapshot() }, null, 2)], { type: 'application/json' }),
})
export const importHistory = async (_file: File) => { throw new BackupError('backupUnsupported') }
export const clearHistory = async () => { client.deleteAll() }
