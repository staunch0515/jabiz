import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  Checkbox,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageHeader,
  PageState,
  runQuery,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import {
  currentMonthInJapan,
  laterChanges,
  nextMonth,
  planInEffect,
  PLANS,
  readOperatorPlans,
  setOperatorPlan,
  type OperatorPlanChange,
} from './account-api'
import { LoadFailed, Section } from './components'
import { useLabels, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION, TEMPLATE } from './ids'
import { OperatorFields } from './operator-components'
import {
  addMember,
  assignSponsor,
  OPERATOR_STATUSES,
  OPERATORS_KEY,
  readMembers,
  readOperator,
  removeMember,
  saveOperator,
  type Operator,
  type OperatorLanguage,
  type OperatorMember,
  type OperatorStatus,
} from './operator-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/** What a failed call says, as the server words it. */
const problemOf = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

/** The operator's own fields and state, changed with the revision the page read (`QB_OPERATOR_SAVE`). */
function Details({ operator }: { operator: Operator }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const id = useId()
  const queryClient = useQueryClient()
  const status = useLabels(DICTIONARY.operatorStatus)
  const languages = useLabels(DICTIONARY.contentLanguage)
  const [values, setValues] = useState({ name: operator.name, language: operator.language ?? '',
    contactName: operator.contactName,
    contactEmail: operator.contactEmail, note: operator.note ?? '' })
  const [state, setState] = useState<OperatorStatus>(operator.status)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const writable = can(PERMISSION.operatorsWrite)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setProblem(null)
    try {
      await saveOperator({ operatorId: operator.operatorId, name: values.name.trim(),
        contactName: values.contactName.trim(), contactEmail: values.contactEmail.trim(),
        language: (values.language || null) as OperatorLanguage | null, status: state,
        note: values.note.trim() || null, baseRevision: operator.revision })
      notify.success(t('operator.detail.saved'))
      await queryClient.invalidateQueries({ queryKey: OPERATORS_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  if (!writable) {
    return (
      <Section title={t('operator.detail.details')} testId="operator-details">
        <p>{operator.contactName} · {operator.contactEmail}</p>
        {operator.language && <p className="text-sm">{t('operator.fields.language')}: {languages(operator.language)}</p>}
        {operator.note && <p className="text-muted-foreground text-sm">{operator.note}</p>}
      </Section>
    )
  }
  return (
    <Section title={t('operator.detail.details')} testId="operator-details">
      <form noValidate className="flex max-w-xl flex-col gap-4" onSubmit={(event) => void submit(event)}>
        <OperatorFields id={id} {...values}
          onChange={(field, value) => setValues((current) => ({ ...current, [field]: value }))} />
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-status`}>{t('operator.fields.status')}</Label>
          <Select value={state} onValueChange={(value) => setState(value as OperatorStatus)}>
            <SelectTrigger id={`${id}-status`} className="w-fit" aria-describedby={`${id}-status-hint`}
              data-testid="operator-status">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {OPERATOR_STATUSES.map((value) => <SelectItem key={value} value={value}>{status(value)}</SelectItem>)}
            </SelectContent>
          </Select>
          <p id={`${id}-status-hint`} className="text-muted-foreground text-sm">{t('operator.detail.statusHint')}</p>
        </div>
        {problem && <Alert variant="destructive" data-testid="operator-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
        <div>
          <Button type="submit" disabled={busy} aria-busy={busy || undefined} data-testid="operator-save">
            {t('operator.detail.save')}
          </Button>
        </div>
      </form>
    </Section>
  )
}

/**
 * The members, current and removed (`qb.admin.operator-members`): naming one by their user id, making them the lead
 * and removing them. A member signs in only with the role `QB_OPERATOR`, which the platform's role assignment gives
 * with another administrator's approval: the table says who lacks it.
 */
function Members({ operatorId }: { operatorId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const id = useId()
  const queryClient = useQueryClient()
  const key = [...OPERATORS_KEY, operatorId, 'members']
  const members = useQuery({ queryKey: key, queryFn: () => readMembers(operatorId) })
  const [userId, setUserId] = useState('')
  const [lead, setLead] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const writable = can(PERMISSION.operatorsWrite)
  const act = async (call: () => Promise<unknown>, done: string) => {
    setBusy(true)
    setProblem(null)
    try {
      await call()
      notify.success(done)
      await queryClient.invalidateQueries({ queryKey: OPERATORS_KEY })
      return true
    } catch (e) {
      setProblem(problemOf(e))
      return false
    } finally {
      setBusy(false)
    }
  }
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !userId.trim()) return
    if (await act(() => addMember(operatorId, userId.trim(), lead), t('operator.members.added'))) {
      setUserId('')
      setLead(false)
    }
  }
  const columns: ColumnDef<OperatorMember, unknown>[] = [
    { id: 'name', header: t('operator.members.name'),
      cell: ({ row }) => row.original.displayName ?? row.original.userName ?? <UserName id={row.original.userId} /> },
    { id: 'email', header: t('operator.members.email'), cell: ({ row }) => row.original.email ?? '' },
    {
      id: 'state',
      header: t('operator.members.state'),
      cell: ({ row }) => (
        <span className="flex flex-wrap gap-1">
          {row.original.lead && <Badge variant="secondary">{t('operator.members.lead')}</Badge>}
          {row.original.removedAt
            ? <Badge variant="outline">{t('operator.members.removed', { at: when(row.original.removedAt) })}</Badge>
            : !row.original.hasRole && (
              <Badge variant="destructive" data-testid="member-no-role">{t('operator.members.noRole')}</Badge>
            )}
        </span>
      ),
    },
    { id: 'addedAt', header: t('operator.members.addedAt'), cell: ({ row }) => when(row.original.addedAt) },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('operator.members.remove')}</span>,
      cell: ({ row }) => (writable && !row.original.removedAt ? (
        <span className="flex justify-end">
          <Button size="sm" variant="outline" disabled={busy} data-testid="member-remove"
            onClick={() => void act(() => removeMember(row.original.userId), t('operator.members.removedDone'))}>
            {t('operator.members.remove')}
            <span className="sr-only">: {row.original.displayName ?? row.original.userId}</span>
          </Button>
        </span>
      ) : null),
    },
  ]
  return (
    <Section title={t('operator.members.title')} testId="operator-members">
      <p className="text-muted-foreground text-sm">{t('operator.members.hint')}</p>
      {members.isError
        ? <LoadFailed error={members.error} onRetry={() => void members.refetch()} />
        : (
          <DataTable label={t('operator.members.title')} columns={columns} data={members.data ?? []}
            loading={members.isPending} getRowId={(row) => row.memberId} empty={t('operator.members.none')}
            rowProps={(row) => ({ 'data-testid': 'member-row', 'data-id': row.userId })} />
        )}
      {writable && (
        <form noValidate className="flex flex-wrap items-end gap-4" onSubmit={(event) => void submit(event)}>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-user`}>{t('operator.members.userId')}</Label>
            <Input id={`${id}-user`} value={userId} maxLength={128} autoComplete="off" className="w-80"
              aria-describedby={`${id}-user-hint`} data-testid="member-user"
              onChange={(event) => setUserId(event.target.value)} />
            <p id={`${id}-user-hint`} className="text-muted-foreground text-sm">{t('operator.members.userIdHint')}</p>
          </div>
          <div className="flex items-center gap-2 pb-7">
            <Checkbox id={`${id}-lead`} checked={lead} data-testid="member-lead"
              onCheckedChange={(value) => setLead(value === true)} />
            <Label htmlFor={`${id}-lead`} className="font-normal">{t('operator.members.makeLead')}</Label>
          </div>
          <div className="pb-7">
            <Button type="submit" disabled={busy || !userId.trim()} aria-busy={busy || undefined}
              data-testid="member-add">{t('operator.members.add')}</Button>
          </div>
        </form>
      )}
      {problem && <Alert variant="destructive" data-testid="member-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
    </Section>
  )
}

/** A row of `qb.admin.sponsors` the sponsors' table lists. */
interface AssignedSponsor {
  userId: string
  company: string | null
  reviewStatus: string
}

/**
 * The operator's sponsors (`qb.admin.sponsors` by operator) and moving one here, or back to headquarters, by their
 * user id with a reason (`QB_SPONSOR_OPERATOR_ASSIGN`; the audit keeps the history).
 */
function Sponsors({ operatorId }: { operatorId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const id = useId()
  const queryClient = useQueryClient()
  const review = useLabels(DICTIONARY.sponsorReviewStatus)
  const key = [...OPERATORS_KEY, operatorId, 'sponsors']
  const readable = can(PERMISSION.usersRead)
  const sponsors = useQuery({
    queryKey: key,
    enabled: readable,
    queryFn: async (): Promise<AssignedSponsor[]> => {
      const page = await runQuery<Record<string, unknown>>(TEMPLATE.sponsors, {
        filters: [{ field: 'operatorId', op: 'eq', value: operatorId }], limit: 100 })
      return page.items.map((row) => ({ userId: String(row.userId), company: row.company == null ? null
        : String(row.company), reviewStatus: String(row.reviewStatus ?? '') }))
    },
  })
  const [userId, setUserId] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const move = async (toHere: boolean, sponsorId: string) => {
    if (busy || !sponsorId || !reason.trim()) return
    setBusy(true)
    setProblem(null)
    try {
      await assignSponsor(sponsorId, toHere ? operatorId : null, reason.trim())
      notify.success(t(toHere ? 'operator.sponsors.assigned' : 'operator.sponsors.returned'))
      setUserId('')
      setReason('')
      await queryClient.invalidateQueries({ queryKey: OPERATORS_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  const columns = useMemo<ColumnDef<AssignedSponsor, unknown>[]>(() => [
    {
      id: 'company',
      header: t('operator.sponsors.company'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.userId)} className={linkClass}>
          {row.original.company ?? <UserName id={row.original.userId} />}
        </Link>
      ),
    },
    { id: 'review', header: t('operator.sponsors.review'), cell: ({ row }) => review(row.original.reviewStatus) },
  ], [t, review])
  return (
    <Section title={t('operator.sponsors.title')} testId="operator-sponsors">
      <p className="text-muted-foreground text-sm">{t('operator.sponsors.hint')}</p>
      {readable && (sponsors.isError
        ? <LoadFailed error={sponsors.error} onRetry={() => void sponsors.refetch()} />
        : (
          <DataTable label={t('operator.sponsors.title')} columns={columns} data={sponsors.data ?? []}
            loading={sponsors.isPending} getRowId={(row) => row.userId} empty={t('operator.sponsors.none')}
            rowProps={(row) => ({ 'data-testid': 'operator-sponsor-row', 'data-id': row.userId })} />
        ))}
      {can(PERMISSION.operatorsWrite) && (
        <form noValidate className="flex flex-col gap-4" onSubmit={(event) => {
          event.preventDefault()
          void move(true, userId.trim())
        }}>
          <div className="flex flex-wrap items-end gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-sponsor`}>{t('operator.sponsors.userId')}</Label>
              <Input id={`${id}-sponsor`} value={userId} maxLength={128} autoComplete="off" className="w-80"
                data-testid="assign-user" onChange={(event) => setUserId(event.target.value)} />
            </div>
            <div className="flex grow flex-col gap-2">
              <Label htmlFor={`${id}-reason`}>{t('operator.sponsors.reason')}</Label>
              <Input id={`${id}-reason`} value={reason} maxLength={500} autoComplete="off"
                aria-describedby={`${id}-reason-hint`} data-testid="assign-reason"
                onChange={(event) => setReason(event.target.value)} />
            </div>
          </div>
          <p id={`${id}-reason-hint`} className="text-muted-foreground text-sm">{t('operator.sponsors.reasonHint')}</p>
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={busy || !userId.trim() || !reason.trim()} aria-busy={busy || undefined}
              data-testid="assign-here">{t('operator.sponsors.assign')}</Button>
            <Button type="button" variant="outline" disabled={busy || !userId.trim() || !reason.trim()}
              data-testid="assign-back" onClick={() => void move(false, userId.trim())}>
              {t('operator.sponsors.returnToHq')}
            </Button>
          </div>
        </form>
      )}
      {problem && <Alert variant="destructive" data-testid="assign-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
    </Section>
  )
}

/**
 * The operator's plan (Q48e-2): the plan in effect this month (Japan's), its changes (`qb.admin.operator-plans`),
 * and proposing one from a later month with a reason (`QB_OPERATOR_PLAN_SET`): another administrator approves it in
 * the approvals.
 */
function Plan({ operatorId }: { operatorId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const id = useId()
  const queryClient = useQueryClient()
  const planLabel = useLabels(DICTIONARY.operatorPlan)
  const statusLabel = useLabels(DICTIONARY.operatorPlanStatus)
  const key = [...OPERATORS_KEY, operatorId, 'plans']
  const plans = useQuery({ queryKey: key, queryFn: () => readOperatorPlans(operatorId) })
  const month = currentMonthInJapan()
  const [plan, setPlan] = useState<string>(PLANS[1])
  const [effectiveMonth, setEffectiveMonth] = useState(nextMonth(month))
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const later = /^\d{4}-\d{2}$/.test(effectiveMonth) && effectiveMonth > month
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !later || !reason.trim()) return
    setBusy(true)
    setProblem(null)
    try {
      await setOperatorPlan({ operatorId, plan, effectiveMonth, reason: reason.trim() })
      notify.success(t('plan.proposed'))
      setReason('')
      await queryClient.invalidateQueries({ queryKey: key })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  const columns = useMemo<ColumnDef<OperatorPlanChange, unknown>[]>(() => [
    { id: 'plan', header: t('plan.plan'), cell: ({ row }) => planLabel(row.original.plan) },
    { id: 'effectiveMonth', header: t('plan.effectiveMonth'), cell: ({ row }) => row.original.effectiveMonth },
    { id: 'status', header: t('plan.status'), cell: ({ row }) => (
      <Badge variant={row.original.status === 'REJECTED' ? 'outline' : 'secondary'}>{statusLabel(row.original.status)}</Badge>
    ) },
    { id: 'reason', header: t('plan.reason'), cell: ({ row }) => row.original.reason ?? '' },
    { id: 'proposedBy', header: t('plan.proposedBy'), cell: ({ row }) => (
      <span className="flex flex-col">
        {row.original.proposedBy && <UserName id={row.original.proposedBy} />}
        <span className="text-muted-foreground text-sm">{when(row.original.proposedAt)}</span>
      </span>
    ) },
  ], [t, planLabel, statusLabel])
  const changes = plans.data ?? []
  const current = planInEffect(changes, month)
  const coming = laterChanges(changes, month)
  return (
    <Section title={t('plan.title')} testId="operator-plan">
      {plans.data && (
        <p data-testid="operator-plan-current" data-plan={current}>
          {t('plan.current', { month, plan: planLabel(current) })}
          {coming.length > 0 && ` ${t('plan.next', { month: coming[0].effectiveMonth, plan: planLabel(coming[0].plan),
            status: statusLabel(coming[0].status) })}`}
        </p>
      )}
      {plans.isError
        ? <LoadFailed error={plans.error} onRetry={() => void plans.refetch()} />
        : (
          <DataTable label={t('plan.changes')} columns={columns} data={changes} loading={plans.isPending}
            getRowId={(row) => row.planChangeId} empty={t('plan.none')}
            rowProps={(row) => ({ 'data-testid': 'plan-change-row', 'data-id': row.planChangeId })} />
        )}
      {can(PERMISSION.operatorsWrite) && (
        <form noValidate className="flex flex-col gap-4" onSubmit={(event) => void submit(event)}>
          <div className="flex flex-wrap items-end gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-plan`}>{t('plan.plan')}</Label>
              <Select value={plan} onValueChange={setPlan}>
                <SelectTrigger id={`${id}-plan`} className="w-40" data-testid="plan-select"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {PLANS.map((value) => <SelectItem key={value} value={value}>{planLabel(value)}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-month`}>{t('plan.effectiveMonth')}</Label>
              <Input id={`${id}-month`} type="month" value={effectiveMonth} min={nextMonth(month)} required
                className="w-44" data-testid="plan-month" onChange={(event) => setEffectiveMonth(event.target.value)} />
            </div>
            <div className="flex grow flex-col gap-2">
              <Label htmlFor={`${id}-reason`}>{t('plan.reason')}</Label>
              <Input id={`${id}-reason`} value={reason} maxLength={500} autoComplete="off" required
                data-testid="plan-reason" onChange={(event) => setReason(event.target.value)} />
            </div>
          </div>
          <p className="text-muted-foreground text-sm">{t('plan.hint')}</p>
          <div>
            <Button type="submit" disabled={busy || !later || !reason.trim()} aria-busy={busy || undefined}
              data-testid="plan-propose">{t('plan.propose')}</Button>
          </div>
        </form>
      )}
      {problem && <Alert variant="destructive" data-testid="plan-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
    </Section>
  )
}

/**
 * One operator (Q48a): its details and state, its plan (Q48e-2), its members and its sponsors. Suspending it keeps its members out of
 * the back office at once; its sponsors stay its own until moved.
 */
export default function OperatorPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { operatorId = '' } = useParams()
  const status = useLabels(DICTIONARY.operatorStatus)
  const operator = useQuery({ queryKey: [...OPERATORS_KEY, operatorId], queryFn: () => readOperator(operatorId) })
  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.operators}><ArrowLeft aria-hidden />{t('operator.detail.back')}</Link>
    </Button>
  )
  if (operator.isPending) return <PageState kind="loading" />
  if (operator.isError) return <LoadFailed error={operator.error} onRetry={() => void operator.refetch()} />
  if (!operator.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('operator.detail.notFound')} /></div>
  }
  const o = operator.data
  return (
    <div className="flex flex-col gap-4" data-testid="operator" data-status={o.status}>
      <div>{back}</div>
      <PageHeader title={o.name} className="pb-0" description={
        <Badge variant={o.status === 'ACTIVE' ? 'secondary' : 'outline'} data-testid="operator-state">
          {status(o.status)}
        </Badge>
      } />
      {/* Keyed by the revision: a saved change reads the operator again and starts the form from it. */}
      <Details key={o.revision} operator={o} />
      <Plan operatorId={o.operatorId} />
      <Members operatorId={o.operatorId} />
      <Sponsors operatorId={o.operatorId} />
    </div>
  )
}
