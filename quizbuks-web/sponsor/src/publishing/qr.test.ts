import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { POSTER_HEIGHT, POSTER_WIDTH, posterPng, qrFileName, qrImageUrl, qrPng, qrSvg, QR_PNG_SIZE } from './qr'

const toString = vi.fn(async (text: string) => `<svg>${text}</svg>`)
const toCanvas = vi.fn(async () => undefined)
vi.mock('qrcode', () => ({ default: { toString: (...args: unknown[]) => toString(...(args as [string])),
  toCanvas: (...args: unknown[]) => toCanvas(...(args as [])) } }))

/** jsdom has no canvas: a recording stand-in for the drawing, and a PNG of the canvas's size. */
function fakeCanvas() {
  const calls: string[] = []
  const ctx = {
    fillStyle: '', font: '', textAlign: '', textBaseline: '',
    fillRect: (...args: number[]) => calls.push(`fillRect ${args.join(',')}`),
    fillText: (text: string, x: number, y: number) => calls.push(`fillText ${text} ${x},${y}`),
    drawImage: (_: unknown, x: number, y: number, w: number) => calls.push(`drawImage ${x},${y} ${w}`),
    measureText: (text: string) => ({ width: Array.from(text).length * 60 }),
  }
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ctx as unknown as CanvasRenderingContext2D)
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (this: HTMLCanvasElement, callback) {
    callback(new Blob([`${this.width}x${this.height}`], { type: 'image/png' }))
  })
  return { calls, ctx }
}

describe('the QR code of a share link', () => {
  beforeEach(() => {
    toString.mockClear()
    toCanvas.mockClear()
  })
  afterEach(() => vi.restoreAllMocks())

  it('is an SVG image URL for the page, and an SVG file with a quiet zone', async () => {
    const url = await qrImageUrl('https://quizbuks.com/p/p1')
    expect(url).toMatch(/^data:image\/svg\+xml;charset=utf-8,/)
    expect(decodeURIComponent(url.split(',')[1])).toBe('<svg>https://quizbuks.com/p/p1</svg>')
    expect(toString).toHaveBeenLastCalledWith('https://quizbuks.com/p/p1', expect.objectContaining({ type: 'svg' }))

    const file = await qrSvg('https://quizbuks.com/p/p1')
    expect(file.type).toBe('image/svg+xml')
    expect(await file.text()).toBe('<svg>https://quizbuks.com/p/p1</svg>')
    expect(toString).toHaveBeenLastCalledWith('https://quizbuks.com/p/p1',
      { type: 'svg', margin: 4, errorCorrectionLevel: 'H' })
  })

  it('is a large PNG with the highest error correction', async () => {
    fakeCanvas()
    const file = await qrPng('https://quizbuks.com/p/p1')
    expect(file.type).toBe('image/png')
    expect(toCanvas).toHaveBeenCalledWith(expect.any(HTMLCanvasElement), 'https://quizbuks.com/p/p1',
      { width: QR_PNG_SIZE, margin: 4, errorCorrectionLevel: 'H' })
  })

  it('makes an A4 poster with the name, the code, every instruction and the link', async () => {
    const { calls } = fakeCanvas()
    const file = await posterPng('https://quizbuks.com/p/p1', { name: 'Coffee week',
      instructions: ['Scan to answer', '扫码答题'] })
    expect(await file.text()).toBe(`${POSTER_WIDTH}x${POSTER_HEIGHT}`)
    expect(calls[0]).toBe(`fillRect 0,0,${POSTER_WIDTH},${POSTER_HEIGHT}`)
    expect(calls).toContain('fillText Coffee week 1240,160')
    expect(calls.filter((call) => call.startsWith('drawImage'))).toHaveLength(1)
    expect(calls.some((call) => call.startsWith('fillText Scan to answer '))).toBe(true)
    expect(calls.some((call) => call.startsWith('fillText 扫码答题 '))).toBe(true)
    expect(calls.at(-1)).toBe(`fillText https://quizbuks.com/p/p1 1240,${POSTER_HEIGHT - 160}`)
    expect(toCanvas).toHaveBeenCalledWith(expect.any(HTMLCanvasElement), 'https://quizbuks.com/p/p1',
      expect.objectContaining({ errorCorrectionLevel: 'H' }))
  })

  it('wraps a long name over at most three lines and shortens the rest', async () => {
    const { calls } = fakeCanvas()
    // 60 px a character, 2160 px a line: 36 characters fit.
    const words = Array.from({ length: 30 }, (_, i) => `word${i}`)
    await posterPng('https://quizbuks.com/p/p1', { name: words.join(' '), instructions: [] })
    const title = calls.filter((call) => call.startsWith('fillText word'))
    expect(title).toHaveLength(3)
    expect(title[2]).toMatch(/…/)
    for (const line of title) expect(Array.from(line.replace(/^fillText /, '').replace(/ \d+,\d+$/, '')).length).toBeLessThanOrEqual(36)
  })

  it('names the files after the publication, without what a file system refuses', () => {
    expect(qrFileName('Coffee week', 'QR', 'png')).toBe('Coffee week QR.png')
    expect(qrFileName('a/b:c*d?"e<f>g|h', 'poster', 'png')).toBe('a b c d e f g h poster.png')
    expect(qrFileName('   ', 'QR', 'svg')).toBe('publication QR.svg')
    expect(qrFileName('x'.repeat(100), 'QR', 'svg')).toBe(`${'x'.repeat(60)} QR.svg`)
  })
})
