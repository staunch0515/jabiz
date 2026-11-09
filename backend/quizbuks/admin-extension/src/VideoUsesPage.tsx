import { useQuery } from '@tanstack/react-query'
import { Fragment, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Button,
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
import { actOf, readVideoUseHistory, readVideoUses, titleIn, type AdminVideoUse } from './video-use-api'

/**
 * The ledger of shared videos' uses (F-S14; docs/quizbuks/operations.md §7.32), read only, one page for headquarters
 * and the operators: each use with its two sides, its state and pricing, the figures (each below the minimum group as
 * "<k") and the reference amount, and on request its history (every version: who, which act, when, the reason sent
 * back). An operator's session reads the uses of its own Sponsors as users and as providers (two read paths, the
 * server's scope); headquarters every one. Nothing is written here: the two Sponsors settle between themselves.
 */
export default function VideoUsesPage() {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const rows = useQuery({ queryKey: [...QUEUE.videoUses, 'list'], queryFn: () => readVideoUses(200) })
  const n = (value: number | null, k: number) => value == null ? t('videoUses.fewer', { n: k })
    : value.toLocaleString(i18n.language)
  const company = (name: string | null, id: string) => name ?? <UserName id={id} />
  const [opened, setOpened] = useState<string | null>(null)
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
                    <TableHead><span className="sr-only">{t('videoUses.history')}</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.data.map((use: AdminVideoUse) => (
                    <Fragment key={use.videoUseId}>
                    <TableRow data-testid="video-use-row" data-id={use.videoUseId}>
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
                      <TableCell>
                        <Button variant="ghost" size="sm" aria-expanded={opened === use.videoUseId}
                          aria-controls={`history-${use.videoUseId}`} data-testid="video-use-history-toggle"
                          aria-label={t('videoUses.historyOf', { name: use.name ?? use.publicationId })}
                          onClick={() => setOpened(opened === use.videoUseId ? null : use.videoUseId)}>
                          {t('videoUses.history')}</Button>
                      </TableCell>
                    </TableRow>
                    {opened === use.videoUseId && (
                      <TableRow>
                        <TableCell colSpan={10} id={`history-${use.videoUseId}`}>
                          <UseHistory use={use} />
                        </TableCell>
                      </TableRow>
                    )}
                    </Fragment>
                  ))}
                </TableBody>
              </Table>
            )}
      </Section>
    </div>
  )
}

/** Every version of a use, oldest first: the act, who (the platform user), when, the entry then and a reason. */
function UseHistory({ use }: { use: AdminVideoUse }) {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const path = use.paths.includes('user') ? 'user' : 'provider'
  const history = useQuery({ queryKey: [...QUEUE.videoUses, 'history', use.videoUseId, use.revision, path],
    queryFn: () => readVideoUseHistory(use.videoUseId, path) })
  if (history.isError) return <LoadFailed error={history.error} onRetry={() => void history.refetch()} />
  if (!history.data) return null
  return (
    <ol className="flex flex-col gap-2 border-l pl-4 text-sm" aria-label={t('videoUses.history')}
      data-testid="video-use-history">
      {history.data.map((entry) => {
        const act = actOf(entry.processName, entry.versionNo)
        return (
          <li key={entry.versionNo} className="flex flex-col gap-0.5" data-testid="video-use-history-entry" data-act={act}>
            <span>
              <span className="font-medium">{t(`videoUses.acts.${act}`)}</span>
              {' · '}<UserName id={entry.actorId} />
              {' · '}<span className="text-muted-foreground">{new Date(entry.recordedAt).toLocaleString(i18n.language)}</span>
            </span>
            <span className="text-muted-foreground">
              {t('videoUses.revision')} {entry.revision}
              {' · '}{t(`videoUses.pricings.${entry.pricing}`, { price: entry.price.toLocaleString(i18n.language) })}
              {' · '}{entry.periodFrom} – {entry.periodTo}
            </span>
            {act === 'REJECTED' && entry.rejectReason && (
              <span className="whitespace-pre-wrap" data-testid="video-use-history-reason">
                {t('videoUses.reason', { reason: entry.rejectReason })}</span>
            )}
          </li>
        )
      })}
    </ol>
  )
}
