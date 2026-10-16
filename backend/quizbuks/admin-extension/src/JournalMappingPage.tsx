import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Badge,
  EXTENSION_NAMESPACE,
  PageHeader,
  PageState,
  paths,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  useAuth,
} from '@jabiz/admin'
import { readJournalMapping } from './journal-api'
import { LoadFailed, Section } from './components'
import { CONTROL, PATH, PERMISSION } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/**
 * The account mapping of the journal export (F-01): each account of the ledger with the accounting system's code now
 * in effect. It changes only through a controlled change (four eyes, platform decision D40): proposed by one
 * administrator, published by another, on the platform's own pages; this page only reads it.
 */
export default function JournalMappingPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const rows = useQuery({ queryKey: ['qb-ext', 'finance', 'journal-mapping'], queryFn: readJournalMapping })
  let body: ReactNode
  if (rows.isError) body = <LoadFailed error={rows.error} onRetry={() => void rows.refetch()} />
  else if (rows.isPending) body = <PageState kind="loading" />
  else {
    body = (
      <Table aria-label={t('finance.mapping.table')} data-testid="journal-mapping">
        <TableHeader>
          <TableRow>
            <TableHead>{t('finance.mapping.account')}</TableHead>
            <TableHead>{t('finance.mapping.externalCode')}</TableHead>
            <TableHead>{t('finance.mapping.param')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.data.map((row) => (
            <TableRow key={row.accountCode} data-testid="mapping-row" data-account={row.accountCode}
              data-code={row.externalCode ?? ''}>
              <TableCell>{`${row.accountCode} ${row.accountName}`}</TableCell>
              <TableCell>
                {row.externalCode
                  ? <code>{row.externalCode}</code>
                  : <Badge variant="warning">{t('finance.mapping.notSet')}</Badge>}
                {!row.paramExists && (
                  <Badge variant="outline" className="ml-2">{t('finance.mapping.notPublished')}</Badge>
                )}
              </TableCell>
              <TableCell><code className="text-xs">{row.paramKey}</code></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    )
  }
  const proposer = can(PERMISSION.controlPropose)
  const publisher = can(PERMISSION.controlPublish)
  return (
    <div className="flex flex-col gap-4" data-testid="finance-journal-mapping">
      <PageHeader title={t('finance.mapping.title')} description={t('finance.mapping.description')} className="pb-0"
        actions={<Link to={PATH.journal} className={linkClass}>{t('finance.journal.title')}</Link>} />
      <Section title={t('finance.mapping.accounts')} testId="journal-mapping-section">
        {body}
      </Section>
      <Section title={t('finance.mapping.change')} testId="journal-mapping-change">
        <p className="text-sm">{t('finance.mapping.changeHint')}</p>
        <pre className="bg-muted overflow-x-auto rounded-md p-3 text-xs" data-testid="mapping-example">
          {'{ "targetEntity": "SysParam", "values": { "paramKey": "qb.journal.account.2110", "value": "135" }, '
            + '"reason": "…" }'}
        </pre>
        <ul className="flex flex-col gap-1 text-sm">
          {proposer && (
            <li>
              <Link to={paths.process(CONTROL.propose, CONTROL.version)} className={linkClass}
                data-testid="mapping-propose">
                {t('finance.mapping.propose')}
              </Link>
            </li>
          )}
          {publisher && (
            <li>
              <Link to={paths.process(CONTROL.publish, CONTROL.version)} className={linkClass}
                data-testid="mapping-publish">
                {t('finance.mapping.publish')}
              </Link>
            </li>
          )}
          {(proposer || publisher) && (
            <li>
              <Link to={paths.dataset(CONTROL.changes)} className={linkClass} data-testid="mapping-changes">
                {t('finance.mapping.changes')}
              </Link>
            </li>
          )}
          {!proposer && !publisher && (
            <li className="text-muted-foreground" data-testid="mapping-no-control">{t('finance.mapping.noControl')}</li>
          )}
        </ul>
      </Section>
    </div>
  )
}
