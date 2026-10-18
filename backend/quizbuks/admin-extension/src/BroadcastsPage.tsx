import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, Save, Search, Send, X } from 'lucide-react'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Checkbox,
  ConfirmDialog,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  MarkdownView,
  notify,
  PageHeader,
  RadioGroup,
  RadioGroupItem,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  Textarea,
  UserName,
  useLanguage,
  type ColumnDef,
} from '@jabiz/admin'
import { searchUsers } from './api'
import {
  AUDIENCES,
  cancelAnnouncement,
  instantOf,
  LANGUAGES,
  localOf,
  MAX_TARGETS,
  rate,
  readBroadcasts,
  readTargets,
  REGIONS,
  saveAnnouncement,
  sendAnnouncement,
  TARGET_MODES,
  TYPES,
  userIdsOf,
  withdrawBroadcast,
  type BroadcastFilter,
  type BroadcastRow,
  type SaveOutput,
  type Text,
} from './broadcasts-api'
import { LoadFailed, Section } from './components'
import { failed } from './finance'
import { StatusFilter } from './finance-components'
import { useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH } from './ids'

const KEY = ['qb-ext', 'broadcasts']
const REASON_LENGTH = 1000
const ALL = '__all'
/** `qb.broadcast.expires-days` by default (the server applies the parameter). */
const DEFAULT_DAYS = 30
const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

/** A text in the reader's language, else English, else Japanese, else any. */
function pick(text: Text, language: string): string {
  return text[language.split('-')[0]] || text.en || text.ja || Object.values(text)[0] || ''
}

/** A count with its rate. */
function stat(t: (key: string, options?: Record<string, unknown>) => string, value: number | null,
  base: number | null): string {
  if (value == null) return ''
  const share = rate(value, base)
  return share == null ? String(value) : t('broadcasts.stat', { count: value, rate: share })
}

