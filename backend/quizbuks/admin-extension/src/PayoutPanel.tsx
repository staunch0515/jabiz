import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Send } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Alert,
  AlertDescription,
  ApiError,
  Button,
  ConfirmDialog,
  DescriptionList,
  EXTENSION_NAMESPACE,
  PageState,
  useAuth,
} from '@jabiz/admin'
import { LoadFailed } from './components'
import { money } from './hooks'
import { PERMISSION } from './ids'
import { previewTransfer, readTakerKudos, transferNow, type TransferNowOutput } from './payout-api'

/**
 * A taker's Kudos and their payout now (Q14), on the user's page for finance: what they earned and were paid, whether
 * the daily transfer would send their balance now (and if not, why: `QB_TRANSFER_PREVIEW`, which writes nothing), and
 * "Transfer now" (`QB_TRANSFER_NOW`, the daily run's checks for this taker at once; never to oneself, which the server
 * refuses; above the review limit the daily run asks for the review instead, so that whoever started a transfer is
 * not the one who approves it). After a transfer the page's figures are read again.
 */
export function PayoutPanel({ userId }: { userId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const [outcome, setOutcome] = useState<TransferNowOutput | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const kudos = useQuery({ queryKey: ['qb-ext', 'kudos', userId], queryFn: () => readTakerKudos([userId]) })
  const preview = useQuery({ queryKey: ['qb-ext', 'transfer-preview', userId], queryFn: () => previewTransfer(userId),
    refetchOnWindowFocus: false })
  const reason = (code: string | null) => (code ? t(`payout.reasons.${code}`, { defaultValue: code }) : '')

  const totals = kudos.data?.get(userId)
  const run = async () => {
    setProblem(null)
    try {
      const output = await transferNow(userId)
      setOutcome(output)
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      void queryClient.invalidateQueries({ queryKey: ['qb-ext', 'kudos', userId] })
      void queryClient.invalidateQueries({ queryKey: ['qb-ext', 'transfer-preview', userId] })
      void queryClient.invalidateQueries({ queryKey: ['qb-ext', 'user'] })
    }
  }

  let status
  if (preview.isError) status = <LoadFailed error={preview.error} onRetry={() => void preview.refetch()} />
  else if (preview.isPending) status = <PageState kind="loading" />
  else {
    const p = preview.data
    status = (
      <div className="flex flex-col gap-2" data-testid="payout-status" data-transfers={p.transfers}
        data-skip={p.skip ?? ''}>
        <p>
          {p.transfers
            ? (p.review
              ? t('payout.readyReview', { amount: money(p.amount ?? '0') })
              : t('payout.ready', { amount: money(p.amount ?? '0'), fee: money(p.fee ?? '0'),
                net: money(p.net ?? '0') }))
            : t('payout.notReady')}
        </p>
        {!p.transfers && p.skip && <p className="text-muted-foreground text-sm" data-testid="payout-reason">
          {reason(p.skip)}</p>}
        {Number(p.held) > 0 && <p className="text-muted-foreground text-sm">{t('payout.held', { held: money(p.held) })}</p>}
        {can(PERMISSION.transferNow) && p.transfers && !p.review && (
          <ConfirmDialog title={t('payout.nowTitle', { amount: money(p.amount ?? '0') })}
            description={t('payout.nowText')} confirmLabel={t('payout.nowConfirm')} cancelLabel={t('payout.cancel')}
            onConfirm={run}
            trigger={<Button className="w-fit" data-testid="transfer-now"><Send aria-hidden />{t('payout.now')}</Button>} />
        )}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-testid="user-kudos">
      <div className="flex flex-col gap-2">
        <h3 className="font-semibold">{t('payout.kudos')}</h3>
        {kudos.isError
          ? <LoadFailed error={kudos.error} onRetry={() => void kudos.refetch()} />
          : kudos.isPending ? <PageState kind="loading" /> : (
            <DescriptionList columns={4} items={[
              { key: 'attempts', label: t('payout.attempts'), value: String(totals?.attempts ?? 0) },
              { key: 'completed', label: t('payout.completed'), value: String(totals?.completed ?? 0) },
              { key: 'earned', label: t('payout.earned'),
                value: <span data-testid="kudos-earned">{money(totals?.earned ?? '0')}</span> },
              { key: 'pending', label: t('payout.pendingRewards'), value: money(totals?.pendingRewards ?? '0') },
              { key: 'balance', label: t('payout.balance'),
                value: <span data-testid="kudos-balance">{money(totals?.balance ?? '0')}</span> },
              { key: 'inTransfer', label: t('payout.inTransfer'), value: money(totals?.inTransfer ?? '0') },
              { key: 'transferred', label: t('payout.transferred'), value: money(totals?.transferred ?? '0') },
            ]} />
          )}
      </div>
      <div className="flex flex-col gap-2">
        <h3 className="font-semibold">{t('payout.status')}</h3>
        {status}
        {outcome && (
          <Alert variant={outcome.transferId ? 'success' : 'warning'} role="status" data-testid="transfer-now-outcome">
            <AlertDescription>
              {outcome.transferId
                ? (outcome.status === 'REVIEW' ? t('payout.inReview') : t('payout.sent'))
                : t('payout.notSent', { reason: reason(outcome.reason) })}
            </AlertDescription>
          </Alert>
        )}
        {problem && (
          <Alert variant="destructive" data-testid="transfer-now-problem"><AlertDescription>{problem}</AlertDescription></Alert>
        )}
      </div>
    </div>
  )
}
