import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Button,
  Checkbox,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  notify,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CirclePause, CirclePlay } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import {
  BENEFITS_QUERY,
  readIncidents,
  recordIncident,
  resolveIncident,
  resumePurchase,
  suspendPurchase,
  type Incident,
  type Purchase,
  type Side,
} from './api'
import { BENEFIT, INCIDENT_KINDS, type IncidentKind } from './ids'
import './messages'

/**
 * Q89d-1 (D-Q89-12, D-Q89-13): a confirmed or suspended purchase, after the fact: what became of it, its suspension
 * (either side suspends, which pauses the publications drawing on it, and resumes it), and its incidents. The
 * server decides everything; the page shows what it says.
 */
export function PurchaseFollowUp({ purchase, side }: { purchase: Purchase; side: Side }) {
  return (
    <div className="flex flex-col gap-6" data-testid="benefit-follow-up">
      <Stats purchase={purchase} />
      <Suspension purchase={purchase} />
      <Incidents purchase={purchase} side={side} />
    </div>
  )
}

function Stats({ purchase }: { purchase: Purchase }) {
  const { t } = useTranslation(SPONSOR)
  const s = purchase.stats
  const left = Math.max(0, purchase.quantity - s.allocated - s.given)
  const items: [string, number][] = [['quantity', purchase.quantity], ['allocated', s.allocated],
    ['given', s.given], ['left', left], ['issued', s.issued], ['collected', s.collected], ['expired', s.expired],
    ['voided', s.voided]]
  return (
    <section className="flex flex-col gap-2" aria-labelledby="benefit-stats-title" data-testid="benefit-stats">
      <h2 id="benefit-stats-title" className="text-lg font-semibold">{t('benefits.followUp.statsTitle')}</h2>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-4">
        {items.map(([key, value]) => (
          <div key={key} className="flex flex-col">
            <dt className="text-muted-foreground">{t(`benefits.followUp.stats.${key}`)}</dt>
            <dd className="font-medium tabular-nums" data-testid={`benefit-stat-${key}`}>{value.toLocaleString()}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

function Suspension({ purchase }: { purchase: Purchase }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [voidUnused, setVoidUnused] = useState(false)
  const refresh = () => void queryClient.invalidateQueries({ queryKey: BENEFITS_QUERY })
  const suspend = useMutation({
    mutationFn: () => suspendPurchase(purchase.purchaseId, reason.trim(), voidUnused),
    onSuccess: (state) => {
      notify.success(t('benefits.followUp.suspended', { count: state.paused }))
      setOpen(false)
      refresh()
    },
  })
  if (purchase.status === 'SUSPENDED') {
    return (
      <Alert variant="warning" data-testid="benefit-suspended">
        <CirclePause aria-hidden />
        <AlertTitle>{t('benefits.followUp.suspendedTitle')}</AlertTitle>
        <AlertDescription className="text-current flex flex-col gap-2">
          <span>{t('benefits.followUp.suspendedText', { when: purchase.suspendedAt
            ? formatDateTime(purchase.suspendedAt) : '' })}</span>
          {purchase.suspendReason && <span className="whitespace-pre-wrap" data-testid="benefit-suspend-reason">
            {purchase.suspendReason}</span>}
          {purchase.voidUnused && <span>{t('benefits.followUp.voiding')}</span>}
          <ConfirmDialog title={t('benefits.followUp.resumeTitle')} description={t('benefits.followUp.resumeText')}
            confirmLabel={t('benefits.followUp.resume')} cancelLabel={t('benefits.offers.cancel')}
            onConfirm={() => resumePurchase(purchase.purchaseId).then(() => {
              notify.success(t('benefits.followUp.resumed'))
              refresh()
            }, (problem) => {
              notify.error(problemText(problem))
              throw problem
            })}
            trigger={<Button size="sm" variant="outline" className="w-fit" data-testid="benefit-resume">
              <CirclePlay aria-hidden />{t('benefits.followUp.resume')}</Button>} />
        </AlertDescription>
      </Alert>
    )
  }
  if (purchase.status !== 'CONFIRMED') return null
  return (
    <section className="flex flex-col gap-2" aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className="text-lg font-semibold">{t('benefits.followUp.suspendTitle')}</h2>
      <p className="text-muted-foreground text-sm">{t('benefits.followUp.suspendIntro')}</p>
      <Button variant="outline" className="w-fit" onClick={() => setOpen(true)} data-testid="benefit-suspend">
        <CirclePause aria-hidden />{t('benefits.followUp.suspend')}</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent data-testid="benefit-suspend-dialog">
          <DialogHeader>
            <DialogTitle>{t('benefits.followUp.suspendTitle')}</DialogTitle>
            <DialogDescription>{t('benefits.followUp.suspendText')}</DialogDescription>
          </DialogHeader>
          <form id={`${id}-form`} className="flex flex-col gap-3" onSubmit={(event) => {
            event.preventDefault()
            if (reason.trim()) suspend.mutate()
          }}>
            <Label htmlFor={`${id}-reason`}>{t('benefits.followUp.reason')}</Label>
            <Textarea id={`${id}-reason`} rows={3} value={reason} maxLength={BENEFIT.reasonLength}
              onChange={(event) => setReason(event.target.value)} data-testid="benefit-suspend-reason-input" />
            <div className="flex items-start gap-2">
              <Checkbox id={`${id}-void`} checked={voidUnused} onCheckedChange={(v) => setVoidUnused(v === true)}
                data-testid="benefit-suspend-void" />
              <Label htmlFor={`${id}-void`} className="font-normal">{t('benefits.followUp.voidUnused')}</Label>
            </div>
            {suspend.isError && <p role="alert" className="text-destructive text-sm">{problemText(suspend.error)}</p>}
          </form>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>{t('benefits.offers.cancel')}</Button>
            <Button type="submit" form={`${id}-form`} disabled={!reason.trim() || suspend.isPending}
              data-testid="benefit-suspend-send">{t('benefits.followUp.suspend')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  )
}

function Incidents({ purchase, side }: { purchase: Purchase; side: Side }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const key = [...BENEFITS_QUERY, 'incidents', side, purchase.purchaseId]
  const incidents = useQuery({ queryKey: key, queryFn: () => readIncidents(side, purchase.purchaseId) })
  const [kind, setKind] = useState<IncidentKind>('NOT_FULFILLED')
  const [note, setNote] = useState('')
  const record = useMutation({
    mutationFn: () => recordIncident(purchase.purchaseId, kind, note),
    onSuccess: async () => {
      notify.success(t('benefits.followUp.recorded'))
      setNote('')
      await queryClient.invalidateQueries({ queryKey: key })
    },
  })
  return (
    <section className="flex flex-col gap-3" aria-labelledby={`${id}-title`} data-testid="benefit-incidents">
      <h2 id={`${id}-title`} className="text-lg font-semibold">{t('benefits.followUp.incidentsTitle')}</h2>
      <p className="text-muted-foreground text-sm">{t('benefits.followUp.incidentsIntro')}</p>
      {incidents.isError && <p role="alert" className="text-destructive text-sm">{problemText(incidents.error)}</p>}
      {incidents.data?.length === 0 && <p className="text-muted-foreground text-sm">{t('benefits.followUp.none')}</p>}
      <ul className="flex flex-col gap-3">
        {incidents.data?.map((incident) => (
          <IncidentRow key={incident.incidentId} incident={incident} side={side} queryKey={key} />
        ))}
      </ul>
      <form className="flex flex-col gap-2 rounded-lg border p-3" onSubmit={(event) => {
        event.preventDefault()
        if (note.trim()) record.mutate()
      }}>
        <Label htmlFor={`${id}-kind`}>{t('benefits.followUp.kind')}</Label>
        <Select value={kind} onValueChange={(value) => setKind(value as IncidentKind)}>
          <SelectTrigger id={`${id}-kind`} className="w-fit" data-testid="benefit-incident-kind"><SelectValue /></SelectTrigger>
          <SelectContent>
            {INCIDENT_KINDS.map((k) => <SelectItem key={k} value={k}>{t(`benefits.followUp.kinds.${k}`)}</SelectItem>)}
          </SelectContent>
        </Select>
        <Label htmlFor={`${id}-note`}>{t('benefits.followUp.note')}</Label>
        <Textarea id={`${id}-note`} rows={3} value={note} maxLength={2000} data-testid="benefit-incident-note"
          onChange={(event) => setNote(event.target.value)} />
        {record.isError && <p role="alert" className="text-destructive text-sm">{problemText(record.error)}</p>}
        <Button type="submit" className="w-fit" disabled={!note.trim() || record.isPending}
          data-testid="benefit-incident-record">{t('benefits.followUp.record')}</Button>
      </form>
    </section>
  )
}

function IncidentRow({ incident, side, queryKey }: { incident: Incident; side: Side; queryKey: unknown[] }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [resolution, setResolution] = useState('')
  const resolve = useMutation({
    mutationFn: () => resolveIncident(incident.incidentId, resolution),
    onSuccess: async () => {
      notify.success(t('benefits.followUp.resolved'))
      await queryClient.invalidateQueries({ queryKey })
    },
  })
  const who = (by: Side) => t(by === side ? 'benefits.followUp.byYou' : 'benefits.followUp.byOther')
  return (
    <li className="flex flex-col gap-2 rounded-lg border p-3 text-sm" data-testid="benefit-incident">
      <span className="font-medium">{t(`benefits.followUp.kinds.${incident.kind}`)}</span>
      <span className="whitespace-pre-wrap">{incident.note}</span>
      <span className="text-muted-foreground text-xs">
        {who(incident.recordedBy)} · {formatDateTime(incident.recordedAt)}</span>
      {incident.resolvedAt ? (
        <span data-testid="benefit-incident-resolution">
          {t('benefits.followUp.outcome', { who: who(incident.resolvedBy ?? side), text: incident.resolution ?? '' })}
        </span>
      ) : (
        <form className="flex flex-col gap-2" onSubmit={(event) => {
          event.preventDefault()
          if (resolution.trim()) resolve.mutate()
        }}>
          <Label htmlFor={`${id}-resolution`}>{t('benefits.followUp.resolution')}</Label>
          <Textarea id={`${id}-resolution`} rows={2} value={resolution} maxLength={2000}
            data-testid="benefit-incident-resolution-input" onChange={(event) => setResolution(event.target.value)} />
          {resolve.isError && <p role="alert" className="text-destructive text-sm">{problemText(resolve.error)}</p>}
          <Button type="submit" size="sm" variant="outline" className="w-fit"
            disabled={!resolution.trim() || resolve.isPending} data-testid="benefit-incident-resolve">
            {t('benefits.followUp.resolve')}</Button>
        </form>
      )}
    </li>
  )
}
