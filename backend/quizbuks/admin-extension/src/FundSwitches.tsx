import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Badge,
  DataTable,
  EXTENSION_NAMESPACE,
  PageState,
  paths,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { LoadFailed, Section } from './components'
import {
  KEY_BEFORE_MEANING_ROWS,
  MANUAL_ROWS,
  nameId,
  NOT_SET_IS_OFF,
  readFundProposals,
  readFundSwitches,
  stateOf,
  WAITING_ROWS,
  type FundProposal,
  type FundState,
} from './first-launch-api'
import { CONTROL, PERMISSION } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/** The badge of each state: green when it is as the first launch wants, amber when it is not or not yet. */
const TONE: Record<FundState | 'waiting' | 'manual', 'success' | 'warning' | 'secondary' | 'outline'> = {
  match: 'success',
  differs: 'warning',
  proposed: 'secondary',
  proposedDiffers: 'warning',
  missing: 'warning',
  amount: 'outline',
  waiting: 'outline',
  manual: 'outline',
}

/** One line of the table, whatever the row's kind. */
interface Line {
  key: string
  group: string
  expected: string | null
  value: string | null
  exists: boolean
  state: FundState | 'waiting' | 'manual'
  feature?: string
  proposals: FundProposal[]
}

/**
 * F-F10b: the first launch's money switches, one row for each parameter the backend registry
 * (`setup.FirstLaunchSwitches`) lists: the value the first launch proposes, the value in force, and whether they match,
 * differ, a proposal waits to be published, or the parameter is not created yet; the rows that wait for a feature of the
 * charge groups, and the ones set up by hand, are listed with what they wait for. Read only (`qb.admin.finance.read`);
 * the open proposals need `approval.read` and are left out without it. A value is proposed and published through the
 * controlled change (four eyes), never from here.
 */
