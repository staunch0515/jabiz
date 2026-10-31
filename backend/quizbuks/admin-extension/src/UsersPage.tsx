import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  PageHeader,
  useAuth,
  useLanguage,
  type ColumnDef,
  type PaginationState,
  type SortingState,
} from '@jabiz/admin'
import { searchUsers, type UserRow } from './api'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { money, sortableColumns, textIn, useLabels, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { readTakerKudos } from './payout-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { userName: 'userName', displayName: 'displayName', registeredAt: 'registeredAt' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/**
 * The users (`qb.admin.users`), searched by user name, address, name or company; each opens its summary (P-01). Finance
 * also sees each taker's attempts, Kudos earned and balance (`qb.admin.taker-kudos`, Q14), read for the page shown.
 */
export default function UsersPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const language = useLanguage()
  const sponsorStatus = useLabels(DICTIONARY.sponsorReviewStatus)
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: 20 })
  // Q53a: sorted on the server by the template's `list.sorts`; a new order starts at the first page.
  const [sorting, setSorting] = useState<SortingState>([])
  const sorts = sorting.filter((sort) => SORTS[sort.id as keyof typeof SORTS])
    .map((sort) => ({ field: SORTS[sort.id as keyof typeof SORTS], asc: !sort.desc }))
  const more = useMoreFilters([
    { kind: 'eq', name: 'countryCode', label: t('users.country'), placeholder: 'JP', upper: true },
    { kind: 'time', name: 'registeredAt', label: t('users.registeredAt') },
    { kind: 'dictionary', name: 'sponsorStatus', label: t('users.sponsorStatus'), dictionary: DICTIONARY.sponsorReviewStatus },
    { kind: 'yesno', name: 'banned', label: t('users.banned') },
    { kind: 'yesno', name: 'emailVerified', label: t('user.emailVerified') },
    { kind: 'yesno', name: 'enabled', label: t('user.enabled') },
  ], 'users-filters')
  // Other filters list from the first page.
  const filterText = JSON.stringify(more.filters)
  const [listed, setListed] = useState(filterText)
  if (listed !== filterText) {
    setListed(filterText)
    if (pagination.pageIndex !== 0) setPagination({ ...pagination, pageIndex: 0 })
  }
  const users = useQuery({
    queryKey: ['qb-ext', 'users', q, pagination, sorts, more.filters],
    queryFn: () => searchUsers(q, pagination.pageIndex * pagination.pageSize, pagination.pageSize,
      sorts.length > 0 ? sorts : undefined, more.filters),
    placeholderData: keepPreviousData,
  })
  const { can } = useAuth()
  const finance = can(PERMISSION.financeRead)
  const ids = (users.data ?? []).map((user) => user.userId)
  const kudos = useQuery({
    queryKey: ['qb-ext', 'kudos', 'page', ids],
    queryFn: () => readTakerKudos(ids),
    enabled: finance && ids.length > 0,
  })
  const columns = useMemo<ColumnDef<UserRow, unknown>[]>(() => [
    {
      id: 'userName',
      header: t('users.userName'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.userId)} className={linkClass} data-testid="user-link"
          aria-label={t('users.open', { name: row.original.userName })}>
          {row.original.userName}
        </Link>
      ),
    },
    { id: 'displayName', header: t('users.displayName'), cell: ({ row }) => row.original.displayName ?? '' },
    { id: 'email', header: t('users.email'), cell: ({ row }) => row.original.email ?? '' },
    { id: 'country', header: t('users.country'), cell: ({ row }) => textIn(row.original.countryName, language) || row.original.countryCode || '' },
    { id: 'registeredAt', header: t('users.registeredAt'), cell: ({ row }) => when(row.original.registeredAt) },
    { id: 'sponsorStatus', header: t('users.sponsorStatus'), cell: ({ row }) => (row.original.sponsorStatus
      ? <StatusBadge code={row.original.sponsorStatus} label={sponsorStatus(row.original.sponsorStatus)} /> : '') },
    {
      id: 'banned',
      header: t('users.banned'),
      cell: ({ row }) => (row.original.banned
        ? <StatusBadge code="BANNED" label={t('common.yes')} testId="user-banned" /> : ''),
    },
    ...(finance ? [
      { id: 'attempts', header: t('payout.attempts'), meta: { className: 'text-right tabular-nums' },
        cell: ({ row }) => String(kudos.data?.get(row.original.userId)?.attempts ?? '') },
      { id: 'earned', header: t('payout.earned'), meta: { className: 'text-right tabular-nums' },
        cell: ({ row }) => {
          const k = kudos.data?.get(row.original.userId)
          return k ? <span data-testid="user-earned">{money(k.earned)}</span> : ''
        } },
      { id: 'balance', header: t('payout.balance'), meta: { className: 'text-right tabular-nums' },
        cell: ({ row }) => {
          const k = kudos.data?.get(row.original.userId)
          return k ? money(k.balance) : ''
        } },
    ] satisfies ColumnDef<UserRow, unknown>[] : []),
  ], [t, language, sponsorStatus, finance, kudos.data])

  return (
    <div className="flex flex-col gap-4" data-testid="qb-users">
      <PageHeader title={t('users.title')} className="pb-0" />
      <form
        role="search"
        className="flex max-w-md items-center gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          setQ(text.trim())
          setPagination((current) => ({ ...current, pageIndex: 0 }))
        }}
      >
        <Input type="search" value={text} onChange={(event) => setText(event.target.value)}
          placeholder={t('users.search')} aria-label={t('users.search')} maxLength={100} data-testid="users-search" />
        <Button type="submit" variant="outline">
          <Search aria-hidden />
          {t('users.searchButton')}
        </Button>
      </form>
      {more.bar}
      {users.isError
        ? <LoadFailed error={users.error} onRetry={() => void users.refetch()} />
        : (
          <DataTable label={t('users.table')} columns={sortableColumns(columns, SORTS)} data={users.data ?? []}
            loading={users.isPending} sorting={sorting} onSortingChange={(next) => {
              setSorting(next)
              setPagination((current) => ({ ...current, pageIndex: 0 }))
            }}
            stale={users.isPlaceholderData} getRowId={(row) => row.userId} empty={<EmptyState art="people" title={t('users.none')} />}
            pagination={pagination} onPaginationChange={setPagination}
            hasNextPage={(users.data?.length ?? 0) === pagination.pageSize}
            rowProps={(row) => ({ 'data-testid': 'user-row', 'data-id': row.userId })} />
        )}
    </div>
  )
}
