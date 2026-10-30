import { useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
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
  Input,
  Label,
  notify,
  PageHeader,
  RadioGroup,
  RadioGroupItem,
  Textarea,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { StatusFilter } from './finance-components'
import { LoadFailed, PublisherLink } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { DIGITAL_RESOLUTIONS, readDigitalPrizes, resolveDigitalPrize, type DigitalPrize, type DigitalResolution }
  from './reward-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { dueAt: 'dueAt', amount: 'amount', status: 'status' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const KEY = ['qb-ext', 'digital-prizes'] as const

/** The states an administrator may still void a prize in; only a disputed one takes the other two decisions. */
const VOIDABLE = ['DUE', 'DELIVERED', 'OVERDUE'] as const

/** A dispute asks for a decision; a late sponsor is a warning; everything else is just a state. */
const badge = (state: DigitalPrize['status']) =>
  state === 'DISPUTED' ? 'destructive' : state === 'OVERDUE' ? 'warning' : 'outline'

/**
 * An administrator decides a disputed digital prize (`QB_DIGITAL_PRIZE_RESOLVE`): delivered, not delivered or void,
 * with a note. A prize still open (due, delivered, overdue) can only be voided, so the other choices are disabled.
 */
function ResolveDialog({ prize, onClose }: { prize: DigitalPrize; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const disputed = prize.status === 'DISPUTED'
  const [resolution, setResolution] = useState<DigitalResolution>(disputed ? 'DELIVERED' : 'VOID')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const taker = prize.takerName ?? '?'
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setProblem(null)
    try {
      await resolveDigitalPrize(prize.digitalPrizeId, resolution, note)
      notify.success(t('reward.digital.resolved', { taker }))
      await queryClient.invalidateQueries({ queryKey: KEY })
      onClose()
    } catch (error) {
      setProblem(error instanceof Error ? error.message : String(error))
    } finally {
      setBusy(false)
    }
  }
  const about = { prize: prize.description, amount: money(prize.amount), sponsor: prize.sponsorCompany ?? '?', taker }
  return (
    <Dialog open onOpenChange={(open) => !open && !busy && onClose()}>
      <DialogContent data-testid="digital-resolve-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={submit}>
          <DialogHeader>
            <DialogTitle>{t('reward.digital.resolveTitle', { taker })}</DialogTitle>
            <DialogDescription>
              {disputed
                ? t('reward.digital.resolveText', { ...about, note: prize.disputeNote ?? '' })
                : t('reward.digital.voidText', about)}
            </DialogDescription>
          </DialogHeader>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('reward.digital.resolution')}</legend>
            <RadioGroup value={resolution} onValueChange={(value) => setResolution(value as DigitalResolution)}
              data-testid="digital-resolution">
              {DIGITAL_RESOLUTIONS.map((value) => (
                <div key={value} className="flex items-center gap-2">
                  <RadioGroupItem id={`${id}-${value}`} value={value} disabled={!disputed && value !== 'VOID'}
                    data-testid={`digital-resolution-${value}`} />
                  <Label htmlFor={`${id}-${value}`} className="font-normal">{t(`reward.digital.resolutions.${value}`)}</Label>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-note`}>{t('reward.digital.note')}</Label>
            <Textarea id={`${id}-note`} value={note} maxLength={500} aria-describedby={`${id}-note-hint`}
              data-testid="digital-resolve-note" onChange={(event) => setNote(event.target.value)} />
            <p id={`${id}-note-hint`} className="text-muted-foreground text-sm">{t('reward.digital.noteHint')}</p>
          </div>
          {problem && <Alert variant="destructive"><AlertDescription>{problem}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('manual.dialog.cancel')}</Button>
            <Button type="submit" disabled={busy} aria-busy={busy || undefined} data-testid="digital-resolve-send">
              {t(disputed ? 'reward.digital.send' : 'reward.digital.void')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * Every digital prize (docs/quizbuks/plans/Q33-digital-prize.md), newest first, by state and text: who owes whom
 * which code, voucher or link and where it stands; a disputed one is decided here by whoever may, an open one voided.
 * The content itself is never shown: only a pool code's last characters.
 */
export default function DigitalPrizesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const status = useLabels(DICTIONARY.digitalStatus)
  const delivery = useLabels(DICTIONARY.digitalDelivery)
  const [search, setSearch] = useSearchParams()
  const filterStatus = search.get('status')
  const [text, setText] = useState(search.get('q') ?? '')
  const q = search.get('q') ?? ''
  const [resolving, setResolving] = useState<DigitalPrize | null>(null)
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const more = useMoreFilters([
    { kind: 'dictionary', name: 'delivery', label: t('reward.digital.delivery'), dictionary: DICTIONARY.digitalDelivery },
    { kind: 'amount', name: 'amount', label: t('reward.digital.value') },
    { kind: 'time', name: 'dueAt', label: t('reward.digital.dueAt') },
  ], 'digital-filters')
  const { query, table, sortable } = usePaged([...KEY, filterStatus, q, more.filters],
    (offset, limit, sorts) => readDigitalPrizes(filterStatus, q, offset, limit, sorts, more.filters), true, SORTS)
  const columns = useMemo<ColumnDef<DigitalPrize, unknown>[]>(() => [
    { id: 'dueAt', header: t('reward.digital.dueAt'), cell: ({ row }) => when(row.original.dueAt) },
    { id: 'sponsor', header: t('reward.digital.sponsor'),
      cell: ({ row }) => (
        <PublisherLink kind={row.original.publisherKind} id={row.original.publisherId}
          label={row.original.sponsorCompany} />
      ) },
    { id: 'taker', header: t('reward.digital.taker'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.userId)} className={linkClass}>
          {row.original.takerName ?? <UserName id={row.original.userId} />}
        </Link>
      ) },
    { id: 'publication', header: t('reward.digital.publication'), cell: ({ row }) => row.original.publicationName },
    { id: 'prize', header: t('reward.digital.prize'),
      cell: ({ row }) => (
        <span className="flex flex-col">
          <span className="whitespace-pre-line">{row.original.description}</span>
          {row.original.hint && (
            <code className="text-muted-foreground font-mono text-xs" data-testid="digital-row-hint">{row.original.hint}</code>
          )}
        </span>
      ) },
    { id: 'delivery', header: t('reward.digital.delivery'), cell: ({ row }) => delivery(row.original.delivery) },
    { id: 'amount', header: t('reward.digital.value'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'status', header: t('reward.digital.status'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <Badge variant={badge(row.original.status)} className="w-fit"
            data-testid="digital-row-status" data-status={row.original.status}>
            {status(row.original.status)}
          </Badge>
          {row.original.status === 'DUE' && row.original.sendBy && (
            <span className="text-muted-foreground text-xs">{t('reward.digital.sendBy', { time: when(row.original.sendBy) })}</span>
          )}
          {row.original.deliveredAt && (
            <span className="text-muted-foreground text-xs">
              {t('reward.digital.deliveredAt', { time: when(row.original.deliveredAt),
                by: t(row.original.deliveredBy === 'SPONSOR' ? 'reward.digital.bySponsor' : 'reward.digital.bySystem') })}
            </span>
          )}
          {row.original.status === 'DELIVERED' && row.original.confirmBy && (
            <span className="text-muted-foreground text-xs">{t('reward.digital.confirmBy', { time: when(row.original.confirmBy) })}</span>
          )}
          {row.original.openedAt && (
            <span className="text-muted-foreground text-xs">{t('reward.digital.openedAt', { time: when(row.original.openedAt) })}</span>
          )}
          {row.original.disputeNote && (
            <span className="text-xs" data-testid="digital-row-dispute">{t('reward.digital.disputeNote', { note: row.original.disputeNote })}</span>
          )}
          {row.original.resolveNote && (
            <span className="text-muted-foreground text-xs">{t('reward.digital.resolveNote', { note: row.original.resolveNote })}</span>
          )}
        </span>
      ) },
    { id: 'actions', header: () => <span className="sr-only">{t('reward.digital.resolve')}</span>,
      cell: ({ row }) => {
        if (!can(PERMISSION.digitalResolve)) return null
        const disputed = row.original.status === 'DISPUTED'
        if (!disputed && !(VOIDABLE as readonly string[]).includes(row.original.status)) return null
        return (
          <span className="flex justify-end">
            <Button size="sm" variant={disputed ? 'default' : 'outline'} onClick={() => setResolving(row.original)}
              data-testid={disputed ? 'digital-row-resolve' : 'digital-row-void'}>
              {t(disputed ? 'reward.digital.resolve' : 'reward.digital.void')}
              <span className="sr-only">: {row.original.takerName ?? row.original.userId}</span>
            </Button>
          </span>
        )
      } },
  ], [t, status, delivery, can])

  return (
    <div className="flex flex-col gap-4" data-testid="digital-prizes">
      <PageHeader title={t('reward.digital.title')} description={t('reward.digital.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.digitalStatus} value={filterStatus} testId="digital-status"
          onChange={(value) => set({ status: value })} />
        <form role="search" className="flex items-end gap-2" onSubmit={(event) => {
          event.preventDefault()
          set({ q: text.trim() || null })
        }}>
          <div className="flex flex-col gap-1">
            <Label htmlFor="digital-q" className="sr-only">{t('reward.digital.search')}</Label>
            <Input id="digital-q" type="search" value={text} maxLength={100} placeholder={t('reward.digital.search')}
              data-testid="digital-search" onChange={(event) => setText(event.target.value)} />
          </div>
          <Button type="submit" variant="outline">{t('reward.digital.search')}</Button>
        </form>
        {(filterStatus || q) && (
          <Button variant="ghost" size="sm" onClick={() => {
            setText('')
            set({ status: null, q: null })
          }}>{t('reward.digital.all')}</Button>
        )}
      </div>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('reward.digital.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.digitalPrizeId}
            empty={t('reward.digital.none')}
            rowProps={(row) => ({ 'data-testid': 'digital-row', 'data-id': row.digitalPrizeId, 'data-status': row.status })} />
        )}
      {resolving && <ResolveDialog prize={resolving} onClose={() => setResolving(null)} />}
    </div>
  )
}
