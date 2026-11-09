import { useQuery } from '@tanstack/react-query'
import { Ban, Film, Megaphone, Users } from 'lucide-react'
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
} from '@jabiz/admin'
import { readBlockedSponsors, readBlockKinds, type BlockKind } from './block-api'
import { EmptyState, LoadFailed, Metric, PublisherLink, Section } from './components'
import { QUEUE } from './hooks'

const ICONS: Record<BlockKind, typeof Ban> = { PUBLICATION: Ban, QUIZ: Ban, SPONSOR: Megaphone, PROVIDER: Film }

/**
 * The figures of blocking (F-S8; docs/quizbuks/operations.md §7.30), for headquarters: by kind, the blocks and the
 * takers who made them, the takers blocking anything, and the Sponsors most blocked (rounded down to tens, from the
 * minimum group up, as each Sponsor sees its own). Aggregates only: blocks are private.
 */
export default function BlocksPage() {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const kinds = useQuery({ queryKey: [...QUEUE.blocks, 'kinds'], queryFn: readBlockKinds })
  const sponsors = useQuery({ queryKey: [...QUEUE.blocks, 'sponsors'], queryFn: () => readBlockedSponsors(20) })
  const n = (value: number) => value.toLocaleString(i18n.language)
  const minGroup = sponsors.data?.[0]?.minGroup ?? 20
  return (
    <div className="flex flex-col gap-6" data-testid="qb-blocks">
      <PageHeader title={t('blocks.title')} description={t('blocks.description')} className="pb-0" />
      {kinds.isError
        ? <LoadFailed error={kinds.error} onRetry={() => void kinds.refetch()} />
        : kinds.data && (
          <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5" data-testid="blocks-kinds">
            {kinds.data.map((row) => (
              <Metric key={row.kind} icon={ICONS[row.kind]} label={t(`blocks.kinds.${row.kind}`)}
                testId={`blocks-kind-${row.kind}`}
                value={<span className="flex flex-col">
                  <span data-testid="blocks-kind-blocks">{t('blocks.blocks', { n: n(row.blocks) })}</span>
                  <span className="text-muted-foreground text-sm font-normal" data-testid="blocks-kind-blockers">
                    {t('blocks.blockers', { n: n(row.blockers) })}
                  </span>
                </span>} />
            ))}
            <Metric icon={Users} label={t('blocks.all')} testId="blocks-all"
              value={n(kinds.data[0]?.allBlockers ?? 0)} />
          </dl>
        )}
      <Section title={t('blocks.sponsors')} testId="blocks-sponsors">
        <p className="text-muted-foreground text-sm">{t('blocks.sponsorsHint', { min: minGroup })}</p>
        {sponsors.isError
          ? <LoadFailed error={sponsors.error} onRetry={() => void sponsors.refetch()} />
          : sponsors.data && sponsors.data.length === 0
            ? <EmptyState art="search" title={t('blocks.none', { min: minGroup })} testId="blocks-sponsors-none" />
            : sponsors.data && (
              <Table aria-label={t('blocks.sponsors')}>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('blocks.sponsor')}</TableHead>
                    <TableHead className="text-right">{t('blocks.sponsorBlockers')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {sponsors.data.map((row) => (
                    <TableRow key={row.sponsorId} data-testid="blocks-sponsor" data-id={row.sponsorId}>
                      <TableCell>
                        <PublisherLink kind="SPONSOR" id={row.sponsorId} label={row.company ?? row.sponsorId} />
                      </TableCell>
                      <TableCell className="text-right tabular-nums" data-testid="blocks-sponsor-count">
                        {n(row.blockers)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
      </Section>
    </div>
  )
}
