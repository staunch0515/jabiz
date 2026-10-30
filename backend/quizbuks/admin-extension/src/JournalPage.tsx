import { useQueryClient } from '@tanstack/react-query'
import { Download, Eye, FileOutput } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  notify,
  PageHeader,
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { failed, tell } from './finance'
import {
  confirmJournal,
  downloadGeneratedFile,
  exportJournal,
  previewJournal,
  readJournalExports,
  type JournalExport,
  type JournalPreview,
} from './journal-api'
import { LoadFailed, Section } from './components'
import { money, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { PATH, PERMISSION } from './ids'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { period: 'periodFrom' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = 'text-right tabular-nums'
const EXPORTS_KEY = ['qb-ext', 'finance', 'journal-exports']

/** What stops the export, as the preview says it; empty when it would run. */
function Blockers({ preview }: { preview: JournalPreview }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const problems: { key: string; text: string }[] = []
  if (!preview.periodEnded) {
    problems.push({ key: 'open', text: t('finance.journal.periodOpen', { end: when(preview.periodEnd) }) })
  }
  if (!preview.reconOk) {
    problems.push({ key: 'recon', text: preview.reconStatus
      ? t('finance.journal.reconNotOk', { status: preview.reconStatus, time: when(preview.reconTime) })
      : t('finance.journal.reconNone') })
  }
  if (preview.unmapped.length > 0) {
    problems.push({ key: 'unmapped', text: t('finance.journal.unmapped', { accounts: preview.unmapped.join(', ') }) })
  }
  if (preview.invalid.length > 0) {
    problems.push({ key: 'invalid', text: t('finance.journal.invalid', { accounts: preview.invalid.join(', ') }) })
  }
  if (!preview.balanced) problems.push({ key: 'unbalanced', text: t('finance.journal.unbalanced') })
  if (!preview.withinLimits) problems.push({ key: 'tooLarge', text: t('finance.journal.tooLarge') })
  if (preview.openTransactions > 0) {
    problems.push({ key: 'transactionOpen', text: t('finance.journal.transactionOpen') })
  }
  if (problems.length === 0) return null
  return (
    <Alert variant="warning" data-testid="journal-blockers">
      <AlertDescription>
        <ul className="list-disc pl-4">
          {problems.map((problem) => <li key={problem.key} data-testid={`journal-blocker-${problem.key}`}>{problem.text}</li>)}
        </ul>
        {(preview.unmapped.length > 0 || preview.invalid.length > 0) && (
          <Link to={PATH.journalMapping} className={linkClass} data-testid="journal-mapping-link">
            {t('finance.journal.mappingLink')}
          </Link>
        )}
      </AlertDescription>
    </Alert>
  )
}

/** The voucher the next export would make: the server's lines, never summed here. */
function PreviewLines({ preview }: { preview: JournalPreview }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  if (preview.lines.length === 0) {
    return <p className="text-muted-foreground text-sm" data-testid="journal-no-lines">{t('finance.journal.noLines')}</p>
  }
  return (
    <Table aria-label={t('finance.journal.linesTable')} data-testid="journal-lines">
      <TableHeader>
        <TableRow>
          <TableHead className="text-right">{t('finance.journal.lineNo')}</TableHead>
          <TableHead>{t('finance.journal.account')}</TableHead>
          <TableHead>{t('finance.journal.externalCode')}</TableHead>
          <TableHead>{t('finance.journal.sponsor')}</TableHead>
          <TableHead className="text-right">{t('finance.journal.debit')}</TableHead>
          <TableHead className="text-right">{t('finance.journal.credit')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {preview.lines.map((line) => (
          <TableRow key={line.lineNo} data-testid="journal-line" data-account={line.accountCode} data-side={line.side}
            data-amount={line.amount}>
            <TableCell className={right}>{line.lineNo}</TableCell>
            <TableCell>{line.accountCode}</TableCell>
            <TableCell>
              {line.externalCode ?? <Badge variant="warning">{t('finance.journal.notMapped')}</Badge>}
            </TableCell>
            <TableCell>{line.sponsorId ? <UserName id={line.sponsorId} /> : ''}</TableCell>
            <TableCell className={right}>{line.side === 'DEBIT' ? money(line.amount) : ''}</TableCell>
            <TableCell className={right}>{line.side === 'CREDIT' ? money(line.amount) : ''}</TableCell>
          </TableRow>
        ))}
      </TableBody>
      <TableFooter>
        <TableRow>
          <TableCell colSpan={4}>{t('finance.journal.total', { n: preview.transactionCount })}</TableCell>
          <TableCell className={right} data-testid="journal-debit-total">{money(preview.debitTotal)}</TableCell>
          <TableCell className={right} data-testid="journal-credit-total">{money(preview.creditTotal)}</TableCell>
        </TableRow>
      </TableFooter>
    </Table>
  )
}

/**
 * The next export (`qb.journal.export`): the preview (`QB_JOURNAL_PREVIEW`, which writes nothing) shows the month, its
 * voucher's lines and what stops it; the export is offered only when it would run and asks first (with a second
 * factor, which the platform asks for).
 */
function NextExport() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queryClient = useQueryClient()
  const [preview, setPreview] = useState<JournalPreview | null>(null)
  const [loading, setLoading] = useState(false)
  const load = async () => {
    setLoading(true)
    try {
      setPreview(await previewJournal())
    } catch (error) {
      setPreview(null)
      tell(error, t('common.failed'))
    } finally {
      setLoading(false)
    }
  }
  return (
    <Section title={t('finance.journal.next')} testId="journal-next"
      actions={(
        <Button variant="outline" size="sm" disabled={loading} aria-busy={loading || undefined}
          data-testid="journal-preview" onClick={() => void load()}>
          <Eye aria-hidden />
          {t('finance.journal.preview')}
        </Button>
      )}>
      <p className="text-muted-foreground text-sm">{t('finance.journal.nextHint')}</p>
      {preview && (
        <div className="flex flex-col gap-4" data-testid="journal-preview-result" data-month={preview.month}
          data-ready={String(preview.ready)}>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-muted-foreground">{t('finance.journal.month')}</dt>
            <dd data-testid="journal-month">
              {t('finance.journal.periodValue', { month: preview.month, from: preview.periodFrom, to: preview.periodTo })}
            </dd>
            <dt className="text-muted-foreground">{t('finance.journal.previous')}</dt>
            <dd>{preview.previousVoucher ?? t('finance.journal.none')}</dd>
            <dt className="text-muted-foreground">{t('finance.journal.knownAt')}</dt>
            <dd>{when(preview.knownAt)}</dd>
            <dt className="text-muted-foreground">{t('finance.journal.detail')}</dt>
            <dd data-testid="journal-detail-size" data-rows={preview.detailRows} data-parts={preview.detailParts}>
              {t('finance.journal.detailSize', { rows: preview.detailRows.toLocaleString(),
                parts: preview.detailParts.toLocaleString() })}
            </dd>
          </dl>
          <Blockers preview={preview} />
          {preview.withinLimits && preview.nearLimit && (
            <Alert variant="warning" data-testid="journal-near-limit">
              <AlertDescription>{t('finance.journal.nearLimit')}</AlertDescription>
            </Alert>
          )}
          <PreviewLines preview={preview} />
          {preview.ready && (
            <div>
              <ConfirmDialog
                title={t('finance.journal.exportTitle', { month: preview.month })}
                description={t('finance.journal.exportDescription')}
                confirmLabel={t('finance.journal.export')}
                onConfirm={async () => {
                  try {
                    const exported = await exportJournal(preview.month)
                    notify.success(t('finance.journal.exported', { voucher: exported.voucherNo }))
                    setPreview(null)
                  } catch (error) {
                    failed(error, t('common.failed'))
                  }
                  await queryClient.invalidateQueries({ queryKey: EXPORTS_KEY })
                }}
                trigger={(
                  <Button data-testid="journal-export">
                    <FileOutput aria-hidden />
                    {t('finance.journal.export')}
                  </Button>
                )}
              />
            </div>
          )}
        </div>
      )}
    </Section>
  )
}

/** Records the accounting system's voucher number of an export, once (`qb.journal.confirm`). */
function ConfirmForm({ row }: { row: JournalExport }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queryClient = useQueryClient()
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!value.trim()) return
    setBusy(true)
    try {
      await confirmJournal(row.exportId, value.trim())
      notify.success(t('finance.journal.confirmed'))
      await queryClient.invalidateQueries({ queryKey: EXPORTS_KEY })
    } catch (error) {
      tell(error, t('common.failed'))
    } finally {
      setBusy(false)
    }
  }
  return (
    <form className="flex items-center gap-2" onSubmit={(event) => void submit(event)} data-testid="journal-confirm">
      <Input className="h-8 w-36" value={value} maxLength={50} onChange={(event) => setValue(event.target.value)}
        aria-label={t('finance.journal.externalVoucherFor', { voucher: row.voucherNo })}
        data-testid="journal-confirm-input" />
      <Button type="submit" size="sm" variant="outline" disabled={busy || !value.trim()}
        data-testid="journal-confirm-submit">
        {t('finance.journal.record')}
      </Button>
    </form>
  )
}

