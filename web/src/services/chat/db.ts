import { client } from '../../runtime/client.js'
export const loadFile = async (id: string): Promise<Blob | null> => client.localFile(id)
