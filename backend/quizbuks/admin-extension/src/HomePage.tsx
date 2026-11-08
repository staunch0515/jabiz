import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  PageHeader,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, Section, StatusBadge } from './components'
import { HOME_KEY, readHealth, readWorkload, type HealthProblem, type Workload } from './home-api'
import { useLabels, usePaged } from './hooks'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { useAcrossOperators } from './operator-filter'
import OperatorHomePage from './OperatorHomePage'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { name: 'name', pending: 'pending', decided: 'decided' } as const
/** The decisions counted on the home page: the last 30 days. */
const DECIDED_DAYS = 30

/** One problem of the daily check, in words, with where to put it right. */
function Problem({ problem }: { problem: HealthProblem }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const where = problem.operatorId
    ? <Link to={PATH.operator(problem.operatorId)} className={linkClass}>{problem.operatorName ?? problem.operatorId}</Link>
    : problem.sponsorId ? <Link to={PATH.user(problem.sponsorId)} className={linkClass}><UserName id={problem.sponsorId} /></Link>
      : null
  return (
    <li className="flex flex-wrap items-baseline gap-2" data-testid="home-problem" data-code={problem.checkCode}>
      <span>{t(`home.problems.${problem.checkCode}`, { defaultValue: problem.checkCode, count: problem.found })}</span>
      {where}
    </li>
  )
}

/**
 * The daily check's problems (`qb.admin.operator-health`): without a default operator nobody can apply as a Sponsor,
 * so that one comes first, with the way to create one.
 */
function Health() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const health = useQuery({ queryKey: [...HOME_KEY, 'health'], queryFn: readHealth })
  if (health.isError) return <LoadFailed error={health.error} onRetry={() => void health.refetch()} />
  const problems = health.data ?? []
  const noDefault = problems.some((problem) => problem.checkCode === 'NO_DEFAULT_OPERATOR')
  const others = problems.filter((problem) => problem.checkCode !== 'NO_DEFAULT_OPERATOR')
  return (
    <>
      {noDefault && (
        <Alert variant="destructive" data-testid="home-no-default">
          <AlertTitle>{t('home.noDefaultTitle')}</AlertTitle>
          <AlertDescription className="flex flex-col items-start gap-2">
            <span>{t('home.noDefaultText')}</span>
            <Button asChild size="sm"><Link to={PATH.operators}>{t('home.noDefaultAction')}</Link></Button>
          </AlertDescription>
        </Alert>
      )}
      <Section title={t('home.problemsTitle')} testId="home-problems">
        {health.isPending ? null : others.length === 0 && !noDefault
          ? <p className="text-muted-foreground text-sm" data-testid="home-all-well">{t('home.allWell')}</p>
          : others.length > 0 && <ul className="flex flex-col gap-1 text-sm">{others.map((problem) => (
            <Problem key={`${problem.checkCode}-${problem.operatorId ?? ''}-${problem.sponsorId ?? ''}`}
              problem={problem} />
          ))}</ul>}
      </Section>
    </>
  )
}

/** Each operator's reviews waiting now and decided in the last 30 days, the most waiting first, page by page. */
function WorkloadTable() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const status = useLabels(DICTIONARY.operatorStatus)
  // Fixed for the page's life, so that its pages read one period.
  const [since] = useState(() => new Date(Date.now() - DECIDED_DAYS * 24 * 3_600_000).toISOString())
  const { query, table, sortable } = usePaged([...HOME_KEY, 'workload', since],
    (offset, limit, sorts) => readWorkload(since, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<Workload, unknown>[]>(() => [
    { id: 'name', header: t('home.workload.operator'), cell: ({ row }) => (
      <span className="flex flex-wrap items-center gap-2">
        <Link to={PATH.operator(row.original.operatorId)} className={linkClass} data-testid="workload-operator">
          {row.original.name}
        </Link>
        {row.original.defaultForNew && <Badge variant="secondary">{t('operator.detail.default')}</Badge>}
      </span>
    ) },
    { id: 'status', header: t('operator.fields.status'),
      cell: ({ row }) => <StatusBadge code={row.original.status} label={status(row.original.status)} /> },
    { id: 'pending', header: t('home.workload.pending'), meta: right,
      cell: ({ row }) => <span data-testid="workload-pending">{row.original.pending}</span> },
    { id: 'decided', header: t('home.workload.decided', { days: DECIDED_DAYS }), meta: right,
      cell: ({ row }) => <span data-testid="workload-decided">{row.original.decided}</span> },
  ], [t, status])
  return (
    <Section title={t('home.workload.title')} testId="home-workload">
      <p className="text-muted-foreground text-sm">{t('home.workload.hint')}</p>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : <DataTable label={t('home.workload.title')} columns={sortable(columns)} {...table}
          getRowId={(row) => row.operatorId} empty={<EmptyState art="people" title={t('operator.list.none')} />}
          rowProps={(row) => ({ 'data-testid': 'workload-row', 'data-id': row.operatorId })} />}
    </Section>
  )
}

/**
 * Where a session lands (F-S1b): in the Operator Management System, the operator's own home; in the platform's, the
 * operators' structure (the daily check, a default operator first) and their reviews, for who reads the operators.
 */
export default function HomePage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const across = useAcrossOperators()
  if (!across) return <OperatorHomePage />
  return (
    <div className="flex flex-col gap-4" data-testid="qb-home">
      <PageHeader title={t('home.title')} description={t('home.description')} className="pb-0" />
      {can(PERMISSION.operatorsRead)
        ? <><Health /><WorkloadTable /></>
        : <p className="text-muted-foreground" data-testid="home-menu-hint">{t('home.menuHint')}</p>}
    </div>
  )
}
