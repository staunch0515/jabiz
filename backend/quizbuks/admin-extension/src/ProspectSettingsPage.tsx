import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
  type ColumnDef,
} from '@jabiz/admin'
import { LoadFailed, Section } from './components'
import { when } from './hooks'
import { PATH } from './ids'
import {
  DIRECT,
  PROSPECTS_KEY,
  readDirections,
  readFunnel,
  readSetting,
  readUsage,
  removeMailServer,
  saveDirection,
  saveMailServer,
  saveSettings,
  type Direction,
  type FunnelRow,
  type ProspectSetting,
  type Usage,
} from './prospect-api'
import { useProspectScope } from './prospect-scope'

const problemOf = (e: unknown) => (e instanceof ApiError ? e.display : String(e))
const right = { className: 'text-right tabular-nums' }

/** Runs a change, reports it, and reads the prospecting pages again; false when it was refused. */
function useAct() {
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const act = async (call: () => Promise<unknown>, done: (result: unknown) => string | null) => {
    if (busy) return false
    setBusy(true)
    setProblem(null)
    try {
      const result = await call()
      const text = done(result)
      if (text) notify.success(text)
      await queryClient.invalidateQueries({ queryKey: PROSPECTS_KEY })
      return true
    } catch (e) {
      setProblem(problemOf(e))
      return false
    } finally {
      setBusy(false)
    }
  }
  return { busy, problem, setProblem, act }
}

function Problem({ text, testId }: { text: string | null; testId: string }) {
  return text ? <Alert variant="destructive" data-testid={testId}><AlertDescription>{text}</AlertDescription></Alert> : null
}

/** Whether and how the owner prospects, and the sender its mails name (`QB_PROSPECT_SETTINGS_SAVE`). */
function Settings({ setting, writable }: { setting: ProspectSetting; writable: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const [values, setValues] = useState({
    enabled: setting.enabled, dailyCap: String(setting.dailyCap), minScore: String(setting.minScore),
    monthlyCostCap: setting.monthlyCostCap, senderName: setting.senderName, senderAddress: setting.senderAddress,
    senderContact: setting.senderContact,
    autoApproveScore: setting.autoApproveScore == null ? '' : String(setting.autoApproveScore),
  })
  const { busy, problem, act } = useAct()
  const set = (field: keyof typeof values, value: string | boolean) =>
    setValues((current) => ({ ...current, [field]: value }))
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    await act(() => saveSettings({
      enabled: values.enabled, dailyCap: Number(values.dailyCap), minScore: Number(values.minScore),
      monthlyCostCap: values.monthlyCostCap.trim(), senderName: values.senderName.trim(),
      senderAddress: values.senderAddress.trim(), senderContact: values.senderContact.trim(),
      autoApproveScore: values.autoApproveScore.trim() === '' ? null : Number(values.autoApproveScore),
      baseRevision: setting.revision,
    }), () => t('prospect.settings.saved'))
  }
  const text = (field: 'senderName' | 'senderAddress' | 'senderContact', max: number, type = 'text') => (
    <div className="flex flex-col gap-2">
      <Label htmlFor={`${id}-${field}`}>{t(`prospect.settings.${field}`)}</Label>
      <Input id={`${id}-${field}`} type={type} value={values[field]} maxLength={max} required readOnly={!writable}
        data-testid={`setting-${field}`} onChange={(event) => set(field, event.target.value)} />
    </div>
  )
  const number = (field: 'dailyCap' | 'minScore' | 'monthlyCostCap' | 'autoApproveScore', min: number,
    max: number) => (
    <div className="flex flex-col gap-2">
      <Label htmlFor={`${id}-${field}`}>{t(`prospect.settings.${field}`)}</Label>
      <Input id={`${id}-${field}`} type="number" inputMode="decimal" min={min} max={max} value={values[field]}
        className="w-40" readOnly={!writable} data-testid={`setting-${field}`}
        onChange={(event) => set(field, event.target.value)} />
    </div>
  )
  return (
    <Section title={t('prospect.settings.title')} testId="prospect-settings">
      <form noValidate className="flex max-w-xl flex-col gap-4" onSubmit={(event) => void submit(event)}>
        <div className="flex items-center gap-2">
          <Checkbox id={`${id}-enabled`} checked={values.enabled} disabled={!writable} data-testid="setting-enabled"
            onCheckedChange={(value) => set('enabled', value === true)} />
          <Label htmlFor={`${id}-enabled`} className="font-normal">{t('prospect.settings.enabled')}</Label>
        </div>
        <div className="flex flex-wrap gap-4">
          {number('dailyCap', 1, 50)}
          {number('minScore', 0, 100)}
          {number('monthlyCostCap', 0, 100000)}
          {number('autoApproveScore', 0, 100)}
        </div>
        <p className="text-muted-foreground text-sm">{t('prospect.settings.autoApproveHint')}</p>
        <p className="text-muted-foreground text-sm">{t('prospect.settings.senderHint')}</p>
        {text('senderName', 100)}
        {text('senderAddress', 300)}
        {text('senderContact', 254, 'email')}
        <Problem text={problem} testId="setting-problem" />
        {writable && (
          <div>
            <Button type="submit" disabled={busy} aria-busy={busy || undefined} data-testid="setting-save">
              {t('prospect.settings.save')}
            </Button>
          </div>
        )}
      </form>
    </Section>
  )
}

