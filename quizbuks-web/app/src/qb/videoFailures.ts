import { useSyncExternalStore } from 'react'

/*
 * F-S15 (docs/quizbuks/plans/impl/F-S15.md): the video files whose `<video>` failed (no such file, not a video, a
 * format the browser does not play), by the address played, for as long as the app is open. A card, the vertical mode
 * and the start page then show the cover and a link to the file instead of a player that stays black. A page framed
 * from another site that refuses to be framed cannot be told (the browser fires `load` all the same): the help and
 * troubleshooting say so.
 */

const failed = new Set<string>()
const listeners = new Set<() => void>()
let version = 0

/** Marks a video file's address as not playing. */
export function markVideoFailed(player: string) {
  if (failed.has(player)) return
  failed.add(player)
  version += 1
  listeners.forEach((listener) => listener())
}

/** Whether a video's address was found not to play. */
export function videoFailed(player: string | null | undefined): boolean {
  return !!player && failed.has(player)
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** `videoFailed`, kept up to date. */
export function useVideoFailed(player: string | null | undefined): boolean {
  useSyncExternalStore(subscribe, () => version, () => version)
  return videoFailed(player)
}

/** Forgets every failure (tests). */
export function resetVideoFailures() {
  failed.clear()
  version += 1
  listeners.forEach((listener) => listener())
}
