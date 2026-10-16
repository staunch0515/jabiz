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
  type ColumnDef,
  type PaginationState,
} from '@jabiz/admin'
import { searchUsers, type UserRow } from './api'
import { DICTIONARY, PATH } from './ids'
import { LoadFailed } from './components'
import { useLabels, when } from './hooks'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/** The users (`qb.admin.users`), searched by user name, address, name or company; each opens its summary (P-01). */
export default function UsersPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const sponsorStatus = useLabels(DICTIONARY.sponsorReviewStatus)
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: 20 })
  const users = useQuery({
    queryKey: ['qb-ext', 'users', q, pagination],
    queryFn: () => searchUsers(q, pagination.pageIndex * pagination.pageSize, pagination.pageSize),
    placeholderData: keepPreviousData,
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
    { id: 'country', header: t('users.country'), cell: ({ row }) => row.original.countryName ?? row.original.countryCode ?? '' },
    { id: 'registeredAt', header: t('users.registeredAt'), cell: ({ row }) => when(row.original.registeredAt) },
    { id: 'sponsorStatus', header: t('users.sponsorStatus'), cell: ({ row }) => sponsorStatus(row.original.sponsorStatus) },
    {
      id: 'banned',
      header: t('users.banned'),
      cell: ({ row }) => (row.original.banned ? t('common.yes') : ''),
    },
  ], [t, sponsorStatus])

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
      {users.isError
        ? <LoadFailed error={users.error} onRetry={() => void users.refetch()} />
        : (
          <DataTable label={t('users.table')} columns={columns} data={users.data ?? []} loading={users.isPending}
            stale={users.isPlaceholderData} getRowId={(row) => row.userId} empty={t('users.none')}
            pagination={pagination} onPaginationChange={setPagination}
            hasNextPage={(users.data?.length ?? 0) === pagination.pageSize}
            rowProps={(row) => ({ 'data-testid': 'user-row', 'data-id': row.userId })} />
        )}
    </div>
  )
}