/** The mail server the mails leave through, tested by a mail to the caller (`QB_PROSPECT_MAIL_SERVER_SET`). */
function MailServer({ setting, writable }: { setting: ProspectSetting; writable: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const [values, setValues] = useState({
    host: setting.serverHost ?? '', port: String(setting.serverPort ?? 465), tls: setting.serverTls ?? 'SMTPS',
    username: setting.serverUsername ?? '', password: '', fromAddress: setting.fromAddress ?? '',
    fromName: setting.fromName ?? '',
  })
  const { busy, problem, setProblem, act } = useAct()
  const set = (field: keyof typeof values, value: string) => setValues((current) => ({ ...current, [field]: value }))
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    await act(() => saveMailServer({
      host: values.host.trim(), port: Number(values.port), tls: values.tls, username: values.username.trim(),
      password: values.password || null, fromAddress: values.fromAddress.trim(), fromName: values.fromName.trim() || null,
    }), (result) => {
      const out = result as { verified: boolean; error: string | null; sentTo: string | null }
      if (!out.verified) {
        setProblem(t('prospect.settings.serverFailed', { error: out.error ?? '' }))
        return null
      }
      return t('prospect.settings.serverSaved', { to: out.sentTo ?? '' })
    })
    setValues((current) => ({ ...current, password: '' }))
  }
  const field = (name: 'host' | 'username' | 'fromAddress' | 'fromName', max: number) => (
    <div className="flex flex-col gap-2">
      <Label htmlFor={`${id}-${name}`}>{t(`prospect.settings.${name}`)}</Label>
      <Input id={`${id}-${name}`} value={values[name]} maxLength={max} autoComplete="off" readOnly={!writable}
        data-testid={`server-${name}`} onChange={(event) => set(name, event.target.value)} />
    </div>
  )
  return (
    <Section title={t('prospect.settings.server')} testId="prospect-server">
      <p className="text-muted-foreground text-sm">{t('prospect.settings.serverHint')}</p>
      <p data-testid="server-state">
        {setting.verifiedAt
          ? <Badge variant="secondary">{t('prospect.settings.verified', { at: when(setting.verifiedAt) })}</Badge>
          : setting.serverHost
            ? <Badge variant="destructive">{t('prospect.settings.notVerified', { error: setting.lastError ?? '' })}</Badge>
            : <span className="text-muted-foreground">{t('prospect.settings.noServer')}</span>}
      </p>
      {writable && (
        <form noValidate className="flex max-w-xl flex-col gap-4" onSubmit={(event) => void submit(event)}>
          {field('host', 253)}
          <div className="flex flex-wrap gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-port`}>{t('prospect.settings.port')}</Label>
              <Input id={`${id}-port`} type="number" min={1} max={65535} value={values.port} className="w-32"
                onChange={(event) => set('port', event.target.value)} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-tls`}>{t('prospect.settings.tls')}</Label>
              <Select value={values.tls} onValueChange={(value) => set('tls', value)}>
                <SelectTrigger id={`${id}-tls`} className="w-40"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="SMTPS">SMTPS</SelectItem>
                  <SelectItem value="STARTTLS">STARTTLS</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          {field('username', 254)}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-password`}>{t('prospect.settings.password')}</Label>
            <Input id={`${id}-password`} type="password" value={values.password} autoComplete="new-password"
              aria-describedby={`${id}-password-hint`} onChange={(event) => set('password', event.target.value)} />
            <p id={`${id}-password-hint`} className="text-muted-foreground text-sm">{t('prospect.settings.passwordHint')}</p>
          </div>
          {field('fromAddress', 254)}
          {field('fromName', 100)}
          <Problem text={problem} testId="server-problem" />
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={busy} aria-busy={busy || undefined} data-testid="server-save">
              {t('prospect.settings.saveServer')}
            </Button>
            {setting.serverHost && (
              <Button type="button" variant="outline" disabled={busy} data-testid="server-remove"
                onClick={() => void act(removeMailServer, () => t('prospect.settings.serverRemoved'))}>
                {t('prospect.settings.removeServer')}
              </Button>
            )}
          </div>
        </form>
      )}
    </Section>
  )
}

