import { formatDateTime, i18n } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos, kudosName } from '../qb/kudos'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { FEE_ACCOUNT_PATH } from '../shell/paths'
import {
  distributionQuery,
  fundsCheckQuery,
  prepaymentsQuery,
  readDistributionSettlements,
  readFundsCheck,
  readPublicationDistribution,
  readPublicationPrepayments,
} from './api'

/** F-M6: a price per start (up to four decimals) as people read it, in the points' name: `0.4 Kudos`. */
export function formatUnit(unit: number): string {
  return `${new Intl.NumberFormat(i18n.language ?? 'en', { maximumFractionDigits: 4 }).format(unit)} ${kudosName()}`
}

/**
 * F-M5: what submitting sets aside from the fee account for this draft, as the server works it out
 * (`QB_PUBLICATION_FUNDS_CHECK`, which writes nothing): each item, the total, what the account holds, and, when it is
 * not enough, the shortfall with the way to top up. The page shows the answer only; the server refuses a submission
 * that the account cannot cover.
 */
export function FundsPanel({ publicationId, revision }: { publicationId: string; revision: number | null }) {
  const { t } = useTranslation(SPONSOR)
  const funds = useQuery({
    queryKey: fundsCheckQuery(publicationId, revision),
    queryFn: () => readFundsCheck(publicationId),
    retry: false,
  })
  if (funds.isPending) return null
  if (funds.isError) {
    return <p className="text-muted-foreground text-sm" data-testid="publication-funds-error">{problemText(funds.error)}</p>
  }
  const check = funds.data
  return (
    <div className="flex flex-col gap-2 rounded-md border p-3" data-testid="publication-funds">
      <h3 className="text-sm font-medium">{t('publishing.funds.title')}</h3>
      {check.total <= 0
        ? <p className="text-sm" data-testid="publication-funds-none">{t('publishing.funds.none')}</p>
        : (
          <>
            <ul className="flex flex-col gap-1 text-sm">
              {check.items.map((item) => (
                <li key={item.purpose} data-testid="publication-funds-item" data-purpose={item.purpose}>
                  {t('publishing.funds.item', { purpose: t(`publishing.funds.purpose.${item.purpose}`),
                    amount: formatKudos(item.amount) })}
                  {item.unit != null && (
                    <span className="text-muted-foreground" data-testid="publication-funds-unit">
                      {' '}{t(item.atFloor ? 'publishing.funds.unitAtFloor' : 'publishing.funds.unit',
                        { unit: formatUnit(item.unit) })}
                    </span>
                  )}
                </li>
              ))}
            </ul>
            <p className="text-sm font-medium" data-testid="publication-funds-total">
              {t('publishing.funds.total', { amount: formatKudos(check.total) })}
            </p>
            <p className="text-sm" data-testid="publication-funds-available">
              {check.billingMode === 'POSTPAID'
                ? t('publishing.funds.availablePostpaid', { amount: formatKudos(check.available) })
                : t('publishing.funds.available', { amount: formatKudos(check.available) })}
            </p>
            {!check.enough && (
              <div className="flex flex-col gap-1" role="status" data-testid="publication-funds-short">
                <p className="text-destructive text-sm">
                  {t('publishing.funds.shortfall', { amount: formatKudos(check.shortfall) })}
                </p>
                {check.billingMode === 'PREPAID' && (
                  <Link to={FEE_ACCOUNT_PATH} className="w-fit text-sm underline underline-offset-4"
                    data-testid="publication-funds-topup">
                    {t('publishing.funds.topUp')}
                  </Link>
                )}
              </div>
            )}
          </>
        )}
      <p className="text-muted-foreground text-sm">{t('publishing.funds.help')}</p>
    </div>
  )
}

/**
 * F-M5: what this publication's submission set aside and what became of it (`qb.sponsor.publication-prepayments`):
 * per purpose the sum, what is left, and whether it is held, was given back (and why) or was charged. Nothing is shown
 * while there are no rows.
 */
