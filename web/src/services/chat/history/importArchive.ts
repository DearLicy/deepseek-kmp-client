import { BackupError } from './errors.js'

/**
 * The chat-demo UI is kept intact, while session persistence now belongs to the
 * KMP snapshot store. Importing the demo's IndexedDB ZIP format into that store
 * needs a shared snapshot migration API, so fail explicitly instead of silently
 * writing data that the MVI runtime cannot restore.
 */
export const importArchive = async (_file: File): Promise<void> => {
  throw new BackupError('backupUnsupported')
}
