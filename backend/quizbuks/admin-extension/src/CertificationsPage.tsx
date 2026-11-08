import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import {
  addRound,
  CERTIFICATIONS_KEY,
  closeRound,
  isUuid,
  readOverview,
  readOverviewOf,
  recordByHand,
  type HandRecord,
  type Round,
} from './certification-api'
import { EmptyState, LoadFailed, Section } from './components'
import { when } from './hooks'
import { CERTIFICATIONS, PATH, PERMISSION } from './ids'
import { useAcrossOperators } from './operator-filter'
import { OperatorPicker } from './pickers'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const REASON_MAX = 500

const problemOf = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

/** A certification's name in the interface language (its key when the page does not know it). */
function useCertificationName() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (key: string) => t(`certification.names.${key}`, { defaultValue: key })
}

/** The certifications to choose from. */
function CertificationSelect({ id, value, onChange, testId }: { id: string; value: string;
  onChange: (value: string) => void; testId: string }) {
  const name = useCertificationName()
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger id={id} className="w-72" data-testid={testId}><SelectValue /></SelectTrigger>
      <SelectContent>
        {CERTIFICATIONS.map((key) => <SelectItem key={key} value={key}>{name(key)}</SelectItem>)}
      </SelectContent>
    </Select>
  )
}

/** Closes a round after asking; a closed round counts only the passes before it. */
function CloseRound({ round }: { round: Round }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queryClient = useQueryClient()
  return (
    <ConfirmDialog title={t('certification.closeTitle')} description={t('certification.closeText')}
      confirmLabel={t('certification.close')} destructive
      onConfirm={async () => {
        try {
          await closeRound(round.roundId)
          notify.success(t('certification.closed'))
        } catch (e) {
          notify.error(problemOf(e))
          throw e
        } finally {
          await queryClient.invalidateQueries({ queryKey: CERTIFICATIONS_KEY })
        }
      }}
      trigger={<Button variant="outline" size="sm" data-testid="round-close">{t('certification.close')}</Button>} />
  )
}

/** Makes a publication of the caller's official publisher a round (`QB_CERTIFICATION_ROUND_ADD`). */
function AddRound() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [publicationId, setPublicationId] = useState('')
  const [certification, setCertification] = useState<string>(CERTIFICATIONS[0])
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const typed = publicationId.trim()
  const invalid = typed !== '' && !isUuid(typed)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !typed || invalid) return
    setBusy(true)
    setProblem(null)
    try {
      await addRound(typed, certification)
      notify.success(t('certification.added'))
      setPublicationId('')
      await queryClient.invalidateQueries({ queryKey: CERTIFICATIONS_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-3"
      aria-labelledby={`${id}-title`} data-testid="round-add-form">
      <h3 id={`${id}-title`} className="text-sm font-semibold">{t('certification.add')}</h3>
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-publication`}>{t('certification.publicationId')}</Label>
          <Input id={`${id}-publication`} value={publicationId} maxLength={36} className="w-80"
            aria-invalid={invalid || undefined} aria-describedby={`${id}-publication-hint`}
            data-testid="round-publication" onChange={(event) => setPublicationId(event.target.value)} />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-certification`}>{t('certification.certification')}</Label>
          <CertificationSelect id={`${id}-certification`} value={certification} onChange={setCertification}
            testId="round-certification" />
        </div>
        <Button type="submit" disabled={busy || !typed || invalid} aria-busy={busy || undefined}
          data-testid="round-add">{t('certification.addSubmit')}</Button>
      </div>
      <p id={`${id}-publication-hint`} className="text-muted-foreground text-sm" data-testid="round-publication-hint">
        {invalid ? t('certification.publicationIdInvalid') : t('certification.addHint')}
      </p>
      {problem && <Alert variant="destructive" data-testid="round-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
    </form>
  )
}

/** Grants or revokes a certification by hand, with a reason, after asking. */
function ByHand() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [userId, setUserId] = useState('')
  const [certification, setCertification] = useState<string>(CERTIFICATIONS[0])
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const ready = !!userId.trim() && !!reason.trim()
  const act = (kind: 'grant' | 'revoke') => async () => {
    setProblem(null)
    try {
      await recordByHand(kind, { userId: userId.trim(), certification, reason: reason.trim() })
      notify.success(t(kind === 'grant' ? 'certification.granted' : 'certification.revoked'))
      setReason('')
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      await queryClient.invalidateQueries({ queryKey: CERTIFICATIONS_KEY })
    }
  }
  return (
    <form noValidate onSubmit={(event) => event.preventDefault()} className="flex flex-col gap-3"
      data-testid="by-hand-form">
      <p className="text-muted-foreground text-sm">{t('certification.byHandHint')}</p>
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-user`}>{t('certification.userId')}</Label>
          <Input id={`${id}-user`} value={userId} maxLength={128} className="w-80" data-testid="by-hand-user"
            onChange={(event) => setUserId(event.target.value)} />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-certification`}>{t('certification.certification')}</Label>
          <CertificationSelect id={`${id}-certification`} value={certification} onChange={setCertification}
            testId="by-hand-certification" />
        </div>
      </div>
      <div className="flex max-w-2xl flex-col gap-2">
        <Label htmlFor={`${id}-reason`}>{t('certification.reason')}</Label>
        <Textarea id={`${id}-reason`} value={reason} maxLength={REASON_MAX} required data-testid="by-hand-reason"
          onChange={(event) => setReason(event.target.value)} />
      </div>
      <div className="flex flex-wrap gap-2">
        <ConfirmDialog title={t('certification.grantTitle')} description={t('certification.grantText')}
          confirmLabel={t('certification.grant')} onConfirm={act('grant')}
          trigger={<Button type="button" disabled={!ready} data-testid="by-hand-grant">{t('certification.grant')}</Button>} />
        <ConfirmDialog title={t('certification.revokeTitle')} description={t('certification.revokeText')}
          confirmLabel={t('certification.revoke')} destructive onConfirm={act('revoke')}
          trigger={<Button type="button" variant="outline" disabled={!ready}
            data-testid="by-hand-revoke">{t('certification.revoke')}</Button>} />
      </div>
      {problem && <Alert variant="destructive" data-testid="by-hand-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
    </form>
  )
}

