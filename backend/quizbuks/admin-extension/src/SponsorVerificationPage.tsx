import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  ApprovalPanel,
  Button,
  DescriptionList,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Label,
  notify,
  PageHeader,
  PageState,
  Textarea,
  useAuth,
  UserName,
  type DescriptionItem,
} from '@jabiz/admin'
import { AfterDecision, LoadFailed, Section, StatusBadge } from './components'
import { QUEUE, useAfterDecision, when } from './hooks'
import { PATH, PERMISSION } from './ids'
import { OperatorName } from './pickers'
import { readSponsorVerification, revocable, revokeVerification, REVOKE_REASON_MAX,
  type SponsorVerification } from './verification-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const failureText = (error: unknown) => (error instanceof ApiError ? error.display : String(error))

/**
 * One company verification (F-M3) as its reviewer and whoever may revoke it see it: the company, its domain, how it
 * was proven and the address, the state with its dates and reasons. The review is the platform's approval panel, given
 * once the page is read in full and quiet; an approved verification is revoked in a dialog with a reason (the platform
 * asks for a second factor and repeats the call).
 */
export default function SponsorVerificationPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { verificationId = '' } = useParams()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const after = useAfterDecision()
  const verification = useQuery({
    queryKey: [...QUEUE.sponsorVerifications, 'one', verificationId],
    queryFn: () => readSponsorVerification(verificationId),
    refetchInterval: (query) => after.interval(query.state.data?.status === 'REVIEW'),
    refetchOnWindowFocus: false,
  })
  const back = (
    <Button variant="ghost" size="sm" asChild>
      <Link to={PATH.sponsorVerifications}><ArrowLeft aria-hidden />{t('verifications.back')}</Link>
    </Button>
  )
  if (verification.isPending) return <PageState kind="loading" />
  if (verification.isError) return <LoadFailed error={verification.error} onRetry={() => void verification.refetch()} />
  if (!verification.data) {
    return <div className="flex flex-col gap-4">{back}<PageState kind="notFound" description={t('verifications.notFound')} /></div>
  }
  const v = verification.data
  const items: DescriptionItem[] = [
    { key: 'company', label: t('verifications.company'), value: v.companyName },
    { key: 'domain', label: t('verifications.domain'), value: v.domain },
    { key: 'method', label: t('verifications.methodLabel'), value: t(`verifications.method.${v.method}`) },
    ...(v.confirmAddress ? [{ key: 'address', label: t('verifications.address'),
      value: <span className="break-all" data-testid="verification-address">{v.confirmAddress}</span> }] : []),
    { key: 'sponsor', label: t('verifications.sponsor'), value: <UserName id={v.sponsorId} /> },
    ...(v.submittedAt ? [{ key: 'submittedAt', label: t('verifications.submittedAt'), value: when(v.submittedAt) }] : []),
    ...(v.confirmedAt ? [{ key: 'confirmedAt', label: t('verifications.confirmedAt'), value: when(v.confirmedAt) }] : []),
    ...(v.decidedAt ? [{ key: 'decidedAt', label: t('verifications.decidedAt'), value: when(v.decidedAt) }] : []),
    ...(v.verifiedAt ? [{ key: 'verifiedAt', label: t('verifications.verifiedAt'), value: when(v.verifiedAt) }] : []),
    ...(v.rejectReason ? [{ key: 'rejectReason', label: t('verifications.rejectReason'),
      value: <span className="whitespace-pre-wrap">{v.rejectReason}</span> }] : []),
    ...(v.endedAt ? [{ key: 'endedAt', label: t('verifications.endedAt'), value: when(v.endedAt) }] : []),
    ...(v.status === 'REVOKED' ? [
      { key: 'revokeReason', label: t('verifications.revokeReason'),
        value: <span className="whitespace-pre-wrap">{v.revokeReason}</span> },
      { key: 'revokedBy', label: t('verifications.revokedBy'), value: v.revokedOperatorId
        ? <>{t('verifications.revokedByOperator')} <OperatorName id={v.revokedOperatorId} name={null} /></>
        : t('verifications.revokedByPlatform') },
    ] : []),
  ]
  // The panel only for a review that waits, read in full and not being read again (a stale one must not decide).
  const reviewing = v.status === 'REVIEW' && !!v.approvalRequestId
  const settled = !verification.isError && !verification.isFetching
  let decision
  if (after.decided && (reviewing || after.levelOnly)) {
    decision = <AfterDecision after={after} onRetry={() => {
      after.retry()
      void verification.refetch()
    }} />
  } else if (!reviewing) {
    decision = <p data-testid="verification-not-in-review">
      {t('verifications.notInReview', { status: t(`verifications.status.${v.status}`) })}</p>
  } else if (settled) {
    decision = <ApprovalPanel requestId={v.approvalRequestId!} onDecided={(status) => {
      after.decide(status)
      void verification.refetch()
      void queryClient.invalidateQueries({ queryKey: QUEUE.sponsorVerifications })
    }} />
  } else {
    decision = <PageState kind="loading" />
  }
  const canRevoke = can(PERMISSION.verificationRevoke) && revocable(v.status)
  return (
    <div className="flex flex-col gap-4" data-testid="verification-page" data-status={v.status}>
      <div>{back}</div>
      <PageHeader title={v.companyName} className="pb-0" description={
        <StatusBadge code={v.status} label={t(`verifications.status.${v.status}`)} testId="verification-status" />
      } />
      <Section title={t('verifications.content')} testId="verification-content">
        <DescriptionList items={items} columns={2} />
        <p><Link to={`${PATH.sponsorVerifications}?${new URLSearchParams({ sponsorId: v.sponsorId })}`}
          className={linkClass} data-testid="verification-sponsor-list">{t('verifications.sponsorLine.show')}</Link></p>
      </Section>
      <Section title={t('verifications.review')} testId="verification-review">
        <p className="text-muted-foreground text-sm">{t('verifications.reviewHint')}</p>
        {decision}
      </Section>
      {canRevoke && (
        <Section title={t('verifications.revoke.title')} testId="verification-revoke">
          <p className="text-muted-foreground text-sm">{t('verifications.revoke.hint')}</p>
          <div><RevokeButton verification={v} onRevoked={() => void verification.refetch()} /></div>
        </Section>
      )}
    </div>
  )
}

