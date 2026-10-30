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

/** The picture is scaled down to this width before decoding by script: enough for a code held up to the camera. */
const MAX_WIDTH = 640

/**
 * The browser's barcode detector where it reads QR codes (Chrome on Android, so the Android app), else jsQR, loaded
 * only then (Safari, so the iOS app, and desktop browsers): nobody who does not scan downloads it.
 */
export async function createDecoder(): Promise<Decoder> {
  const Detector = (globalThis as { BarcodeDetector?: BarcodeDetectorClass }).BarcodeDetector
  if (Detector) {
    try {
      if ((await Detector.getSupportedFormats()).includes('qr_code')) {
        const detector = new Detector({ formats: ['qr_code'] })
        return {
          async decode(video) {
            if (video.readyState < video.HAVE_CURRENT_DATA) return null
            const found = await detector.detect(video)
            return found[0]?.rawValue || null
          },
        }
      }
    } catch {
      // A detector that cannot say what it reads: decode by script.
    }
  }
  const { default: jsQR } = await import('jsqr')
  const canvas = document.createElement('canvas')
  const context = canvas.getContext('2d', { willReadFrequently: true })
  return {
    async decode(video) {
      if (!context || video.readyState < video.HAVE_CURRENT_DATA || !video.videoWidth) return null
      const scale = Math.min(1, MAX_WIDTH / video.videoWidth)
      canvas.width = Math.round(video.videoWidth * scale)
      canvas.height = Math.round(video.videoHeight * scale)
      context.drawImage(video, 0, 0, canvas.width, canvas.height)
      const image = context.getImageData(0, 0, canvas.width, canvas.height)
      // Codes on a lit screen are often light on dark: try both.
      return jsQR(image.data, image.width, image.height, { inversionAttempts: 'attemptBoth' })?.data || null
    },
  }
}
