import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, Badge, Button, DescriptionList, PageState } from '@jabiz/ui'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ExternalLink, Loader2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { Link, useLocation } from '../qb/router'
import {
  checkoutReturn,
  FINAL_STATUSES,
  forgetJustStarted,
  payableUrl,
  readTopUp,
  WALLET_PATH,
  WALLET_QUERY,
  wasJustStarted,
  type TopUp,
} from './api'
import './messages'
import { pollUntil } from './poll'
import { goToStripe, stripePageUrl } from './stripePage'
import { STATUS_BADGES, useTopUpStatusLabel } from './WalletPage'
import { SoundOnce } from '../qb/SoundSettings'

/** How long the page asks before saying it will update later. */
export const OPEN_TIMEOUT_MS = 60_000
export const CONFIRM_TIMEOUT_MS = 90_000
export const CANCELLED_TIMEOUT_MS = 30_000

type Phase = 'watching' | 'settled' | 'late'

/**
 * One top-up (M-04; docs/quizbuks/plans/QP-pages.md section 4): the page Stripe sends the sponsor back to
 * (`?checkout=paid|cancelled`, `CheckoutRequest`) and the one a new top-up opens.
 *
 * - Just started (in this tab): reads the top-up until its checkout is open, then leaves for it once, if its address
 *   is Stripe's (`stripePageUrl`). Opened any other way it offers "Continue to payment" instead of leaving by itself.
 * - Back from a payment: reads until the server recorded the outcome (paid, expired, failed). The browser's return
 *   proves nothing and credits nothing: only Stripe's signed notice (or the server's own check with Stripe) does.
 * - Back after cancelling: the checkout stays payable until it expires; the page says so and offers it again.
 *
 * Reads are bounded (`pollUntil`); after the time is up the page says the wallet will update shortly.
 */
