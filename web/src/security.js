const KEY_ID = 'deepseek.api-key.v1'
const SNAPSHOT_ID = 'deepseek-client.snapshot.v1'

export class Vault {
  #storage
  #key = ''

  constructor(storage = globalThis.sessionStorage) {
    this.#storage = storage
    this.#key = storage.getItem(KEY_ID) || ''
  }
  get configured() { return this.#key.length > 0 }
  get masked() { return this.configured ? `•••• ${this.#key.slice(-4)}` : null }
  keyForRequest() { return this.#key }
  save(key) {
    const cleaned = key.trim()
    if (!cleaned || cleaned.length > 2048) throw new Error('请输入有效的 API Key。')
    this.#storage.setItem(KEY_ID, cleaned)
    this.#key = cleaned
  }
  clear() { this.#storage.removeItem(KEY_ID); this.#key = '' }
}

export class BrowserSnapshotStore {
  #storage
  constructor(storage = globalThis.localStorage) { this.#storage = storage }
  read() { return this.#storage.getItem(SNAPSHOT_ID) }
  write(value) { this.#storage.setItem(SNAPSHOT_ID, value) }
}
