export const tokens = Object.freeze({
  ink: '#191916',
  muted: '#77766e',
  canvas: '#f7f6f2',
  paper: '#fffefa',
  line: 'rgba(35,32,23,.095)',
  brand: '#536b4d',
  brandSoft: '#e7ebe2',
  danger: '#a94e42',
  radius: '22px',
  shadow: '0 18px 70px rgba(34,31,22,.07)',
})

export const icons = Object.freeze({
  menu: '<path d="M4 8h16M4 16h12"/>',
  new: '<path d="M12 20H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v4"/><path d="M18 14v7m-3.5-3.5h7"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
  more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  pin: '<path d="m16 3 5 5-4 1-4 5-3-3-5 5-2-2 5-5-3-3 5-4z"/>',
  edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4z"/>',
  trash: '<path d="M3 6h18M8 6V4h8v2m3 0-1 14H6L5 6m4 4v6m6-6v6"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  chevron: '<path d="m9 18 6-6-6-6"/>',
  think: '<circle cx="12" cy="12" r="8"/><path d="M12 8v8M8 12h8m-14 0h2m14 0h2M12 2v2m0 16v2"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a15 15 0 0 1 0 18m0-18a15 15 0 0 0 0 18"/>',
  send: '<path d="m22 2-7 20-4-9-9-4z"/><path d="M22 2 11 13"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
  sound: '<path d="M4 10v4h4l5 4V6l-5 4z"/><path d="M17 9a5 5 0 0 1 0 6m3-9a9 9 0 0 1 0 12"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
})

export function icon(name, className = '') {
  const path = icons[name]
  if (!path) return ''
  return `<svg class="icon ${className}" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${path}</svg>`
}
