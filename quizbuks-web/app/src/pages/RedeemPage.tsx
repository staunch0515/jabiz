import { ApiError, formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, AlertTitle, Button, PageState } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, Gift, Hourglass } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { checkRedeemCode, claimRedemption, readAttemptResult, type RedeemPrize, type RedemptionStatus } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'

/** How often the page asks whether the sponsor confirmed a claim from their printed code. */
const WAIT_POLL = 4_000

/** After a claim: collected at once, or waiting for the sponsor to confirm (polled until they do). */
function Claimed({ prize, status }: { prize: RedeemPrize; status: RedemptionStatus }) {
  const { t } = useTranslation(APP)
  const attemptId = prize.attemptId
  const result = useQuery({
    queryKey: ['qb', 'redeem-wait', prize.redemptionId],
    queryFn: () => readAttemptResult(attemptId),
    enabled: status === 'CLAIMED',
    refetchInterval: (query) => (query.state.data?.redeem?.status === 'CLAIMED' ? WAIT_POLL : false),
  })
  const now = result.data?.redeem?.status ?? status
  const collected = now === 'REDEEMED'
  return (
    <Alert variant={collected ? 'success' : 'default'} data-testid="redeem-done" data-status={now}>
      {collected ? <CircleCheck aria-hidden /> : <Hourglass aria-hidden />}
      <AlertTitle>{t(collected ? 'redeemPage.collected' : 'redeemPage.waiting')}</AlertTitle>
      <AlertDescription className="text-current flex flex-col gap-2">
        <span className="font-medium">{prize.description}</span>
        <span className="flex items-center gap-2">
          <span>{t('cash.code')}</span>
          <span className="rounded bg-muted px-2 py-0.5 font-mono text-base font-semibold" data-testid="redeem-code">
            {prize.code}
          </span>
        </span>
        <span>{t(collected ? 'redeemPage.collectedText' : now === 'DUE' ? 'redeemPage.declinedText' : 'redeemPage.waitingText')}</span>
        <Link to={`/result/${encodeURIComponent(attemptId)}`} className="underline underline-offset-4"
          data-testid="redeem-result-link">
          {t('redeemPage.seeResult')}
        </Link>
      </AlertDescription>
    </Alert>
  )
}

/**
 * The page a scanned redeem code opens (docs/quizbuks/plans/Q22-reward-methods.md, Q22b): whose code it is and the
 * caller's prizes to collect there, each with a button to collect it. The console's rotating code collects at once;
 * a printed code makes a claim the sponsor confirms, which the page waits for.
 */
export function RedeemPage({ scannedKey }: { scannedKey: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const check = useQuery({ queryKey: ['qb', 'redeem-check', scannedKey], queryFn: () => checkRedeemCode(scannedKey),
    retry: false })
  const [claimed, setClaimed] = useState<{ prize: RedeemPrize; status: RedemptionStatus } | null>(null)
  const claim = useMutation({
    mutationFn: (prize: RedeemPrize) => claimRedemption(scannedKey, prize.redemptionId).then((output) =>
      ({ prize, status: output.status })),
    onSuccess: (outcome) => {
      setClaimed(outcome)
      void queryClient.invalidateQueries({ queryKey: ['qb', 'redemptions'] })
      void queryClient.invalidateQueries({ queryKey: ['qb', 'attempt-result', outcome.prize.attemptId] })
    },
  })

  if (check.isPending) return <PageState kind="loading" />
  if (check.isError) {
    const invalid = check.error instanceof ApiError && check.error.status === 422
    return (
      <PageState kind={invalid ? 'empty' : 'error'} titleAs="h1" title={invalid ? t('redeemPage.invalidTitle') : undefined}
        description={invalid ? t('redeemPage.invalid') : problemText(check.error)}
        action={invalid ? <Link to="/" className="underline underline-offset-4">{t('redeemPage.home')}</Link>
          : <Button variant="outline" onClick={() => void check.refetch()}>{tQb('retry')}</Button>} />
    )
  }
  if (claimed) {
    return (
      <section className="flex flex-col gap-4" aria-labelledby="redeem-title" data-testid="redeem-page">
        <h1 id="redeem-title" className="text-xl font-semibold">{check.data.sponsorCompany ?? t('cash.theSponsor')}</h1>
        <Claimed {...claimed} />
      </section>
    )
  }
  const open = check.data.prizes.filter((prize) => prize.status === 'DUE' || prize.status === 'CLAIMED')
  return (
    <section className="flex flex-col gap-4" aria-labelledby="redeem-title" data-testid="redeem-page"
      data-kind={check.data.kind}>
      <h1 id="redeem-title" className="text-xl font-semibold">
        {t('redeemPage.title', { sponsor: check.data.sponsorCompany ?? t('cash.theSponsor') })}
      </h1>
      {open.length === 0 ? (
        <PageState kind="empty" title={t('redeemPage.noneTitle')} description={t('redeemPage.none')}
          action={<Link to="/" className="underline underline-offset-4">{t('redeemPage.home')}</Link>} />
      ) : (
        <>
          <p className="text-muted-foreground text-sm">
            {t(check.data.kind === 'PRINTED' ? 'redeemPage.introPrinted' : 'redeemPage.intro')}
          </p>
          {claim.isError && (
            <Alert variant="destructive" data-testid="redeem-failed">
              <AlertDescription>{problemText(claim.error)}</AlertDescription>
            </Alert>
          )}
          <ul className="flex flex-col gap-3">
            {open.map((prize) => (
              <li key={prize.redemptionId} className="flex flex-col gap-2 rounded-lg border p-3" data-testid="redeem-prize"
                data-status={prize.status}>
                <span className="flex items-center gap-2">
                  <Gift aria-hidden className="text-primary size-5" />
                  <span className="font-semibold">{t('redeem.title', { amount: formatKudos(prize.amount) })}</span>
                </span>
                {prize.publicationName && <span className="text-muted-foreground text-sm">{prize.publicationName}</span>}
                <span className="whitespace-pre-line">{prize.description}</span>
                <span className="text-muted-foreground text-xs">
                  {t('redeemPage.code', { code: prize.code })}
                  {prize.expiresAt ? ` · ${t('redeemPage.by', { time: formatDateTime(prize.expiresAt) })}` : ''}
                </span>
                {prize.status === 'CLAIMED' ? (
                  <span className="text-sm" data-testid="redeem-claimed">{t('redeemPage.alreadyClaimed')}</span>
                ) : (
                  <Button className="w-fit" disabled={claim.isPending} aria-busy={claim.isPending || undefined}
                    data-testid="redeem-collect" onClick={() => claim.mutate(prize)}>
                    {t('redeemPage.collect')}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
