import { Alert, AlertDescription, Button } from '@jabiz/ui'
import { CameraOff, ScanLine } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { navigate } from '../qb/router'
import { createDecoder } from './decoder'
import './messages'
import { scanTarget, targetPath } from './target'

/** The app's scanner (Q52). */
export const SCAN_PATH = '/scan'

/** How often the picture is read: often enough to feel instant, rarely enough to spare an old phone's battery. */
export const SCAN_INTERVAL_MS = 200

type Problem = 'denied' | 'noCamera' | 'unavailable' | 'failed'

/** What a refused `getUserMedia` means for the person. */
function problemOf(error: unknown): Problem {
  const name = error instanceof Error || error instanceof DOMException ? error.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'denied'
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return 'noCamera'
  return 'failed'
}

/**
 * Scans a QuizBuks code with the camera inside the app (Q52): a sponsor's redeem code on their screen or poster, or a
 * publication's code in a shop. The phone's own camera app opens a scanned link in the browser, where a taker using
 * the app from the home screen (or the store app) is not signed in; scanning here keeps them in their session.
 * The camera starts when the page opens (the person asked for it) and stops when it leaves or a code is found. Only this
 * site's own links are opened ({@link scanTarget}); the picture never leaves the phone.
 */
export function ScanPage() {
  const { t } = useTranslation(APP)
  const video = useRef<HTMLVideoElement>(null)
  const [started, setStarted] = useState(false)
  const [foreign, setForeign] = useState(false)
  const [problem, setProblem] = useState<Problem | null>(null)
  // Trying again starts the camera anew.
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let stopped = false
    let stream: MediaStream | null = null
    let timer = 0
    const stop = () => {
      stopped = true
      window.clearTimeout(timer)
      stream?.getTracks().forEach((track) => track.stop())
    }
    const run = async () => {
      if (!navigator.mediaDevices?.getUserMedia) {
        setProblem('unavailable')
        return
      }
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false })
      } catch (error) {
        if (!stopped) setProblem(problemOf(error))
        return
      }
      const element = video.current
      if (stopped || !element) {
        stop()
        return
      }
      element.srcObject = stream
      // Muted and inline, a video plays without a tap on every phone; a refusal still shows the picture's first frame.
      await element.play().catch(() => undefined)
      const decoder = await createDecoder()
      if (stopped) return
      setStarted(true)
      const read = async () => {
        if (stopped) return
        const text = await decoder.decode(element).catch(() => null)
        if (stopped) return
        if (text) {
          const target = scanTarget(text)
          if (target) {
            stop()
            navigator.vibrate?.(40)
            // The scanner is not a page to come back to: back from the prize goes where the person was before.
            navigate(targetPath(target), { replace: true })
            return
          }
          setForeign(true)
        }
        timer = window.setTimeout(() => void read(), SCAN_INTERVAL_MS)
      }
      void read()
    }
    void run()
    return stop
  }, [attempt])

  const again = () => {
    setProblem(null)
    setStarted(false)
    setForeign(false)
    setAttempt((n) => n + 1)
  }

  return (
    <section className="flex flex-col gap-4" aria-labelledby="scan-title" data-testid="scan-page">
      <h1 id="scan-title" className="flex items-center gap-2 text-xl font-semibold">
        <ScanLine aria-hidden className="text-primary size-5" />
        {t('scan.title')}
      </h1>
      {problem ? (
        <Alert variant="warning" data-testid={`scan-${problem}`}>
          <CameraOff aria-hidden />
          <AlertDescription className="text-current">{t(`scan.${problem}`)}</AlertDescription>
        </Alert>
      ) : (
        <>
          <p className="text-muted-foreground text-sm">{t('scan.hint')}</p>
          <div className="bg-muted relative aspect-square w-full overflow-hidden rounded-xl">
            <video ref={video} muted playsInline aria-hidden className="size-full object-cover" data-testid="scan-video" />
            {/* Where to hold the code. */}
            <div aria-hidden className="border-primary pointer-events-none absolute inset-[15%] rounded-lg border-4" />
          </div>
          <p role="status" className={foreign ? 'text-destructive text-sm' : 'text-muted-foreground text-sm'}
            data-testid="scan-status">
            {foreign ? t('scan.foreign') : started ? t('scan.scanning') : t('scan.starting')}
          </p>
          <p className="text-muted-foreground text-xs">{t('scan.privacy')}</p>
        </>
      )}
      <div className="flex gap-2">
        {problem && problem !== 'unavailable' && problem !== 'noCamera' && (
          <Button onClick={again} data-testid="scan-again">{t('scan.again')}</Button>
        )}
        <Button variant="outline" onClick={() => navigate('/')} data-testid="scan-close">{t('scan.cancel')}</Button>
      </div>
    </section>
  )
}
