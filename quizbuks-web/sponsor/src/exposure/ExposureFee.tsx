import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { problemText } from '../qb/problems'
import { formatUnit } from '../publishing/FundsPanel'
import { prepaymentsQuery } from '../publishing/api'
import {
  feeQuery,
  isRunningLow,
  readExposureFee,
  quoteTopUp,
  readExposureSettlements,
  RUNNING_STATUSES,
  topUpExposure,
  type ExposureFee,
} from './fee'
import './messages'

/**
 * F-M8: what the exposures of another Sponsor's shared video cost (`qb.sponsor.publication-exposure`): the price
 * frozen at the submission, what was bought, counted and is left (a bar), what the exposures since the last
 * settlement come to, a notice when little is left with the way to buy more, and the settlements. Nothing while the
 * publication bought none (the platform did not charge for exposures when it was submitted).
 */
export function ExposureFeeSection({ publicationId, status }: { publicationId: string; status: string }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const fee = useQuery({ queryKey: feeQuery(publicationId), queryFn: () => readExposureFee(publicationId) })
  const settlements = useQuery({ queryKey: [...feeQuery(publicationId), 'settlements'],
    queryFn: () => readExposureSettlements(publicationId), enabled: fee.data?.unit != null })
  const [buying, setBuying] = useState(false)
  const data = fee.data
  if (!data || data.unit == null || data.purchased == null) return null
  const n = (value: number) => value.toLocaleString(i18n.language)
  const left = Math.max(0, data.purchased - data.counted)
  const share = data.purchased > 0 ? Math.min(100, Math.round((data.counted / data.purchased) * 100)) : 0
  const running = RUNNING_STATUSES.includes(status)
  return (
    <div className="flex flex-col gap-2" data-testid="exposure-fee">
      <h3 className="text-sm font-medium">{t('exposure.fee.title')}</h3>
      <p className="text-sm" data-testid="exposure-fee-price">{t('exposure.fee.price', { unit: formatUnit(data.unit) })}</p>
      <div role="progressbar" aria-valuemin={0} aria-valuemax={data.purchased} aria-valuenow={Math.min(data.counted, data.purchased)}
        aria-label={t('exposure.fee.left', { left: n(left), bought: n(data.purchased) })}
        className="bg-muted h-2 w-full overflow-hidden rounded-full" data-testid="exposure-fee-bar">
        <div className="bg-primary h-full" style={{ width: `${share}%` }} />
      </div>
      <p className="text-muted-foreground text-sm" data-testid="exposure-fee-left">
        {t('exposure.fee.left', { left: n(left), bought: n(data.purchased) })}
      </p>
      {!data.finalSettled && data.pendingEstimate > 0 && (
        <p className="text-sm" data-testid="exposure-fee-pending">
          {t('exposure.fee.pending', { amount: formatKudos(data.pendingEstimate) })}
        </p>
      )}
      {running && isRunningLow(data) && (
        <p className="text-sm" role="status" data-testid="exposure-low">
          {t('exposure.fee.low', { left: n(left), bought: n(data.purchased) })}
        </p>
      )}
      {running && (
        <Button type="button" variant="outline" className="w-fit" onClick={() => setBuying(true)}
          data-testid="exposure-buy-more">
          {t('exposure.fee.buyMore')}
        </Button>
      )}
      {settlements.data && settlements.data.length > 0 && (
        <section aria-labelledby="exposure-settlements-title" className="flex flex-col gap-1">
          <h4 id="exposure-settlements-title" className="text-sm font-medium">{t('exposure.fee.settlements')}</h4>
          <ul className="flex flex-col gap-1 text-sm">
            {settlements.data.map((row) => (
              <li key={row.settlementId} data-testid="exposure-settlement" data-period={row.period}>
                {t(row.final ? 'exposure.fee.finalRow' : 'exposure.fee.row', { period: row.period, n: n(row.counted),
                  amount: formatKudos(row.amount), tax: formatKudos(row.tax) })}
              </li>
            ))}
          </ul>
        </section>
      )}
      <p className="text-muted-foreground text-sm">{t('exposure.fee.help')}</p>
      {buying && <BuyMoreDialog publicationId={publicationId} fee={data} onClose={() => setBuying(false)} />}
    </div>
  )
}