function DownloadButton({ fileId, name, label, ariaLabel, testId }: { fileId: string; name: string; label: string;
  ariaLabel: string; testId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [busy, setBusy] = useState(false)
  return (
    <Button variant="ghost" size="sm" disabled={busy} data-testid={testId} aria-label={ariaLabel}
      onClick={async () => {
        setBusy(true)
        try {
          await downloadGeneratedFile(fileId, name)
        } catch (error) {
          tell(error, t('common.failed'))
        } finally {
          setBusy(false)
        }
      }}>
      <Download aria-hidden />
      {label}
    </Button>
  )
}

/**
 * The journal export to the accounting system (F-01 – F-03): one summary voucher per Japanese month, exported in
 * order, numbered without gaps and kept as CSV exactly as made (the voucher for the accounting system to import, and
 * its detail, in as many files as the platform's limits need). A posting recorded after its month was exported comes in the next export; exported vouchers never
 * change. The accounting system's own voucher number is recorded once per export.
 */
export default function JournalPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const more = useMoreFilters([
    { kind: 'date', name: 'periodFrom', label: t('finance.journal.period') },
    { kind: 'eq', name: 'voucherNo', label: t('finance.journal.voucherNo') },
    { kind: 'eq', name: 'externalVoucherNo', label: t('finance.journal.externalVoucher') },
  ], 'journal-filters')
  const { query, table, sortable } = usePaged([...EXPORTS_KEY, more.filters],
    (offset, limit, sorts) => readJournalExports(offset, limit, sorts, more.filters), true, SORTS)
  const files = can(PERMISSION.generatedFileRead)
  const confirmer = can(PERMISSION.journalConfirm)
  const columns = useMemo<ColumnDef<JournalExport, unknown>[]>(() => [
    { id: 'period', header: t('finance.journal.period'), cell: ({ row }) =>
      `${row.original.periodFrom} – ${row.original.periodTo}` },
    { id: 'voucher', header: t('finance.journal.voucherNo'), cell: ({ row }) =>
      <code className="text-xs" data-testid="journal-voucher">{row.original.voucherNo}</code> },
    { id: 'lines', header: t('finance.journal.lines'), meta: { className: right },
      cell: ({ row }) => row.original.lineCount.toLocaleString() },
    { id: 'debit', header: t('finance.journal.debitTotal'), meta: { className: right },
      cell: ({ row }) => money(row.original.debitTotal) },
    { id: 'credit', header: t('finance.journal.creditTotal'), meta: { className: right },
      cell: ({ row }) => money(row.original.creditTotal) },
    { id: 'exported', header: t('finance.journal.exportedBy'), cell: ({ row }) => (
      <span className="flex flex-col">
        <span data-testid="journal-exported-at">{when(row.original.exportedAt)}</span>
        {row.original.exportedBy && <UserName id={row.original.exportedBy} />}
      </span>
    ) },
    { id: 'files', header: t('finance.journal.files'), cell: ({ row }) => (files
      ? (
        <span className="flex flex-wrap gap-1">
          <DownloadButton fileId={row.original.fileId} name={`${row.original.voucherNo}.csv`}
            label={t('finance.journal.voucherFile')}
            ariaLabel={t('finance.journal.voucherFileOf', { voucher: row.original.voucherNo })}
            testId="journal-download" />
          {row.original.detailParts.map((part) => (
            <DownloadButton key={part.fileId} fileId={part.fileId}
              name={`${row.original.voucherNo}-detail-${part.partNo}.csv`}
              label={row.original.detailParts.length === 1 ? t('finance.journal.detailFile')
                : t('finance.journal.detailPart', { n: part.partNo })}
              ariaLabel={t('finance.journal.detailPartOf', { voucher: row.original.voucherNo, n: part.partNo,
                count: row.original.detailParts.length })}
              testId="journal-download-detail" />
          ))}
        </span>
      )
      : '') },
    { id: 'external', header: t('finance.journal.externalVoucher'), cell: ({ row }) => {
      if (row.original.externalVoucherNo) {
        return <span data-testid="journal-external">{row.original.externalVoucherNo}</span>
      }
      return confirmer ? <ConfirmForm row={row.original} /> : <Badge variant="outline">{t('finance.journal.unconfirmed')}</Badge>
    } },
  ], [t, files, confirmer])

  return (
    <div className="flex flex-col gap-4" data-testid="finance-journal">
      <PageHeader title={t('finance.journal.title')} description={t('finance.journal.description')} className="pb-0"
        actions={<Link to={PATH.journalMapping} className={linkClass}>{t('finance.journal.mappingTitle')}</Link>} />
      {can(PERMISSION.journalExport) && <NextExport />}
      <Section title={t('finance.journal.exports')} testId="journal-exports">
        {more.bar}
        {query.isError
          ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
          : (
            <DataTable label={t('finance.journal.exportsTable')} columns={sortable(columns)} {...table}
              getRowId={(row) => row.exportId} empty={t('finance.journal.noExports')}
              rowProps={(row) => ({ 'data-testid': 'journal-export-row', 'data-id': row.exportId,
                'data-voucher': row.voucherNo })} />
          )}
      </Section>
    </div>
  )
}