/**
 * Certifications (docs/quizbuks/plans/Q69-certifications.md): an operator's rounds with how many passes each recorded,
 * adding and closing them, grants and revocations by hand with their reasons (what the platform spot-checks). F-S1b:
 * the platform reads an operator's, chosen by name, and runs none of it; the official publisher is each operator's own
 * Sponsor. Who holds what is the server's: the page shows the overview's counts.
 */
export default function CertificationsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const name = useCertificationName()
  // F-S1b: the platform reads an operator's (chosen), an operator's member its own.
  const across = useAcrossOperators() && can(PERMISSION.certificationAdmin)
  const [operatorId, setOperatorId] = useState<string | null>(null)
  const overview = useQuery({ queryKey: [...CERTIFICATIONS_KEY, across ? operatorId : 'own'],
    queryFn: () => (across ? readOverviewOf(operatorId as string) : readOverview()), enabled: !across || !!operatorId })
  const pickerId = useId()
  const linkPublications = can(PERMISSION.publicationRead)
  const roundColumns = useMemo<ColumnDef<Round, unknown>[]>(() => [
    { id: 'publication', header: t('certification.publication'), cell: ({ row }) => {
      const label = row.original.publicationName ?? row.original.publicationId
      return linkPublications
        ? <Link to={PATH.publication(row.original.publicationId)} className={linkClass}
          data-testid="round-publication-link">{label}</Link>
        : <span>{label}</span>
    } },
    { id: 'certification', header: t('certification.certification'), cell: ({ row }) => name(row.original.certification) },
    { id: 'addedAt', header: t('certification.addedAt'), cell: ({ row }) => when(row.original.addedAt) },
    { id: 'closedAt', header: t('certification.closedAt'), cell: ({ row }) => (row.original.closedAt
      ? <span data-testid="round-closed">{when(row.original.closedAt)}</span>
      : across ? '—' : <CloseRound round={row.original} />) },
    { id: 'holders', header: t('certification.holders'), meta: right, cell: ({ row }) => row.original.holders },
  ], [t, name, linkPublications, across])
  const recordColumns = useMemo<ColumnDef<HandRecord, unknown>[]>(() => [
    { id: 'userId', header: t('certification.person'), cell: ({ row }) => <UserName id={row.original.userId} /> },
    { id: 'certification', header: t('certification.certification'), cell: ({ row }) => name(row.original.certification) },
    { id: 'kind', header: t('certification.kind'), cell: ({ row }) => (
      <Badge variant={row.original.kind === 'REVOKED' ? 'outline' : 'success'} data-kind={row.original.kind}>
        {t(`certification.kinds.${row.original.kind}`, { defaultValue: row.original.kind })}
      </Badge>
    ) },
    { id: 'reason', header: t('certification.reason'),
      cell: ({ row }) => <span className="whitespace-pre-wrap" data-testid="record-reason">{row.original.reason}</span> },
    { id: 'actor', header: t('certification.by'),
      cell: ({ row }) => (row.original.actor ? <UserName id={row.original.actor} /> : '') },
    { id: 'at', header: t('certification.at'), cell: ({ row }) => when(row.original.at) },
    { id: 'validUntil', header: t('certification.validUntil'), cell: ({ row }) => when(row.original.validUntil) || '—' },
  ], [t, name])
  const data = overview.data
  return (
    <div className="flex flex-col gap-4" data-testid="certifications-page">
      <PageHeader title={t('certification.title')} description={t('certification.description')} className="pb-0" />
      {across && (
        <div className="flex w-72 flex-col gap-1">
          <Label htmlFor={pickerId}>{t('pickers.byOperator')}</Label>
          <OperatorPicker id={pickerId} value={operatorId} onChange={setOperatorId}
            data-testid="certifications-operator" />
          <p className="text-muted-foreground text-sm">{t('certification.pickOperator')}</p>
        </div>
      )}
      {across && !operatorId ? null : overview.isError
        ? <LoadFailed error={overview.error} onRetry={() => void overview.refetch()} />
        : (
          <>
            <Section title={t('certification.rounds')} testId="rounds-section">
              <p className="text-muted-foreground text-sm">{t('certification.roundsHint')}</p>
              <DataTable label={t('certification.rounds')} columns={roundColumns} data={data?.rounds ?? []}
                loading={overview.isPending} getRowId={(row) => row.roundId}
                empty={<EmptyState art="inbox" title={t('certification.roundsNone')} />}
                rowProps={(row) => ({ 'data-testid': 'round-row', 'data-id': row.roundId })} />
              {!across && <AddRound />}
            </Section>
            <Section title={t('certification.byHand')} testId="by-hand-section">
              {!across && <ByHand />}
              <h3 className="text-sm font-semibold">{t('certification.records')}</h3>
              <DataTable label={t('certification.records')} columns={recordColumns} data={data?.byHand ?? []}
                loading={overview.isPending} getRowId={(row) => row.recordId}
                empty={<EmptyState art="inbox" title={t('certification.recordsNone')} />}
                rowProps={(row) => ({ 'data-testid': 'record-row', 'data-id': row.recordId,
                  'data-kind': row.kind })} />
            </Section>
          </>
        )}
    </div>
  )
}
