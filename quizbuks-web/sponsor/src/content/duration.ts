/**
 * The duration of the video to watch as the sponsor types it (Q75, D-Q75-5): `m:ss`, `h:mm:ss` or plain seconds, up
 * to two hours (the server's `ContentLimits.MAX_VIDEO_SECONDS`). Shown back as `m:ss`.
 */
export const MAX_VIDEO_SECONDS = 7_200

export function parseDuration(text: string): number | null {
  const trimmed = text.trim()
  if (trimmed === '') return null
  let seconds: number
  if (/^\d+$/.test(trimmed)) {
    seconds = Number(trimmed)
  } else {
    const parts = trimmed.split(':')
    if (parts.length < 2 || parts.length > 3 || !parts.every((p, i) => (i === 0 ? /^\d{1,3}$/ : /^[0-5]?\d$/).test(p))) {
      return null
    }
    seconds = parts.map(Number).reduce((total, part) => total * 60 + part, 0)
  }
  return seconds >= 1 && seconds <= MAX_VIDEO_SECONDS ? seconds : null
}

export function formatDuration(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds))
  const h = Math.floor(whole / 3600)
  const m = Math.floor((whole % 3600) / 60)
  const s = whole % 60
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`
}
