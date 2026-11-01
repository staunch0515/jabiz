import { formatDateTime, runQuery } from '@jabiz/client'
import { Badge, PageState, Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { problemText } from '../qb/problems'
import { PUBLISHING_TEMPLATE } from './ids'

/** A row of `qb.sponsor.referrals` (Q70): one referral of the sponsor's, never naming the people. */
export interface SponsorReferral {
  referralId: string
  status: 'REFERRED' | 'REWARDED' | 'EXPIRED' | 'VOID'
  referredAt: string
  expiresAt: string
  amount: unknown
  rewardedAt: string | null
  fundsShort: boolean
}

export async function readReferrals(publicationId: string): Promise<SponsorReferral[]> {
  const page = await runQuery<Record<string, unknown>>(PUBLISHING_TEMPLATE.referrals,
    { params: { publicationId }, limit: 200 })
  return page.items.map((row) => ({
    referralId: String(row.referralId),
    status: String(row.status) as SponsorReferral['status'],
    referredAt: String(row.referredAt ?? ''),
    expiresAt: String(row.expiresAt ?? ''),
    amount: row.amount,
    rewardedAt: row.rewardedAt == null ? null : String(row.rewardedAt),
    fundsShort: row.fundsShort === true || row.fundsShort === 'true',
  }))
}

/**
 * A publication's referrals (Q70): how many came, were rewarded or ended, and one by one when and what; a referral
 * reward waiting for the wallet is marked (top up to pay it before it expires).
 */
export function ReferralsSection({ publicationId }: { publicationId: string }) {
  const { t } = useTranslation(SPONSOR)
  const titleId = useId()
  const referrals = useQuery({ queryKey: ['qb', 'sponsor-referrals', publicationId],
    queryFn: () => readReferrals(publicationId) })
  const rows = referrals.data ?? []
  const count = (status: SponsorReferral['status']) => rows.filter((r) => r.status === status).length
  return (
    <section className="flex flex-col gap-2" aria-labelledby={titleId} data-testid="publication-referrals">
      <h2 id={titleId} className="text-sm font-medium">{t('publishing.referrals.title')}</h2>
      {referrals.isPending ? <PageState kind="loading" />
        : referrals.isError ? <PageState kind="error" description={problemText(referrals.error)} />
          : rows.length === 0 ? <p className="text-muted-foreground text-sm">{t('publishing.referrals.none')}</p>
            : (
              <>
                <p className="text-sm" data-testid="publication-referrals-counts">
                  {t('publishing.referrals.counts', { referred: count('REFERRED'), rewarded: count('REWARDED'),
                    ended: count('EXPIRED') + count('VOID') })}
                </p>
                {rows.some((r) => r.fundsShort) && (
                  <p className="text-sm text-destructive" data-testid="publication-referrals-short">
                    {t('publishing.referrals.fundsShort')}
                  </p>
                )}
                <Table aria-labelledby={titleId}>
                  <TableHeader>
                    <TableRow>
                      <TableHead scope="col">{t('publishing.referrals.referredAt')}</TableHead>
                      <TableHead scope="col">{t('publishing.referrals.status')}</TableHead>
                      <TableHead scope="col" className="text-right">{t('publishing.referrals.amount')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((r) => (
                      <TableRow key={r.referralId} data-testid="publication-referral-row" data-status={r.status}>
                        <TableCell>{formatDateTime(r.referredAt)}</TableCell>
                        <TableCell><Badge variant="outline">{t(`publishing.referrals.state.${r.status}`)}</Badge></TableCell>
                        <TableCell className="text-right">{formatKudos(r.amount)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </>
            )}
    </section>
  )
}
