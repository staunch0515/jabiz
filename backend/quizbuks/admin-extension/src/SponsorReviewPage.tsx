import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApprovalPanel,
  Badge,
  Button,
  Checkbox,
  DescriptionList,
  Input,
  Label,
  notify,
  EXTENSION_NAMESPACE,
  FilePreview,
  MarkdownView,
  PageHeader,
  PageState,
  UserName,
  useAuth,
  type DescriptionItem,
} from '@jabiz/admin'
import { readSponsor, readUser } from './api'
import { readSponsorMethods, REWARD_METHODS, setSponsorMethods, type RewardMethod } from './reward-api'
import { AVATAR_VARIANTS, DICTIONARY, isUserId, PATH, PERMISSION, SPONSOR_PENDING } from './ids'
import { AfterDecision, LoadFailed, Section } from './components'
import { QUEUE, useAfterDecision, useLabels, when } from './hooks'

const AVATAR = { image: true, variants: AVATAR_VARIANTS }
const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/**
 * The reward methods the sponsor may publish with (Q22, `QB_SPONSOR_REWARD_METHODS_SET`): the platform's Kudos alone
 * until an administrator allows cash or prizes, which the sponsor hands over themselves. Shown to everyone who sees
 * the sponsor; changed only with the permission.
 */
