import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, Button, DataTable, type ColumnDef, type PaginationState, type SortingState } from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileDown } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { creditNotesQuery, downloadCreditNoteDocument, searchCreditNotes, type CreditNoteRow } from './api'
import './messages'

const PAGE_SIZE = 10

/**
 * F-M7: the credit notes the platform issued to the Sponsor (`qb.sponsor.fee-credit-notes`): the audience fee of the
 * unused days of a publication that ended early (when the platform's setting gives them back), or one issued by hand.
 * Each has its document, issued once and downloaded as kept (`QB_FEE_CREDIT_NOTE_DOCUMENT`). The page records nothing
 * itself; the credit a credit note leaves is used by the next invoices before the account.
 */
export function CreditNotes() {
  const { t, i18n } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [sorting, setSorting] = useState<SortingState>([{ id: 'issuedAt', desc: true }])
  const notes = useQuery({
    queryKey: [...creditNotesQuery(), 'list', pagination, sorting],
    queryFn: () => searchCreditNotes({
      offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize,
      sorts: sorting.map((s) => ({ field: s.id, asc: !s.desc })),
    }),
    placeholderData: keepPreviousData,
  })
  const download = useMutation({
    mutationFn: (creditNoteId: string) => downloadCreditNoteDocument(creditNoteId, i18n.language),
    onMutate: () => setError(null),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: creditNotesQuery() }),
    onError: (e) => setError(problemText(e)),
  })
  const columns = useMemo<ColumnDef<CreditNoteRow, unknown>[]>(() => [
    { id: 'issuedAt', header: t('wallet.creditNotes.issued'), meta: { card: 'subtitle' },
      cell: ({ row }) => formatDateTime(row.original.issuedAt) },
    { id: 'creditNoteNo', header: t('wallet.creditNotes.number'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => <span data-testid="credit-note-number">{row.original.creditNoteNo}</span> },
    { id: 'invoiceNo', header: t('wallet.creditNotes.invoice'), enableSorting: false,
      cell: ({ row }) => row.original.invoiceNo ?? '—' },
    { id: 'reason', header: t('wallet.creditNotes.reason'), enableSorting: false,
      cell: ({ row }) => t(`wallet.creditNotes.reasons.${row.original.reason}`) },
    { id: 'usedDays', header: t('wallet.creditNotes.usedDays'), enableSorting: false, meta: { className: 'text-right' },
      cell: ({ row }) => row.original.usedDays ?? '—' },
    { id: 'total', header: t('wallet.creditNotes.total'), enableSorting: false, meta: { card: 'badge', className: 'text-right' },
      cell: ({ row }) => <span data-testid="credit-note-total">{formatKudos(row.original.total)}</span> },
    { id: 'givenBack', header: t('wallet.creditNotes.givenBack'), enableSorting: false, meta: { className: 'text-right' },
      cell: ({ row }) => (
        <span data-testid="credit-note-given-back">
          {t('wallet.creditNotes.givenBackValue', { cash: formatKudos(row.original.refundedCash),
            credit: formatKudos(row.original.refundedCredit) })}
        </span>
      ) },
    { id: 'creditLeft', header: t('wallet.creditNotes.creditLeft'), enableSorting: false, meta: { className: 'text-right' },
      cell: ({ row }) => <span data-testid="credit-note-left">{formatKudos(row.original.creditLeft)}</span> },
    { id: 'actions', header: () => <span className="sr-only">{t('wallet.creditNotes.document')}</span>, enableSorting: false,
      meta: { card: 'actions' },
      cell: ({ row }) => (
        <span className="flex flex-wrap justify-end gap-2">
          <Button size="sm" variant="outline" disabled={download.isPending} aria-busy={download.isPending || undefined}
            data-testid="credit-note-document" onClick={() => download.mutate(row.original.creditNoteId)}>
            <FileDown aria-hidden />
            {t('wallet.creditNotes.document')}
            <span className="sr-only">: {row.original.creditNoteNo}</span>
          </Button>
        </span>
      ) },
  ], [t, download])
  return (
    <section aria-labelledby="wallet-credit-notes" className="flex flex-col gap-3" data-testid="wallet-credit-notes">
      <h2 id="wallet-credit-notes" className="text-lg font-semibold">{t('wallet.creditNotes.title')}</h2>
      <p className="text-muted-foreground text-sm">{t('wallet.creditNotes.intro')}</p>
      {error && (
        <Alert variant="destructive" data-testid="credit-note-notice">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {notes.isError
        ? <p className="text-destructive text-sm" role="alert">{tQb('failed')}</p>
        : (
          <DataTable label={t('wallet.creditNotes.table')} columns={columns} data={notes.data?.items ?? []}
            storageKey="qb.sponsor.fee-credit-notes" getRowId={(row) => row.creditNoteId} loading={notes.isPending}
            stale={notes.isPlaceholderData} empty={t('wallet.creditNotes.none')} pagination={pagination}
            onPaginationChange={setPagination} rowCount={notes.data?.total} sorting={sorting}
            onSortingChange={(next) => {
              setSorting(next)
              setPagination((p) => ({ ...p, pageIndex: 0 }))
            }}
            rowProps={(row) => ({ 'data-testid': 'credit-note-row', 'data-credit-note': row.creditNoteId,
              'data-reason': row.reason })} />
        )}
    </section>
  )
}