/** The dialog that revokes: a reason is required; the platform's second-factor prompt appears on its own. */
function RevokeButton({ verification, onRevoked }: { verification: SponsorVerification; onRevoked: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const revoke = useMutation({
    mutationFn: () => revokeVerification(verification.sponsorId, reason.trim()),
    onSuccess: () => {
      notify.success(t('verifications.revoke.done'))
      setOpen(false)
      setReason('')
      void queryClient.invalidateQueries({ queryKey: QUEUE.sponsorVerifications })
      onRevoked()
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setMissing(!reason.trim())
    if (reason.trim()) revoke.mutate()
  }
  return (
    <>
      <Button type="button" variant="destructive" data-testid="verification-revoke-open"
        onClick={() => setOpen(true)}>{t('verifications.revoke.action')}</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent data-testid="verification-revoke-dialog">
          <form noValidate className="flex flex-col gap-4" onSubmit={submit}>
            <DialogHeader>
              <DialogTitle>{t('verifications.revoke.dialogTitle', { company: verification.companyName })}</DialogTitle>
              <DialogDescription>{t('verifications.revoke.hint')}</DialogDescription>
            </DialogHeader>
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-reason`}>{t('verifications.revoke.reason')}</Label>
              <Textarea id={`${id}-reason`} value={reason} rows={4} maxLength={REVOKE_REASON_MAX}
                aria-describedby={`${id}-hint`} aria-invalid={missing} data-testid="verification-revoke-reason"
                onChange={(event) => { setReason(event.target.value); setMissing(false) }} />
              <p id={`${id}-hint`} className="text-muted-foreground text-sm">
                {t('verifications.revoke.reasonHint', { max: REVOKE_REASON_MAX })}</p>
              {missing && <p role="alert" className="text-destructive text-sm">{t('verifications.revoke.reasonRequired')}</p>}
            </div>
            {revoke.isError && (
              <Alert variant="destructive"><AlertDescription>{failureText(revoke.error)}</AlertDescription></Alert>
            )}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t('verifications.revoke.cancel')}</Button>
              <Button type="submit" variant="destructive" disabled={revoke.isPending}
                data-testid="verification-revoke-submit">{t('verifications.revoke.submit')}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  )
}
