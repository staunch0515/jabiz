/** "My maps" (F-S3c): the list, and one map at `/maps/<graph>`. */
export const MAPS_PATH = '/maps'

export function mapPath(treeId: string): string {
  return `${MAPS_PATH}/${encodeURIComponent(treeId)}`
}

/** The graph a path names, or null for any other path (the list is not one). */
export function mapOf(path: string): string | null {
  const match = /^\/maps\/([^/]+)$/.exec(path)
  if (!match) return null
  try {
    return decodeURIComponent(match[1])
  } catch {
    return null
  }
}

export function isMapsPath(path: string): boolean {
  return path === MAPS_PATH || mapOf(path) !== null
}
