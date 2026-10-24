import QRCode from 'qrcode'

/**
 * The QR code of a publication's share link (docs/quizbuks/plans/Q21-publication-qr.md): drawn in the browser from
 * the link alone, nothing is asked of the server. The code only leads to `/p/<publication>`, where the visitor
 * signs in and answers.
 */

/** The code as an SVG image URL for `<img>` (the CSP allows `data:` images). */
export async function qrImageUrl(link: string): Promise<string> {
  const svg = await QRCode.toString(link, { type: 'svg', margin: 2, errorCorrectionLevel: 'M' })
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

/** The code as an SVG file. */
export async function qrSvg(link: string): Promise<Blob> {
  const svg = await QRCode.toString(link, { type: 'svg', margin: 4, errorCorrectionLevel: 'H' })
  return new Blob([svg], { type: 'image/svg+xml' })
}

/** The code's edge in pixels in the PNG: enough for a printed poster. */
export const QR_PNG_SIZE = 1024

/** The code as a PNG file, with a quiet zone and the highest error correction (a dirty poster still scans). */
export async function qrPng(link: string): Promise<Blob> {
  const canvas = document.createElement('canvas')
  await QRCode.toCanvas(canvas, link, { width: QR_PNG_SIZE, margin: 4, errorCorrectionLevel: 'H' })
  return toBlob(canvas)
}

/** A4 at 300 dpi. */
export const POSTER_WIDTH = 2480
export const POSTER_HEIGHT = 3508

export interface PosterText {
  /** The publication's name: the poster's title. */
  name: string
  /** What a passer-by is told to do, one line per language. */
  instructions: readonly string[]
}

/**
 * A poster as a PNG file: the name, a large code, the instructions and the link in words (for those who cannot
 * scan). Black on white, so it prints on anything.
 */
export async function posterPng(link: string, text: PosterText): Promise<Blob> {
  const canvas = document.createElement('canvas')
  canvas.width = POSTER_WIDTH
  canvas.height = POSTER_HEIGHT
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('canvas')
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, POSTER_WIDTH, POSTER_HEIGHT)
  ctx.fillStyle = '#000000'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'top'
  const margin = 160
  const width = POSTER_WIDTH - 2 * margin
  const centre = POSTER_WIDTH / 2

  let y = margin
  ctx.font = `bold 140px ${FONTS}`
  for (const line of wrap(ctx, text.name, width, 3)) {
    ctx.fillText(line, centre, y)
    y += 170
  }

  const qrSize = 1600
  const qr = document.createElement('canvas')
  await QRCode.toCanvas(qr, link, { width: qrSize, margin: 2, errorCorrectionLevel: 'H' })
  y += 80
  ctx.drawImage(qr, centre - qrSize / 2, y, qrSize, qrSize)
  y += qrSize + 120

  ctx.font = `96px ${FONTS}`
  for (const instruction of text.instructions) {
    for (const line of wrap(ctx, instruction, width, 2)) {
      ctx.fillText(line, centre, y)
      y += 120
    }
  }

  // The link is for those who cannot scan: never cut, drawn smaller when it is long.
  let linkSize = 64
  ctx.font = `${linkSize}px ${FONTS}`
  while (linkSize > 24 && ctx.measureText(link).width > width) {
    linkSize -= 4
    ctx.font = `${linkSize}px ${FONTS}`
  }
  ctx.textBaseline = 'bottom'
  ctx.fillText(link, centre, POSTER_HEIGHT - margin)
  return toBlob(canvas)
}

const FONTS = 'system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans", "Noto Sans CJK SC", "Noto Sans CJK JP", sans-serif'

/** Breaks text into at most `maxLines` lines that fit; the last line is shortened with an ellipsis if needed. */
function wrap(ctx: CanvasRenderingContext2D, text: string, width: number, maxLines: number): string[] {
  const units = text.includes(' ') ? text.split(' ') : Array.from(text)
  const glue = text.includes(' ') ? ' ' : ''
  const lines: string[] = []
  let current = ''
  for (const unit of units) {
    const candidate = current ? current + glue + unit : unit
    if (ctx.measureText(candidate).width <= width || !current) {
      current = candidate
    } else {
      lines.push(current)
      current = unit
    }
  }
  if (current) lines.push(current)
  const kept = lines.slice(0, maxLines)
  if (lines.length > maxLines) kept[maxLines - 1] = lines.slice(maxLines - 1).join(glue)
  // A single word wider than the page, or what did not fit on the last line, is shortened.
  return kept.map((line) => fit(ctx, line, width))
}

/** The text, or as much of it as fits followed by an ellipsis. */
function fit(ctx: CanvasRenderingContext2D, text: string, width: number): string {
  if (ctx.measureText(text).width <= width) return text
  let kept = Array.from(text)
  while (kept.length > 1 && ctx.measureText(kept.join('') + '…').width > width) kept = kept.slice(0, -1)
  return kept.join('') + '…'
}

function toBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('canvas'))), 'image/png')
  })
}

/** A file name from the publication's name: what the file system allows, no longer than needed. */
export function qrFileName(name: string, suffix: string, extension: 'png' | 'svg'): string {
  // eslint-disable-next-line no-control-regex -- control characters are exactly what a file name must not hold
  const cleaned = name.replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ')
  const base = Array.from(cleaned.trim()).slice(0, 60).join('').replace(/[\s.]+$/, '')
  return `${base || 'publication'} ${suffix}.${extension}`
}
