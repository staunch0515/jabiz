import { Button, Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { QrCode, RefreshCw } from 'lucide-react'
import QRCode from 'qrcode'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { problemText } from '../qb/problems'
import { issuePass, passLink, type IssuedPass } from '../staff/api'
import { PASS } from '../staff/ids'
import './messages'

/** While the pass is shown, the prize is read again this often: the dialog says so once the counter hands it over. */
const POLL_EVERY = 3_000

/** The pass's link as an SVG image URL for `<img>` (the CSP allows `data:` images); drawn here, nothing sent. */
async function qrImageUrl(link: string): Promise<string> {
  const svg = await QRCode.toString(link, { type: 'svg', margin: 2, errorCorrectionLevel: 'M' })
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

/** `123456` as `123 456`, easier to read out. */
export const spaced = (digits: string) => `${digits.slice(0, 3)} ${digits.slice(3)}`

/**
 * The taker's pass for a prize to collect at the counter (Q89c, D-Q89-10): a QR and six digits, good for a minute
 * (counted from the answer's arrival, so the device's clock does not matter) and once; a new one when it runs out.
 * While it is shown the prize is read again, and the dialog says when the counter has handed it over.
 */
export function PassButton({ redemptionId, collected }: { redemptionId: string; collected: boolean }) {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [shown, setShown] = useState<{ pass: IssuedPass; until: number } | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const issue = useMutation({
    mutationFn: () => issuePass(redemptionId),
    onSuccess: (pass) => {
      const until = Date.now() + PASS.lifeMs
      setShown({ pass, until })
      setNow(Date.now())
    },
  })
  const link = shown ? passLink(shown.pass.passKey) : null
  const image = useQuery({ queryKey: ['pass-qr', link], queryFn: () => qrImageUrl(link!), enabled: !!link,
    staleTime: Infinity, gcTime: 0 })
  const left = shown ? Math.max(0, Math.ceil((shown.until - now) / 1000)) : 0
  const live = open && !!shown && left > 0 && !collected

  useEffect(() => {
    if (!live) return
    const tick = window.setInterval(() => setNow(Date.now()), 1000)
    const poll = window.setInterval(() => {
      void queryClient.invalidateQueries({ queryKey: ['qb', 'attempt-result'] })
      void queryClient.invalidateQueries({ queryKey: ['qb', 'redemptions'] })
    }, POLL_EVERY)
    return () => {
      window.clearInterval(tick)
      window.clearInterval(poll)
    }
  }, [live, queryClient])

  const openDialog = () => {
    setOpen(true)
    setShown(null)
    issue.mutate()
  }

  return (
    <>
      {/* Collected: no pass to show; a dialog open when the counter handed it over stays, to say so. */}
      {!collected && (
        <Button size="sm" className="self-start" onClick={openDialog} data-testid="pass-show">
          <QrCode aria-hidden />
          {t('pass.show')}
        </Button>
      )}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent data-testid="pass-dialog">
          <DialogHeader>
            <DialogTitle>{t('pass.title')}</DialogTitle>
            <DialogDescription>{t('pass.intro')}</DialogDescription>
          </DialogHeader>
          {collected ? (
            <p role="status" className="text-center font-medium" data-testid="pass-collected">{t('pass.collected')}</p>
          ) : issue.isError ? (
            <p role="alert" className="text-destructive text-sm">{problemText(issue.error)}</p>
          ) : (
            <div className="flex flex-col items-center gap-3">
              {shown && left > 0 && image.data ? (
                // The image carries its own light background: it stays scannable in the dark appearance.
                <img src={image.data} alt={t('pass.qrAlt')} width={240} height={240} className="rounded-md"
                  data-testid="pass-qr" />
              ) : shown && left > 0 && image.isError ? (
                <p role="alert" className="text-destructive text-sm">{t('pass.qrFailed')}</p>
              ) : shown && left === 0 ? (
                <p className="text-muted-foreground text-sm" data-testid="pass-expired">{t('pass.expired')}</p>
              ) : (
                <div className="bg-muted size-[240px] animate-pulse rounded-md" aria-hidden />
              )}
              {shown && left > 0 && (
                <>
                  <p className="text-center text-lg">
                    <span className="sr-only">{t('pass.digitsLabel')} </span>
                    <span className="font-mono text-2xl font-semibold tracking-widest" data-testid="pass-digits">
                      {spaced(shown.pass.passDigits)}
                    </span>
                  </p>
                  <p className="text-muted-foreground text-sm" aria-live="off" data-testid="pass-left">
                    {t('pass.left', { count: left })}
                  </p>
                </>
              )}
            </div>
          )}
          <DialogFooter>
            {!collected && shown && left === 0 && (
              <Button onClick={() => issue.mutate()} disabled={issue.isPending} data-testid="pass-renew">
                <RefreshCw aria-hidden />
                {t('pass.renew')}
              </Button>
            )}
            <Button variant="outline" onClick={() => setOpen(false)}>{t('pass.close')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
