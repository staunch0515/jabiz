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
  type ColumnDef,
} from '@jabiz/admin'
import { LoadFailed } from './components'
import { useLabels, usePaged } from './hooks'
import { DICTIONARY, PATH } from './ids'
import { banSponsor, readOperatorSponsors, unbanSponsor, type OperatorSponsor } from './operator-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const KEY = ['qb-ext', 'operator-sponsors'] as const

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
  const review = useLabels(DICTIONARY.sponsorReviewStatus)
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
  const { query, table } = usePaged([...KEY, q, mine], (offset, limit) => readOperatorSponsors(q, mine, offset, limit))
  const columns = useMemo<ColumnDef<OperatorSponsor, unknown>[]>(() => [
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
  ], [t, review])
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
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('operator.sponsors.pageTitle')} columns={columns} {...table}
            getRowId={(row) => row.onboardingId} empty={t('operator.sponsors.none')}
            rowProps={(row) => ({ 'data-testid': 'sponsor-row', 'data-id': row.userId })} />
        )}
      {acting && <BanDialog sponsor={acting.sponsor} lift={acting.lift} onClose={() => setActing(null)} />}
    </div>
  )
}
