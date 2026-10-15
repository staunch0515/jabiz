import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  Button,
  DataTable,
  DescriptionList,
  EXTENSION_NAMESPACE,
  PageHeader,
  PageState,
  UserName,
  useAuth,
  type ColumnDef,
  type DescriptionItem,
} from '@jabiz/admin'
import {
  readAttempts,
  readBan,
  readPayoutAccount,
  readTopUps,
  readTransfers,
  readUser,
  type AttemptRow,
  type TopUpRow,
  type TransferRow,
  type UserRow,
} from './api'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { LoadFailed, Section } from './components'
import { money, useLabels, when } from './hooks'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

function Profile({ user }: { user: UserRow }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const sponsorStatus = useLabels(DICTIONARY.sponsorReviewStatus)
  const ban = useQuery({ queryKey: ['qb-ext', 'ban', user.userId], queryFn: () => readBan(user.userId) })
  const notSet = t('common.notSet')
  const yesNo = (value: boolean) => (value ? t('common.yes') : t('common.no'))
  const items: DescriptionItem[] = [
    { key: 'userName', label: t('user.userName'), value: user.userName },
    { key: 'displayName', label: t('user.displayName'), value: user.displayName ?? notSet },
    { key: 'email', label: t('user.email'), value: user.email ?? notSet },
    { key: 'emailVerified', label: t('user.emailVerified'), value: yesNo(user.emailVerified) },
    { key: 'enabled', label: t('user.enabled'), value: yesNo(user.enabled) },
    {
      key: 'country',
      label: t('user.country'),
      value: [user.countryName ?? user.countryCode, user.city].filter(Boolean).join(', ') || notSet,
    },
    { key: 'registeredAt', label: t('user.registeredAt'), value: when(user.registeredAt) || notSet },
    {
      key: 'sponsor',
      label: t('user.sponsorStatus'),
      value: user.sponsorStatus
        ? (
          <span className="flex flex-wrap items-center gap-2">
            {[sponsorStatus(user.sponsorStatus), user.company].filter(Boolean).join(' · ')}
            <Link to={PATH.sponsor(user.userId)} className={linkClass} data-testid="user-application">
              {t('user.application')}
            </Link>
          </span>
        )
        : t('user.noSponsor'),
    },
    {
      key: 'ban',
      label: t('user.ban'),
      value: (
        <span data-testid="user-ban" data-banned={user.banned}>
          {user.banned ? t('user.banned', { time: when(ban.data?.bannedAt) || '?' }) : t('user.notBanned')}
        </span>
      ),
    },
    ...(user.banned && ban.data
      ? [
        { key: 'banReason', label: t('user.banReason'), value: ban.data.reason ?? notSet },
        { key: 'bannedBy', label: t('user.bannedBy'), value: <UserName id={ban.data.bannedBy} /> },
      ]
      : []),
    ...(!user.banned && ban.data?.liftedAt
      ? [{ key: 'liftedAt', label: t('user.liftedAt'), value: when(ban.data.liftedAt) }]
      : []),
  ]
  return (
    <Section title={t('user.profile')} testId="user-profile">
      <DescriptionList items={items} columns={2} />
    </Section>
  )
}

