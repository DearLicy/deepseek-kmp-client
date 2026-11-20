import { BrowserSnapshotStore, Vault } from './security.js'
import { browserFiles } from './attachments.js'

const shared = await import('@deepseek/shared')
const { BrowserClient } = shared

export function createRuntime() {
  const vault = new Vault()
  const snapshots = new BrowserSnapshotStore()
  const client = new BrowserClient(browserFiles)
  return { client, vault, snapshots, browserFiles }
}
