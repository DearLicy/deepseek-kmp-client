import { create } from 'zustand'
import type { TtsVoice } from './api/speech.js'
import { normalizeLocation, type UserLocation } from './location.js'
import { client, onKmpState } from '../runtime/client.js'

export type Locale = 'zh-CN' | 'en-US'
export type ThemePreference = 'light' | 'dark' | 'system'

type Settings = {
  apiKey: string | null
  locale: Locale
  themePreference: ThemePreference
  location: UserLocation | null
  ttsVoice: TtsVoice
  thinking: boolean
  search: boolean
  siderCollapsed: boolean
}

const KEYS = {
  locale: 'deepseek-client:locale',
  location: 'deepseek-client:location',
  themePreference: 'deepseek-client:theme-preference',
  ttsVoice: 'deepseek-client:tts-voice',
  siderCollapsed: 'deepseek-client:sider-collapsed',
} as const

const read = (key: string) => { try { return localStorage.getItem(key) } catch { return null } }
const write = (key: string, value: string | null) => {
  try { value === null ? localStorage.removeItem(key) : localStorage.setItem(key, value) } catch { /* memory only */ }
}
export const isChineseBrowser = (): boolean =>
  (typeof navigator === 'undefined' ? 'zh' : navigator.language).toLowerCase().startsWith('zh')
const initialLocale = (): Locale => {
  const saved = read(KEYS.locale)
  return saved === 'en-US' ? 'en-US' : 'zh-CN'
}
const initialLocation = (): UserLocation | null => {
  try { return normalizeLocation(JSON.parse(read(KEYS.location) ?? 'null')) } catch { return null }
}
const initialTheme = (): ThemePreference => {
  const saved = read(KEYS.themePreference)
  return saved === 'light' || saved === 'dark' ? saved : 'system'
}
const initialVoice = (): TtsVoice => read(KEYS.ttsVoice) === 'tide' ? 'tide' : 'stella'

export const useSettings = create<Settings>(() => ({
  apiKey: null,
  locale: initialLocale(),
  themePreference: initialTheme(),
  location: initialLocation(),
  ttsVoice: initialVoice(),
  thinking: false,
  search: false,
  siderCollapsed: read(KEYS.siderCollapsed) === 'true',
}))

export const setLocale = (locale: Locale) => { write(KEYS.locale, locale); useSettings.setState({ locale }) }
export const setLocation = (location: UserLocation | null) => {
  const normalized = normalizeLocation(location)
  write(KEYS.location, normalized ? JSON.stringify(normalized) : null)
  useSettings.setState({ location: normalized })
}
export const setThemePreference = (themePreference: ThemePreference) => {
  write(KEYS.themePreference, themePreference)
  useSettings.setState({ themePreference })
  client.setTheme(themePreference)
}
export const setTtsVoice = (ttsVoice: TtsVoice) => { write(KEYS.ttsVoice, ttsVoice); useSettings.setState({ ttsVoice }) }
/** The key is persisted by the KMP BrowserClient in sessionStorage. */
export const setApiKey = (apiKey: string | null) => {
  if (apiKey === null) client.clearKey()
  useSettings.setState({ apiKey })
}
export const setThinking = (thinking: boolean) => {
  if (useSettings.getState().thinking !== thinking) client.toggleThinking()
  useSettings.setState({ thinking })
}
export const setSearch = (search: boolean) => {
  if (useSettings.getState().search !== search) client.toggleSearch()
  useSettings.setState({ search })
}
export const setSiderCollapsed = (siderCollapsed: boolean) => {
  write(KEYS.siderCollapsed, String(siderCollapsed))
  useSettings.setState({ siderCollapsed })
}

onKmpState(state => {
  useSettings.setState({
    apiKey: state.configured ? (state.keyMasked || 'configured') : null,
    thinking: state.thinking,
    search: state.search,
    themePreference: state.theme === 'light' || state.theme === 'dark' ? state.theme : 'system',
  })
})
