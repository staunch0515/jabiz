import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Label,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  UserName,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { QUEUE, usePaged, when } from './hooks'
import { PATH } from './ids'
import { operatorColumn, useOperatorFilter } from './operator-filter'
import {
  readSponsorVerifications,
  VERIFICATION_STATUSES,
  type SponsorVerification,
  type VerificationStatus,
} from './verification-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const ALL = 'ALL'
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { company: 'companyName', domain: 'domain', submitted: 'submittedAt', verified: 'verifiedAt' } as const

/**
 * The Sponsors' company verifications (F-M3): those in review first to hand, newest first, each opened for its review
 * (the platform's approval panel) or its revoking. One page for both management systems: an operator's session reads
 * its own Sponsors' (the server's scope), the platform's reads every operator's, filtered by operator. The status
 * (`?status=`) and one Sponsor (`?sponsorId=`) are in the address, so that a link can point at them.
 */
export default function SponsorVerificationsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [search, setSearch] = useSearchParams()
  const sponsorId = search.get('sponsorId')
  // Without a choice: those in review; for one Sponsor: all of its.
  const show = search.get('status') ?? (sponsorId ? ALL : 'REVIEW')
  const status = (VERIFICATION_STATUSES as readonly string[]).includes(show) ? show as VerificationStatus : null
  const change = (next: Record<string, string | null>) => {
    const params = new URLSearchParams(search)
    for (const [key, value] of Object.entries(next)) {
      if (value) params.set(key, value)
      else params.delete(key)
    }
    setSearch(params, { replace: true })
  }
  const operators = useOperatorFilter('sponsor-verifications-operator-filter')
  const { query, table, sortable } = usePaged([...QUEUE.sponsorVerifications, show, sponsorId, operators.filters],
    (offset, limit, sorts) => readSponsorVerifications(offset, limit, sorts, status, sponsorId, operators.filters),
    true, SORTS)
  const columns = useMemo<ColumnDef<SponsorVerification, unknown>[]>(() => [
    {
      id: 'company',
      header: t('verifications.columns.company'),
      cell: ({ row }) => (
        <Link to={PATH.sponsorVerification(row.original.verificationId)} className={linkClass}
          data-testid="verification-link">{row.original.companyName}</Link>
      ),
    },
    { id: 'domain', header: t('verifications.columns.domain'), cell: ({ row }) => row.original.domain },
    { id: 'method', header: t('verifications.columns.method'),
      cell: ({ row }) => t(`verifications.method.${row.original.method}`) },
    { id: 'sponsor', header: t('verifications.columns.sponsor'),
      cell: ({ row }) => <UserName id={row.original.sponsorId} /> },
    ...(operators.across ? [operatorColumn<SponsorVerification>(t('pickers.operatorColumn'))] : []),
    { id: 'status', header: t('verifications.columns.status'),
      cell: ({ row }) => <StatusBadge code={row.original.status}
        label={t(`verifications.status.${row.original.status}`)} testId="verification-status" /> },
    { id: 'submitted', header: t('verifications.columns.submitted'), cell: ({ row }) => when(row.original.submittedAt) },
    { id: 'verified', header: t('verifications.columns.verified'), cell: ({ row }) => when(row.original.verifiedAt) },
  ], [t, operators.across])
  return (
    <div className="flex flex-col gap-4" data-testid="qb-sponsor-verifications">
      <PageHeader title={t('verifications.title')} description={t('verifications.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor="verifications-show">{t('verifications.filter')}</Label>
          <Select value={show} onValueChange={(value) => change({ status: value })}>
            <SelectTrigger id="verifications-show" className="w-48" data-testid="verifications-show"><SelectValue /></SelectTrigger>
            <SelectContent>
              {VERIFICATION_STATUSES.map((code) => (
                <SelectItem key={code} value={code}>{t(`verifications.status.${code}`)}</SelectItem>
              ))}
              <SelectItem value={ALL}>{t('verifications.all')}</SelectItem>
            </SelectContent>
          </Select>
        </div>
        {sponsorId && (
          <div className="flex items-center gap-2" data-testid="verifications-sponsor">
            <span className="text-sm">{t('verifications.forSponsor')} <UserName id={sponsorId} /></span>
            <Button type="button" variant="outline" size="sm" data-testid="verifications-all-sponsors"
              onClick={() => change({ sponsorId: null })}>{t('verifications.allSponsors')}</Button>
          </div>
        )}
      </div>
      {operators.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : <DataTable label={t('verifications.table')} columns={sortable(columns)} {...table}
          getRowId={(row) => row.verificationId} empty={<EmptyState art="search" title={t('verifications.none')} />}
          rowProps={(row) => ({ 'data-testid': 'verification-row', 'data-id': row.verificationId })} />}
    </div>
  )
}
