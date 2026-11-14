import { useQuery } from '@tanstack/react-query'
import { Download } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Button,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  useAuth,
} from '@jabiz/admin'
import { aiFeeParams, AI_FEE_MAX_MONTHS, exportAiFees, readAiFees, validAiFeeSpan, type AiFeeFilter } from './ai-api'
import { LoadFailed, Section } from './components'
import { tell } from './finance'
import type { ExportFormat } from './finance-api'
import { money } from './hooks'
import { PERMISSION } from './ids'
import { OperatorPicker, UserPicker } from './pickers'
import { currentMonthInJapan } from './account-api'

const EXPORTS: readonly ExportFormat[] = ['csv', 'xlsx']

/**
 * The AI fees by month, sponsor and operator (F-M9 b2; `qb.admin.ai-fees`, finance only: the money of the sponsors is
 * not the content administrators'). What was charged, what the platform took on (the overrun and the calls with no
 * answer, with the total the template adds up, not worked out here) and what is not charged yet; a sponsor's AI
 * credit only when one sponsor is chosen. Read only; exported whole through the platform. A span of more than 12
 * months or a reversed one is not asked (the server would return no rows).
 */
export function AiFeesSection() {
  const { can } = useAuth()
  if (!can(PERMISSION.financeRead)) return null
  return <AiFees />
}

function AiFees() {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const id = useId()
  const [month, setMonth] = useState(() => currentMonthInJapan())
  const [toMonth, setToMonth] = useState('')
  const [sponsorId, setSponsorId] = useState<string | null>(null)
  const [operatorId, setOperatorId] = useState<string | null>(null)
  const [busy, setBusy] = useState<ExportFormat | null>(null)
  const filter: AiFeeFilter = { month, toMonth, sponsorId, operatorId }
  const valid = validAiFeeSpan(filter)
  const fees = useQuery({ queryKey: ['qb-ext', 'ai', 'fees', aiFeeParams(filter)], queryFn: () => readAiFees(filter),
    enabled: valid })
  const n = (value: number) => value.toLocaleString(i18n.language)
  const download = async (format: ExportFormat) => {
    setBusy(format)
    try {
      await exportAiFees(filter, format)
    } catch (error) {
      tell(error, t('ai.fees.exportFailed'))
    } finally {
      setBusy(null)
    }
  }
  const showCredit = sponsorId != null
  // The last month allowed from the first: a picker that cannot be set beyond 12 months in all.
  const lastAllowed = /^\d{4}-\d{2}$/.test(month) ? addMonths(month, AI_FEE_MAX_MONTHS - 1) : undefined
  let body
  if (!valid) {
    body = <p role="alert" className="text-destructive text-sm" data-testid="ai-fees-invalid">
      {t('ai.fees.invalidSpan', { max: AI_FEE_MAX_MONTHS })}</p>
  } else if (fees.isError) body = <LoadFailed error={fees.error} onRetry={() => void fees.refetch()} />
  else if (fees.isPending) body = <PageState kind="loading" />
  else if (fees.data.length === 0) body = <p className="text-muted-foreground text-sm" data-testid="ai-fees-none">{t('ai.fees.none')}</p>
  else {
    const right = 'text-right tabular-nums'
    body = (
      <div className="overflow-x-auto" tabIndex={0} role="region" aria-label={t('ai.fees.table')}>
        <Table data-testid="ai-fees">
          <TableHeader>
            <TableRow>
              <TableHead>{t('ai.month')}</TableHead>
              <TableHead>{t('ai.sponsor')}</TableHead>
              <TableHead>{t('ai.fees.operator')}</TableHead>
              <TableHead className="text-right">{t('ai.jobs')}</TableHead>
              <TableHead className="text-right">{t('ai.fees.costUsd')}</TableHead>
              <TableHead className="text-right">{t('ai.fees.feeYen')}</TableHead>
              <TableHead className="text-right">{t('ai.fees.absorbedYen')}</TableHead>
              <TableHead className="text-right">{t('ai.fees.unrecordedUsd')}</TableHead>
              <TableHead className="text-right">{t('ai.fees.absorbedTotalYen')}</TableHead>
              <TableHead className="text-right">{t('ai.fees.unbilledYen')}</TableHead>
              {showCredit && <TableHead className="text-right">{t('ai.fees.aiCredit')}</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {fees.data.map((row) => (
              <TableRow key={`${row.month}:${row.sponsorId}:${row.operatorId ?? ''}`} data-testid="ai-fee-row"
                data-sponsor={row.sponsorId}>
                <TableCell>{row.month}</TableCell>
                <TableCell>{row.company ?? row.sponsorId}</TableCell>
                <TableCell>{row.operatorName ?? row.operatorId ?? t('ai.fees.platform')}</TableCell>
                <TableCell className={right}>{n(row.jobs)}</TableCell>
                <TableCell className={right}>{row.costUsd}</TableCell>
                <TableCell className={right}>{money(row.feeYen)}</TableCell>
                <TableCell className={right}>{money(row.absorbedYen)}</TableCell>
                <TableCell className={right}>{row.unrecordedUsd}</TableCell>
                <TableCell className={right} data-testid="ai-fee-absorbed-total">{money(row.absorbedTotalYen)}</TableCell>
                <TableCell className={right}>{money(row.unbilledYen)}</TableCell>
                {showCredit && <TableCell className={right} data-testid="ai-fee-credit">{money(row.aiCredit)}</TableCell>}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    )
  }
  return (
    <Section title={t('ai.fees.title')} testId="ai-fees-section">
      <p className="text-muted-foreground text-sm">{t('ai.fees.hint')}</p>
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-month`}>{t('ai.fees.from')}</Label>
          <Input id={`${id}-month`} type="month" className="w-44" value={month} data-testid="ai-fees-month"
            onChange={(event) => setMonth(event.target.value)} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-to`}>{t('ai.fees.to')}</Label>
          <Input id={`${id}-to`} type="month" className="w-44" value={toMonth} min={month || undefined}
            max={lastAllowed} data-testid="ai-fees-to" onChange={(event) => setToMonth(event.target.value)} />
        </div>
        {can(PERMISSION.usersRead) && (
          <div className="flex w-64 flex-col gap-1">
            <Label htmlFor={`${id}-sponsor`}>{t('ai.sponsor')}</Label>
            <UserPicker id={`${id}-sponsor`} value={sponsorId} clearable onChange={setSponsorId}
              placeholder={t('ai.fees.allSponsors')} data-testid="ai-fees-sponsor" />
          </div>
        )}
        {can(PERMISSION.operatorsRead) && (
          <div className="flex w-64 flex-col gap-1">
            <Label htmlFor={`${id}-operator`}>{t('ai.fees.operator')}</Label>
            <OperatorPicker id={`${id}-operator`} value={operatorId} clearable onChange={setOperatorId}
              placeholder={t('pickers.allOperators')} data-testid="ai-fees-operator" />
          </div>
        )}
        <div className="flex flex-wrap gap-2">
          {EXPORTS.map((format) => (
            <Button key={format} variant="outline" size="sm" disabled={!valid || busy !== null}
              aria-busy={busy === format || undefined} data-testid={`ai-fees-export-${format}`}
              onClick={() => void download(format)}>
              <Download aria-hidden />{t(`ai.fees.export.${format}`)}
            </Button>
          ))}
        </div>
      </div>
      {body}
    </Section>
  )
}

/** The month `count` months after `month` (`yyyy-MM`). */
function addMonths(month: string, count: number): string {
  const total = Number(month.slice(0, 4)) * 12 + Number(month.slice(5, 7)) - 1 + count
  return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, '0')}`
}