/** A direction's fields, for a new one or a change with the revision read (`QB_PROSPECT_DIRECTION_SAVE`). */
function DirectionForm({ direction, onDone }: { direction: Direction | null; onDone: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const [values, setValues] = useState({
    name: direction?.name ?? '', brief: direction?.brief ?? '', country: direction?.country ?? '',
    language: direction?.language ?? '', perSearch: String(direction?.perSearch ?? 5), active: direction?.active ?? true,
  })
  const { busy, problem, act } = useAct()
  const set = (field: keyof typeof values, value: string | boolean) =>
    setValues((current) => ({ ...current, [field]: value }))
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (await act(() => saveDirection({
      directionId: direction?.directionId, name: values.name.trim(), brief: values.brief.trim(),
      country: values.country.trim() || null, language: values.language.trim() || null, active: values.active,
      perSearch: Number(values.perSearch), baseRevision: direction?.revision,
    }), () => t('prospect.settings.directionSaved'))) onDone()
  }
  return (
    <form noValidate className="flex max-w-xl flex-col gap-4 rounded-md border p-4" data-testid="direction-form"
      onSubmit={(event) => void submit(event)}>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-name`}>{t('prospect.settings.name')}</Label>
        <Input id={`${id}-name`} value={values.name} maxLength={100} required data-testid="direction-name"
          onChange={(event) => set('name', event.target.value)} />
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-brief`}>{t('prospect.settings.brief')}</Label>
        <Textarea id={`${id}-brief`} value={values.brief} maxLength={1000} required data-testid="direction-brief"
          onChange={(event) => set('brief', event.target.value)} />
      </div>
      <div className="flex flex-wrap gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-country`}>{t('prospect.settings.countryCode')}</Label>
          <Input id={`${id}-country`} value={values.country} maxLength={2} className="w-24"
            onChange={(event) => set('country', event.target.value)} />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-language`}>{t('prospect.settings.languageCode')}</Label>
          <Input id={`${id}-language`} value={values.language} maxLength={2} className="w-24"
            onChange={(event) => set('language', event.target.value)} />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-per`}>{t('prospect.settings.perSearch')}</Label>
          <Input id={`${id}-per`} type="number" min={1} max={20} value={values.perSearch} className="w-24"
            onChange={(event) => set('perSearch', event.target.value)} />
        </div>
      </div>
      <div className="flex items-center gap-2">
        <Checkbox id={`${id}-active`} checked={values.active} onCheckedChange={(value) => set('active', value === true)} />
        <Label htmlFor={`${id}-active`} className="font-normal">{t('prospect.settings.active')}</Label>
      </div>
      <Problem text={problem} testId="direction-problem" />
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={busy} aria-busy={busy || undefined} data-testid="direction-save">
          {t('prospect.settings.saveDirection')}
        </Button>
        <Button type="button" variant="outline" onClick={onDone}>{t('prospect.cancel')}</Button>
      </div>
    </form>
  )
}