export function PrepaymentsSection({ publicationId }: { publicationId: string }) {
  const { t } = useTranslation(SPONSOR)
  const rows = useQuery({ queryKey: prepaymentsQuery(publicationId),
    queryFn: () => readPublicationPrepayments(publicationId) })
  if (!rows.data || rows.data.length === 0) return null
  return (
    <section aria-labelledby="publication-prepayments-title" className="flex flex-col gap-2"
      data-testid="publication-prepayments">
      <h2 id="publication-prepayments-title" className="text-sm font-medium">{t('publishing.prepayments.title')}</h2>
      <ul className="flex flex-col gap-2 text-sm">
        {rows.data.map((row) => {
          const charged = row.amount - row.remaining
          return (
            <li key={row.prepaymentId} className="flex flex-col gap-0.5 rounded-md border p-3"
              data-testid="publication-prepayment" data-purpose={row.purpose} data-status={row.status}>
              <span className="font-medium">{t(`publishing.funds.purpose.${row.purpose}`)}</span>
              <span>{t('publishing.prepayments.amounts', { amount: formatKudos(row.amount),
                remaining: formatKudos(row.remaining) })}</span>
              {charged > 0 && (
                <span data-testid="publication-prepayment-charged">
                  {t('publishing.prepayments.charged', { amount: formatKudos(charged) })}
                  {row.chargedAt ? ` (${formatDateTime(row.chargedAt)})` : ''}
                </span>
              )}
              <span className="text-muted-foreground" data-testid="publication-prepayment-state">
                {row.status === 'OPEN'
                  ? t(row.mode === 'POSTPAID' ? 'publishing.prepayments.heldPostpaid' : 'publishing.prepayments.held')
                  : t('publishing.prepayments.released', {
                    reason: row.releaseReason ? t(`publishing.prepayments.reason.${row.releaseReason}`) : '—',
                    time: row.releasedAt ? formatDateTime(row.releasedAt) : '—' })}
              </span>
            </li>
          )
        })}
      </ul>
      <p className="text-muted-foreground text-sm">{t('publishing.funds.help')}</p>
    </section>
  )
}

/**
 * F-M6: this publication's distribution fee (`qb.sponsor.publication-distribution` and its settlements): the price
 * per start frozen at its submission, the starts since the last settlement and what they come to now, and each month's
 * settlement. Nothing is shown for a publication without a price per start.
 */
export function DistributionSection({ publicationId }: { publicationId: string }) {
  const { t } = useTranslation(SPONSOR)
  const summary = useQuery({ queryKey: distributionQuery(publicationId),
    queryFn: () => readPublicationDistribution(publicationId) })
  const settlements = useQuery({ queryKey: [...distributionQuery(publicationId), 'settlements'],
    queryFn: () => readDistributionSettlements(publicationId), enabled: summary.data?.unit != null })
  const data = summary.data
  if (!data || data.unit == null) return null
  return (
    <section aria-labelledby="publication-distribution-title" className="flex flex-col gap-2"
      data-testid="publication-distribution">
      <h2 id="publication-distribution-title" className="text-sm font-medium">{t('publishing.distribution.title')}</h2>
      <p className="text-sm" data-testid="publication-distribution-unit">
        {t(data.atFloor ? 'publishing.funds.unitAtFloor' : 'publishing.funds.unit', { unit: formatUnit(data.unit) })}
      </p>
      {!data.finalSettled && (
        <p className="text-sm" data-testid="publication-distribution-pending">
          {t('publishing.distribution.pending', { starts: data.pendingStarts,
            amount: formatKudos(data.pendingEstimate) })}
        </p>
      )}
      {settlements.data && settlements.data.length > 0 && (
        <ul className="flex flex-col gap-1 text-sm">
          {settlements.data.map((row) => (
            <li key={row.settlementId} data-testid="publication-distribution-settlement" data-period={row.period}>
              {t(row.final ? 'publishing.distribution.finalRow' : 'publishing.distribution.row', {
                period: row.period, starts: row.starts, amount: formatKudos(row.amount), tax: formatKudos(row.tax) })}
            </li>
          ))}
        </ul>
      )}
      <p className="text-muted-foreground text-sm">{t('publishing.distribution.help')}</p>
    </section>
  )
}
