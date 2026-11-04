import { afterEach, describe, expect, it, vi } from 'vitest'
import { createDecoder, NATIVE_MISSES_BEFORE_SCRIPT } from './decoder'

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

  /** A canvas whose picture jsQR is given (its content does not matter: jsQR is mocked). */
  function canvas() {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn(),
      getImageData: () => ({ data: new Uint8ClampedArray(4), width: 1, height: 1 }) } as unknown as CanvasRenderingContext2D)
  }

  // Android phones without Google Play services (most phones sold in China): Chrome has the detector and says it reads
  // QR codes, but its detection service is not there, so every detect() fails.
  it('decodes by script when the browser\'s detector fails', async () => {
    const detect = vi.fn(async () => { throw new DOMException('Barcode detection service unavailable.', 'NotSupportedError') })
    ;(globalThis as { BarcodeDetector?: unknown }).BarcodeDetector = Object.assign(
      vi.fn(function () { return { detect } }), { getSupportedFormats: async () => ['qr_code'] })
    canvas()
    jsQR.mockReturnValue({ data: 'https://quizbuks.com/taker/redeem/k2' })
    const decoder = await createDecoder()
    expect(await decoder.decode(video())).toBe('https://quizbuks.com/taker/redeem/k2')
    expect(await decoder.decode(video())).toBe('https://quizbuks.com/taker/redeem/k2')
    // Once failed, the detector is not asked again.
    expect(detect).toHaveBeenCalledTimes(1)
  })

  // Play services present but its barcode model never downloaded (Google's servers cannot be reached from China):
  // detect() answers, always with nothing.
  it('also decodes by script when the browser\'s detector keeps finding nothing', async () => {
    const detect = vi.fn(async () => [])
    ;(globalThis as { BarcodeDetector?: unknown }).BarcodeDetector = Object.assign(
      vi.fn(function () { return { detect } }), { getSupportedFormats: async () => ['qr_code'] })
    canvas()
    jsQR.mockReturnValue({ data: 'https://quizbuks.com/taker/redeem/k3' })
    const decoder = await createDecoder()
    for (let i = 0; i < NATIVE_MISSES_BEFORE_SCRIPT; i++) expect(await decoder.decode(video())).toBeNull()
    expect(jsQR).not.toHaveBeenCalled()
    expect(await decoder.decode(video())).toBe('https://quizbuks.com/taker/redeem/k3')
    // The detector is still tried first: it may start working (the model downloaded late).
    expect(detect).toHaveBeenCalledTimes(NATIVE_MISSES_BEFORE_SCRIPT + 1)
  })

  it('keeps the browser\'s detector alone while it finds codes', async () => {
    let calls = 0
    const detect = vi.fn(async () => (++calls % 2 ? [] : [{ rawValue: 'found' }]))
    ;(globalThis as { BarcodeDetector?: unknown }).BarcodeDetector = Object.assign(
      vi.fn(function () { return { detect } }), { getSupportedFormats: async () => ['qr_code'] })
    const decoder = await createDecoder()
    for (let i = 0; i < NATIVE_MISSES_BEFORE_SCRIPT * 2; i++) await decoder.decode(video())
    expect(jsQR).not.toHaveBeenCalled()
  })

  it('reads nothing before the camera shows a picture', async () => {
    const element = document.createElement('video')
    expect(await (await createDecoder()).decode(element)).toBeNull()
    expect(jsQR).not.toHaveBeenCalled()
  })
})
