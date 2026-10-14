import { formatDateTime, uploadFile, useAuth } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  Checkbox,
  FileUpload,
  Input,
  Label,
  PageState,
  Textarea,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { applyAsSponsor, readMySponsor, saveSponsorProfile, type Sponsor, type SponsorOutput } from '../qb/api'
import { FileImage } from '../qb/FileImage'
import { FILE_POLICY } from '../qb/ids'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem } from '../qb/problems'

/** The query of the sponsor's onboarding, which the routes and the dashboard read too. */
export const SPONSOR_QUERY = ['qb', 'sponsor'] as const

/** The sponsor's onboarding, or null before applying. */
export function useMySponsor() {
  return useQuery({ queryKey: SPONSOR_QUERY, queryFn: readMySponsor })
}

const FIELDS = ['displayName', 'company', 'intro', 'avatar', 'acceptTerms'] as const
const INTRO_LENGTH = 2_000
const AVATAR_TYPES = 'image/jpeg,image/png'
const AVATAR_BYTES = 2 * 1024 * 1024

/** Where the onboarding stands: not applied, in review, approved or sent back with the reviewer's reason. */
function OnboardingStatus({ sponsor }: { sponsor: Sponsor | null }) {
  const { t } = useTranslation(SPONSOR)
  const status = sponsor?.reviewStatus ?? 'NONE'
  const variant = status === 'APPROVED' ? 'success' : status === 'REJECTED' ? 'destructive' : 'secondary'
  return (
    <div className="flex flex-col gap-2" data-testid="onboarding-status" data-status={status}>
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-muted-foreground">{t('onboarding.statusLabel')}</span>
        <Badge variant={variant}>{t(`onboarding.status.${status}`)}</Badge>
        {status === 'PENDING' && sponsor?.submittedAt && (
          <span className="text-muted-foreground">
            {t('onboarding.submittedAt', { time: formatDateTime(sponsor.submittedAt) })}
          </span>
        )}
        {status === 'APPROVED' && sponsor?.approvedAt && (
          <span className="text-muted-foreground">
            {t('onboarding.approvedAt', { time: formatDateTime(sponsor.approvedAt) })}
          </span>
        )}
      </div>
      {status === 'REJECTED' && sponsor?.rejectReason && (
        <Alert variant="destructive" data-testid="onboarding-reject-reason">
          <AlertTitle>{t('onboarding.rejectReason')}</AlertTitle>
          <AlertDescription className="whitespace-pre-wrap">{sponsor.rejectReason}</AlertDescription>
        </Alert>
      )}
    </div>
  )
}

/**
 * The sponsor's onboarding and profile (M-01, M-03, P-09; D-Q2-3): name, company, introduction (Markdown, shown as
 * written until the content pages render it) and a logo. Before approval, saving applies (`QB_SPONSOR_APPLY`, with
 * the terms accepted; applying again while in review replaces the request); after approval it changes the profile
 * without a review (`QB_SPONSOR_PROFILE_SAVE`).
 */
export function OnboardingPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const sponsor = useMySponsor()
  return (
    <section className="flex max-w-xl flex-col gap-6" aria-labelledby="onboarding-title" data-testid="onboarding">
      <div className="flex flex-col gap-2">
        <h1 id="onboarding-title" className="text-xl font-semibold">{t('onboarding.title')}</h1>
        <p className="text-muted-foreground text-sm">{t('onboarding.intro')}</p>
      </div>
      {sponsor.isPending && <PageState kind="loading" />}
      {sponsor.isError && <PageState kind="error" description={tQb('failed')} />}
      {sponsor.isSuccess && (
        <>
          <OnboardingStatus sponsor={sponsor.data} />
          <OnboardingForm sponsor={sponsor.data} />
        </>
      )}
    </section>
  )
}

