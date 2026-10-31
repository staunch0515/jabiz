import { useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApprovalPanel,
  Button,
  DescriptionList,
  EXTENSION_NAMESPACE,
  PageHeader,
  PageState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  UserName,
  useAuth,
} from '@jabiz/admin'
import {
  readAttemptRewards,
  readBan,
  readPayoutAccount,
  readUser,
  type AttemptRewardRow,
  type Ban,
  type PayoutAccount,
  type UserRow,
} from './api'
import { readTakerWallet, readTransfer, type FinanceTransfer, type TakerWallet } from './finance-api'
import { AfterDecision, LoadFailed, Section, StatusBadge } from './components'
import { money, useAfterDecision, useLabels, when } from './hooks'
import { DICTIONARY, isUserId, PATH, PERMISSION, TRANSFER_REVIEW } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/** How many of the payee's latest rewards the page shows. */
export const RECENT_REWARDS = 10

/** Everything about the payee, read together for the review request the transfer waited for. */
interface Payee {
  requestId: string | null
  user: UserRow | null
  wallet: TakerWallet | null
  ban: Ban | null
  account: PayoutAccount | null
  rewards: AttemptRewardRow[]
}

async function readPayee(userId: string, requestId: string | null): Promise<Payee> {
  const [user, wallet, ban, account, rewards] = await Promise.all([readUser(userId), readTakerWallet(userId),
    readBan(userId), readPayoutAccount(userId), readAttemptRewards(userId, 0, RECENT_REWARDS)])
  return { requestId, user, wallet, ban, account, rewards: rewards.items }
}

function TransferFacts({ transfer }: { transfer: FinanceTransfer }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <Section title={t('finance.review.transfer')} testId="review-transfer">
      <DescriptionList columns={3} items={[
        { key: 'amount', label: t('finance.transfers.amount'), value: <span data-testid="review-amount">{money(transfer.amount)}</span> },
        { key: 'fee', label: t('finance.transfers.fee'), value: money(transfer.fee) },
        { key: 'net', label: t('finance.transfers.net'), value: money(transfer.net) },
        { key: 'account', label: t('finance.transfers.account'), value: transfer.accountLast4
          ? t('finance.transfers.last4', { last4: transfer.accountLast4 }) : t('common.notSet') },
        { key: 'createdAt', label: t('finance.transfers.createdAt'), value: when(transfer.createdAt) },
      ]} />
    </Section>
  )
}