export function TopUpPage({ topUpId }: { topUpId: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const statusLabel = useTopUpStatusLabel()
  const queryClient = useQueryClient()
  const { search } = useLocation()
  const back = checkoutReturn(search)
  // The mark of a top-up just started here is read once and removed at once, whatever happens next (left while
  // opening, not found, timed out): opened again later, the page offers a button instead of leaving by itself.
  const [goOn] = useState(() => back === null && wasJustStarted(topUpId))
  useEffect(() => forgetJustStarted(), [])
  const [topUp, setTopUp] = useState<TopUp | null | undefined>(undefined)
  const [round, setRound] = useState(0)
  // How the latest round of reads ended; a new round (or another return) is watching until it ends.
  const watch = `${round}:${back}`
  const [ended, setEnded] = useState<{ watch: string; phase: Phase } | null>(null)
  const phase: Phase = ended?.watch === watch ? ended.phase : 'watching'
  const [leaving, setLeaving] = useState(false)
  const [unsafe, setUnsafe] = useState(false)
  const went = useRef(false)

  useEffect(() => {
    const controller = new AbortController()
    const final = (value: TopUp | null) => value === null || FINAL_STATUSES.includes(value.status)
    // A top-up just started goes on to its checkout by itself, once, as soon as it is open.
    const goOnWith = (value: TopUp | null) => {
      if (!goOn || went.current || !value || value.status === 'OPENING') return
      went.current = true
      const url = payableUrl(value)
      if (!url) return
      const safe = stripePageUrl(url)
      if (!safe) {
        setUnsafe(true)
        return
      }
      setLeaving(true)
      goToStripe(safe)
    }
    void pollUntil<TopUp | null>({
      read: () => readTopUp(topUpId),
      // Just started: until the checkout is open. Back from Stripe: until the server has the outcome.
      done: back === null ? (value) => final(value) || value?.status === 'OPEN' : final,
      onValue: (value) => {
        setTopUp(value)
        goOnWith(value)
      },
      timeoutMs: back === 'paid' ? CONFIRM_TIMEOUT_MS : back === 'cancelled' ? CANCELLED_TIMEOUT_MS
        : OPEN_TIMEOUT_MS,
      signal: controller.signal,
    }).then((outcome) => {
      if (outcome.kind === 'aborted') return
      setEnded({ watch, phase: outcome.kind === 'done' ? 'settled' : 'late' })
      // The wallet's figures and list may have changed.
      void queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
    })
    return () => controller.abort()
  }, [topUpId, back, watch, goOn, queryClient])

  // Back from Stripe through the browser's page cache: the page is as it was left, so it reads the top-up again and
  // offers the checkout rather than saying it is leaving.
  useEffect(() => {
    const shown = (event: PageTransitionEvent) => {
      if (!event.persisted) return
      setLeaving(false)
      // The page may be minutes old: read the top-up again.
      setRound((n) => n + 1)
    }
    window.addEventListener('pageshow', shown)
    return () => window.removeEventListener('pageshow', shown)
  }, [])

  const pay = () => {
    const url = topUp ? payableUrl(topUp) : null
    const safe = url ? stripePageUrl(url) : null
    if (!safe) {
      setUnsafe(true)
      return
    }
    setLeaving(true)
    goToStripe(safe)
  }

  const backLink = (
    <Button asChild variant="ghost" size="sm" className="w-fit">
      <Link to={WALLET_PATH} data-testid="topup-back">
        <ArrowLeft aria-hidden />
        {t('wallet.detail.back')}
      </Link>
    </Button>
  )

  if (topUp === undefined) {
    if (phase === 'late') {
      return (
        <section className="flex flex-col gap-4">
          {backLink}
          <PageState kind="error" titleAs="h1" description={tQb('failed')}
            action={<Button variant="outline" onClick={() => setRound((n) => n + 1)}>{tQb('retry')}</Button>} />
        </section>
      )
    }
    return <PageState kind="loading" />
  }
  if (topUp === null) {
    return (
      <section className="flex flex-col gap-4">
        {backLink}
        <PageState kind="notFound" titleAs="h1" description={t('wallet.detail.notFound')}
          data-testid="topup-missing" />
      </section>
    )
  }

  const payable = payableUrl(topUp)
  const waiting = phase === 'watching'
  let state: string
  let message: string
  let tone: 'default' | 'success' | 'warning' | 'destructive' = 'default'
  if (unsafe) {
    state = 'unsafe'
    message = t('wallet.detail.unsafe')
    tone = 'destructive'
  } else if (topUp.status === 'PAID') {
    state = 'paid'
    message = t('wallet.detail.paid', { amount: formatKudos(topUp.amount) })
    tone = 'success'
  } else if (topUp.status === 'EXPIRED') {
    state = 'expired'
    message = t('wallet.detail.expired')
  } else if (topUp.status === 'FAILED') {
    state = 'failed'
    message = t('wallet.detail.failed')
    tone = 'destructive'
  } else if (topUp.status === 'OPENING') {
    state = waiting ? 'opening' : 'opening-late'
    message = waiting ? t('wallet.detail.opening') : t('wallet.detail.openLate')
    tone = waiting ? 'default' : 'warning'
  } else if (back === 'paid' || !payable) {
    // Back from paying (or past the checkout's time): the outcome is Stripe's to report, never this page's.
    state = waiting ? 'confirming' : 'confirming-late'
    message = waiting ? t('wallet.detail.confirming') : t('wallet.detail.confirmLate')
    tone = waiting ? 'default' : 'warning'
  } else if (leaving) {
    state = 'leaving'
    message = t('wallet.detail.leaving')
  } else {
    state = back === 'cancelled' ? 'cancelled' : 'open'
    message = back === 'cancelled' ? t('wallet.detail.cancelled') : t('wallet.detail.openReady')
    tone = back === 'cancelled' ? 'warning' : 'default'
  }
  const busy = state === 'opening' || state === 'confirming' || state === 'leaving'
  const canPay = !unsafe && !leaving && back !== 'paid' && topUp.status === 'OPEN' && payable !== null
  const canCheck = !waiting && (state === 'opening-late' || state === 'confirming-late')
  const over = topUp.status === 'EXPIRED' || topUp.status === 'FAILED'

  return (
    <section className="flex max-w-xl flex-col gap-4" aria-labelledby="topup-title" data-testid="topup">
      {/* Q57: back from paying and the Kudos are in. */}
      <SoundOnce cue={state === 'paid' && back === 'paid' ? 'reward' : null} />
      {backLink}
      <h1 id="topup-title" className="text-xl font-semibold">{t('wallet.detail.title')}</h1>
      <Alert variant={tone} role="status" aria-live="polite" data-testid="topup-state" data-state={state}>
        <AlertDescription className="flex items-start gap-2">
          {busy && <Loader2 aria-hidden className="mt-0.5 size-4 shrink-0 animate-spin motion-reduce:animate-none" />}
          <span>{message}</span>
        </AlertDescription>
      </Alert>
      <DescriptionList data-testid="topup-details" items={[
        { key: 'amount', label: t('wallet.detail.amount'),
          value: <span data-testid="topup-amount">{formatKudos(topUp.amount)}</span> },
        { key: 'status', label: t('wallet.detail.status'),
          value: (
            <Badge variant={STATUS_BADGES[topUp.status] ?? 'outline'} data-testid="topup-status"
              data-status={topUp.status}>
              {statusLabel(topUp.status)}
            </Badge>
          ) },
        ...(topUp.method === 'MANUAL' ? [{ key: 'kind', label: t('wallet.detail.kind'),
          value: (
            <span data-testid="topup-kind" data-kind={topUp.manualKind ?? ''}>
              {t(`wallet.manual.kinds.${topUp.manualKind ?? 'PAID_OUTSIDE'}`)}
              {topUp.transferCode ? ` · ${topUp.transferCode}` : ''}
            </span>
          ) }] : []),
        { key: 'created', label: t('wallet.detail.created'), value: formatDateTime(topUp.createdAt) },
        ...(topUp.paidAt ? [{ key: 'paidAt', label: t('wallet.detail.paidAt'), value: formatDateTime(topUp.paidAt) }]
          : []),
        ...(canPay && topUp.expiresAt ? [{ key: 'expiresAt', label: t('wallet.detail.expiresAt'),
          value: formatDateTime(topUp.expiresAt) }] : []),
      ]} />
      <div className="flex flex-wrap gap-2">
        {canPay && (
          <Button onClick={pay} data-testid="topup-pay">
            <ExternalLink aria-hidden />
            {t('wallet.pay')}
          </Button>
        )}
        {canCheck && (
          <Button variant="outline" onClick={() => setRound((n) => n + 1)} data-testid="topup-check">
            {t('wallet.detail.checkAgain')}
          </Button>
        )}
        {over && (
          <Button asChild variant="outline">
            <Link to={WALLET_PATH}>{t('wallet.detail.newTopUp')}</Link>
          </Button>
        )}
      </div>
    </section>
  )
}
