import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { createRuntime } from './bootstrap.js'
import { icon } from './design-system.js'
import './style.css'

const h = React.createElement
const { client, vault } = createRuntime()
const attachmentAccept = client.acceptedFiles()
const timeFormat = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' })

function safe(value = '') { return String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]) }
function iconNode(name, cls = '') { return h('span', { className: `icon ${cls}`, dangerouslySetInnerHTML: { __html: icon(name) }, 'aria-hidden': true }) }
function time(value) { return value ? timeFormat.format(new Date(value)) : '' }

function markdown(text = '') {
  const tokens = []
  let value = safe(text).replace(/```([\w-]*)\n?([\s\S]*?)```/g, (_, language, code) => {
    const token = `\u0000${tokens.length}\u0000`; tokens.push(h('pre', { className: 'code-block', key: token }, [h('code', { key: 'code' }, code), language && h('small', { key: 'language' }, language)])); return token
  }).replace(/`([^`\n]+)`/g, (_, code) => { const token = `\u0000${tokens.length}\u0000`; tokens.push(h('code', { className: 'inline-code', key: token }, code)); return token })
  value = value.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/gi, (whole, label, url) => {
    try { const safeUrl = new URL(url); if (!['https:', 'http:'].includes(safeUrl.protocol)) return label; const token = `\u0000${tokens.length}\u0000`; tokens.push(h('a', { key: token, href: safeUrl.href, target: '_blank', rel: 'noopener noreferrer' }, label)); return token } catch { return label }
  })
  const lines = value.split('\n'), out = []; let list = null; let items = []
  const flush = () => { if (list) out.push(h(list, { className: 'md-list', key: `list-${out.length}` }, items.map((item, i) => h('li', { key: i }, restore(item, tokens))))); list = null; items = [] }
  const restore = content => {
    const parts = String(content).split(/(\u0000\d+\u0000)/g).filter(Boolean)
    return parts.map((part, i) => { const match = /^\u0000(\d+)\u0000$/.exec(part); return match ? tokens[Number(match[1])] : part.replace(/\*\*(.+?)\*\*/g, '$1') })
  }
  lines.forEach(line => {
    const heading = /^(#{1,4})\s+(.+)$/.exec(line), bullet = /^\s*[-*+]\s+(.+)$/.exec(line), ordered = /^\s*\d+\.\s+(.+)$/.exec(line)
    if (heading) { flush(); const level = heading[1].length + 1; out.push(h(`h${level}`, { key: `heading-${out.length}` }, restore(heading[2]))) }
    else if (bullet || ordered) { const tag = bullet ? 'ul' : 'ol'; if (list && list !== tag) flush(); list = tag; items.push((bullet || ordered)[1]) }
    else if (/^\s*([-*_]\s*){3,}$/.test(line)) { flush(); out.push(h('hr', { key: `rule-${out.length}` })) }
    else if (/^\s*>/.test(line)) { flush(); out.push(h('blockquote', { key: `quote-${out.length}` }, restore(line.replace(/^\s*>\s?/, '')))) }
    else if (line.trim()) { flush(); out.push(h('p', { key: `p-${out.length}` }, restore(line))) } else flush()
  })
  flush()
  return out
}

function SideIconButton({ iconName, label, action, className = '' }) {
  return h('button', { className: `icon-button ${className}`, type: 'button', 'aria-label': label, onClick: action }, iconNode(iconName))
}
function Brand() { return h('div', { className: 'brand' }, h('span', { className: 'brand-mark', 'aria-hidden': true }, 'D.'), h('span', null, '拾光')) }

function Thought({ block, running, onToggle }) {
  const [, tick] = useState(0)
  useEffect(() => { if (!running || block.endedAt) return; const id = setInterval(() => tick(n => n + 1), 1000); return () => clearInterval(id) }, [running, block.endedAt])
  const elapsed = block.startedAt ? Math.max(0, Math.floor(((block.endedAt || Date.now()) - block.startedAt) / 1000)) : 0
  return h('section', { className: `thought ${running && !block.endedAt ? 'is-active' : ''}` },
    h('button', { type: 'button', className: 'thought-toggle', onClick: onToggle, 'aria-expanded': !block.collapsed },
      h('span', { className: `thought-dot ${!block.endedAt ? 'pulse' : ''}` }),
      block.endedAt ? `已思考　用时 ${elapsed} 秒` : '正在思考', iconNode('chevron', block.collapsed ? '' : 'rotate-90')),
    !block.collapsed && h('div', { className: 'thought-copy' }, block.text || h('span', { className: 'muted' }, '正在整理思路…')))
}

function AttachmentTile({ attachment, removable, onRemove }) {
  return h('div', { className: 'attachment-tile', title: attachment.name },
    h('span', { className: 'attachment-glyph', 'aria-hidden': true }, attachment.mediaType === 'application/pdf' ? 'PDF' : attachment.name.split('.').pop()?.toUpperCase().slice(0, 4) || 'FILE'),
    h('span', { className: 'attachment-name' }, attachment.name),
    removable && h('button', { type: 'button', className: 'attachment-remove', onClick: () => onRemove(attachment.id), 'aria-label': `移除附件 ${attachment.name}` }, iconNode('close')))
}

function UserFile({ attachment, onError }) {
  const [url, setUrl] = useState('')
  const [preview, setPreview] = useState(false)
  useEffect(() => {
    let current, cancelled = false
    client.localFile(attachment.id).then(blob => {
      if (!blob || cancelled) return
      current = URL.createObjectURL(blob)
      setUrl(current)
      setPreview(attachment.mediaType.startsWith('image/'))
    }).catch(() => onError?.('本地附件暂时无法打开'))
    return () => { cancelled = true; if (current) URL.revokeObjectURL(current) }
  }, [attachment.id])
  const open = () => {
    if (!url) return onError?.('找不到本地附件原件')
    window.open(url, '_blank', 'noopener,noreferrer')
  }
  return h('button', { type: 'button', className: `user-file ${preview ? 'has-preview' : ''}`, onClick: open, 'aria-label': `打开附件 ${attachment.name}` },
    preview && url ? h('img', { src: url, alt: '' }) : h('span', { className: 'file-sheet' }, attachment.name.split('.').pop()?.toUpperCase().slice(0, 4) || 'FILE'),
    h('span', null, h('strong', null, attachment.name), h('small', null, preview ? '图片' : '本地附件')))
}

function Composer({ state, onSend, onStop, onInput, onToggleThinking, onToggleSearch, onAddFiles, onRemoveAttachment, openKey, error }) {
  const input = useRef(null)
  const picker = useRef(null)
  const sending = state.activeId != null
  const hasContent = state.draft.trim().length > 0 || (state.attachments?.length || 0) > 0
  const canSend = hasContent && state.ready
  useEffect(() => { if (state.selectedId && input.current) input.current.value = state.draft }, [state.selectedId])
  useEffect(() => { const el = input.current; if (!el) return; el.style.height = 'auto'; el.style.height = `${Math.min(el.scrollHeight, 160)}px` }, [state.draft])
  const key = event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); if (!state.configured) openKey(); else if (canSend && !sending) onSend() }
  }
  return h('section', { className: 'composer-shell', 'aria-label': '撰写消息' },
    (state.attachments?.length || 0) > 0 && h('div', { className: 'attachment-strip', 'aria-label': '待发送附件' },
      state.attachments.map(file => h(AttachmentTile, { key: file.id, attachment: file, removable: !sending, onRemove: onRemoveAttachment }))),
    h('textarea', { ref: input, className: 'composer-input', placeholder: state.configured ? '发消息或添加文件' : '配置 API Key 后开始聊天', rows: 1, value: state.draft, onChange: event => onInput(event.target.value), onKeyDown: key, disabled: !state.ready || sending, 'aria-label': '消息内容' }),
    h('input', { ref: picker, type: 'file', multiple: true, hidden: true, accept: attachmentAccept, onChange: event => { onAddFiles(Array.from(event.target.files || [])); event.target.value = '' } }),
    h('div', { className: 'composer-bottom' },
      h('div', { className: 'composer-modes' },
        h('button', { className: `mode-chip ${state.thinking ? 'active' : ''}`, type: 'button', onClick: onToggleThinking, 'aria-pressed': state.thinking }, iconNode('think'), h('span', null, '深度思考')),
        h('button', { className: `mode-chip ${state.search ? 'active' : ''}`, type: 'button', onClick: onToggleSearch, 'aria-pressed': state.search }, iconNode('globe'), h('span', null, '智能搜索'))),
      h('div', { className: 'composer-actions' },
        h('button', { className: 'attachment-button', type: 'button', disabled: !state.ready || sending || (state.attachments?.length || 0) >= 50, onClick: () => picker.current?.click(), 'aria-label': '添加附件' }, '+'),
        h('button', { className: `send-button ${sending ? 'stop' : ''}`, type: 'button', onClick: sending ? onStop : !state.configured ? openKey : onSend, disabled: !state.ready || (!sending && !state.configured && !hasContent) || (!sending && state.configured && !canSend), 'aria-label': sending ? '停止生成' : '发送' }, iconNode(sending ? 'stop' : 'send')))),
    error ? h('div', { className: 'composer-error', role: 'alert' }, error) : h('div', { className: 'composer-footnote' }, 'Enter 发送　·　Shift + Enter 换行'))
}

function SessionRow({ session, selected, onSelect, onRename, onPin, onDelete, toast }) {
  const [menu, setMenu] = useState(false)
  return h('div', { className: `session-row ${selected ? 'selected' : ''}` },
    h('button', { className: 'session-select', type: 'button', onClick: onSelect }, session.pinned && iconNode('pin', 'pin-icon'), h('span', null, session.title || '新对话'), h('time', null, time(session.updatedAt))),
    h('div', { className: 'session-menu-wrap' },
      h('button', { className: 'session-more', type: 'button', 'aria-label': '对话操作', 'aria-expanded': menu, onClick: () => setMenu(value => !value) }, iconNode('more')),
      menu && h('div', { className: 'session-menu' },
        h('button', { onClick: () => { setMenu(false); onRename(session) } }, iconNode('edit'), '重命名'),
        h('button', { onClick: () => { onPin(session.id); setMenu(false); toast(session.pinned ? '已取消置顶' : '已置顶') } }, iconNode('pin'), session.pinned ? '取消置顶' : '置顶'),
        h('button', { className: 'danger', onClick: () => { setMenu(false); onDelete(session) } }, iconNode('trash'), '删除'))))
}

function SettingsSheet({ state, vault, setTheme, setFontScale, verifyKey, clearKey, close, toast }) {
  const [keyInput, setKeyInput] = useState('')
  const [error, setError] = useState('')
  const [checking, setChecking] = useState(false)
  const keyForm = async event => {
    event.preventDefault(); setError(''); setChecking(true)
    try { await verifyKey(keyInput); setKeyInput(''); toast('API Key 已校验并保存到当前标签页') }
    catch (reason) { setError(reason.message || '校验失败，请检查网络和 API Key。') }
    finally { setChecking(false) }
  }
  return h('div', { className: 'sheet-backdrop', role: 'presentation', onMouseDown: event => { if (event.target === event.currentTarget) close() } },
    h('section', { className: 'settings-sheet', role: 'dialog', 'aria-modal': true, 'aria-labelledby': 'settings-title' },
      h('div', { className: 'sheet-handle' }),
      h('header', { className: 'sheet-header' }, h('div', null, h('h2', { id: 'settings-title' }, '设置'), h('p', null, '只保存在这台设备的浏览器中。')), h('button', { className: 'icon-button', onClick: close, 'aria-label': '关闭设置' }, iconNode('close'))),
      h('div', { className: 'settings-content' },
        h('section', { className: 'settings-group' }, h('h3', null, 'API Key'),
          vault.configured ? h('div', { className: 'key-summary' }, h('span', null, `当前标签页　${vault.masked}`), h('button', { className: 'text-button danger', onClick: () => { clearKey(); setKeyInput(''); toast('API Key 已从当前标签页移除') } }, '移除'))
            : h('form', { onSubmit: keyForm, className: 'key-form' },
              h('label', { htmlFor: 'api-key' }, 'DeepSeek API Key'),
              h('input', { id: 'api-key', name: 'apiKey', value: keyInput, onChange: event => setKeyInput(event.target.value), type: 'password', autoComplete: 'off', autoCapitalize: 'none', spellCheck: false, placeholder: '粘贴 API Key', disabled: checking }),
              error && h('p', { className: 'field-error', role: 'alert' }, error),
              h('p', { className: 'privacy-note' }, 'Key 使用前会请求 /models 校验；本机只放在当前标签页的 sessionStorage。关闭标签页后自动清除，绝不写入会话历史。'),
              h('button', { className: `primary-button ${checking ? 'loading' : ''}`, type: 'submit', disabled: checking || keyInput.trim().length === 0 }, checking ? '正在校验…' : '校验并保存'))),
        h('section', { className: 'settings-group' }, h('h3', null, '外观'),
          h('div', { className: 'setting-row' }, h('span', null, '主题'), h('div', { className: 'segmented-control' }, ['system', 'light', 'dark'].map((value, index) => h('button', { key: value, className: state.theme === value ? 'active' : '', onClick: () => setTheme(value) }, ['系统', '浅色', '深色'][index])))),
          h('label', { className: 'range-setting' }, h('span', null, '字体大小'), h('output', null, `${Math.round(state.fontScale * 100)}%`), h('input', { type: 'range', min: 0.8, max: 1.4, step: 0.1, value: state.fontScale, onChange: event => setFontScale(Number(event.target.value)) }))),
        h('section', { className: 'settings-group' }, h('h3', null, '本地数据'), h('p', { className: 'privacy-note' }, '历史消息使用本浏览器的 localStorage，不会同步到官方账号或云端。', ' ', h('button', { className: 'text-button danger', onClick: () => { clearAll(); close(); toast('本机历史已删除') } }, '删除全部本机历史'))))))
}

function App() {
  const [state, setState] = useState({ ready: false, sessions: [], draft: '', thinking: false, search: false, theme: 'system', fontScale: 1, selectedId: null })
  const [viewport, setViewport] = useState(() => window.matchMedia('(max-width: 767px)').matches ? 'mobile' : 'desktop')
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [keyPrompt, setKeyPrompt] = useState(false)
  const [toastText, setToastText] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(null)
  const [search, setSearch] = useState('')
  const [rename, setRename] = useState(null)
  const [renameValue, setRenameValue] = useState('')
  const [sources, setSources] = useState(null)
  const [showScroll, setShowScroll] = useState(false)
  const [pageError, setPageError] = useState('')
  const scrollRef = useRef(null)
  const inputRef = useRef(null)
  const toastTimer = useRef()
  const session = state.sessions.find(item => item.id === state.selectedId) || null
  const messages = session?.messages || []

  const toast = useCallback(message => { setToastText(message); clearTimeout(toastTimer.current); toastTimer.current = setTimeout(() => setToastText(''), 2400) }, [])
  useEffect(() => {
    client.start(value => {
      try { const parsed = JSON.parse(value); setState(parsed); setPageError('') }
      catch { setPageError('共享对话状态读取失败。') }
    })
    return () => { client.flush(); client.close() }
  }, [])
  useEffect(() => {
    const mq = matchMedia('(max-width: 767px)')
    const update = () => { setViewport(mq.matches ? 'mobile' : 'desktop'); if (!mq.matches) setSidebarOpen(true) }
    update(); mq.addEventListener('change', update); return () => mq.removeEventListener('change', update)
  }, [])
  useEffect(() => { document.documentElement.dataset.theme = state.theme === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : state.theme || 'light' }, [state.theme])
  useEffect(() => { document.documentElement.style.setProperty('--reply-size', `${17 * (state.fontScale || 1)}px`) }, [state.fontScale])
  useEffect(() => {
    const root = scrollRef.current
    if (!root) return
    const update = () => { const remaining = root.scrollHeight - root.scrollTop - root.clientHeight; setShowScroll(remaining > 220) }
    root.addEventListener('scroll', update, { passive: true }); update()
    return () => root.removeEventListener('scroll', update)
  }, [state.selectedId, messages.length])
  useEffect(() => { if (!state.activeId) return; const el = scrollRef.current; if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 420) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' }) }, [messages, state.activeId])
  useEffect(() => {
    const onKey = event => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); setSidebarOpen(true); setTimeout(() => document.querySelector('.session-search input')?.focus(), 40) }
      if (event.key === 'Escape') { setSettingsOpen(false); setKeyPrompt(false); setConfirmDelete(null); setSources(null); setSidebarOpen(viewport !== 'mobile') }
    }
    window.addEventListener('keydown', onKey); return () => window.removeEventListener('keydown', onKey)
  }, [viewport])
  useEffect(() => { if (keyPrompt || settingsOpen || confirmDelete || sources || rename) document.body.classList.add('overlay-open'); else document.body.classList.remove('overlay-open') }, [keyPrompt, settingsOpen, confirmDelete, sources, rename])

  const activeSources = useMemo(() => session?.messages.flatMap(message => message.sources.map(source => ({ ...source, messageId: message.id }))) || [], [session])
  const send = () => {
    if ((!state.draft.trim() && !(state.attachments?.length || 0)) || !state.configured) { if (!state.configured) setSettingsOpen(true); return }
    if (viewport === 'mobile') setViewport('mobile-chat')
    client.send()
  }
  const cancel = () => client.stop()
  const select = id => { client.selectSession(id); setViewport('mobile-chat'); setSidebarOpen(false) }
  const newSession = () => { client.newSession(); setViewport('mobile-chat'); setSidebarOpen(false); setTimeout(() => inputRef.current?.focus(), 50) }
  const verifyKey = key => {
    return new Promise((resolve, reject) => {
      client.validateAndSaveKey(key)
      const timeout = setTimeout(() => { clean(); reject(new Error('校验超时，请检查网络后重试。')) }, 12_000)
      const observer = event => { if (event.type !== 'state') return; const current = event.detail; if (current.keyChecking) return; if (current.keyError) { clean(); reject(new Error(current.keyError)) } else if (current.configured) { clean(); resolve() } }
      function clean() { clearTimeout(timeout); window.removeEventListener('deepseek:state', observer) }
      window.addEventListener('deepseek:state', observer)
    })
  }
  const clearKey = () => { client.clearKey(); vault.clear(); setSettingsOpen(false) }
  const clearAll = () => client.deleteAll()
  const setTheme = theme => client.setTheme(theme)
  const setFontScale = scale => client.setFontScale(scale)

  const filtered = state.sessions.filter(item => !search || item.title.toLocaleLowerCase().includes(search.toLocaleLowerCase()) || item.messages.some(message => message.blocks.some(block => block.kind === 'text' && block.text.toLocaleLowerCase().includes(search.toLocaleLowerCase())))).sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt)
  const keyNeeded = state.ready && !state.configured
  const brand = h('button', { className: 'brand-button', onClick: newSession, 'aria-label': 'DeepSeek 私人对话首页' }, h('span', { className: 'brand-mark' }, 'D.'), h('span', { className: 'brand-word' }, '拾光'), h('span', { className: 'brand-meta' }, '私人对话'))
  const side = h('aside', { className: `sidebar ${viewport.startsWith('mobile') ? sidebarOpen || viewport === 'mobile' ? 'mobile-visible' : 'mobile-hidden' : ''}`, 'aria-label': '会话列表' },
    h('div', { className: 'sidebar-header' }, brand, h('button', { className: 'new-session-button', onClick: newSession }, iconNode('new'), h('span', null, '新对话')), h('label', { className: 'session-search' }, iconNode('search'), h('input', { value: search, placeholder: '搜索对话内容…', 'aria-label': '搜索对话内容', onChange: event => setSearch(event.target.value) }), h('kbd', null, '⌘ K'))),
    h('div', { className: 'sidebar-section-label' }, h('span', null, '最近对话'), h('span', null, state.sessions.length || '')),
    h('nav', { className: 'session-list', 'aria-label': '最近对话' }, filtered.length ? filtered.map(item => h(SessionRow, { key: item.id, session: item, selected: item.id === state.selectedId, onSelect: () => select(item.id), onRename: target => { setRename(target); setRenameValue(target.title) }, onPin: id => client.pinSession(id), onDelete: setConfirmDelete, toast })) : h('div', { className: 'list-empty' }, state.sessions.length ? '没有匹配的对话。' : h(React.Fragment, null, h('strong', null, '还没有对话'), h('span', null, '开启一段只保存在本机的对话。'), h('button', { className: 'secondary-button', onClick: newSession }, '开始聊天')))),
    h('footer', { className: 'sidebar-footer' }, h('div', { className: 'local-status' }, h('span', { className: 'status-mark' }), h('span', null, '本机保存'), h('span', { className: 'status-caption' }, '不登录 · 不同步')), h('button', { className: 'settings-button', onClick: () => setSettingsOpen(true) }, h('span', null, '设置与隐私'), iconNode('chevron'))))

  const top = h('header', { className: 'chat-topbar' },
    h('button', { className: 'mobile-back', onClick: () => { if (viewport === 'mobile-chat') { setViewport('mobile'); setSidebarOpen(true) } else setSidebarOpen(!sidebarOpen) }, 'aria-label': viewport === 'mobile-chat' ? '返回对话列表' : '展开侧栏' }, iconNode(viewport === 'mobile-chat' ? 'chevron-left' : 'menu')),
    h('div', { className: 'chat-title' }, h('span', null, session?.title || '新对话'), h('span', { className: 'title-status' }, state.activeId ? '正在回复' : '本机对话')),
    h('button', { className: 'top-new-button', onClick: newSession, 'aria-label': '新对话' }, iconNode('new')),
    h('button', { className: 'top-settings-button', onClick: () => setSettingsOpen(true), 'aria-label': '打开设置' }, iconNode('settings')))

  const welcome = h('section', { className: 'welcome-scene' }, h('div', { className: 'welcome-monogram' }, 'D.'), h('div', { className: 'welcome-copy' }, h('h1', null, '你好，'), h('p', null, '有什么我能帮你的吗？')), h('p', { className: 'welcome-subtitle' }, keyNeeded ? '先配置自己的 DeepSeek API Key，再开启一段私密对话。' : '写下此刻的想法，答案会在这里慢慢出现。'), h('button', { className: 'welcome-cta', onClick: keyNeeded ? () => setSettingsOpen(true) : () => inputRef.current?.focus() }, keyNeeded ? '配置 API Key' : '开始提问'))

  const thread = messages.length > 0 ? h('section', { className: 'messages', 'aria-label': '对话内容' }, messages.map(message => {
    const answer = message.role === 'Assistant'
    return h('article', { key: message.id, className: `message ${answer ? 'assistant-message' : 'user-message'}`, 'data-status': message.status },
      answer ? h('span', { className: 'answer-mark', 'aria-hidden': true }, 'D.') : null,
      h('div', { className: 'message-body' }, !answer && h('div', { className: 'message-meta' }, h('span', null, '你'), h('time', null, time(message.createdAt))),
        h('div', { className: answer ? 'answer-content' : 'user-bubble' }, !answer && message.attachments?.length > 0 && h('div', { className: 'user-files' }, message.attachments.map(file => h(UserFile, { key: file.id, attachment: file, onError: toast }))), message.blocks.length ? message.blocks.map(block => {
          if (block.kind === 'thinking') return h(Thought, { key: block.id, block, running: message.status === 'Streaming', onToggle: () => client.toggleThinkingBlock(block.id) })
          if (block.kind === 'search' || block.kind === 'readLink') return h('button', { key: block.id, className: 'research-step', onClick: () => setSources(message.sources), disabled: !message.sources.length }, h('span', { className: 'research-mark' }, iconNode('globe')), h('span', { className: 'research-copy' }, h('strong', null, block.status === 'Wip' ? '正在搜索网页' : block.status === 'Failed' ? '网页读取未完成' : `已找到 ${message.sources.length} 个网页`), block.text && h('small', null, block.text)), iconNode('chevron'))
          return h('div', { className: 'answer-text', key: block.id }, markdown(block.text), block.citations?.length ? h('span', { className: 'citation-inline' }, block.citations.map((citation, index) => { const source = message.sources.find(item => item.url === citation.url); return source ? h('button', { key: `${citation.url}-${index}`, onClick: () => setSources([source]) }, source.index) : null })) : null)
        }) : message.status === 'Streaming' ? h('div', { className: 'typing-status' }, h('span'), h('span'), h('span'), h('span', null, state.preparingAttachments ? '正在上传附件' : '正在整理回复')) : h('p', null, '回复内容为空。'),
        message.error && h('div', { className: 'message-error', role: 'status' }, h('span', null, message.error), h('button', { onClick: () => { client.editForResend(message.id); setTimeout(() => inputRef.current?.focus(), 60) } }, '编辑后重发')),
        answer && message.sources.length > 0 && h('button', { className: 'source-pill', onClick: () => setSources(message.sources) }, iconNode('globe'), h('span', null, '参考网页'), h('span', { className: 'source-count' }, message.sources.length), iconNode('chevron')),
        answer && message.status !== 'Streaming' && h('div', { className: 'message-tools' }, h('button', { onClick: async () => { try { await navigator.clipboard.writeText(message.blocks.filter(block => block.kind === 'text').map(block => block.text).join('\n\n')); toast('已复制回复') } catch { toast('复制失败，请检查剪贴板权限') } }, 'aria-label': '复制回答' }, iconNode('copy')), h('time', null, time(message.createdAt))))))
  })) : welcome

  const inputArea = h('div', { className: 'composer-dock' }, h(Composer, { state, onSend: () => client.send(), onStop: () => client.stop(), onInput: value => client.input(value), onToggleThinking: () => client.toggleThinking(), onToggleSearch: () => client.toggleSearch(), onAddFiles: files => client.addFiles(files), onRemoveAttachment: id => client.removeAttachment(id), openKey: () => setSettingsOpen(true), error: pageError, ref: inputRef }))
  const chatPanel = h('main', { className: `chat-panel ${viewport === 'mobile-chat' ? 'mobile-chat-active' : ''}`, 'aria-busy': !state.ready }, top, h('div', { className: `thread-viewport ${messages.length ? 'has-messages' : 'is-empty'}`, ref: scrollRef }, thread), showScroll && h('button', { className: 'scroll-bottom', onClick: () => scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' }), 'aria-label': '滚动到最新回复' }, iconNode('chevron-down')), inputArea,
    h('p', { className: 'disclaimer' }, 'DeepSeek AI · API Key 仅在此标签页有效，关闭标签页后清除。'), pageError && h('div', { className: 'toast error-toast', role: 'alert' }, pageError))

  const overlay = settingsOpen && h(SettingsSheet, { state, vault, setTheme, setFontScale, verifyKey, clearKey, close: () => setSettingsOpen(false), toast })
  const sourceSheet = sources && h('div', { className: 'sheet-backdrop', onMouseDown: event => { if (event.target === event.currentTarget) setSources(null) } }, h('section', { className: 'source-sheet', role: 'dialog', 'aria-modal': true }, h('div', { className: 'sheet-handle' }), h('header', { className: 'sheet-header' }, h('div', null, h('h2', null, '参考网页'), h('p', null, '根据回答中的联网搜索整理')), h('button', { className: 'icon-button', onClick: () => setSources(null), 'aria-label': '关闭网页来源' }, iconNode('close'))), h('div', { className: 'source-list' }, (sources || []).map((source, index) => { let link; try { link = new URL(source.url); if (!['http:', 'https:'].includes(link.protocol)) return null } catch { return null }; return h('a', { className: 'source-item', href: link.href, target: '_blank', rel: 'noopener noreferrer', key: link.href }, h('span', { className: 'source-index' }, source.index || index + 1), h('span', { className: 'source-copy' }, h('span', null, source.siteName || link.hostname), h('strong', null, source.title || link.hostname), source.snippet && h('small', null, source.snippet)), iconNode('chevron')) }))))
  const deleteOverlay = confirmDelete && h('div', { className: 'sheet-backdrop', onMouseDown: event => { if (event.target === event.currentTarget) setConfirmDelete(null) } }, h('section', { className: 'confirm-sheet', role: 'dialog', 'aria-modal': true }, h('div', { className: 'sheet-handle' }), h('h2', null, '删除这段对话？'), h('p', null, '删除后无法恢复。本操作仅影响当前浏览器保存的历史。'), h('button', { className: 'danger-button', onClick: () => { client.deleteSession(confirmDelete.id); setConfirmDelete(null); toast('对话已删除') } }, '删除对话'), h('button', { className: 'secondary-button', onClick: () => setConfirmDelete(null) }, '保留对话')))
  const renameOverlay = rename && h('div', { className: 'sheet-backdrop', onMouseDown: event => { if (event.target === event.currentTarget) setRename(null) } }, h('form', { className: 'confirm-sheet', onSubmit: event => { event.preventDefault(); client.renameSession(rename.id, renameValue); setRename(null); toast('标题已保存') } }, h('div', { className: 'sheet-handle' }), h('h2', null, '重命名对话'), h('input', { autoFocus: true, value: renameValue, maxLength: 120, onChange: event => setRenameValue(event.target.value) }), h('button', { className: 'primary-button', type: 'submit', disabled: !renameValue.trim() }, '保存'), h('button', { className: 'secondary-button', type: 'button', onClick: () => setRename(null) }, '取消')))

  return h('div', { className: `application-shell ${viewport.startsWith('mobile') ? 'mobile-layout' : ''} ${sidebarOpen ? 'sidebar-open' : ''}` },
    side,
    h('button', { className: `sidebar-shade ${sidebarOpen ? 'shown' : ''}`, 'aria-label': '收起对话列表', onClick: () => { setSidebarOpen(false); if (viewport === 'mobile') setViewport('mobile-chat') } }),
    chatPanel, overlay, sourceSheet, deleteOverlay, renameOverlay,
    toastText && h('div', { className: 'toast', role: 'status', 'aria-live': 'polite' }, h('span', { className: 'toast-dot' }), toastText))
}

function verifyKeyWithState(client, key) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { cleanup(); reject(new Error('API Key 校验超时，请重试。')) }, 25_000)
    const onState = event => {
      const state = event.detail
      if (state.keyChecking) return
      cleanup()
      if (state.keyError) reject(new Error(state.keyError))
      else if (state.configured) resolve()
      else reject(new Error('API Key 校验未完成。'))
    }
    function cleanup() { clearTimeout(timeout); window.removeEventListener('deepseek:state', onState) }
    window.addEventListener('deepseek:state', onState)
    client.validateAndSaveKey(key)
  })
}

const runtime = { client, vault }
const oldStart = client.start.bind(client)
client.start = callback => oldStart(json => { try { const value = JSON.parse(json); window.dispatchEvent(new CustomEvent('deepseek:state', { detail: value })); callback(json) } catch (error) { console.error(error) } })
rootBootstrap()
function rootBootstrap() {
  const app = document.querySelector('#app')
  app.removeAttribute('aria-busy')
  createRoot(app).render(h(App))
}