function Attempts({ userId }: { userId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const attemptStatus = useLabels(DICTIONARY.attemptStatus)
  const rewardStatus = useLabels(DICTIONARY.rewardStatus)
  const attempts = useQuery({ queryKey: ['qb-ext', 'attempts', userId], queryFn: () => readAttempts(userId) })
  const columns = useMemo<ColumnDef<AttemptRow, unknown>[]>(() => [
    { id: 'name', header: t('user.publication'), cell: ({ row }) => row.original.name ?? row.original.publicationId },
    { id: 'status', header: t('user.attemptStatus'), cell: ({ row }) => attemptStatus(row.original.status) },
    { id: 'startedAt', header: t('user.startedAt'), cell: ({ row }) => when(row.original.startedAt) },
    {
      id: 'score',
      header: t('user.score'),
      meta: right,
      cell: ({ row }) => (row.original.score == null ? ''
        : t('user.scoreValue', { score: row.original.score, full: row.original.fullScore ?? '?' })),
    },
    { id: 'reward', header: t('user.reward'), meta: right, cell: ({ row }) => money(row.original.reward) },
    { id: 'rewardStatus', header: t('user.rewardStatus'), cell: ({ row }) => rewardStatus(row.original.rewardStatus) },
  ], [t, attemptStatus, rewardStatus])
  return (
    <Section title={t('user.attempts')} testId="user-attempts">
      {attempts.isError ? <LoadFailed error={attempts.error} onRetry={() => void attempts.refetch()} /> : (
        <DataTable label={t('user.attemptsTable')} columns={columns} data={attempts.data ?? []}
          loading={attempts.isPending} getRowId={(row) => row.attemptId} empty={t('user.noAttempts')} />
      )}
    </Section>
  )
}

/** The money of a user: transfers to them, their payout account and (as a sponsor) their top-ups. Finance only. */
function Finance({ userId, sponsor }: { userId: string; sponsor: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const transferStatus = useLabels(DICTIONARY.transferStatus)
  const topUpStatus = useLabels(DICTIONARY.topUpStatus)
  const accountStatus = useLabels(DICTIONARY.payoutAccountStatus)
  const transfers = useQuery({ queryKey: ['qb-ext', 'transfers', userId], queryFn: () => readTransfers(userId) })
  const account = useQuery({ queryKey: ['qb-ext', 'payout', userId], queryFn: () => readPayoutAccount(userId) })
  const topUps = useQuery({ queryKey: ['qb-ext', 'topups', userId], queryFn: () => readTopUps(userId), enabled: sponsor })
  const transferColumns = useMemo<ColumnDef<TransferRow, unknown>[]>(() => [
    { id: 'createdAt', header: t('user.createdAt'), cell: ({ row }) => when(row.original.createdAt) },
    { id: 'amount', header: t('user.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'fee', header: t('user.fee'), meta: right, cell: ({ row }) => money(row.original.fee) },
    { id: 'net', header: t('user.net'), meta: right, cell: ({ row }) => money(row.original.net) },
    { id: 'status', header: t('user.transferStatus'), cell: ({ row }) => transferStatus(row.original.status) },
    { id: 'sentAt', header: t('user.sentAt'), cell: ({ row }) => when(row.original.sentAt) },
  ], [t, transferStatus])
  const topUpColumns = useMemo<ColumnDef<TopUpRow, unknown>[]>(() => [
    { id: 'createdAt', header: t('user.createdAt'), cell: ({ row }) => when(row.original.createdAt) },
    { id: 'amount', header: t('user.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'status', header: t('user.topUpStatus'), cell: ({ row }) => topUpStatus(row.original.status) },
    { id: 'paidAt', header: t('user.paidAt'), cell: ({ row }) => when(row.original.paidAt) },
    { id: 'refunded', header: t('user.refunded'), meta: right, cell: ({ row }) => money(row.original.refundedAmount) },
  ], [t, topUpStatus])

  let payout
  if (account.isError) payout = <LoadFailed error={account.error} onRetry={() => void account.refetch()} />
  else if (account.isPending) payout = <PageState kind="loading" />
  else if (!account.data) payout = <p className="text-muted-foreground text-sm">{t('user.noPayout')}</p>
  else {
    payout = (
      <DescriptionList columns={2} items={[
        { key: 'status', label: t('user.payoutStatus'), value: accountStatus(account.data.status) },
        { key: 'enabled', label: t('user.payoutsEnabled'),
          value: account.data.payoutsEnabled ? t('common.yes') : t('common.no') },
        { key: 'last4', label: t('user.accountLast4'), value: account.data.accountLast4 ?? '' },
        { key: 'createdAt', label: t('user.createdAt'), value: when(account.data.createdAt) },
      ]} />
    )
  }
  return (
    <Section title={t('user.finance')} testId="user-finance">
      <div className="flex flex-col gap-2">
        <h3 className="font-semibold">{t('user.transfers')}</h3>
        {transfers.isError ? <LoadFailed error={transfers.error} onRetry={() => void transfers.refetch()} /> : (
          <DataTable label={t('user.transfersTable')} columns={transferColumns} data={transfers.data ?? []}
            loading={transfers.isPending} getRowId={(row) => row.transferId} empty={t('user.noTransfers')} />
        )}
      </div>
      <div className="flex flex-col gap-2" data-testid="user-payout">
        <h3 className="font-semibold">{t('user.payout')}</h3>
        {payout}
      </div>
      {sponsor && (
        <div className="flex flex-col gap-2">
          <h3 className="font-semibold">{t('user.topUps')}</h3>
          {topUps.isError ? <LoadFailed error={topUps.error} onRetry={() => void topUps.refetch()} /> : (
            <DataTable label={t('user.topUpsTable')} columns={topUpColumns} data={topUps.data ?? []}
              loading={topUps.isPending} getRowId={(row) => row.topUpId} empty={t('user.noTopUps')} />
          )}
        </div>
      )}
    </Section>
  )
}

/**
 * One user's summary (P-01): the profile, country, registration, ban and sponsor state, and the attempts; the money
 * (transfers, payout account, top-ups) only for those who read finance (`qb.admin.finance.read`): it is neither shown
 * nor asked for otherwise, and the server refuses those templates to anyone else.
 */
export default function UserSummaryPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { userId = '' } = useParams()
  const { can } = useAuth()
  const user = useQuery({ queryKey: ['qb-ext', 'user', userId], queryFn: () => readUser(userId) })
  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.users}><ArrowLeft aria-hidden />{t('user.back')}</Link>
    </Button>
  )
  if (user.isPending) return <PageState kind="loading" />
  if (user.isError) return <LoadFailed error={user.error} onRetry={() => void user.refetch()} />
  if (!user.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('user.notFound')} /></div>
  }
  return (
    <div className="flex flex-col gap-4" data-testid="user-summary">
      <div>{back}</div>
      <PageHeader title={user.data.displayName ?? user.data.userName} description={user.data.userName} className="pb-0" />
      <Profile user={user.data} />
      <Attempts userId={userId} />
      {can(PERMISSION.financeRead) && <Finance userId={userId} sponsor={!!user.data.sponsorStatus} />}
    </div>
  )
}
