import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageState,
  paths,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  UserName,
  useAuth,
} from '@jabiz/admin'
import { LoadFailed, Section } from './components'
import { CONTROL, PERMISSION } from './ids'
import {
  EFFORTS,
  findRunToday,
  MODELS,
  proposeSwitch,
  readSwitch,
  readSwitchProposals,
  SWITCH_KEYS,
  type SwitchKey,
  type SwitchSetting,
} from './prospect-switch-api'

const KEY = ['qb-ext', 'prospect-switch'] as const
const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
/** The messages' key of each parameter (`prospect.switch.<label>`). */
const LABEL: Record<SwitchKey, string> = {
  'qb.prospect.enabled': 'enabled',
  'qb.prospect.model': 'model',
  'qb.prospect.effort': 'effort',
}

/** The form: the three as proposed, starting from the values in force (or the setup's defaults). */
function ProposeForm({ current }: { current: Record<SwitchKey, SwitchSetting> }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [enabled, setEnabled] = useState(current['qb.prospect.enabled'].value === 'true')
  const [model, setModel] = useState(current['qb.prospect.model'].value ?? MODELS[0])
  const [effort, setEffort] = useState(current['qb.prospect.effort'].value ?? 'medium')
  const [reason, setReason] = useState('')
  const [local, setLocal] = useState<string | null>(null)
  const propose = useMutation({
    mutationFn: () => proposeSwitch(current,
      { 'qb.prospect.enabled': String(enabled), 'qb.prospect.model': model, 'qb.prospect.effort': effort }, reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: KEY }),
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!reason.trim()) {
      setLocal(t('prospect.switch.reasonRequired'))
      return
    }
    setLocal(null)
    propose.mutate()
  }
  return (
    <form noValidate onSubmit={submit} className="flex max-w-xl flex-col gap-4" data-testid="switch-form">
      <div className="flex items-center gap-2">
        <Checkbox id={`${id}-enabled`} checked={enabled} data-testid="switch-enabled"
          onCheckedChange={(value) => setEnabled(value === true)} />
        <Label htmlFor={`${id}-enabled`} className="font-normal">{t('prospect.switch.enabledLabel')}</Label>
      </div>
      <div className="flex flex-wrap gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-model`}>{t('prospect.switch.model')}</Label>
          <Select value={model} onValueChange={setModel}>
            <SelectTrigger id={`${id}-model`} className="w-56" data-testid="switch-model"><SelectValue /></SelectTrigger>
            <SelectContent>
              {MODELS.map((m) => <SelectItem key={m} value={m}>{m}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-effort`}>{t('prospect.switch.effort')}</Label>
          <Select value={effort} onValueChange={setEffort}>
            <SelectTrigger id={`${id}-effort`} className="w-40" data-testid="switch-effort"><SelectValue /></SelectTrigger>
            <SelectContent>
              {EFFORTS.map((e) => <SelectItem key={e} value={e}>{t(`prospect.switch.efforts.${e}`)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-reason`}>{t('prospect.switch.reason')}</Label>
        <Input id={`${id}-reason`} value={reason} maxLength={500} data-testid="switch-reason"
          aria-invalid={local ? true : undefined} aria-describedby={local ? `${id}-problem` : undefined}
          onChange={(event) => setReason(event.target.value)} />
      </div>
      {local && <p id={`${id}-problem`} role="alert" className="text-destructive text-sm">{local}</p>}
      {propose.isError && (
        <p role="alert" className="text-destructive text-sm" data-testid="switch-problem">
          {propose.error instanceof ApiError ? propose.error.display : t('common.failed')}
        </p>
      )}
      {propose.isSuccess && (
        <p role="status" className="text-sm" data-testid="switch-proposed">
          {propose.data.length ? t('prospect.switch.proposed') : t('prospect.switch.unchanged')}
        </p>
      )}
      <div>
        <Button type="submit" disabled={propose.isPending} aria-busy={propose.isPending || undefined}
          data-testid="switch-propose">
          {t('prospect.switch.propose')}
        </Button>
      </div>
    </form>
  )
}