function Directions({ directions, writable }: { directions: Direction[]; writable: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [editing, setEditing] = useState<Direction | 'new' | null>(null)
  const columns: ColumnDef<Direction, unknown>[] = [
    { id: 'name', header: t('prospect.settings.name'), cell: ({ row }) => (
      <span className="flex flex-col">
        <span className="font-medium">{row.original.name}</span>
        <span className="text-muted-foreground text-sm">{row.original.brief}</span>
      </span>
    ) },
    { id: 'active', header: t('prospect.settings.active'),
      cell: ({ row }) => (row.original.active ? <Badge variant="secondary">{t('prospect.settings.active')}</Badge> : '') },
    { id: 'lastSearch', header: t('prospect.settings.lastSearch'), cell: ({ row }) => when(row.original.lastSearchAt) },
    { id: 'found', header: t('prospect.settings.found'), meta: right, cell: ({ row }) => row.original.found },
    { id: 'sent', header: t('prospect.settings.sent'), meta: right, cell: ({ row }) => row.original.sent },
    {
      id: 'edit',
      header: () => <span className="sr-only">{t('prospect.settings.saveDirection')}</span>,
      cell: ({ row }) => (writable ? (
        <Button size="sm" variant="outline" data-testid="direction-edit" onClick={() => setEditing(row.original)}>
          {t('prospect.settings.saveDirection')}<span className="sr-only">: {row.original.name}</span>
        </Button>
      ) : null),
    },
  ]
  return (
    <Section title={t('prospect.settings.directions')} testId="prospect-directions" actions={writable && !editing && (
      <Button size="sm" onClick={() => setEditing('new')} data-testid="direction-add">
        {t('prospect.settings.addDirection')}
      </Button>
    )}>
      <p className="text-muted-foreground text-sm">{t('prospect.settings.directionsHint')}</p>
      {editing && <DirectionForm key={editing === 'new' ? 'new' : editing.directionId}
        direction={editing === 'new' ? null : editing} onDone={() => setEditing(null)} />}
      <DataTable label={t('prospect.settings.directions')} columns={columns} data={directions}
        getRowId={(row) => row.directionId} empty={t('prospect.settings.noDirections')}
        rowProps={(row) => ({ 'data-testid': 'direction-row', 'data-id': row.directionId })} />
    </Section>
  )
}

function Funnel({ rows }: { rows: FunnelRow[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const count = (key: keyof FunnelRow, label: string): ColumnDef<FunnelRow, unknown> =>
    ({ id: key, header: t(`prospect.settings.${label}`), meta: right, cell: ({ row }) => row.original[key] })
  const columns: ColumnDef<FunnelRow, unknown>[] = [
    { id: 'name', header: t('prospect.settings.name'), cell: ({ row }) => row.original.name },
    count('found', 'found'), count('researched', 'researched'), count('drafted', 'drafted'), count('sent', 'sent'),
    count('followedUp', 'followedUp'), count('signedUp', 'signedUp'), count('approved', 'approvedSponsors'),
  ]
  return (
    <Section title={t('prospect.settings.funnel')} testId="prospect-funnel">
      <DataTable label={t('prospect.settings.funnel')} columns={columns} data={rows}
        getRowId={(row) => row.directionId} empty={t('prospect.settings.noDirections')} />
    </Section>
  )
}

function UsageTable({ usage }: { usage: Usage[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const columns: ColumnDef<Usage, unknown>[] = [
    { id: 'month', header: t('prospect.settings.month'), cell: ({ row }) => row.original.month },
    { id: 'jobs', header: t('prospect.settings.jobs'), meta: right, cell: ({ row }) => row.original.jobs },
    { id: 'searches', header: t('prospect.settings.searches'), meta: right, cell: ({ row }) => row.original.searches },
    { id: 'tokens', header: t('prospect.settings.tokens'), meta: right,
      cell: ({ row }) => `${row.original.inputTokens.toLocaleString()} / ${row.original.outputTokens.toLocaleString()}` },
    { id: 'cost', header: t('prospect.settings.cost'), meta: right,
      cell: ({ row }) => Number(row.original.costUsd).toFixed(2) },
  ]
  return (
    <Section title={t('prospect.settings.usage')} testId="prospect-usage">
      <DataTable label={t('prospect.settings.usage')} columns={columns} data={usage} getRowId={(row) => row.month}
        empty={t('prospect.settings.noUsage')} />
    </Section>
  )
}

/**
 * The owner's prospecting settings (Q48c): the switch, the caps and the sender, the mail server, the directions of
 * search and what it cost by month. Headquarters looking at an operator's (`?owner=`) only reads them.
 */
export default function ProspectSettingsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const scope = useProspectScope()
  const setting = useQuery({ queryKey: [...PROSPECTS_KEY, scope, 'setting'], queryFn: () => readSetting(scope) })
  const directions = useQuery({ queryKey: [...PROSPECTS_KEY, scope, 'directions'],
    queryFn: () => readDirections(scope) })
  const usage = useQuery({ queryKey: [...PROSPECTS_KEY, scope, 'usage'], queryFn: () => readUsage(scope) })
  const funnel = useQuery({ queryKey: [...PROSPECTS_KEY, scope, 'funnel'], queryFn: () => readFunnel(scope) })
  const writable = !scope.admin || scope.owner === DIRECT
  const back = PATH.prospects + (scope.admin ? `?owner=${encodeURIComponent(scope.owner)}` : '')
  return (
    <div className="flex flex-col gap-4" data-testid="prospect-settings-page">
      <Button variant="ghost" size="sm" asChild className="w-fit">
        <Link to={back}><ArrowLeft aria-hidden />{t('prospect.settings.back')}</Link>
      </Button>
      <PageHeader title={t('prospect.settings.title')} description={t('prospect.settings.description')}
        className="pb-0" />
      {setting.isError
        ? <LoadFailed error={setting.error} onRetry={() => void setting.refetch()} />
        : setting.isPending
          ? <PageState kind="loading" />
          : setting.data && (
            <>
              <Settings key={String(setting.data.revision)} setting={setting.data} writable={writable} />
              <MailServer key={`${setting.data.serverHost}-${setting.data.verifiedAt}`} setting={setting.data}
                writable={writable} />
            </>
          )}
      {directions.isError
        ? <LoadFailed error={directions.error} onRetry={() => void directions.refetch()} />
        : <Directions directions={directions.data ?? []} writable={writable} />}
      {funnel.isError
        ? <LoadFailed error={funnel.error} onRetry={() => void funnel.refetch()} />
        : <Funnel rows={funnel.data ?? []} />}
      {usage.isError
        ? <LoadFailed error={usage.error} onRetry={() => void usage.refetch()} />
        : <UsageTable usage={usage.data ?? []} />}
    </div>
  )
}
