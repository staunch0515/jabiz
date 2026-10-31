import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CircleCheck, ClipboardCheck, ClipboardList, Megaphone, Radio, Store, Users, type LucideIcon } from 'lucide-react'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  notify,
  PageHeader,
  PageState,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, Metric, Section, StatusBadge } from './components'
import { useLabels, when } from './hooks'
import { DICTIONARY, PATH } from './ids'
import { dropMember, readOverview, readTeam, type Overview, type Team } from './operator-api'

const KEY = ['qb-ext', 'operator-home'] as const

type Member = Team['members'][number]

/** The totals of the operator's sponsors and of the whole platform (D-Q48-4: the takers in aggregate only). */
function Figures({ overview }: { overview: Overview }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const figures: { key: keyof Overview; icon: LucideIcon; to?: string }[] = [
    { key: 'mySponsors', icon: Store, to: `${PATH.operatorSponsors}?mine=true` },
    { key: 'myLivePublications', icon: Megaphone },
    { key: 'myCompletions', icon: CircleCheck },
    { key: 'sponsorsInReview', icon: ClipboardCheck, to: `${PATH.reviews}?tab=sponsors` },
    { key: 'publicationsInReview', icon: ClipboardList, to: PATH.reviews },
    { key: 'livePublications', icon: Radio },
    { key: 'sponsors', icon: Users, to: PATH.operatorSponsors },
  ]
  return (
    <Section title={t('operator.home.figures')} testId="operator-figures">
      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {figures.map(({ key, icon, to }) => (
          <Metric key={key} label={t(`operator.home.${key}`)} value={overview[key].toLocaleString()} icon={icon}
            to={to} testId={`figure-${key}`} />
        ))}
      </dl>
    </Section>
  )
}

/** The lead removes a member, after confirming (`QB_OPERATOR_MEMBER_DROP`). */
function DropDialog({ member, onClose }: { member: Member; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const name = member.displayName ?? member.email ?? member.userId
  const confirm = async () => {
    setBusy(true)
    setProblem(null)
    try {
      await dropMember(member.userId)
      notify.success(t('operator.home.removed', { name }))
      void queryClient.invalidateQueries({ queryKey: KEY })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="member-drop-dialog">
        <DialogHeader>
          <DialogTitle>{t('operator.home.removeTitle', { name })}</DialogTitle>
          <DialogDescription>{t('operator.home.removeHint')}</DialogDescription>
        </DialogHeader>
        {problem && <Alert variant="destructive"><AlertDescription>{problem}</AlertDescription></Alert>}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('operator.cancel')}</Button>
          <Button type="button" variant="destructive" disabled={busy} aria-busy={busy || undefined}
            data-testid="member-drop-confirm" onClick={() => void confirm()}>{t('operator.home.remove')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function TeamSection({ team }: { team: Team }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [dropping, setDropping] = useState<Member | null>(null)
  const columns = useMemo<ColumnDef<Member, unknown>[]>(() => [
    {
      id: 'name',
      header: t('operator.members.name'),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2">
          {row.original.displayName ?? row.original.userId}
          {row.original.lead && <Badge variant="secondary">{t('operator.members.lead')}</Badge>}
          {row.original.self && <Badge variant="outline">{t('operator.home.you')}</Badge>}
        </span>
      ),
    },
    { id: 'email', header: t('operator.members.email'), cell: ({ row }) => row.original.email ?? '' },
    { id: 'addedAt', header: t('operator.members.addedAt'), cell: ({ row }) => when(row.original.addedAt) },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('operator.home.remove')}</span>,
      cell: ({ row }) => (team.lead && !row.original.self ? (
        <span className="flex justify-end">
          <Button size="sm" variant="outline" data-testid="member-drop" onClick={() => setDropping(row.original)}>
            {t('operator.home.remove')}
            <span className="sr-only">: {row.original.displayName ?? row.original.userId}</span>
          </Button>
        </span>
      ) : null),
    },
  ], [t, team.lead])
  return (
    <Section title={t('operator.home.team')} testId="operator-team">
      <p className="text-muted-foreground text-sm">{t(team.lead ? 'operator.home.teamLeadHint' : 'operator.home.teamHint')}</p>
      <DataTable label={t('operator.home.team')} columns={columns} data={team.members} getRowId={(row) => row.userId}
        empty={<EmptyState art="people" title={t('operator.members.none')} />}
        rowProps={(row) => ({ 'data-testid': 'team-row', 'data-id': row.userId })} />
      {dropping && <DropDialog member={dropping} onClose={() => setDropping(null)} />}
    </Section>
  )
}

/**
 * The operator's home page in its back office (/operator/, Q48a): who the operator is, the totals of its sponsors and
 * of the platform, and its team, whose lead removes members. Headquarters names new members.
 */
export default function OperatorHomePage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const status = useLabels(DICTIONARY.operatorStatus)
  const team = useQuery({ queryKey: [...KEY, 'team'], queryFn: readTeam })
  const overview = useQuery({ queryKey: [...KEY, 'overview'], queryFn: readOverview })
  if (team.isPending) return <PageState kind="loading" />
  if (team.isError) return <LoadFailed error={team.error} onRetry={() => void team.refetch()} />
  return (
    <div className="flex flex-col gap-4" data-testid="operator-home">
      <PageHeader title={team.data.name} className="pb-0" description={
        <span className="flex flex-wrap items-center gap-2">
          {t('operator.home.description')}
          <StatusBadge code={team.data.status} label={status(team.data.status)} />
        </span>
      } />
      {overview.isError
        ? <LoadFailed error={overview.error} onRetry={() => void overview.refetch()} />
        : overview.data && <Figures overview={overview.data} />}
      <TeamSection team={team.data} />
    </div>
  )
}
