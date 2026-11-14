import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Badge,
  Button,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  type ColumnDef,
} from '@jabiz/admin'
import { Download } from 'lucide-react'
import { EmptyState, LoadFailed } from './components'
import { tell } from './finance'
import type { ExportFormat } from './finance-api'
import { useLabels, usePaged, when } from './hooks'
import { DICTIONARY } from './ids'
import { exportSettlementLines, readSettlementLines, type Settlement, type SettlementLine } from './settlement-api'

const right = { className: 'text-right tabular-nums' }
const SORTS = { occurredAt: 'occurredAt', amount: 'amount' } as const
const EXPORTS: readonly ExportFormat[] = ['csv', 'xlsx']

/**
 * A settlement's lines (F-M12 §6), by Sponsor and kind: each fee counted, each fee voided, each part of a payment made
 * with granted credit; a fee counted after its month is marked late. Finance reads them from `qb.admin.settlement-lines`,
 * an operator from its own, limited by the server to its scope; both export every line through the platform. Read only.
 */
export function SettlementLines({ settlement, finance, amount, onClose }: {
  settlement: Settlement
  finance: boolean
  amount: (value: string | null | undefined) => string
  onClose: () => void
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const feeKind = useLabels(DICTIONARY.feeKind)
  const [busy, setBusy] = useState<ExportFormat | null>(null)
  const { query, table, sortable } = usePaged(['qb-ext', 'settlement-lines', finance, settlement.settlementId],
    (offset, limit, sorts) => readSettlementLines(finance, settlement.settlementId, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<SettlementLine, unknown>[]>(() => [
    { id: 'sponsor', header: t('settlement.lines.sponsor'),
      cell: ({ row }) => row.original.sponsorName ?? row.original.sponsorId },
    { id: 'lineKind', header: t('settlement.lines.kind'),
      cell: ({ row }) => <span data-testid="line-kind">{t(`settlement.lines.kinds.${row.original.lineKind}`,
        { defaultValue: row.original.lineKind })}</span> },
    { id: 'feeKind', header: t('settlement.lines.feeKind'), cell: ({ row }) => feeKind(row.original.feeKind) },
    { id: 'period', header: t('settlement.lines.period'), cell: ({ row }) => row.original.period ?? '' },
    { id: 'amount', header: t('settlement.lines.amount'), meta: right, cell: ({ row }) => amount(row.original.amount) },
    { id: 'excluded', header: t('settlement.lines.excluded'), meta: right, cell: ({ row }) => amount(row.original.excluded) },
    { id: 'late', header: t('settlement.lines.lateColumn'),
      cell: ({ row }) => row.original.late
        ? <Badge variant="outline" data-testid="line-late">{t('settlement.lines.late')}</Badge> : null },
    { id: 'occurredAt', header: t('settlement.lines.occurredAt'), cell: ({ row }) => when(row.original.occurredAt) },
  ], [t, feeKind, amount])
  const download = async (format: ExportFormat) => {
    setBusy(format)
    try {
      await exportSettlementLines(finance, settlement.settlementId, format)
    } catch (error) {
      tell(error, t('settlement.lines.exportFailed'))
    } finally {
      setBusy(null)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent className="max-w-5xl" data-testid="settlement-lines">
        <DialogHeader>
          <DialogTitle>{t('settlement.lines.title', { month: settlement.month })}</DialogTitle>
          <DialogDescription>{t('settlement.lines.hint')}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-wrap gap-2">
          {EXPORTS.map((format) => (
            <Button key={format} variant="outline" size="sm" disabled={busy !== null}
              aria-busy={busy === format || undefined} data-testid={`lines-export-${format}`}
              onClick={() => void download(format)}>
              <Download aria-hidden />{t(`settlement.lines.export.${format}`)}
            </Button>
          ))}
        </div>
        {query.isError
          ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
          : (
            <DataTable label={t('settlement.lines.title', { month: settlement.month })}
              columns={sortable(columns)} {...table} getRowId={(row) => row.lineId}
              empty={<EmptyState art="money" title={t('settlement.lines.none')} />}
              rowProps={(row) => ({ 'data-testid': 'settlement-line', 'data-kind': row.lineKind })} />
          )}
      </DialogContent>
    </Dialog>
  )
}
