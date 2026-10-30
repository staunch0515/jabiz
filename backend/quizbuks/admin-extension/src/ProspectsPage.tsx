import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Badge,
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  type ColumnDef,
} from '@jabiz/admin'
import { LoadFailed } from './components'
import { useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { DICTIONARY, PATH } from './ids'
import { PROSPECT_STATUSES, PROSPECTS_KEY, readProspects, type Prospect } from './prospect-api'
import { useProspectScope } from './prospect-scope'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { score: 'score', foundAt: 'foundAt', sentAt: 'sentAt' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const ALL = 'ALL'

/**
 * The businesses AI prospecting found (Q48c), the drafts waiting for a person first: by state and search, each opens
 * on its page, where its draft is read, edited and approved or discarded. Headquarters picks whose (`?owner=`).
 */
export default function ProspectsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const scope = useProspectScope()
  const statusLabel = useLabels(DICTIONARY.prospectStatus)
  const [search, setSearch] = useSearchParams()
  const status = search.get('status') ?? 'DRAFTED'
  const q = search.get('q') ?? ''
  const [typed, setTyped] = useState(q)
  const [owner, setOwner] = useState(scope.owner)
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const more = useMoreFilters([
    { kind: 'time', name: 'foundAt', label: t('prospect.list.foundAt') },
    { kind: 'time', name: 'sentAt', label: t('prospect.list.sentAt') },
  ], 'prospects-filters')
  const { query, table, sortable } = usePaged([...PROSPECTS_KEY, scope, status, q, more.filters],
    (offset, limit, sorts) => readProspects(scope, status === ALL ? null : status, q, offset, limit, sorts, more.filters), true, SORTS)
  const ownerQuery = scope.admin ? `?owner=${encodeURIComponent(scope.owner)}` : ''
  const columns = useMemo<ColumnDef<Prospect, unknown>[]>(() => [
    {
      id: 'business',
      header: t('prospect.list.business'),
      cell: ({ row }) => (
        <span className="flex flex-col">
          <Link to={PATH.prospect(row.original.prospectId) + ownerQuery} className={linkClass}
            data-testid="prospect-link">
            {row.original.company ?? row.original.domain}
          </Link>
          <span className="text-muted-foreground text-sm">{row.original.domain}</span>
        </span>
      ),
    },
    { id: 'status', header: t('prospect.list.status'),
      cell: ({ row }) => <Badge variant="secondary">{statusLabel(row.original.status)}</Badge> },
    { id: 'score', header: t('prospect.list.score'), meta: right, cell: ({ row }) => row.original.score ?? '' },
    { id: 'foundAt', header: t('prospect.list.foundAt'), cell: ({ row }) => when(row.original.foundAt) },
    { id: 'sentAt', header: t('prospect.list.sentAt'), cell: ({ row }) => when(row.original.sentAt) },
  ], [t, statusLabel, ownerQuery])
  return (
    <div className="flex flex-col gap-4" data-testid="prospects-page">
      <PageHeader title={t('prospect.list.title')} description={t('prospect.list.description')} className="pb-0"
        actions={<Button asChild variant="outline"><Link to={PATH.prospectSettings + ownerQuery}
          data-testid="prospect-settings-link">{t('prospect.list.settings')}</Link></Button>} />
      <form className="flex flex-wrap items-end gap-4" role="search" onSubmit={(event) => {
        event.preventDefault()
        set({ q: typed.trim() || null, owner: scope.admin ? owner.trim() || null : null })
      }}>
        {scope.admin && (
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-owner`}>{t('prospect.owner')}</Label>
            <Input id={`${id}-owner`} value={owner} maxLength={36} className="w-80" data-testid="prospects-owner"
              aria-describedby={`${id}-owner-hint`} onChange={(event) => setOwner(event.target.value)} />
            <p id={`${id}-owner-hint`} className="text-muted-foreground max-w-80 text-sm">{t('prospect.ownerHint')}</p>
          </div>
        )}
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-status`}>{t('prospect.list.status')}</Label>
          <Select value={status} onValueChange={(value) => set({ status: value })}>
            <SelectTrigger id={`${id}-status`} className="w-48" data-testid="prospects-status">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t('prospect.list.all')}</SelectItem>
              {PROSPECT_STATUSES.map((value) => <SelectItem key={value} value={value}>{statusLabel(value)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-q`}>{t('prospect.list.search')}</Label>
          <Input id={`${id}-q`} type="search" value={typed} maxLength={100} className="w-72" data-testid="prospects-q"
            onChange={(event) => setTyped(event.target.value)} />
        </div>
        <Button type="submit" variant="outline">{t('prospect.list.find')}</Button>
      </form>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('prospect.list.title')} columns={sortable(columns)} {...table}
            getRowId={(row) => row.prospectId} empty={t('prospect.list.none')}
            rowProps={(row) => ({ 'data-testid': 'prospect-row', 'data-id': row.prospectId })} />
        )}
    </div>
  )
}
