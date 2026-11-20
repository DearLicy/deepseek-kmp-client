export type TtsVoice = 'stella' | 'tide'
export const TTS_SAMPLE_RATE = 24000
export const synthesize = async (_apiKey: string, _text: string, _voice: TtsVoice, _signal: AbortSignal): Promise<ReadableStream<Uint8Array>> => {
  throw new Error('TTS 尚未接入当前共享客户端')
}
export const transcribe = async (_apiKey: string, _blob: Blob, _signal?: AbortSignal): Promise<string> => {
  throw new Error('语音转写尚未接入当前共享客户端')
}
