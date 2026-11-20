import { create } from 'zustand'
import type { TranslationMessage } from '../../i18n/index.js'
export type ReadAloudStatus = 'idle' | 'loading' | 'playing'
export const useReadAloud = create<{ messageId: string | null; status: ReadAloudStatus }>(() => ({ messageId: null, status: 'idle' }))
export const stopReadAloud = () => useReadAloud.setState({ messageId: null, status: 'idle' })
export const describeReadAloudError = (_error: unknown): TranslationMessage | string => ({ key: 'ttsFailed' })
export const toggleReadAloud = (_messageId: string, _text: string, onError: (error: unknown) => void) => {
  onError(new Error('TTS 尚未接入当前共享客户端'))
}
