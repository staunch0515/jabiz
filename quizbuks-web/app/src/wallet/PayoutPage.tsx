import { Alert, AlertDescription, Badge, Button, PageState } from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ExternalLink, Loader2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { noticeConsentProblem } from '../qb/ConsentGate'
import { problemText } from '../qb/problems'
import { Link, useLocation } from '../qb/router'
import {
  freshLink,
  onboardingReturn,
  PAYOUT_QUERY,
  readPayoutAccount,
  readPayoutStatuses,
  startOnboarding,
  WALLET_PATH,
  WALLET_QUERY,
  type PayoutAccount,
} from './api'
import './messages'
import { pollUntil } from './poll'
import { goToStripe, stripePageUrl } from './stripePage'

/** How long the page waits for Stripe's link, and for Stripe's report after the taker came back. */
export const LINK_TIMEOUT_MS = 60_000
export const REPORT_TIMEOUT_MS = 60_000

/** What the page is doing about onboarding (its own click), apart from what the account is. */
type Step =
  | { kind: 'idle' }
  | { kind: 'preparing' }
  | { kind: 'leaving' }
  | { kind: 'late' }
  | { kind: 'unsafe' }
  | { kind: 'failed'; error: unknown }

const SETTLED = new Set(['ENABLED', 'RESTRICTED', 'DEAUTHORIZED'])

/**
 * The taker's payout account (U-53; docs/quizbuks/plans/QP-pages.md section 4). "Set up" runs `QB_PAYOUT_ONBOARD`,
 * which starts the job creating the Stripe account and a fresh onboarding link; the page reads the account until that
 * link appears and leaves for it, if its address is Stripe's (`stripePageUrl`). Stripe sends the taker back to
 * `?onboarding=done` (the page reads until Stripe's report arrived: the return itself proves nothing) or
 * `?onboarding=refresh` (the link expired or was used: a new one is one click away). Reads are bounded (`pollUntil`).
 */
