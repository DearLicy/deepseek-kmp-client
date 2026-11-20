import type { BrowserClient } from '@deepseek/shared'

export interface BrowserRuntime {
  client: BrowserClient & {
    acceptedFiles(): string
    addFiles(files: File[]): void
    removeAttachment(id: string): void
    editForResend(id: string): void
    localFile(id: string): Promise<Blob | null>
    input(text: string): void
    send(): void
    stop(): void
    newSession(): void
    selectSession(id: string): void
    renameSession(id: string, title: string): void
    pinSession(id: string): void
    deleteSession(id: string): void
    deleteAll(): void
    toggleThinking(): void
    toggleSearch(): void
    toggleThinkingBlock(id: string): void
    setTheme(value: string): void
    setFontScale(value: number): void
    validateAndSaveKey(value: string): void
    clearKey(): void
    dismissNotice(): void
    showNotice(message: string): void
    flush(): void
    close(): void
    start(onState: (json: string) => void): void
  }
  vault: { configured: boolean; masked: string | null; clear(): void }
  snapshots: unknown
}

export function createRuntime(): BrowserRuntime