/** The dialog that buys more: how many, what it comes to before tax, and the server's answer when it refuses. */
function BuyMoreDialog({ publicationId, fee, onClose }: {
  publicationId: string
  fee: ExposureFee
  onClose: () => void
}) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const id = useId()
  const [text, setText] = useState('')
  const additional = /^\d+$/.test(text.trim()) ? Number(text.trim()) : null
  const buy = useMutation({
    mutationFn: () => topUpExposure(publicationId, fee.purchased ?? 0, additional ?? 0),
    onSuccess: async () => {
      notify.success(t('exposure.fee.bought', { n: additional }))
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: feeQuery(publicationId) }),
        queryClient.invalidateQueries({ queryKey: ['qb', 'sponsor', 'exposure', publicationId] }),
        queryClient.invalidateQueries({ queryKey: prepaymentsQuery(publicationId) }),
      ])
      onClose()
    },
    // A refusal (the exposures bought changed, the account is short) may mean the page is out of date: read it again.
    onError: () => queryClient.invalidateQueries({ queryKey: feeQuery(publicationId) }),
  })
  // The price is the server's (what the top-up would set aside, with tax, and whether the account covers it): asked
  // a moment after the typing stops, so that the page works out none of it itself.
  const [asked, setAsked] = useState<number | null>(null)
  useEffect(() => {
    const timer = setTimeout(() => setAsked(additional !== null && additional > 0 ? additional : null), 400)
    return () => clearTimeout(timer)
  }, [additional])
  const quote = useQuery({ queryKey: [...feeQuery(publicationId), 'quote', fee.purchased, asked],
    queryFn: () => quoteTopUp(publicationId, fee.purchased ?? 0, asked ?? 0), enabled: asked !== null && asked === additional,
    // The same question is not asked again at once (typing back and forth over the same number).
    staleTime: 30_000, retry: false })
  return (
    <Dialog open onOpenChange={(open) => {
      if (!open && !buy.isPending) onClose()
    }}>
      <DialogContent data-testid="exposure-buy-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={(event) => {
          event.preventDefault()
          if (additional !== null && additional > 0) buy.mutate()
        }}>
          <DialogHeader>
            <DialogTitle>{t('exposure.fee.buyTitle')}</DialogTitle>
            <DialogDescription>{t('exposure.fee.buyText', { unit: fee.unit == null ? '' : formatUnit(fee.unit) })}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-additional`}>{t('exposure.fee.additional')}</Label>
            <Input id={`${id}-additional`} inputMode="numeric" autoComplete="off" value={text}
              disabled={buy.isPending} data-testid="exposure-buy-additional"
              onChange={(event) => setText(event.target.value)} />
            {quote.data && asked === additional && (
              <p className="text-muted-foreground text-sm" data-testid="exposure-buy-estimate">
                {t('exposure.fee.quote', { amount: formatKudos(quote.data.deposit) })}
                {quote.data.shortfall > 0 && (
                  <span className="text-destructive block" data-testid="exposure-buy-short">
                    {t('exposure.fee.short', { available: formatKudos(quote.data.available),
                      shortfall: formatKudos(quote.data.shortfall) })}
                  </span>
                )}
              </p>
            )}
            {quote.isError && asked === additional && (
              <p className="text-destructive text-sm" role="alert" data-testid="exposure-buy-quote-problem">
                {problemText(quote.error)}
              </p>
            )}
          </div>
          {buy.isError && <p className="text-destructive text-sm" role="alert" data-testid="exposure-buy-problem">
            {problemText(buy.error)}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={buy.isPending} onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={additional === null || additional < 1 || buy.isPending}
              aria-busy={buy.isPending || undefined} data-testid="exposure-buy-confirm">
              {t('exposure.fee.confirm')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