export function FundSwitches() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const readsChanges = can(PERMISSION.approvalRead)
  const switches = useQuery({ queryKey: ['qb-ext', 'finance', 'fund-switches'], queryFn: readFundSwitches })
  const keys = (switches.data ?? []).map((row) => row.key)
  const proposals = useQuery({ queryKey: ['qb-ext', 'finance', 'fund-proposals', keys], enabled: readsChanges && keys.length > 0,
    queryFn: () => readFundProposals(keys) })

  const columns = useMemo<ColumnDef<Line, unknown>[]>(() => {
    const shown = (line: Line) => {
      if (line.state === 'waiting' || line.state === 'manual') return '—'
      if (!line.exists) return t('fundSwitch.notSet')
      return line.value === null || line.value === '' ? t('fundSwitch.none') : <code>{line.value}</code>
    }
    return [
      { id: 'name', header: t('fundSwitch.columns.name'), enableSorting: false,
        cell: ({ row }) => (
          <>
            <div>{t(`fundSwitch.names.${nameId(row.original.key)}`)}</div>
            <code className="text-muted-foreground text-xs">{row.original.key}</code>
            {row.original.feature && row.original.state !== 'waiting' && (
              <p className="text-muted-foreground mt-1 text-xs" data-testid="fund-switch-note">
                {t('fundSwitch.waitsNote', { feature: row.original.feature })}
              </p>
            )}
            {NOT_SET_IS_OFF.has(row.original.key) && (
              <p className="text-muted-foreground mt-1 text-xs" data-testid="fund-switch-note">
                {t('fundSwitch.notSetIsOff')}
              </p>
            )}
          </>
        ) },
      { id: 'group', header: t('fundSwitch.columns.group'), enableSorting: false,
        cell: ({ row }) => t(`fundSwitch.group.${row.original.group}`) },
      { id: 'expected', header: t('fundSwitch.columns.firstLaunch'), enableSorting: false,
        cell: ({ row }) => {
          const line = row.original
          return (
            <span data-testid="fund-switch-expected">
              {line.state === 'waiting' || line.state === 'manual' ? '—'
                : line.expected === null ? t('fundSwitch.declared')
                  : line.expected === '' ? t('fundSwitch.none') : <code>{line.expected}</code>}
            </span>
          )
        } },
      { id: 'value', header: t('fundSwitch.columns.current'), enableSorting: false,
        cell: ({ row }) => <span data-testid="fund-switch-value">{shown(row.original)}</span> },
      { id: 'state', header: t('fundSwitch.columns.state'), enableSorting: false,
        cell: ({ row }) => {
          const line = row.original
          return (
            <>
              <Badge variant={TONE[line.state]} className="h-auto max-w-full whitespace-normal text-left"
                data-testid="fund-switch-state">
                {line.state === 'waiting' ? t('fundSwitch.state.waiting', { feature: line.feature })
                  : t(`fundSwitch.state.${line.state}`)}
              </Badge>
              {line.proposals.map((p) => (
                <p key={p.changeId} className="text-muted-foreground mt-1 text-xs" data-testid="fund-switch-proposal">
                  {t('fundSwitch.proposal', { value: p.value === null || p.value === '' ? t('fundSwitch.none') : p.value })}
                  {' · '}<UserName id={p.proposedBy} />{p.reason ? ` · ${p.reason}` : ''}
                </p>
              ))}
            </>
          )
        } },
    ]
  }, [t])

  let body
  if (switches.isError) body = <LoadFailed error={switches.error} onRetry={() => void switches.refetch()} />
  else if (switches.isPending) body = <PageState kind="loading" />
  else {
    const open = proposals.data ?? []
    const lines: Line[] = [
      ...switches.data.map((row) => {
        const mine = open.filter((p) => p.key === row.key)
        const waiting = KEY_BEFORE_MEANING_ROWS.find((w) => w.key === row.key)
        return { ...row, state: stateOf(row, mine), proposals: mine, feature: waiting?.waitingFor }
      }),
      ...WAITING_ROWS.map((row) => ({ key: row.key, group: row.group, expected: null, value: null, exists: false,
        state: 'waiting' as const, feature: row.waitingFor, proposals: [] })),
      ...MANUAL_ROWS.map((row) => ({ key: row.key, group: row.group, expected: null, value: null, exists: false,
        state: 'manual' as const, proposals: [] })),
    ]
    const comparable = lines.filter((line) => line.state !== 'amount' && line.state !== 'waiting'
      && line.state !== 'manual')
    const match = comparable.filter((line) => line.state === 'match').length
    body = (
      <>
        <p className="text-sm" role="status" data-testid="fund-switches-summary" data-match={match}
          data-total={comparable.length}>
          {comparable.length > 0 && match === comparable.length ? t('fundSwitch.summaryAll')
            : t('fundSwitch.summary', { match, total: comparable.length })}
        </p>
        {!readsChanges && (
          <p className="text-muted-foreground text-sm" data-testid="fund-switches-no-proposals">
            {t('fundSwitch.noProposals')}
          </p>
        )}
        {proposals.isError && <LoadFailed error={proposals.error} onRetry={() => void proposals.refetch()} />}
        <div data-testid="fund-switches">
          <DataTable label={t('fundSwitch.table')} columns={columns} data={lines} getRowId={(line) => line.key}
            cards={false} rowProps={(line) => ({ 'data-testid': 'fund-switch', 'data-key': line.key,
              'data-state': line.state })} />
        </div>
        {(can(PERMISSION.controlPropose) || can(PERMISSION.controlPublish) || readsChanges) && (
          <Link to={paths.dataset(CONTROL.changes)} className={linkClass} data-testid="fund-switches-changes">
            {t('fundSwitch.changes')}
          </Link>
        )}
      </>
    )
  }
  return (
    <Section title={t('fundSwitch.title')} testId="finance-fund-switches">
      <p className="text-muted-foreground text-sm">{t('fundSwitch.hint')}</p>
      {body}
    </Section>
  )
}
