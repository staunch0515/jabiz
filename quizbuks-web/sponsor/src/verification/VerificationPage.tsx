import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, AlertTitle, Badge, Button, Input, Label, PageState } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BadgeCheck } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { readMySponsorPage } from '../page/api'
import { formatKudos } from '../qb/kudos'
import { minuteNow } from '../qb/legal'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem, problemText } from '../qb/problems'
import { Link } from '../qb/router'
import {
  domainOfWebsite,
  readVerification,
  submitVerification,
  topUpOf,
  VERIFICATION_PATH,
  VERIFICATION_QUERY,
  type Verification,
} from './api'
import './messages'

const FIELDS = ['companyName', 'domain', 'confirmAddress'] as const

/** The Sponsor's verification (F-M3), which the platform fee account's hint reads too. */
export function useVerification() {
  return useQuery({ queryKey: VERIFICATION_QUERY, queryFn: readVerification })
}

/**
 * The Sponsor's company verification (F-M3, docs/quizbuks/plans/impl/F-M.md §6): where it stands (the link out, in
 * review, verified, sent back or withdrawn, each with its reason) and the form that submits it, sends the link again
 * or changes the name or domain. What is allowed is the server's to say (`QB_SPONSOR_VERIFICATION_SUBMIT`); the page
 * shows its problems.
 */
export function VerificationPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const verification = useVerification()
  const page = useQuery({ queryKey: ['qb', 'sponsor', 'page', 'mine'], queryFn: readMySponsorPage })
  if (verification.isPending) return <PageState kind="loading" />
  if (verification.isError) {
    return <PageState kind="error" description={problemText(verification.error)}
      action={<Button variant="outline" onClick={() => void verification.refetch()}>{tQb('retry')}</Button>} />
  }
  const v = verification.data
  return (
    <div className="flex flex-col gap-6" data-testid="verification-page">
      <div className="flex flex-col gap-1">
        <h2 className="text-xl font-semibold">{t('verification.title')}</h2>
        <p className="text-muted-foreground text-sm">{t('verification.intro')}</p>
      </div>
      <VerificationStatus verification={v} />
      <TopUpHint verification={v} />
      <VerificationForm key={`${v.verificationId ?? 'none'}-${v.status ?? ''}`} verification={v}
        defaultDomain={domainOfWebsite(page.data?.website)} />
    </div>
  )
}

/** Where the verification stands, with the reason of a rejection or a withdrawal. */
function VerificationStatus({ verification: v }: { verification: Verification }) {
  const { t } = useTranslation(SPONSOR)
  if (!v.status) return null
  const reason = (value: string | null) => value ?? t('verification.noReason')
  const expired = v.status === 'LINK_SENT' && v.linkExpiresAt != null
    && new Date(v.linkExpiresAt).getTime() <= new Date(minuteNow()).getTime()
  return (
    <section className="flex flex-col gap-2" data-testid="verification-status" data-status={v.status}
      aria-labelledby="verification-status-title">
      <h3 id="verification-status-title" className="text-base font-medium">{t('verification.statusTitle')}</h3>
      {v.verified && v.status === 'APPROVED' ? (
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <Badge variant="success"><BadgeCheck aria-hidden="true" />{t('verification.verified')}</Badge>
          <span>{t('verification.verifiedAs', { company: v.companyName, domain: v.domain,
            time: v.verifiedAt ? formatDateTime(v.verifiedAt) : '' })}</span>
        </div>
      ) : (
        <p className="text-sm"><Badge variant="secondary">{t(`verification.status.${v.status}`)}</Badge></p>
      )}
      {v.status === 'LINK_SENT' && (
        <p className="text-sm" data-testid="verification-link">
          {expired
            ? t('verification.linkExpired', { address: v.confirmAddress })
            : t('verification.linkSent', { address: v.confirmAddress,
              time: v.linkExpiresAt ? formatDateTime(v.linkExpiresAt) : '' })}
        </p>
      )}
      {v.status === 'REVIEW' && (
        <p className="text-muted-foreground text-sm">
          {t('verification.inReview', { company: v.companyName, domain: v.domain })}
        </p>
      )}
      {v.status === 'REJECTED' && (
        <Alert variant="destructive" data-testid="verification-rejected">
          <AlertDescription>{t('verification.rejected', { reason: reason(v.rejectReason) })}</AlertDescription>
        </Alert>
      )}
      {v.status === 'REVOKED' && (
        <Alert variant="destructive" data-testid="verification-revoked">
          <AlertDescription>{t('verification.revoked', { reason: reason(v.revokeReason) })}</AlertDescription>
        </Alert>
      )}
    </section>
  )
}

/** What a verification would top up (the onboarding granted credit below the certified amount); none otherwise. */
export function TopUpHint({ verification }: { verification: Verification }) {
  const { t } = useTranslation(SPONSOR)
  const amount = topUpOf(verification)
  if (amount == null) return null
  return <p className="text-sm" data-testid="verification-top-up">
    {t('verification.topUp', { amount: formatKudos(amount) })}
  </p>
}

