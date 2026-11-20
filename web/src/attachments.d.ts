export const browserFiles: {
  read(id: string, limit: number): Promise<Int8Array | null>
  write(id: string, bytes: Int8Array): Promise<void>
  remove(ids: string[]): Promise<void>
  bytes(file: Blob): Promise<Int8Array>
  blob(bytes: Int8Array, type: string): Blob
  resize(name: string, type: string, bytes: Int8Array, maxEdge: number): Promise<{ name: string; type: string; bytes: Int8Array } | null>
}
export function fitImage(width: number, height: number, maxEdge: number): [number, number]
