import { useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  Checkbox,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageHeader,
  Textarea,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed } from './components'
import { BillingDialog } from './BillingDialog'
import { useLabels, usePaged, usePoints } from './hooks'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { confirmOnboarding } from './fee-api'
import { RegionExceptionAction } from './RegionExceptionAction'
import { banSponsor, readOperatorSponsors, unbanSponsor, type OperatorSponsor } from './operator-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { company: 'company' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const KEY = ['qb-ext', 'operator-sponsors'] as const

/** F-F11b: a Sponsor of the operator, approved and not banned, may be confirmed for the onboarding Kudos. */
const isGrantable = (s: OperatorSponsor) => s.mine && s.reviewStatus === 'APPROVED' && !s.banned

/**
 * Bans a sponsor (`QB_OPERATOR_SPONSOR_BAN`, with a reason headquarters reads; the sponsor reads only that the account
 * is suspended), or lifts a ban the operator made (`QB_OPERATOR_SPONSOR_UNBAN`). Banning pauses their running
 * publications; lifting it does not resume them.
 */
function BanDialog({ sponsor, lift, onClose }: { sponsor: OperatorSponsor; lift: boolean; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || (!lift && !text.trim())) return
    setBusy(true)
    setProblem(null)
    try {
      if (lift) await unbanSponsor(sponsor.userId, text.trim() || null)
      else await banSponsor(sponsor.userId, text.trim())
      notify.success(t(lift ? 'operator.ban.lifted' : 'operator.ban.banned', { name: sponsor.company }))
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
      <DialogContent data-testid="ban-dialog">
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t(lift ? 'operator.ban.liftTitle' : 'operator.ban.title', { name: sponsor.company })}</DialogTitle>
            <DialogDescription>{t(lift ? 'operator.ban.liftHint' : 'operator.ban.hint')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-text`}>{t(lift ? 'operator.ban.note' : 'operator.ban.reason')}</Label>
            <Textarea id={`${id}-text`} value={text} maxLength={500} required={!lift} data-testid="ban-text"
              onChange={(event) => setText(event.target.value)} />
          </div>
          {problem && <Alert variant="destructive" data-testid="ban-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('operator.cancel')}</Button>
            <Button type="submit" variant={lift ? 'default' : 'destructive'} disabled={busy || (!lift && !text.trim())}
              aria-busy={busy || undefined} data-testid="ban-submit">
              {t(lift ? 'operator.ban.lift' : 'operator.ban.ban')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * The sponsors in the operator's back office (Q48a): every ordinary sponsor while there is one operator (D-Q48-2),
 * the operator's own marked, by search; each opens on its review page. The operator bans a sponsor here and lifts only
 * its own bans; headquarters' come first.
 */
export default function OperatorSponsorsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const review = useLabels(DICTIONARY.sponsorReviewStatus)
  const billingMode = useLabels(DICTIONARY.billingMode)
  const points = usePoints()
  const { can } = useAuth()
  const biller = can(PERMISSION.feeBilling)
  const granter = can(PERMISSION.feeCreditOnboarding)
  // F-F11b: the onboarding Kudos for several Sponsors at once. Each is its own call (one wallet lock, one transaction);
  // one batch runs at a time, and a Sponsor that failed stays ticked to try again.
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())
  const [results, setResults] = useState<Readonly<Record<string, { ok: boolean; text: string }>>>({})
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null)
  const [billing, setBilling_] = useState<OperatorSponsor | null>(null)
  const [search, setSearch] = useSearchParams()
  const q = search.get('q') ?? ''
  const mine = search.get('mine') === 'true'
  const [typed, setTyped] = useState(q)
  const [acting, setActing] = useState<{ sponsor: OperatorSponsor; lift: boolean } | null>(null)
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const toggle = (userId: string, on: boolean) => setSelected((now) => {
    const next = new Set(now)
    if (on) next.add(userId)
    else next.delete(userId)
    return next
  })
  const grantSelected = async () => {
    if (progress || selected.size === 0) return
    const ids = [...selected]
    setProgress({ done: 0, total: ids.length })
    for (const [index, userId] of ids.entries()) {
      try {
        const given = await confirmOnboarding(userId)
        setResults((now) => ({ ...now, [userId]: given.granted ? { ok: true, text: t('fee.onboarding.bulk.ok') }
          : { ok: false, text: t('fee.onboarding.bulk.nothing') } }))
        if (given.granted) toggle(userId, false)
      } catch (e) {
        setResults((now) => ({ ...now, [userId]: { ok: false, text: e instanceof ApiError ? e.display : String(e) } }))
      }
      setProgress({ done: index + 1, total: ids.length })
    }
    setProgress(null)
    void queryClient.invalidateQueries({ queryKey: ['qb-ext'] })
  }
  const { query, table, sortable } = usePaged([...KEY, q, mine], (offset, limit, sorts) => readOperatorSponsors(q, mine, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<OperatorSponsor, unknown>[]>(() => [
    ...(granter ? [{
      id: 'grant',
      header: t('fee.onboarding.bulk.title'),
      cell: ({ row }: { row: { original: OperatorSponsor } }) => {
        const s = row.original
        const result = results[s.userId]
        return (
          <span className="flex flex-wrap items-center gap-2">
            {isGrantable(s) && !result?.ok && (
              <Checkbox checked={selected.has(s.userId)} disabled={progress !== null} data-testid="sponsor-grant-select"
                aria-label={t('fee.onboarding.bulk.select', { name: s.company })}
                onCheckedChange={(value) => toggle(s.userId, value === true)} />
            )}
            {result && (
              <span className={result.ok ? 'text-sm' : 'text-destructive text-sm'} role={result.ok ? undefined : 'alert'}
                data-testid="sponsor-grant-result" data-state={result.ok ? 'ok' : 'error'}>{result.text}</span>
            )}
          </span>
        )
      },
    } satisfies ColumnDef<OperatorSponsor, unknown>] : []),
    {
      id: 'company',
      header: t('operator.sponsors.company'),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2">
          <Link to={PATH.sponsor(row.original.userId)} className={linkClass} data-testid="sponsor-link">
            {row.original.company || row.original.contactName}
          </Link>
          {row.original.mine && <Badge variant="secondary">{t('operator.sponsors.mine')}</Badge>}
        </span>
      ),
    },
    { id: 'contact', header: t('operator.sponsors.contact'),
      cell: ({ row }) => [row.original.contactName, row.original.email].filter(Boolean).join(' · ') },
    { id: 'operator', header: t('operator.sponsors.operator'),
      cell: ({ row }) => row.original.operatorName ?? t('operator.sponsors.headquarters') },
    { id: 'review', header: t('operator.sponsors.review'), cell: ({ row }) => review(row.original.reviewStatus) },
    { id: 'running', header: t('operator.sponsors.running'), meta: right,
      cell: ({ row }) => row.original.runningPublications },
    // Q87: how the sponsor is billed for the platform's fees; the operator (or finance) changes it with a reason.
    {
      id: 'billing',
      header: t('operator.sponsors.billing'),
      cell: ({ row }) => {
        const s = row.original
        return (
          <span className="flex flex-wrap items-center gap-2" data-testid="sponsor-billing" data-mode={s.billingMode}>
            <span>{billingMode(s.billingMode)}</span>
            {s.billingMode === 'POSTPAID' && (
              <span className="text-muted-foreground text-sm">
                {t('operator.sponsors.creditLimit', { amount: points(s.creditLimit) })}
              </span>
            )}
            {biller && s.mine && (
              <Button size="sm" variant="outline" data-testid="sponsor-billing-set" onClick={() => setBilling_(s)}>
                {t('operator.sponsors.billingSet')}<span className="sr-only">: {s.company}</span>
              </Button>
            )}
          </span>
        )
      },
    },
    // F-F11b: a mainland China exception for the operator's own Sponsor, or the note that one is in force.
    ...(can(PERMISSION.regionWrite) ? [{
      id: 'region',
      header: t('region.title'),
      cell: ({ row }: { row: { original: OperatorSponsor } }) => (row.original.mine
        ? <RegionExceptionAction kind="SPONSOR" id={row.original.userId} name={row.original.company} /> : null),
    } satisfies ColumnDef<OperatorSponsor, unknown>] : []),
    {
      id: 'ban',
      header: t('operator.sponsors.ban'),
      cell: ({ row }) => {
        const s = row.original
        if (!s.banned) {
          return (
            <Button size="sm" variant="outline" data-testid="sponsor-ban" onClick={() => setActing({ sponsor: s, lift: false })}>
              {t('operator.ban.ban')}<span className="sr-only">: {s.company}</span>
            </Button>
          )
        }
        return (
          <span className="flex flex-wrap items-center gap-2">
            <Badge variant="destructive">{t('operator.sponsors.banned')}</Badge>
            {s.bannedByOperator
              ? (
                <Button size="sm" variant="outline" data-testid="sponsor-unban"
                  onClick={() => setActing({ sponsor: s, lift: true })}>
                  {t('operator.ban.lift')}<span className="sr-only">: {s.company}</span>
                </Button>
              )
              : <span className="text-muted-foreground text-sm">{t('operator.sponsors.bannedByHq')}</span>}
          </span>
        )
      },
    },
  ], [t, review, billingMode, points, biller, granter, can, selected, results, progress])
  return (
    <div className="flex flex-col gap-4" data-testid="operator-sponsors-page">
      <PageHeader title={t('operator.sponsors.pageTitle')} description={t('operator.sponsors.description')}
        className="pb-0" />
      <form className="flex flex-wrap items-end gap-4" role="search" onSubmit={(event) => {
        event.preventDefault()
        set({ q: typed.trim() || null })
      }}>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-q`}>{t('operator.sponsors.search')}</Label>
          <Input id={`${id}-q`} type="search" value={typed} maxLength={100} className="w-72" data-testid="sponsors-q"
            onChange={(event) => setTyped(event.target.value)} />
        </div>
        <Button type="submit" variant="outline">{t('operator.sponsors.find')}</Button>
        <div className="flex items-center gap-2 pb-2">
          <Checkbox id={`${id}-mine`} checked={mine} data-testid="sponsors-mine"
            onCheckedChange={(value) => set({ mine: value === true ? 'true' : null })} />
          <Label htmlFor={`${id}-mine`} className="font-normal">{t('operator.sponsors.onlyMine')}</Label>
        </div>
      </form>
      {granter && (
        <div className="flex flex-col gap-2" data-testid="sponsors-grant-bar">
          <p className="text-muted-foreground text-sm">{t('fee.onboarding.bulk.hint')}</p>
          <span className="flex flex-wrap items-center gap-3">
            <Button type="button" disabled={selected.size === 0 || progress !== null}
              aria-busy={progress !== null || undefined} data-testid="operator-sponsors-confirm-selected"
              onClick={() => void grantSelected()}>
              {t('fee.onboarding.bulk.confirm', { n: selected.size })}
            </Button>
            {progress && (
              <span role="status" className="text-sm" data-testid="sponsors-grant-progress">
                {t('fee.onboarding.bulk.running', progress)}
              </span>
            )}
          </span>
        </div>
      )}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('operator.sponsors.pageTitle')} columns={sortable(columns)} {...table}
            getRowId={(row) => row.onboardingId} empty={<EmptyState art="people" title={t('operator.sponsors.none')} />}
            rowProps={(row) => ({ 'data-testid': 'sponsor-row', 'data-id': row.userId })} />
        )}
      {acting && <BanDialog sponsor={acting.sponsor} lift={acting.lift} onClose={() => setActing(null)} />}
      {billing && (
        <BillingDialog sponsor={billing} onClose={() => setBilling_(null)}
          onSaved={() => void queryClient.invalidateQueries({ queryKey: KEY })} />
      )}
    </div>
  )
}