function OnboardingForm({ sponsor }: { sponsor: Sponsor | null }) {
  const { t } = useTranslation(SPONSOR)
  const { displayName: signedUpAs } = useAuth()
  const queryClient = useQueryClient()
  const id = useId()
  const approved = sponsor?.reviewStatus === 'APPROVED'
  const [displayName, setDisplayName] = useState(() => sponsor?.contactName || signedUpAs || '')
  const [company, setCompany] = useState(() => sponsor?.company ?? '')
  const [intro, setIntro] = useState(() => sponsor?.intro ?? '')
  const [avatar, setAvatar] = useState<string | null>(() => sponsor?.avatar ?? null)
  const [acceptTerms, setAcceptTerms] = useState(false)
  const [done, setDone] = useState<string | null>(null)

  const save = useMutation({
    mutationFn: () => {
      const input = {
        displayName: displayName.trim(),
        company: company.trim(),
        intro: intro.trim() === '' ? null : intro,
        avatar,
        baseRevision: sponsor?.revision ?? null,
      }
      return approved ? saveSponsorProfile(input) : applyAsSponsor({ ...input, acceptTerms })
    },
    onSuccess: async (output: SponsorOutput) => {
      setDone(approved ? t('onboarding.saved')
        : output.reviewStatus === 'APPROVED' ? t('onboarding.approvedNow') : t('onboarding.applied'))
      setAcceptTerms(false)
      await queryClient.invalidateQueries({ queryKey: SPONSOR_QUERY })
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setDone(null)
    save.mutate()
  }
  const problem = (field: string) => fieldProblem(save.error, field)
  const general = generalProblem(save.error, FIELDS)
  const label = approved ? t('onboarding.save') : sponsor ? t('onboarding.applyAgain') : t('onboarding.apply')

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4" data-testid="onboarding-form">
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-name`}>{t('onboarding.displayName')}</Label>
        <Input id={`${id}-name`} value={displayName} required maxLength={100} autoComplete="name"
          aria-invalid={problem('displayName') ? true : undefined} data-testid="onboarding-name"
          onChange={(event) => setDisplayName(event.target.value)} />
        {problem('displayName') && <p className="text-destructive text-sm">{problem('displayName')}</p>}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-company`}>{t('onboarding.company')}</Label>
        <Input id={`${id}-company`} value={company} required maxLength={100} autoComplete="organization"
          aria-invalid={problem('company') ? true : undefined} data-testid="onboarding-company"
          onChange={(event) => setCompany(event.target.value)} />
        {problem('company') && <p className="text-destructive text-sm">{problem('company')}</p>}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-intro`}>{t('onboarding.intro2')}</Label>
        <Textarea id={`${id}-intro`} value={intro} rows={6} maxLength={INTRO_LENGTH}
          aria-describedby={`${id}-intro-hint`} aria-invalid={problem('intro') ? true : undefined}
          data-testid="onboarding-intro" onChange={(event) => setIntro(event.target.value)} />
        <p id={`${id}-intro-hint`} className="text-muted-foreground text-sm">{t('onboarding.introHint')}</p>
        {problem('intro') && <p className="text-destructive text-sm">{problem('intro')}</p>}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-avatar`}>{t('onboarding.avatar')}</Label>
        {avatar && (
          <div className="flex items-center gap-3">
            <FileImage fileId={avatar} variant="w128" alt={t('onboarding.avatarAlt')}
              className="size-16 rounded-full border" data-testid="onboarding-avatar-image" />
            <Button type="button" variant="outline" size="sm" data-testid="onboarding-avatar-remove"
              onClick={() => setAvatar(null)}>
              {t('onboarding.removeAvatar')}
            </Button>
          </div>
        )}
        <FileUpload id={`${id}-avatar`} accept={AVATAR_TYPES} maxSize={AVATAR_BYTES} maxSizeText="2 MB"
          label={t('onboarding.uploadAvatar')} hint={t('onboarding.avatarHint')}
          aria-invalid={problem('avatar') ? true : undefined} data-testid="onboarding-avatar"
          onUpload={async (file) => {
            const uploaded = await uploadFile(FILE_POLICY.sponsorAvatar, file, file.name)
            setAvatar(uploaded.fileId ?? null)
          }} />
        {problem('avatar') && <p className="text-destructive text-sm">{problem('avatar')}</p>}
      </div>
      {!approved && (
        <div className="flex flex-col gap-2">
          <div className="flex items-start gap-2">
            <Checkbox id={`${id}-terms`} checked={acceptTerms} data-testid="onboarding-terms"
              aria-invalid={problem('acceptTerms') ? true : undefined}
              onCheckedChange={(checked) => setAcceptTerms(checked === true)} />
            <Label htmlFor={`${id}-terms`} className="leading-snug">{t('onboarding.terms')}</Label>
          </div>
          {problem('acceptTerms') && <p className="text-destructive text-sm">{problem('acceptTerms')}</p>}
        </div>
      )}
      {general && (
        <Alert variant="destructive" data-testid="onboarding-error">
          <AlertDescription>{general}</AlertDescription>
        </Alert>
      )}
      {done && !save.isPending && (
        <p role="status" className="text-success flex items-center gap-2 text-sm" data-testid="onboarding-done">
          <CircleCheck aria-hidden className="size-4" />
          {done}
        </p>
      )}
      <Button type="submit" className="self-start" disabled={save.isPending} aria-busy={save.isPending || undefined}
        data-testid="onboarding-submit">
        {label}
      </Button>
    </form>
  )
}