function VerificationForm({ verification: v, defaultDomain }: { verification: Verification; defaultDomain: string }) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const id = useId()
  const standing = v.status === 'LINK_SENT' || v.status === 'REVIEW' || v.status === 'APPROVED'
  const [companyName, setCompanyName] = useState(standing || v.status === 'REJECTED' ? v.companyName ?? '' : '')
  const [domain, setDomain] = useState(v.domain ?? defaultDomain)
  const [confirmAddress, setConfirmAddress] = useState(v.confirmAddress ?? '')
  const [done, setDone] = useState(false)

  const save = useMutation({
    mutationFn: () => submitVerification({ companyName: companyName.trim(), domain: domain.trim(),
      confirmAddress: confirmAddress.trim() === '' ? null : confirmAddress.trim() }),
    onSuccess: async () => {
      setDone(true)
      await queryClient.invalidateQueries({ queryKey: VERIFICATION_QUERY })
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setDone(false)
    save.mutate()
  }
  const problem = (field: string) => fieldProblem(save.error, field)
  const errorId = (field: string) => `${id}-${field}-error`
  const describedBy = (field: string, hint?: string) =>
    [hint, problem(field) ? errorId(field) : undefined].filter(Boolean).join(' ') || undefined
  const errorOf = (field: string) => problem(field)
    && <p id={errorId(field)} className="text-destructive text-sm">{problem(field)}</p>
  const general = generalProblem(save.error, FIELDS)
  const resend = v.status === 'LINK_SENT' && companyName.trim() === v.companyName && domain.trim() === v.domain
    && confirmAddress.trim().toLowerCase() === (v.confirmAddress ?? '')
  const changing = v.status === 'APPROVED'
  const placeholder = domain.trim() || 'example.co.jp'

  return (
    <form noValidate onSubmit={submit} className="flex max-w-xl flex-col gap-4" data-testid="verification-form"
      aria-labelledby={`${id}-title`}>
      <h3 id={`${id}-title`} className="text-base font-medium">
        {changing ? t('verification.changeTitle') : t('verification.formTitle')}
      </h3>
      {changing && <p className="text-muted-foreground text-sm">{t('verification.changeWarning')}</p>}
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-company`}>{t('verification.companyName')}</Label>
        <Input id={`${id}-company`} value={companyName} required maxLength={200} autoComplete="organization"
          aria-invalid={problem('companyName') ? true : undefined} aria-describedby={describedBy('companyName')}
          data-testid="verification-company" onChange={(event) => setCompanyName(event.target.value)} />
        {errorOf('companyName')}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-domain`}>{t('verification.domain')}</Label>
        <Input id={`${id}-domain`} value={domain} required maxLength={253} autoComplete="off" inputMode="url"
          placeholder="example.co.jp" aria-invalid={problem('domain') ? true : undefined}
          aria-describedby={describedBy('domain', `${id}-domain-hint`)} data-testid="verification-domain"
          onChange={(event) => setDomain(event.target.value)} />
        <p id={`${id}-domain-hint`} className="text-muted-foreground text-sm">{t('verification.domainHint')}</p>
        {errorOf('domain')}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-address`}>{t('verification.confirmAddress')}</Label>
        <Input id={`${id}-address`} type="email" value={confirmAddress} maxLength={320} autoComplete="off"
          aria-invalid={problem('confirmAddress') ? true : undefined}
          aria-describedby={describedBy('confirmAddress', `${id}-address-hint`)} data-testid="verification-address"
          onChange={(event) => setConfirmAddress(event.target.value)} />
        <p id={`${id}-address-hint`} className="text-muted-foreground text-sm">
          {t('verification.confirmAddressHint', { domain: placeholder })}
        </p>
        {errorOf('confirmAddress')}
      </div>
      {general && (
        <Alert variant="destructive" data-testid="verification-problem">
          <AlertTitle>{general}</AlertTitle>
        </Alert>
      )}
      {done && <p role="status" className="text-sm" data-testid="verification-submitted">
        {t('verification.submitted')}
      </p>}
      <div>
        <Button type="submit" disabled={save.isPending || companyName.trim() === '' || domain.trim() === ''}
          data-testid="verification-submit">
          {resend ? t('verification.resend') : t('verification.submit')}
        </Button>
      </div>
    </form>
  )
}

/**
 * The platform fee account's hint (F-M2 left it to F-M3): what verifying the company would top up, with the way there.
 * Nothing while there is nothing to top up (or the verification cannot be read).
 */
export function VerificationTopUpLink() {
  const { t } = useTranslation(SPONSOR)
  const verification = useVerification()
  if (!verification.data || topUpOf(verification.data) == null) return null
  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="fee-account-verification">
      <TopUpHint verification={verification.data} />
      <Link to={VERIFICATION_PATH} className="text-primary text-sm underline underline-offset-4">
        {t('verification.toVerification')}
      </Link>
    </div>
  )
}