function RewardMethods({ userId }: { userId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const id = useId()
  const queryClient = useQueryClient()
  const methodLabel = useLabels(DICTIONARY.rewardMethod)
  const setting = useQuery({ queryKey: ['qb-ext', 'sponsor-methods', userId], queryFn: () => readSponsorMethods(userId) })
  const current = setting.data?.methods ?? ['PLATFORM']
  const [chosen, setChosen] = useState<RewardMethod[] | null>(null)
  const [note, setNote] = useState('')
  const methods = chosen ?? current
  const save = useMutation({
    mutationFn: () => setSponsorMethods(userId, methods, note),
    onSuccess: async () => {
      notify.success(t('reward.methods.saved'))
      setChosen(null)
      setNote('')
      await queryClient.invalidateQueries({ queryKey: ['qb-ext', 'sponsor-methods', userId] })
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (methods.length > 0 && !save.isPending) save.mutate()
  }
  const toggle = (method: RewardMethod, on: boolean) =>
    setChosen(REWARD_METHODS.filter((m) => (m === method ? on : methods.includes(m))))
  if (setting.isError) return <LoadFailed error={setting.error} onRetry={() => void setting.refetch()} />
  return (
    <Section title={t('reward.methods.title')} testId="sponsor-methods">
      <p className="text-muted-foreground text-sm">{t('reward.methods.hint')}</p>
      <p className="text-sm" data-testid="sponsor-methods-current">
        {t('reward.methods.current', { methods: current.map(methodLabel).join(', ') })}
        {setting.data?.setBy && (
          <span className="text-muted-foreground"> · {t('reward.methods.setBy')} <UserName id={setting.data.setBy} />
            {setting.data.setAt ? `, ${when(setting.data.setAt)}` : ''}</span>
        )}
      </p>
      {can(PERMISSION.rewardMethods) ? (
        <form noValidate className="flex flex-col gap-3" onSubmit={submit}>
          <fieldset className="flex flex-col gap-2">
            <legend className="sr-only">{t('reward.methods.title')}</legend>
            {REWARD_METHODS.map((method) => (
              <div key={method} className="flex items-center gap-2">
                <Checkbox id={`${id}-${method}`} checked={methods.includes(method)}
                  data-testid={`sponsor-method-${method}`} onCheckedChange={(value) => toggle(method, value === true)} />
                <Label htmlFor={`${id}-${method}`} className="font-normal">{methodLabel(method)}</Label>
              </div>
            ))}
          </fieldset>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-note`}>{t('reward.methods.note')}</Label>
            <Input id={`${id}-note`} value={note} maxLength={500} aria-describedby={`${id}-note-hint`}
              data-testid="sponsor-methods-note" onChange={(event) => setNote(event.target.value)} />
            <p id={`${id}-note-hint`} className="text-muted-foreground text-sm">{t('reward.methods.noteHint')}</p>
          </div>
          {methods.length === 0 && <p className="text-destructive text-sm">{t('reward.methods.none')}</p>}
          {save.isError && (
            <Alert variant="destructive"><AlertDescription>{save.error instanceof Error ? save.error.message : String(save.error)}</AlertDescription></Alert>
          )}
          <div>
            <Button type="submit" size="sm" disabled={save.isPending || methods.length === 0}
              aria-busy={save.isPending || undefined} data-testid="sponsor-methods-save">
              {t('reward.methods.save')}
            </Button>
          </div>
        </form>
      ) : (
        <p className="text-muted-foreground text-sm">{t('reward.methods.notAllowed')}</p>
      )}
    </Section>
  )
}

/**
 * A sponsor's application as the reviewer decides it (P-09): the contact name, company, introduction and picture the
 * approval is bound to, when the terms were accepted, and the account's address and whether it is verified. The
 * decision is the platform's `ApprovalPanel`, offered while the application waits; afterwards the page reads again
 * until the state follows it.
 */
export default function SponsorReviewPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { userId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const after = useAfterDecision()
  const statusLabel = useLabels(DICTIONARY.sponsorReviewStatus)
  const sponsor = useQuery({
    queryKey: ['qb-ext', 'sponsor', userId],
    queryFn: () => readSponsor(userId),
    refetchInterval: (query) => after.interval(query.state.data?.reviewStatus === SPONSOR_PENDING),
    refetchOnWindowFocus: false,
  })
  const user = useQuery({ queryKey: ['qb-ext', 'user', userId], queryFn: () => readUser(userId), enabled: isUserId(userId) })

  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={`${PATH.reviews}?tab=sponsors`}><ArrowLeft aria-hidden />{t('sponsor.back')}</Link>
    </Button>
  )
  if (sponsor.isPending) return <PageState kind="loading" />
  if (sponsor.isError) return <LoadFailed error={sponsor.error} onRetry={() => void sponsor.refetch()} />
  if (!sponsor.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('sponsor.notFound')} /></div>
  }
  const s = sponsor.data
  const pending = s.reviewStatus === SPONSOR_PENDING && !!s.approvalRequestId
  const notSet = t('common.notSet')
  const email = user.data?.email
    ? `${user.data.email} (${user.data.emailVerified ? t('sponsor.verified') : t('sponsor.notVerified')})`
    : notSet
  const items: DescriptionItem[] = [
    { key: 'contactName', label: t('sponsor.contactName'), value: s.contactName ?? notSet },
    { key: 'company', label: t('sponsor.company'), value: s.company ?? notSet },
    { key: 'email', label: t('sponsor.email'), value: <span data-testid="sponsor-email">{email}</span> },
    { key: 'terms', label: t('sponsor.termsAcceptedAt'), value: when(s.termsAcceptedAt) || notSet },
    { key: 'submittedAt', label: t('sponsor.submittedAt'), value: when(s.submittedAt) || notSet },
    ...(s.approvedAt ? [{ key: 'approvedAt', label: t('sponsor.approvedAt'), value: when(s.approvedAt) }] : []),
    ...(s.rejectReason ? [{ key: 'reason', label: t('sponsor.rejectReason'), value: s.rejectReason }] : []),
  ]

  const decided = (status: string) => {
    after.decide(status)
    void sponsor.refetch()
    void queryClient.invalidateQueries({ queryKey: QUEUE.sponsors })
  }
  let decision
  if (after.decided && (pending || after.levelOnly)) {
    decision = <AfterDecision after={after} onRetry={() => {
      after.retry()
      void sponsor.refetch()
    }} />
  } else if (!pending) {
    decision = <p data-testid="sponsor-not-pending">{t('sponsor.notPending', { status: statusLabel(s.reviewStatus) })}</p>
  } else if (!can(PERMISSION.reviewSponsor)) {
    decision = <p className="text-muted-foreground text-sm">{t('sponsor.notAllowed')}</p>
  } else {
    decision = <ApprovalPanel requestId={s.approvalRequestId!} onDecided={decided} />
  }

  return (
    <div className="flex flex-col gap-4" data-testid="sponsor-review" data-status={s.reviewStatus}>
      <div>{back}</div>
      <PageHeader
        title={s.company ?? s.contactName ?? <UserName id={userId} />}
        className="pb-0"
        description={
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <Link to={PATH.user(userId)} className={linkClass} data-testid="sponsor-summary">{t('sponsor.summary')}</Link>
            <Badge variant={pending ? 'secondary' : 'outline'} data-testid="sponsor-status">
              {statusLabel(s.reviewStatus)}
            </Badge>
          </span>
        }
      />
      <Section title={t('sponsor.application')} testId="sponsor-application">
        <DescriptionList items={items} columns={2} />
        <div className="flex flex-col gap-1">
          <h3 className="font-semibold">{t('sponsor.avatar')}</h3>
          {s.avatar ? <FilePreview fileId={s.avatar} field={AVATAR} width={128} />
            : <p className="text-muted-foreground text-sm">{t('sponsor.noAvatar')}</p>}
        </div>
        <div className="flex flex-col gap-1" data-testid="sponsor-intro">
          <h3 className="font-semibold">{t('sponsor.intro')}</h3>
          {s.intro ? <MarkdownView text={s.intro} /> : <p className="text-muted-foreground text-sm">{notSet}</p>}
        </div>
      </Section>
      <Section title={t('publication.decision')} testId="sponsor-decision">{decision}</Section>
      <RewardMethods userId={userId} />
    </div>
  )
}
