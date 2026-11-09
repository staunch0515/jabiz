import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  EXTENSION_NAMESPACE,
  PageHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  useAuth,
  UserName,
} from '@jabiz/admin'
import { readSuspiciousExposures } from './exposure-api'
import { EmptyState, LoadFailed, Section } from './components'
import { QUEUE } from './hooks'
import { PATH, PERMISSION } from './ids'

/**
 * The suspicious inflation of exposures (F-S13; docs/quizbuks/operations.md §7.31), one page for headquarters and the
 * operators: the Takers the daily cap refused, most refusals first, with the publications refused, the last day and
 * the exposures they counted. An operator's session reads its own Sponsors' publications only (the server's scope).
 */
export default function ExposurePage() {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const users = can(PERMISSION.usersRead)
  const rows = useQuery({ queryKey: [...QUEUE.exposure, 'suspicious'], queryFn: () => readSuspiciousExposures(100) })
  const n = (value: number) => value.toLocaleString(i18n.language)
  return (
    <div className="flex flex-col gap-6" data-testid="qb-exposure">
      <PageHeader title={t('exposure.title')} description={t('exposure.description')} className="pb-0" />
      <Section title={t('exposure.title')} testId="exposure-suspicious">
        {rows.isError
          ? <LoadFailed error={rows.error} onRetry={() => void rows.refetch()} />
          : rows.data && rows.data.length === 0
            ? <EmptyState art="search" title={t('exposure.none')} testId="exposure-none" />
            : rows.data && (
              <Table aria-label={t('exposure.title')}>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('exposure.taker')}</TableHead>
                    <TableHead className="text-right">{t('exposure.hits')}</TableHead>
                    <TableHead className="text-right">{t('exposure.publications')}</TableHead>
                    <TableHead>{t('exposure.lastCappedOn')}</TableHead>
                    <TableHead className="text-right">{t('exposure.successes')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.data.map((row) => (
                    <TableRow key={row.userId} data-testid="exposure-row" data-id={row.userId}>
                      <TableCell>
                        {users
                          ? <Link to={PATH.user(row.userId)} className="underline-offset-4 hover:underline">
                            <UserName id={row.userId} /></Link>
                          : <span className="font-mono text-xs">{row.userId}</span>}
                      </TableCell>
                      <TableCell className="text-right tabular-nums" data-testid="exposure-hits">{n(row.hits)}</TableCell>
                      <TableCell className="text-right tabular-nums">{n(row.publications)}</TableCell>
                      <TableCell>{row.lastCappedOn ?? '—'}</TableCell>
                      <TableCell className="text-right tabular-nums">{n(row.successes)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
      </Section>
    </div>
  )
}
