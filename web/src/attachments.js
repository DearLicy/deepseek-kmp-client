let database

function openDatabase() {
  if (!database) {
    database = new Promise((resolve, reject) => {
      const request = indexedDB.open('deepseek-client.attachments', 1)
      request.onupgradeneeded = () => request.result.createObjectStore('originals')
      request.onsuccess = () => {
        const connection = request.result
        connection.onversionchange = () => { connection.close(); database = undefined }
        resolve(connection)
      }
      request.onerror = () => reject(request.error)
      request.onblocked = () => reject(new Error('附件存储暂不可用'))
    }).catch(error => { database = undefined; throw error })
  }
  return database
}

async function access(mode, action) {
  const connection = await openDatabase()
  return new Promise((resolve, reject) => {
    const tx = connection.transaction('originals', mode)
    const result = action(tx.objectStore('originals'))
    tx.oncomplete = () => resolve(result?.result)
    tx.onabort = () => reject(tx.error || new Error('附件存储操作已取消'))
    tx.onerror = () => reject(tx.error)
  })
}

export function fitImage(width, height, maxEdge) {
  const scale = Math.min(1, maxEdge / Math.max(width, height))
  return [Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))]
}

export const browserFiles = {
  async read(id, limit) {
    const original = await access('readonly', store => store.get(id))
    if (!original || original.size < 1 || original.size > limit) return null
    return this.bytes(original)
  },
  write(id, bytes) { return access('readwrite', store => store.put(new Blob([bytes]), id)) },
  remove(ids) { return access('readwrite', store => { for (const id of ids) store.delete(id) }) },
  async bytes(file) { return new Int8Array(await file.arrayBuffer()) },
  blob(bytes, type) { return new Blob([bytes], { type }) },
  async resize(name, type, bytes, maxEdge) {
    const bitmap = await createImageBitmap(this.blob(bytes, type))
    try {
      const [width, height] = fitImage(bitmap.width, bitmap.height, maxEdge)
      if (width === bitmap.width && height === bitmap.height) return null
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const context = canvas.getContext('2d')
      if (!context) throw new Error('无法缩放图片')
      context.drawImage(bitmap, 0, 0, width, height)
      const output = await new Promise((resolve, reject) => {
        const outputType = type === 'image/jpeg' || type === 'image/webp' ? type : 'image/png'
        canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('无法编码图片')), outputType, 0.95)
      })
      const extension = output.type === 'image/jpeg' ? 'jpg' : output.type.split('/')[1]
      return { name: `${name.slice(0, name.lastIndexOf('.'))}.${extension}`, type: output.type, bytes: await this.bytes(output) }
    } finally { bitmap.close() }
  },
}
