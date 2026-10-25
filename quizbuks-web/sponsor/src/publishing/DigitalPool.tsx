import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  Label,
  notify,
  PageState,
  Textarea,
  type ColumnDef,
  type PaginationState,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Undo2, Upload } from 'lucide-react'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { EmptyState } from '../EmptyState'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import {
  countCodeLines,
  DIGITAL_CODES_PER_LOAD,
  loadDigitalCodes,
  readDigitalPoolSummary,
  revokeDigitalCodes,
  searchDigitalCodes,
  type DigitalCodeRow,
  type DigitalCodeStatus,
  type PublicationStatus,
} from './api'
import { useDigitalCodeStatusLabel } from './shared'

const PAGE_SIZE = 20
const STATUS_BADGES: Record<DigitalCodeStatus, 'success' | 'secondary' | 'outline'> = {
  AVAILABLE: 'success',
  ISSUED: 'secondary',
  REVOKED: 'outline',
}

export const digitalPoolQuery = (publicationId: string) => ['qb', 'digital-pool', publicationId] as const

/**
 * The code pool of a digital prize handed out from a pool (Q33, templates `qb.sponsor.digital-pool-summary` and
 * `qb.sponsor.digital-codes`): the counts, a box to paste codes one a line (`QB_DIGITAL_CODES_LOAD`; the server seals
 * them and drops blank lines and repeats), the button that takes back every code not yet handed to a winner
 * (`QB_DIGITAL_CODES_REVOKE`; refused while the publication is in review or running), and the rows with each code's
 * last characters only: the codes themselves are never read back.
 */
