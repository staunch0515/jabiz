/** Reads a QR code from the camera's current picture. */
export interface Decoder {
  /** The text of a QR code in the picture now, or null when there is none (yet). */
  decode(video: HTMLVideoElement): Promise<string | null>
}

/** The browser's own detector (not in TypeScript's DOM types). */
interface BarcodeDetectorLike {
  detect(source: CanvasImageSource): Promise<{ rawValue: string }[]>
}
interface BarcodeDetectorClass {
  new (options: { formats: string[] }): BarcodeDetectorLike
  getSupportedFormats(): Promise<string[]>
}

/**
 * The script reads the square in the middle of the picture, where the scan page's frame asks for the code, at the
 * camera's own resolution up to this edge: a code held at arm's length covers a fifth of the picture, and scaled down
 * with the rest of a picture its modules are too few pixels for jsQR (Q52b). Every other picture is read whole, scaled
 * down to the same edge, for a code held outside the frame.
 */
export const SCRIPT_EDGE = 800

/** The share of the picture's shorter side read in the middle: the frame on the scan page and a margin around it. */
export const MIDDLE_SHARE = 0.8

/**
 * Pictures the browser's detector may find nothing in before the script reads each picture too (about two seconds at
 * the scan page's pace). A detector that says it reads QR codes may still never find one: on Android it is Google Play
 * services, which most phones sold in China do not have, and whose barcode model is downloaded from Google, which
 * cannot be reached from China. In Japan the same phone finds the code at once.
 */
export const NATIVE_MISSES_BEFORE_SCRIPT = 10

/**
 * The browser's barcode detector where it reads QR codes (Chrome on Android, so the Android app), else jsQR, loaded
 * only then (Safari, so the iOS app, and desktop browsers): nobody who does not scan downloads it. Where the
 * detector fails, the script takes over; where it keeps finding nothing, the script reads each picture after it.
 */
export async function createDecoder(): Promise<Decoder> {
  const native = await nativeDecoder()
  if (!native) return scriptDecoder()
  let script: Promise<Decoder> | null = null
  let failed = false
  let misses = 0
  return {
    async decode(video) {
      if (video.readyState < video.HAVE_CURRENT_DATA) return null
      if (!failed) {
        try {
          const text = await native.decode(video)
          if (text) {
            misses = 0
            return text
          }
          misses++
        } catch {
          // No detection service on this phone: it will not come back while the page is open.
          failed = true
        }
      }
      if (!failed && misses <= NATIVE_MISSES_BEFORE_SCRIPT) return null
      script ??= scriptDecoder()
      return (await script).decode(video)
    },
  }
}

/** The browser's own detector, or null where it does not say it reads QR codes. Its decode may throw. */
async function nativeDecoder(): Promise<Decoder | null> {
  const Detector = (globalThis as { BarcodeDetector?: BarcodeDetectorClass }).BarcodeDetector
  if (!Detector) return null
  try {
    if (!(await Detector.getSupportedFormats()).includes('qr_code')) return null
    const detector = new Detector({ formats: ['qr_code'] })
    return {
      async decode(video) {
        const found = await detector.detect(video)
        return found[0]?.rawValue || null
      },
    }
  } catch {
    // A detector that cannot say what it reads: decode by script.
    return null
  }
}

/** The part of the picture read this time: the middle square at full size, or the whole picture scaled down. */
export function region(width: number, height: number, middle: boolean):
  { sx: number; sy: number; sw: number; sh: number; dw: number; dh: number } {
  if (middle) {
    const side = Math.round(Math.min(width, height) * MIDDLE_SHARE)
    const edge = Math.min(side, SCRIPT_EDGE)
    return { sx: Math.round((width - side) / 2), sy: Math.round((height - side) / 2), sw: side, sh: side, dw: edge, dh: edge }
  }
  const scale = Math.min(1, SCRIPT_EDGE / Math.max(width, height))
  return { sx: 0, sy: 0, sw: width, sh: height, dw: Math.round(width * scale), dh: Math.round(height * scale) }
}

/** jsQR, on the middle of the picture and the whole picture in turn ({@link region}), both ways round. */
async function scriptDecoder(): Promise<Decoder> {
  const { default: jsQR } = await import('jsqr')
  const canvas = document.createElement('canvas')
  const context = canvas.getContext('2d', { willReadFrequently: true })
  let reads = 0
  return {
    async decode(video) {
      if (!context || video.readyState < video.HAVE_CURRENT_DATA || !video.videoWidth) return null
      const { sx, sy, sw, sh, dw, dh } = region(video.videoWidth, video.videoHeight, reads++ % 2 === 0)
      canvas.width = dw
      canvas.height = dh
      context.drawImage(video, sx, sy, sw, sh, 0, 0, dw, dh)
      const image = context.getImageData(0, 0, dw, dh)
      // Codes on a lit screen are often light on dark: try both.
      return jsQR(image.data, image.width, image.height, { inversionAttempts: 'attemptBoth' })?.data || null
    },
  }
}