function PayeeFacts({ userId, payee }: { userId: string; payee: UseQueryResult<Payee> }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const accountStatus = useLabels(DICTIONARY.payoutAccountStatus)
  const rewardStatus = useLabels(DICTIONARY.rewardStatus)
  let body: ReactNode
  if (payee.isError) body = <LoadFailed error={payee.error} onRetry={() => void payee.refetch()} />
  else if (payee.isPending) body = <PageState kind="loading" />
  else {
    const p = payee.data
    const notSet = t('common.notSet')
    body = (
      <>
        <DescriptionList columns={3} items={[
          { key: 'user', label: t('finance.review.payee'), value: (
            <Link to={PATH.user(userId)} className={linkClass} data-testid="review-payee">
              {p.user?.displayName ?? p.user?.userName ?? <UserName id={userId} />}
            </Link>) },
          { key: 'email', label: t('user.email'), value: p.user?.email ?? notSet },
          { key: 'registeredAt', label: t('user.registeredAt'), value: when(p.user?.registeredAt) || notSet },
          { key: 'balance', label: t('finance.review.balance'), value: money(p.wallet?.balance ?? '0') },
          { key: 'out', label: t('finance.review.pendingOut'), value: money(p.wallet?.pendingOut ?? '0') },
          { key: 'ban', label: t('user.ban'), value: (
            <span data-testid="review-ban" data-banned={p.user?.banned === true}>
              {p.user?.banned ? t('user.banned', { time: when(p.ban?.bannedAt) || '?' }) : t('user.notBanned')}
            </span>) },
          { key: 'account', label: t('user.payoutStatus'), value: p.account
            ? `${accountStatus(p.account.status)}${p.account.accountLast4
              ? ` · ${t('finance.transfers.last4', { last4: p.account.accountLast4 })}` : ''}`
            : t('user.noPayout') },
        ]} />
        <div className="flex flex-col gap-2">
          <h3 className="font-semibold">{t('finance.review.rewards', { n: RECENT_REWARDS })}</h3>
          {p.rewards.length === 0
            ? <p className="text-muted-foreground text-sm">{t('user.noRewards')}</p>
            : (
              <Table aria-label={t('finance.review.rewardsTable')} data-testid="review-rewards">
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('user.publication')}</TableHead>
                    <TableHead>{t('user.createdAt')}</TableHead>
                    <TableHead className="text-right">{t('user.reward')}</TableHead>
                    <TableHead className="text-right">{t('user.fee')}</TableHead>
                    <TableHead>{t('user.rewardStatus')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {p.rewards.map((reward) => (
                    <TableRow key={reward.attemptId} data-testid="review-reward">
                      <TableCell>{reward.name ?? ''}</TableCell>
                      <TableCell>{when(reward.submittedAt)}</TableCell>
                      <TableCell className="text-right tabular-nums">{money(reward.reward)}</TableCell>
                      <TableCell className="text-right tabular-nums">{money(reward.fee)}</TableCell>
                      <TableCell>{rewardStatus(reward.rewardStatus)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
        </div>
      </>
    )
  }
  return <Section title={t('finance.review.payeeTitle')} testId="review-payee-section">{body}</Section>
}

/**
 * A large transfer waiting for review (P-03): the transfer (who, how much, to which account: what the approval is
 * bound to) and its payee (account, balance, latest rewards, ban, payout account), with the platform's
 * `ApprovalPanel`. As on the publication's review, the decision is offered only while the transfer and everything
 * about the payee are shown as read for the request the transfer waits for (none failed or is being read again);
 * these reads do not follow the window's focus. After the decision the page reads the transfer again until its
 * state follows. The payee prepared the request: the platform refuses their own decision.
 */
export default function TransferReviewPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { transferId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const after = useAfterDecision()
  const statusLabel = useLabels(DICTIONARY.transferStatus)
  const known = isUserId(transferId)

  const transfer = useQuery({
    queryKey: ['qb-ext', 'finance', 'transfer', transferId],
    queryFn: () => readTransfer(transferId),
    enabled: known,
    refetchInterval: (query) => after.interval(query.state.data?.status === TRANSFER_REVIEW),
    refetchOnWindowFocus: false,
  })
  const requestId = transfer.data?.approvalRequestId ?? null
  const userId = transfer.data?.userId ?? ''
  const payee = useQuery({
    queryKey: ['qb-ext', 'finance', 'payee', transferId, requestId],
    queryFn: () => readPayee(userId, requestId),
    enabled: transfer.isSuccess && !!transfer.data,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })

  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.transfers}><ArrowLeft aria-hidden />{t('finance.review.back')}</Link>
    </Button>
  )
  if (known && transfer.isPending) return <PageState kind="loading" />
  if (transfer.isError) return <LoadFailed error={transfer.error} onRetry={() => void transfer.refetch()} />
  if (!known || !transfer.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('finance.review.notFound')} /></div>
  }
  const tr = transfer.data
  const pending = tr.status === TRANSFER_REVIEW && !!tr.approvalRequestId
  // The approval is bound to the account the transfer was made for; the payee may have another one by now.
  const accountChanged = payee.isSuccess && (payee.data.account?.accountId ?? null) !== tr.accountId
  // Everything the reviewer decides on, settled and read for the request the transfer waits for.
  const everythingShown = transfer.isSuccess && !transfer.isFetching
    && payee.isSuccess && !payee.isFetching && payee.data.requestId === tr.approvalRequestId

  const decided = (status: string) => {
    after.decide(status)
    void transfer.refetch()
    void queryClient.invalidateQueries({ queryKey: ['qb-ext', 'finance', 'transfers'] })
    void queryClient.invalidateQueries({ queryKey: ['qb-ext', 'finance', 'summary'] })
  }

  let decision: ReactNode
  if (after.decided && (pending || after.levelOnly)) {
    decision = <AfterDecision after={after} onRetry={() => {
      after.retry()
      void transfer.refetch()
    }} />
  } else if (!pending) {
    decision = (
      <p data-testid="review-not-pending">{t('finance.review.notPending', { status: statusLabel(tr.status) })}</p>
    )
  } else if (!can(PERMISSION.payoutReview)) {
    decision = <p className="text-muted-foreground text-sm">{t('finance.review.notAllowed')}</p>
  } else if (!everythingShown) {
    decision = <p className="text-muted-foreground text-sm" data-testid="review-needs-data">{t('finance.review.needsData')}</p>
  } else {
    decision = (
      <>
        <p className="text-muted-foreground text-sm">{t('finance.review.decisionHint')}</p>
        <ApprovalPanel requestId={tr.approvalRequestId!} onDecided={decided} />
      </>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-testid="transfer-review" data-status={tr.status}>
      <div>{back}</div>
      <PageHeader
        title={t('finance.review.title')}
        className="pb-0"
        description={
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span>{t('finance.review.payee')}: <UserName id={tr.userId} /></span>
            <StatusBadge code={tr.status} label={statusLabel(tr.status)} testId="review-status" />
          </span>
        }
      />
      <TransferFacts transfer={tr} />
      {accountChanged && (
        <Alert variant="warning" data-testid="review-account-changed">
          <AlertDescription>{t('finance.review.accountChanged', {
            bound: tr.accountLast4 ?? '?', current: payee.data?.account?.accountLast4 ?? t('common.none') })}
          </AlertDescription>
        </Alert>
      )}
      <PayeeFacts userId={tr.userId} payee={payee} />
      <Section title={t('finance.review.decision')} testId="review-decision">{decision}</Section>
    </div>
  )
}
