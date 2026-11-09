import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import {
  EXTENSION_NAMESPACE,
  PageHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  UserName,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, Section, StatusBadge } from './components'
import { QUEUE } from './hooks'
import { readVideoUses, titleIn, type AdminVideoUse } from './video-use-api'

/**
 * The ledger of shared videos' uses (F-S14; docs/quizbuks/operations.md §7.32), read only, one page for headquarters
 * and the operators: each use with its two sides, its state and pricing, the figures (each below the minimum group as
 * "<k") and the reference amount. An operator's session reads its own Sponsors' (the users') uses only (the server's
 * scope). Nothing is written here: the two Sponsors settle between themselves.
 */
export default function VideoUsesPage() {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const rows = useQuery({ queryKey: [...QUEUE.videoUses, 'list'], queryFn: () => readVideoUses(200) })
  const n = (value: number | null, k: number) => value == null ? t('videoUses.fewer', { n: k })
    : value.toLocaleString(i18n.language)
  const company = (name: string | null, id: string) => name ?? <UserName id={id} />
  return (
    <div className="flex flex-col gap-6" data-testid="qb-video-uses">
      <PageHeader title={t('videoUses.title')} description={t('videoUses.description')} className="pb-0" />
      <Section title={t('videoUses.title')} testId="video-uses-list">
        {rows.isError
          ? <LoadFailed error={rows.error} onRetry={() => void rows.refetch()} />
          : rows.data && rows.data.length === 0
            ? <EmptyState art="search" title={t('videoUses.none')} testId="video-uses-none" />
            : rows.data && (
              <Table aria-label={t('videoUses.title')}>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('videoUses.publication')}</TableHead>
                    <TableHead>{t('videoUses.user')}</TableHead>
                    <TableHead>{t('videoUses.provider')}</TableHead>
                    <TableHead>{t('videoUses.status')}</TableHead>
                    <TableHead>{t('videoUses.pricing')}</TableHead>
                    <TableHead>{t('videoUses.period')}</TableHead>
                    <TableHead className="text-right">{t('videoUses.figures')}</TableHead>
                    <TableHead className="text-right">{t('videoUses.amount')}</TableHead>
                    <TableHead className="text-right">{t('videoUses.revision')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.data.map((use: AdminVideoUse) => (
                    <TableRow key={use.videoUseId} data-testid="video-use-row" data-id={use.videoUseId}>
                      <TableCell>
                        <span className="block font-medium">{use.name ?? use.publicationId}</span>
                        <span className="text-muted-foreground text-xs">{titleIn(use.videoTitles, i18n.language)}</span>
                      </TableCell>
                      <TableCell data-testid="video-use-user">{company(use.sponsorCompany, use.sponsorId)}</TableCell>
                      <TableCell data-testid="video-use-provider">{company(use.providerCompany, use.providerId)}</TableCell>
                      <TableCell>
                        <StatusBadge code={use.status} label={t(`videoUses.statuses.${use.status}`)}
                          testId="video-use-status" />
                        {use.status === 'REJECTED' && use.rejectReason && (
                          <span className="text-muted-foreground block text-xs whitespace-pre-wrap">
                            {t('videoUses.reason', { reason: use.rejectReason })}</span>
                        )}
                      </TableCell>
                      <TableCell data-testid="video-use-pricing">
                        {t(`videoUses.pricings.${use.pricing}`, { price: use.price.toLocaleString(i18n.language) })}</TableCell>
                      <TableCell className="whitespace-nowrap">{use.periodFrom} – {use.periodTo}</TableCell>
                      <TableCell className="text-right tabular-nums" data-testid="video-use-figures">
                        {[use.opens, use.successes, use.goes, use.qualified].map((v) => n(v, use.minGroup)).join(' / ')}
                        {use.finalisedAt && <span className="text-muted-foreground block text-xs">{t('videoUses.final')}</span>}
                      </TableCell>
                      <TableCell className="text-right tabular-nums" data-testid="video-use-amount">
                        {use.amount == null ? '—' : t('videoUses.yen', { amount: use.amount.toLocaleString(i18n.language) })}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {use.revision}
                        {use.confirmedRevision != null && (
                          <span className="text-muted-foreground block text-xs">
                            {t('videoUses.confirmedRevision', { revision: use.confirmedRevision })}</span>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
      </Section>
    </div>
  )
}
