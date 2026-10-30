import { afterEach, describe, expect, it, vi } from 'vitest'
import { createDecoder } from './decoder'

const jsQR = vi.hoisted(() => vi.fn())
vi.mock('jsqr', () => ({ default: jsQR }))

/** A video showing a picture of 1280 × 720. */
function video(): HTMLVideoElement {
  const element = document.createElement('video')
  Object.defineProperty(element, 'readyState', { value: HTMLMediaElement.HAVE_ENOUGH_DATA })
  Object.defineProperty(element, 'videoWidth', { value: 1280 })
  Object.defineProperty(element, 'videoHeight', { value: 720 })
  return element
}

afterEach(() => {
  vi.restoreAllMocks()
  jsQR.mockReset()
  delete (globalThis as { BarcodeDetector?: unknown }).BarcodeDetector
})

describe('createDecoder (Q52)', () => {
  it('uses the browser\'s detector where it reads QR codes, never loading the script', async () => {
    const detect = vi.fn(async () => [{ rawValue: 'https://quizbuks.com/taker/redeem/k1' }])
    ;(globalThis as { BarcodeDetector?: unknown }).BarcodeDetector = Object.assign(
      vi.fn(function () { return { detect } }), { getSupportedFormats: async () => ['ean_13', 'qr_code'] })
    const decoder = await createDecoder()
    expect(await decoder.decode(video())).toBe('https://quizbuks.com/taker/redeem/k1')
    expect(jsQR).not.toHaveBeenCalled()
  })

  it('decodes by script where the browser cannot, on a picture scaled down to 640 wide, both ways round', async () => {
    const getImageData = vi.fn((_x: number, _y: number, width: number, height: number) =>
      ({ data: new Uint8ClampedArray(width * height * 4), width, height }))
    const drawImage = vi.fn()
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
      { drawImage, getImageData } as unknown as CanvasRenderingContext2D)
    jsQR.mockReturnValueOnce(null).mockReturnValueOnce({ data: 'https://quizbuks.com/taker/p/pub-1' })
    const decoder = await createDecoder()
    expect(await decoder.decode(video())).toBeNull()
    expect(await decoder.decode(video())).toBe('https://quizbuks.com/taker/p/pub-1')
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 640, 360)
    expect(jsQR).toHaveBeenCalledWith(expect.any(Uint8ClampedArray), 640, 360, { inversionAttempts: 'attemptBoth' })
  })

  it('decodes by script when the browser\'s detector reads no QR codes', async () => {
    ;(globalThis as { BarcodeDetector?: unknown }).BarcodeDetector = Object.assign(vi.fn(),
      { getSupportedFormats: async () => ['ean_13'] })
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn(),
      getImageData: () => ({ data: new Uint8ClampedArray(4), width: 1, height: 1 }) } as unknown as CanvasRenderingContext2D)
    jsQR.mockReturnValue({ data: 'x' })
    expect(await (await createDecoder()).decode(video())).toBe('x')
  })

  it('reads nothing before the camera shows a picture', async () => {
    const element = document.createElement('video')
    expect(await (await createDecoder()).decode(element)).toBeNull()
    expect(jsQR).not.toHaveBeenCalled()
  })
})