/** Finds users by name, address or company (`qb.admin.users`) to name them; ids can also be pasted. */
function UserSearch({ onAdd }: { onAdd: (userId: string) => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const found = useQuery({ queryKey: [...KEY, 'users', q], queryFn: () => searchUsers(q, 0, 10), enabled: !!q })
  return (
    <div className="flex flex-col gap-2">
      <form role="search" className="flex gap-2" onSubmit={(event) => {
        event.preventDefault()
        setQ(text.trim())
      }}>
        <Input value={text} maxLength={100} aria-label={t('broadcasts.filters.search')}
          data-testid="broadcast-user-search" onChange={(event) => setText(event.target.value)} />
        <Button type="submit" variant="outline" size="icon" aria-label={t('broadcasts.filters.search')}>
          <Search aria-hidden />
        </Button>
      </form>
      {(found.data ?? []).length > 0 && (
        <ul className="flex flex-col gap-1" data-testid="broadcast-user-results">
          {found.data!.map((user) => (
            <li key={user.userId} className="flex items-center gap-2 text-sm">
              <span className="flex-1">{user.displayName ?? user.userName} · {user.email}</span>
              <Button type="button" size="sm" variant="outline" onClick={() => onAdd(user.userId)}>
                <Plus aria-hidden />
                <span className="sr-only">{user.userName}</span>
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/**
 * Writes an announcement (P-04): to the app or the sponsor console, of a type, to everyone, regions or named users
 * (at most {@link MAX_TARGETS}), its title and Markdown text in each language with a preview, now or at a time and
 * until when. Every save answers with the problems sending would meet; sending asks first.
 */
function Editor({ row, onClose }: { row: BroadcastRow | null; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const audienceLabel = useLabels(DICTIONARY.broadcastAudience)
  const typeLabel = useLabels(DICTIONARY.broadcastType)
  const targetLabel = useLabels(DICTIONARY.broadcastTarget)
  const regionLabel = useLabels(DICTIONARY.region)
  const [audience, setAudience] = useState(row?.audience ?? 'APP')
  const [type, setType] = useState(row?.type ?? 'SYSTEM')
  const [targetMode, setTargetMode] = useState(row?.targetMode ?? 'ALL')
  const [regions, setRegions] = useState<string[]>(row?.regions ?? [])
  const [title, setTitle] = useState<Text>(row?.title ?? {})
  const [body, setBody] = useState<Text>(row?.body ?? {})
  const [later, setLater] = useState(!!row?.scheduledAt)
  const [at, setAt] = useState(localOf(row?.scheduledAt ?? null))
  const [expires, setExpires] = useState(localOf(row?.expiresAt ?? null))
  const [pasted, setPasted] = useState('')
  const targets = useQuery({ queryKey: [...KEY, 'targets', row?.broadcastId], enabled: !!row
    && row.targetMode === 'USERS', queryFn: () => readTargets(row!.broadcastId) })
  const [users, setUsers] = useState<string[] | null>(null)
  const named = users ?? targets.data ?? []
  const [saved, setSaved] = useState(row ? { broadcastId: row.broadcastId, revision: row.revision } : null)
  const [output, setOutput] = useState<SaveOutput | null>(null)
  const [savedAs, setSavedAs] = useState('')
  const current = JSON.stringify({ audience, type, targetMode, regions, title, body, later, at, expires, named })
  const save = useMutation({
    mutationFn: () => saveAnnouncement({
      broadcastId: saved?.broadcastId ?? null,
      baseRevision: saved?.revision ?? null,
      audience,
      type,
      title,
      body,
      targetMode,
      regions: targetMode === 'REGIONS' ? regions : [],
      userIds: targetMode === 'USERS' ? named : undefined,
      scheduledAt: later ? instantOf(at) : null,
      expiresAt: instantOf(expires),
    }),
    onSuccess: (result) => {
      setSaved({ broadcastId: result.broadcastId, revision: result.revision })
      setOutput(result)
      notify.success(t('broadcasts.editor.saved'))
      void queryClient.invalidateQueries({ queryKey: KEY })
    },
  })
  const ready = !!output && output.problems.length === 0 && savedAs === current
  const add = (ids: string[]) => setUsers([...new Set([...named, ...ids])].slice(0, MAX_TARGETS + 1))
  return (
    <Section title={row ? t('broadcasts.editor.title') : t('broadcasts.editor.newTitle')} testId="broadcast-editor">
      <form className="flex flex-col gap-4" onSubmit={(event) => {
        event.preventDefault()
        setSavedAs(current)
        save.mutate()
      }}>
        <div className="flex flex-wrap gap-4">
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-audience`}>{t('broadcasts.editor.audience')}</Label>
            <Select value={audience} onValueChange={setAudience}>
              <SelectTrigger id={`${id}-audience`} className="w-48" data-testid="broadcast-audience"><SelectValue />
              </SelectTrigger>
              <SelectContent>
                {AUDIENCES.map((code) => <SelectItem key={code} value={code}>{audienceLabel(code)}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-type`}>{t('broadcasts.editor.type')}</Label>
            <Select value={type} onValueChange={setType}>
              <SelectTrigger id={`${id}-type`} className="w-48" data-testid="broadcast-type"
                aria-describedby={`${id}-type-hint`}><SelectValue /></SelectTrigger>
              <SelectContent>
                {TYPES.map((code) => <SelectItem key={code} value={code}>{typeLabel(code)}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-target`}>{t('broadcasts.editor.target')}</Label>
            <Select value={targetMode} onValueChange={setTargetMode}>
              <SelectTrigger id={`${id}-target`} className="w-48" data-testid="broadcast-target"><SelectValue />
              </SelectTrigger>
              <SelectContent>
                {TARGET_MODES.map((code) => <SelectItem key={code} value={code}>{targetLabel(code)}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
        </div>
        <p id={`${id}-type-hint`} className="text-muted-foreground text-sm">{t('broadcasts.editor.typeHint')}</p>
        {targetMode === 'REGIONS' && (
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('broadcasts.editor.regions')}</legend>
            <div className="flex flex-wrap gap-3">
              {REGIONS.map((region) => (
                <div key={region} className="flex items-center gap-2">
                  <Checkbox id={`${id}-region-${region}`} checked={regions.includes(region)}
                    data-testid={`broadcast-region-${region}`}
                    onCheckedChange={(on) => setRegions((current) => on === true
                      ? REGIONS.filter((r) => r === region || current.includes(r))
                      : current.filter((r) => r !== region))} />
                  <Label htmlFor={`${id}-region-${region}`}>{regionLabel(region)}</Label>
                </div>
              ))}
            </div>
          </fieldset>
        )}
        {targetMode === 'USERS' && (
          <fieldset className="flex flex-col gap-2" data-testid="broadcast-users">
            <legend className="text-sm font-medium">{t('broadcasts.editor.users', { max: MAX_TARGETS })}</legend>
            <UserSearch onAdd={(userId) => add([userId])} />
            <Textarea value={pasted} rows={3} aria-label={t('broadcasts.editor.users', { max: MAX_TARGETS })}
              data-testid="broadcast-paste" onChange={(event) => setPasted(event.target.value)}
              onBlur={() => {
                if (pasted.trim()) {
                  add(userIdsOf(pasted))
                  setPasted('')
                }
              }} />
            <p className="text-sm" data-testid="broadcast-user-count">
              {t('broadcasts.editor.usersShown', { count: named.length })}
            </p>
            <ul className="flex flex-wrap gap-2">
              {named.slice(0, 50).map((userId) => (
                <li key={userId}>
                  <Badge variant="outline" className="gap-1">
                    <UserName id={userId} />
                    <button type="button" aria-label={t('broadcasts.editor.remove', { id: userId })}
                      onClick={() => setUsers(named.filter((idOf) => idOf !== userId))}>
                      <X aria-hidden className="size-3" />
                    </button>
                  </Badge>
                </li>
              ))}
            </ul>
          </fieldset>
        )}
        <Tabs defaultValue="en">
          <TabsList>
            {LANGUAGES.map((lang) => (
              <TabsTrigger key={lang} value={lang} data-testid={`broadcast-lang-${lang}`}>
                {t(`broadcasts.editor.languages.${lang}`)}
              </TabsTrigger>
            ))}
          </TabsList>
          {LANGUAGES.map((lang) => (
            <TabsContent key={lang} value={lang} className="grid gap-4 pt-2 lg:grid-cols-2">
              <div className="flex flex-col gap-3">
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-title-${lang}`}>{t('broadcasts.editor.heading', { lang })}</Label>
                  <Input id={`${id}-title-${lang}`} lang={lang} value={title[lang] ?? ''} maxLength={120}
                    data-testid={`broadcast-title-${lang}`}
                    onChange={(event) => setTitle({ ...title, [lang]: event.target.value })} />
                </div>
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-body-${lang}`}>{t('broadcasts.editor.text', { lang })}</Label>
                  <Textarea id={`${id}-body-${lang}`} lang={lang} rows={8} maxLength={5000} value={body[lang] ?? ''}
                    data-testid={`broadcast-body-${lang}`}
                    onChange={(event) => setBody({ ...body, [lang]: event.target.value })} />
                </div>
              </div>
              <div className="flex flex-col gap-1 rounded-md border p-3" data-testid={`broadcast-preview-${lang}`}>
                <p className="text-muted-foreground text-xs">{t('broadcasts.editor.preview')}</p>
                <p className="font-semibold" lang={lang}>{title[lang] ?? ''}</p>
                <MarkdownView text={body[lang] ?? ''} lang={lang} />
              </div>
            </TabsContent>
          ))}
        </Tabs>
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium">{t('broadcasts.editor.when')}</legend>
          <RadioGroup value={later ? 'later' : 'now'} onValueChange={(value) => setLater(value === 'later')}
            className="flex flex-wrap gap-4">
            <div className="flex items-center gap-2">
              <RadioGroupItem id={`${id}-now`} value="now" data-testid="broadcast-now" />
              <Label htmlFor={`${id}-now`}>{t('broadcasts.editor.now')}</Label>
            </div>
            <div className="flex items-center gap-2">
              <RadioGroupItem id={`${id}-later`} value="later" data-testid="broadcast-later" />
              <Label htmlFor={`${id}-later`}>{t('broadcasts.editor.later')}</Label>
            </div>
          </RadioGroup>
          <div className="flex flex-wrap gap-4">
            {later && (
              <Input type="datetime-local" className="w-60" value={at} aria-label={t('broadcasts.editor.later')}
                data-testid="broadcast-at" onChange={(event) => setAt(event.target.value)} />
            )}
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-expires`}>{t('broadcasts.editor.expires', { days: DEFAULT_DAYS })}</Label>
              <Input id={`${id}-expires`} type="datetime-local" className="w-60" value={expires}
                data-testid="broadcast-expires" onChange={(event) => setExpires(event.target.value)} />
            </div>
          </div>
        </fieldset>
        {save.isError && (
          <Alert variant="destructive" data-testid="broadcast-error">
            <AlertDescription>{(save.error as { display?: string }).display ?? t('common.failed')}</AlertDescription>
          </Alert>
        )}
        {output && savedAs === current && (output.problems.length > 0 ? (
          <Alert variant="warning" data-testid="broadcast-problems">
            <AlertDescription>
              <p className="font-medium">{t('broadcasts.editor.problems')}</p>
              <ul className="list-disc pl-5">
                {output.problems.map((problem) => (
                  <li key={`${problem.field}:${problem.code}`} data-code={problem.code}>{problem.message}</li>
                ))}
              </ul>
            </AlertDescription>
          </Alert>
        ) : <p className="text-sm" data-testid="broadcast-ready">{t('broadcasts.editor.ready')}</p>)}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" variant="outline" disabled={save.isPending} data-testid="broadcast-save">
            <Save aria-hidden />
            {t('broadcasts.editor.save')}
          </Button>
          <ConfirmDialog title={t('broadcasts.editor.sendTitle')} description={t('broadcasts.editor.sendText')}
            confirmLabel={later ? t('broadcasts.editor.schedule') : t('broadcasts.editor.send')}
            onConfirm={async () => {
              try {
                const sent = await sendAnnouncement(saved!.broadcastId, saved!.revision)
                notify.success(sent.status === 'SCHEDULED' ? t('broadcasts.editor.scheduled')
                  : t('broadcasts.editor.sent'))
                onClose()
              } catch (error) {
                failed(error, t('common.failed'))
              } finally {
                await queryClient.invalidateQueries({ queryKey: KEY })
              }
            }}
            trigger={(
              <Button type="button" disabled={!ready} data-testid="broadcast-send">
                <Send aria-hidden />
                {later ? t('broadcasts.editor.schedule') : t('broadcasts.editor.send')}
              </Button>
            )} />
          <Button type="button" variant="ghost" onClick={onClose} data-testid="broadcast-close">
            {t('broadcasts.editor.close')}
          </Button>
        </div>
      </form>
    </Section>
  )
}

/** Withdraws any announcement or sponsor's notice, with a reason (the sponsor reads it) and a second factor. */
function Withdraw({ row }: { row: BroadcastRow }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [reason, setReason] = useState('')
  return (
    <div className="flex items-center gap-1" data-testid="broadcast-withdraw-form">
      <Label htmlFor={`${id}-reason`} className="sr-only">{t('broadcasts.actions.reason')}</Label>
      <Input id={`${id}-reason`} className="h-8 w-36" value={reason} maxLength={REASON_LENGTH}
        placeholder={t('broadcasts.actions.reason')} data-testid="broadcast-withdraw-reason"
        onChange={(event) => setReason(event.target.value)} />
      <ConfirmDialog title={t('broadcasts.actions.withdrawTitle')} description={t('broadcasts.actions.withdrawText')}
        confirmLabel={t('broadcasts.actions.withdraw')} destructive
        onConfirm={async () => {
          try {
            await withdrawBroadcast(row.broadcastId, reason.trim())
            notify.success(t('broadcasts.actions.done'))
            setReason('')
          } catch (error) {
            failed(error, t('common.failed'))
          } finally {
            await queryClient.invalidateQueries({ queryKey: KEY })
          }
        }}
        trigger={(
          <Button size="sm" variant="outline" disabled={!reason.trim()} data-testid="broadcast-withdraw">
            {t('broadcasts.actions.withdraw')}
          </Button>
        )} />
    </div>
  )
}

/**
 * Announcements and notices (P-04; Q7c): every announcement of QuizBuks and every sponsor's notice, page by page,
 * with who sent it, its state and its statistics (exact here: counts of people only); writing, sending and
 * scheduling QuizBuks's, and withdrawing any.
 */
export default function BroadcastsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const language = useLanguage()
  const queryClient = useQueryClient()
  const status = useLabels(DICTIONARY.broadcastStatus)
  const typeLabel = useLabels(DICTIONARY.broadcastType)
  const audienceLabel = useLabels(DICTIONARY.broadcastAudience)
  const [filter, setFilter] = useState<BroadcastFilter>({})
  const [text, setText] = useState('')
  const [editing, setEditing] = useState<BroadcastRow | 'new' | null>(null)
  const { query, table } = usePaged([...KEY, 'list', filter], (offset, limit) => readBroadcasts(filter, offset,
    limit))
  const columns = useMemo<ColumnDef<BroadcastRow, unknown>[]>(() => [
    {
      id: 'title',
      header: t('broadcasts.columns.title'),
      cell: ({ row }) => (
        <div className="flex max-w-80 flex-col gap-0.5 whitespace-normal">
          <span className="font-medium">{pick(row.original.title, language) || '—'}</span>
          {row.original.publicationName && (
            <span className="text-muted-foreground text-xs">
              {t('broadcasts.publication', { name: row.original.publicationName })}
            </span>
          )}
          {row.original.targetMode === 'USERS' && (
            <span className="text-muted-foreground text-xs">
              {t('broadcasts.targets', { count: row.original.targetCount ?? 0 })}
            </span>
          )}
          {row.original.status === 'WITHDRAWN' && (
            <span className="text-warning text-xs" data-testid="broadcast-withdrawn">
              {t('broadcasts.withdrawnBy', { reason: row.original.withdrawReason === 'QB_ACCOUNT_SUSPENDED'
                ? t('broadcasts.suspended') : row.original.withdrawReason ?? '' })}
              {row.original.withdrawnBy && <> · <UserName id={row.original.withdrawnBy} /></>}
            </span>
          )}
        </div>
      ),
    },
    {
      id: 'sender',
      header: t('broadcasts.columns.sender'),
      cell: ({ row }) => (row.original.senderKind === 'SPONSOR'
        ? <Link to={PATH.user(row.original.senderId)} className={linkClass}>
          {row.original.sponsorCompany ?? <UserName id={row.original.senderId} />}</Link>
        : <span>{t('broadcasts.platform')} · <UserName id={row.original.senderId} /></span>),
    },
    { id: 'type', header: t('broadcasts.columns.type'), cell: ({ row }) => typeLabel(row.original.type) },
    { id: 'audience', header: t('broadcasts.columns.audience'), cell: ({ row }) => audienceLabel(row.original.audience) },
    {
      id: 'status',
      header: t('broadcasts.columns.status'),
      cell: ({ row }) => <Badge variant="outline">{status(row.original.status)}</Badge>,
    },
    {
      id: 'when',
      header: t('broadcasts.columns.when'),
      cell: ({ row }) => when(row.original.sentAt ?? row.original.scheduledAt),
    },
    { id: 'reached', header: t('broadcasts.columns.reached'), meta: right,
      cell: ({ row }) => row.original.audienceCount ?? '' },
    { id: 'reads', header: t('broadcasts.columns.reads'), meta: right,
      cell: ({ row }) => stat(t, row.original.reads, row.original.audienceCount) },
    { id: 'clicks', header: t('broadcasts.columns.clicks'), meta: right,
      cell: ({ row }) => stat(t, row.original.clicks, row.original.reads) },
    { id: 'conversions', header: t('broadcasts.columns.conversions'), meta: right,
      cell: ({ row }) => stat(t, row.original.conversions, row.original.clicks) },
    {
      id: 'actions',
      header: t('broadcasts.columns.actions'),
      cell: ({ row }) => {
        const own = row.original.senderKind === 'PLATFORM'
        const change = async (run: () => Promise<unknown>) => {
          try {
            await run()
            notify.success(t('broadcasts.actions.done'))
          } catch (error) {
            failed(error, t('common.failed'))
          } finally {
            await queryClient.invalidateQueries({ queryKey: KEY })
          }
        }
        return (
          <div className="flex flex-wrap gap-1">
            {own && row.original.status === 'DRAFT' && (
              <>
                <Button size="sm" variant="outline" onClick={() => setEditing(row.original)}
                  data-testid="broadcast-edit">{t('broadcasts.actions.edit')}</Button>
                <ConfirmDialog title={t('broadcasts.actions.discard')} destructive
                  confirmLabel={t('broadcasts.actions.confirm')}
                  onConfirm={() => change(() => cancelAnnouncement(row.original.broadcastId, row.original.revision))}
                  trigger={<Button size="sm" variant="ghost" data-testid="broadcast-discard">
                    {t('broadcasts.actions.discard')}</Button>} />
              </>
            )}
            {own && row.original.status === 'SCHEDULED' && (
              <ConfirmDialog title={t('broadcasts.actions.unschedule')} confirmLabel={t('broadcasts.actions.confirm')}
                onConfirm={() => change(() => cancelAnnouncement(row.original.broadcastId, row.original.revision))}
                trigger={<Button size="sm" variant="outline" data-testid="broadcast-unschedule">
                  {t('broadcasts.actions.unschedule')}</Button>} />
            )}
            {(row.original.status === 'SENT' || row.original.status === 'SCHEDULED')
              && <Withdraw row={row.original} />}
          </div>
        )
      },
    },
  ], [t, language, status, typeLabel, audienceLabel, queryClient])

  return (
    <div className="flex flex-col gap-4" data-testid="broadcasts">
      <PageHeader title={t('broadcasts.title')} description={t('broadcasts.description')} className="pb-0"
        actions={!editing && (
          <Button onClick={() => setEditing('new')} data-testid="broadcast-new">
            <Plus aria-hidden />
            {t('broadcasts.new')}
          </Button>
        )} />
      {editing && (
        <Editor key={editing === 'new' ? 'new' : editing.broadcastId} row={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)} />
      )}
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('broadcasts.filters.status')}>
        <div className="flex flex-col gap-1">
          <Label htmlFor="broadcasts-sender">{t('broadcasts.filters.sender')}</Label>
          <Select value={filter.senderKind ?? ALL} onValueChange={(value) => setFilter({ ...filter,
            senderKind: value === ALL ? undefined : value })}>
            <SelectTrigger id="broadcasts-sender" className="w-40" data-testid="broadcasts-sender"><SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t('broadcasts.filters.all')}</SelectItem>
              <SelectItem value="PLATFORM">{t('broadcasts.platform')}</SelectItem>
              <SelectItem value="SPONSOR">{t('broadcasts.columns.sender')}</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <StatusFilter dictionary={DICTIONARY.broadcastStatus} value={filter.status ?? null}
          testId="broadcasts-status" onChange={(value) => setFilter({ ...filter, status: value ?? undefined })} />
        <form role="search" className="flex gap-2" onSubmit={(event) => {
          event.preventDefault()
          setFilter({ ...filter, q: text.trim() || undefined })
        }}>
          <Input value={text} maxLength={100} aria-label={t('broadcasts.filters.search')}
            placeholder={t('broadcasts.filters.search')} data-testid="broadcasts-search"
            onChange={(event) => setText(event.target.value)} />
          <Button type="submit" variant="outline" size="icon" aria-label={t('broadcasts.filters.search')}>
            <Search aria-hidden />
          </Button>
        </form>
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('broadcasts.title')} columns={columns} {...table}
            getRowId={(row) => row.broadcastId} empty={t('broadcasts.none')}
            rowProps={(row) => ({ 'data-testid': 'broadcast-row', 'data-id': row.broadcastId,
              'data-status': row.status })} />
        )}
    </div>
  )
}
