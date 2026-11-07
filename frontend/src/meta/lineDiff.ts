/** A line of a comparison: in both texts, only in the newer one, or only in the older one. */
export interface DiffLine {
  kind: 'same' | 'added' | 'removed'
  text: string
}

/** Texts longer than this many lines (either side) are compared line by line in order, without aligning. */
const MAX_LINES = 2000

/**
 * A line diff of two texts (longest common subsequence): what a version of a mail text changed against another one.
 * Mail texts are short (at most 20 000 characters), so the quadratic table is small.
 */
export function lineDiff(older: string | null | undefined, newer: string | null | undefined): DiffLine[] {
  const a = (older ?? '').split('\n')
  const b = (newer ?? '').split('\n')
  if (older == null || older === '') a.length = 0
  if (newer == null || newer === '') b.length = 0
  if (a.length > MAX_LINES || b.length > MAX_LINES) {
    return [...a.map((text) => ({ kind: 'removed' as const, text })), ...b.map((text) => ({ kind: 'added' as const, text }))]
  }
  // lengths[i][j]: the longest common subsequence of a[i..] and b[j..].
  const lengths: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lengths[i][j] = a[i] === b[j] ? lengths[i + 1][j + 1] + 1 : Math.max(lengths[i + 1][j], lengths[i][j + 1])
    }
  }
  const lines: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      lines.push({ kind: 'same', text: a[i] })
      i++
      j++
    } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
      lines.push({ kind: 'removed', text: a[i++] })
    } else {
      lines.push({ kind: 'added', text: b[j++] })
    }
  }
  while (i < a.length) lines.push({ kind: 'removed', text: a[i++] })
  while (j < b.length) lines.push({ kind: 'added', text: b[j++] })
  return lines
}

/** Whether a comparison found any difference. */
export function differs(lines: DiffLine[]): boolean {
  return lines.some((line) => line.kind !== 'same')
}