/**
 * The platform switch of AI prospecting (F-F8, T9): the three controlled parameters in force, their open proposals,
 * a warning when the owner prospects every day but the switch is off, when the daily search runs (in the reader's
 * zone; a missed time is not run later), and, for `control.propose`, proposing new values; another administrator
 * publishes them. Headquarters only (`qb.admin.prospect`): the parameters hold every owner's prospecting.
 */
export function ProspectSwitch({ prospectsDaily }: { prospectsDaily: boolean }) {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const readsChanges = can(PERMISSION.approvalRead)
  const settings = useQuery({ queryKey: [...KEY, 'settings'], queryFn: readSwitch })
  const proposals = useQuery({ queryKey: [...KEY, 'proposals'], queryFn: readSwitchProposals, enabled: readsChanges })
  const run = findRunToday()
  const local = new Intl.DateTimeFormat(i18n.language, { hour: '2-digit', minute: '2-digit', timeZoneName: 'short' })
    .format(run)
  const utc = new Intl.DateTimeFormat(i18n.language, { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' }).format(run)

  let body
  if (settings.isError) body = <LoadFailed error={settings.error} onRetry={() => void settings.refetch()} />
  else if (settings.isPending) body = <PageState kind="loading" />
  else {
    const on = settings.data['qb.prospect.enabled'].value === 'true'
    body = (
      <>
        {prospectsDaily && !on && (
          <Alert variant="destructive" data-testid="switch-off-warning">
            <AlertDescription>{t('prospect.switch.offWarning')}</AlertDescription>
          </Alert>
        )}
        <dl className="grid max-w-xl grid-cols-[auto_1fr] gap-x-4 gap-y-2" data-testid="switch-values">
          {SWITCH_KEYS.map((key) => {
            const s = settings.data[key]
            return (
              <div key={key} className="contents" data-testid="switch-value" data-key={key}>
                <dt className="font-medium">{t(`prospect.switch.${LABEL[key]}`)}</dt>
                <dd className="flex flex-wrap items-center gap-2">
                  {!s.exists ? <Badge variant="warning">{t('prospect.switch.notPublished')}</Badge>
                    : key === 'qb.prospect.enabled'
                      ? <Badge variant={on ? 'success' : 'outline'}>{on ? t('prospect.switch.on') : t('prospect.switch.off')}</Badge>
                      : <code>{s.value}</code>}
                  <code className="text-muted-foreground text-xs">{key}</code>
                </dd>
              </div>
            )
          })}
        </dl>
        {readsChanges && (proposals.isError
          ? <LoadFailed error={proposals.error} onRetry={() => void proposals.refetch()} />
          : proposals.data && proposals.data.length > 0 && (
            <div className="flex flex-col gap-2" data-testid="switch-proposals">
              <h3 className="font-medium">{t('prospect.switch.open')}</h3>
              <ul className="flex flex-col gap-1 text-sm">
                {proposals.data.map((p) => (
                  <li key={p.changeId} data-testid="switch-proposal" data-key={p.key}>
                    {t(`prospect.switch.${LABEL[p.key]}`)}: <code>{p.value ?? '—'}</code>{' · '}
                    <UserName id={p.proposedBy} />{p.reason ? ` · ${p.reason}` : ''}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        {can(PERMISSION.controlPropose)
          ? <ProposeForm key={SWITCH_KEYS.map((k) => settings.data[k].value).join('|')} current={settings.data} />
          : <p className="text-muted-foreground text-sm" data-testid="switch-no-propose">{t('prospect.switch.noPropose')}</p>}
        {(can(PERMISSION.controlPropose) || can(PERMISSION.controlPublish)) && (
          <Link to={paths.dataset(CONTROL.changes)} className={linkClass}>{t('prospect.switch.changes')}</Link>
        )}
      </>
    )
  }
  return (
    <Section title={t('prospect.switch.title')} testId="prospect-switch">
      <p className="text-muted-foreground text-sm">{t('prospect.switch.hint')}</p>
      <p className="text-sm" data-testid="switch-run-time">{t('prospect.switch.runTime', { local, utc })}</p>
      {body}
    </Section>
  )
}