export function DigitalPoolSection({ publicationId, status }: { publicationId: string; status: PublicationStatus }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const queryClient = useQueryClient()
  const statusLabel = useDigitalCodeStatusLabel()
  const [lines, setLines] = useState('')
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [confirmRevoke, setConfirmRevoke] = useState(false)
  const key = digitalPoolQuery(publicationId)
  const refresh = () => queryClient.invalidateQueries({ queryKey: key })

  const summary = useQuery({ queryKey: [...key, 'summary'], queryFn: () => readDigitalPoolSummary(publicationId) })
  const list = useQuery({
    queryKey: [...key, 'list', pagination],
    queryFn: () => searchDigitalCodes({ publicationId, offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize }),
    placeholderData: keepPreviousData,
  })
  const count = countCodeLines(lines)
  const load = useMutation({
    mutationFn: () => loadDigitalCodes(publicationId, lines),
    onSuccess: async (output) => {
      notify.success(t('publishing.digital.pool.loaded', { loaded: output.loaded, available: output.available }))
      setLines('')
      await refresh()
    },
  })
  // The dialog stays open while the call runs and when it is refused (the refusal is told by a toast).
  const revoke = useMutation({
    mutationFn: () => revokeDigitalCodes(publicationId),
    onSuccess: async (output) => {
      notify.success(t('publishing.digital.pool.revoked', { revoked: output.revoked }))
      await refresh()
    },
    onError: (error) => notify.error(problemText(error)),
  })
  // The server refuses to take codes back while the publication is in review or running (`QB_DIGITAL_POOL_LOCKED`);
  // a draft (also one sent back) and an ended publication may. The server decides all the same.
  const revocable = status === 'DRAFT' || status === 'ENDED'

  const columns = useMemo<ColumnDef<DigitalCodeRow, unknown>[]>(() => [
    { id: 'seq', header: t('publishing.digital.pool.seq'), enableSorting: false, meta: { className: 'tabular-nums' },
      cell: ({ row }) => row.original.seq },
    { id: 'hint', header: t('publishing.digital.pool.hint'), enableSorting: false,
      cell: ({ row }) => row.original.hint
        ? <span className="font-mono" data-testid="digital-code-hint">{row.original.hint}</span>
        : <span className="text-muted-foreground">{t('publishing.digital.pool.noHint')}</span> },
    { id: 'status', header: t('publishing.digital.pool.status'), enableSorting: false,
      cell: ({ row }) => (
        <Badge variant={STATUS_BADGES[row.original.status]} data-testid="digital-code-status"
          data-status={row.original.status}>
          {statusLabel(row.original.status)}
        </Badge>
      ) },
    { id: 'loadedAt', header: t('publishing.digital.pool.loadedAt'), enableSorting: false,
      cell: ({ row }) => formatDateTime(row.original.loadedAt) },
    { id: 'issuedAt', header: t('publishing.digital.pool.issuedAt'), enableSorting: false,
      cell: ({ row }) => (row.original.issuedAt ? formatDateTime(row.original.issuedAt) : '') },
    { id: 'revokedAt', header: t('publishing.digital.pool.revokedAt'), enableSorting: false,
      cell: ({ row }) => (row.original.revokedAt ? formatDateTime(row.original.revokedAt) : '') },
  ], [t, statusLabel])

  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${id}-title`} data-testid="digital-pool">
      <div className="flex flex-col gap-1">
        <h2 id={`${id}-title`} className="text-lg font-semibold">{t('publishing.digital.pool.title')}</h2>
        <p className="text-muted-foreground text-sm">{t('publishing.digital.pool.intro')}</p>
        {summary.data && (
          <dl className="flex flex-wrap gap-x-6 gap-y-1 text-sm" data-testid="digital-pool-summary">
            <div className="flex gap-1">
              <dt className="text-muted-foreground">{t('publishing.digital.pool.available')}</dt>
              <dd className="font-medium tabular-nums" data-testid="digital-pool-available">{summary.data.available}</dd>
            </div>
            <div className="flex gap-1">
              <dt className="text-muted-foreground">{t('publishing.digital.pool.issued')}</dt>
              <dd className="font-medium tabular-nums" data-testid="digital-pool-issued">{summary.data.issued}</dd>
            </div>
            <div className="flex gap-1">
              <dt className="text-muted-foreground">{t('publishing.digital.pool.revokedCount')}</dt>
              <dd className="font-medium tabular-nums" data-testid="digital-pool-revoked">{summary.data.revoked}</dd>
            </div>
          </dl>
        )}
      </div>
      <form noValidate className="flex flex-col gap-2" onSubmit={(event) => {
        event.preventDefault()
        if (count > 0 && count <= DIGITAL_CODES_PER_LOAD && !load.isPending) load.mutate()
      }}>
        <Label htmlFor={`${id}-lines`}>{t('publishing.digital.pool.lines')}</Label>
        <Textarea id={`${id}-lines`} value={lines} rows={4} autoComplete="off" spellCheck={false}
          className="font-mono" data-testid="digital-pool-lines" aria-describedby={`${id}-lines-hint`}
          onChange={(event) => setLines(event.target.value)} />
        <p id={`${id}-lines-hint`} className="text-muted-foreground text-sm">
          {t('publishing.digital.pool.linesHint', { max: DIGITAL_CODES_PER_LOAD.toLocaleString() })}
        </p>
        {load.isError && <p className="text-destructive text-sm" role="alert">{problemText(load.error)}</p>}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" disabled={count === 0 || count > DIGITAL_CODES_PER_LOAD || load.isPending}
            aria-busy={load.isPending || undefined} data-testid="digital-pool-load">
            <Upload aria-hidden />
            {t('publishing.digital.pool.load')}
          </Button>
          {revocable && (
            <Button type="button" variant="outline" data-testid="digital-pool-revoke"
              disabled={revoke.isPending || (summary.data?.available ?? 0) === 0} onClick={() => setConfirmRevoke(true)}>
              <Undo2 aria-hidden />
              {t('publishing.digital.pool.revoke')}
            </Button>
          )}
        </div>
      </form>
      {list.isError ? (
        <PageState kind="error" description={problemText(list.error)}
          action={<Button variant="outline" onClick={() => void list.refetch()}>{tQb('retry')}</Button>} />
      ) : (
        <DataTable label={t('publishing.digital.pool.title')} columns={columns} data={list.data?.items ?? []}
          getRowId={(row) => row.codeId} loading={list.isPending} stale={list.isPlaceholderData}
          empty={<EmptyState testId="digital-pool-empty">{t('publishing.digital.pool.none')}</EmptyState>}
          pagination={pagination} onPaginationChange={setPagination} rowCount={list.data?.total}
          rowProps={(row) => ({ 'data-testid': 'digital-code-row', 'data-status': row.status })} />
      )}
      <ConfirmDialog open={confirmRevoke} title={t('publishing.digital.pool.revokeTitle')}
        description={t('publishing.digital.pool.revokeText')} confirmLabel={t('publishing.digital.pool.revoke')}
        cancelLabel={t('common.cancel')} destructive onConfirm={() => revoke.mutateAsync()}
        onOpenChange={setConfirmRevoke} />
    </section>
  )
}