export function PayoutPage() {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const { search } = useLocation()
  const back = onboardingReturn(search)
  const statuses = useQuery({ queryKey: ['qb', 'dict', 'payout-account-status'], queryFn: readPayoutStatuses,
    staleTime: Infinity })
  const account = useQuery({ queryKey: PAYOUT_QUERY, queryFn: readPayoutAccount })
  const [step, setStep] = useState<Step>({ kind: 'idle' })
  const [round, setRound] = useState(0)
  const watch = `${round}:${back}`
  const [reported, setReported] = useState<{ watch: string; late: boolean } | null>(null)
  const checking = back === 'done' && reported?.watch !== watch
  const checkLate = back === 'done' && reported?.watch === watch && reported.late
  const leave = useRef<AbortController | null>(null)

  // Back from Stripe: read until Stripe's report about the account arrived (through the server).
  useEffect(() => {
    if (back !== 'done') return
    const controller = new AbortController()
    void pollUntil<PayoutAccount | null>({
      read: readPayoutAccount,
      done: (value) => !!value && SETTLED.has(value.status),
      onValue: (value) => queryClient.setQueryData(PAYOUT_QUERY, value),
      timeoutMs: REPORT_TIMEOUT_MS,
      signal: controller.signal,
    }).then((outcome) => {
      if (outcome.kind === 'aborted') return
      setReported({ watch, late: outcome.kind === 'timeout' })
      void queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
    })
    return () => controller.abort()
  }, [back, watch, queryClient])

  useEffect(() => () => leave.current?.abort(), [])

  // Back from Stripe through the browser's page cache: the page is as it was left, so it reads again and offers the
  // step again.
  useEffect(() => {
    const shown = (event: PageTransitionEvent) => {
      if (!event.persisted) return
      setStep({ kind: 'idle' })
      // The page may be minutes old: read the account again (and Stripe's report, when back from it).
      setRound((n) => n + 1)
      void queryClient.invalidateQueries({ queryKey: PAYOUT_QUERY })
    }
    window.addEventListener('pageshow', shown)
    return () => window.removeEventListener('pageshow', shown)
  }, [queryClient])

  /** Asks for a fresh link and follows it once it is there. */
  const onboard = async () => {
    leave.current?.abort()
    const controller = new AbortController()
    leave.current = controller
    setStep({ kind: 'preparing' })
    try {
      const before = await readPayoutAccount()
      await startOnboarding(crypto.randomUUID())
      const outcome = await pollUntil<PayoutAccount | null>({
        read: readPayoutAccount,
        done: (value) => freshLink(before, value) !== null,
        timeoutMs: LINK_TIMEOUT_MS,
        signal: controller.signal,
      })
      if (outcome.kind === 'aborted') return
      if (outcome.kind === 'timeout') {
        setStep({ kind: 'late' })
        return
      }
      queryClient.setQueryData(PAYOUT_QUERY, outcome.value)
      const safe = stripePageUrl(freshLink(before, outcome.value))
      if (!safe) {
        setStep({ kind: 'unsafe' })
        return
      }
      setStep({ kind: 'leaving' })
      goToStripe(safe)
    } catch (error) {
      if (!controller.signal.aborted) setStep({ kind: 'failed', error })
      noticeConsentProblem(queryClient, error)
    }
  }

  const backLink = (
    <Button asChild variant="ghost" size="sm" className="w-fit">
      <Link to={WALLET_PATH} data-testid="payout-back">
        <ArrowLeft aria-hidden />
        {t('payout.back')}
      </Link>
    </Button>
  )

  if (account.isPending) return <PageState kind="loading" />
  const data = account.isSuccess ? account.data : null
  const status = data?.status ?? null
  const label = status ? statuses.data?.find((item) => item.code === status)?.label ?? status : t('payout.none')

  let state: string
  let message: string | null
  let tone: 'default' | 'success' | 'warning' | 'destructive' = 'default'
  if (step.kind === 'unsafe') {
    state = 'unsafe'
    message = t('payout.unsafe')
    tone = 'destructive'
  } else if (step.kind === 'failed') {
    state = 'failed'
    message = problemText(step.error)
    tone = 'destructive'
  } else if (step.kind === 'preparing' || step.kind === 'leaving') {
    state = step.kind
    message = step.kind === 'preparing' ? t('payout.preparing') : t('payout.leaving')
  } else if (step.kind === 'late') {
    state = 'late'
    message = t('payout.late')
    tone = 'warning'
  } else if (status === 'ENABLED') {
    state = 'enabled'
    message = t('payout.enabled')
    tone = 'success'
  } else if (status === 'DEAUTHORIZED') {
    state = 'closed'
    message = t('payout.closed')
    tone = 'warning'
  } else if (checking) {
    state = 'checking'
    message = t('payout.checking')
  } else if (checkLate) {
    state = 'check-late'
    message = t('payout.checkLate')
    tone = 'warning'
  } else if (back === 'refresh') {
    state = 'refresh'
    message = t('payout.refresh')
    tone = 'warning'
  } else if (status) {
    state = 'unfinished'
    message = t('payout.notFinished')
    tone = 'warning'
  } else {
    state = 'none'
    message = null
  }
  const busy = state === 'preparing' || state === 'leaving' || state === 'checking'
  const canOnboard = !busy && status !== 'ENABLED' && status !== 'DEAUTHORIZED'
  const action = !status ? t('payout.setUp') : t('payout.continue')

  return (
    <section className="flex flex-col gap-4" aria-labelledby="payout-title" data-testid="payout">
      {backLink}
      <h1 id="payout-title" className="text-xl font-semibold">{t('payout.title')}</h1>
      <p className="text-muted-foreground text-sm">{t('payout.intro')}</p>
      {account.isError && (
        <Alert variant="destructive"><AlertDescription>{problemText(account.error)}</AlertDescription></Alert>
      )}
      <p className="flex items-center gap-2 text-sm">
        <span className="text-muted-foreground">{t('payout.status')}</span>
        <Badge variant={status === 'ENABLED' ? 'success' : status ? 'outline' : 'secondary'}
          data-testid="payout-status" data-status={status ?? 'NONE'}>
          {label}
        </Badge>
      </p>
      {data?.requirementsDue && status !== 'ENABLED' && (
        <p className="text-sm" data-testid="payout-requirements">{t('payout.requirements')}</p>
      )}
      <Alert variant={tone} role="status" aria-live="polite" data-testid="payout-state" data-state={state}
        className={message ? undefined : 'sr-only'}>
        <AlertDescription className="flex items-start gap-2">
          {busy && <Loader2 aria-hidden className="mt-0.5 size-4 shrink-0 animate-spin motion-reduce:animate-none" />}
          <span>{message}</span>
        </AlertDescription>
      </Alert>
      <div className="flex flex-wrap gap-2">
        {canOnboard && (
          <Button onClick={() => void onboard()} data-testid="payout-onboard">
            <ExternalLink aria-hidden />
            {action}
          </Button>
        )}
        {checkLate && (
          <Button variant="outline" onClick={() => setRound((n) => n + 1)} data-testid="payout-check">
            {t('payout.checkAgain')}
          </Button>
        )}
      </div>
    </section>
  )
}
