import { formatDateTime, uploadFile } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  ConfirmDialog,
  FileUpload,
  Input,
  Label,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, Copy, ExternalLink } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { neutralName } from '../content/api'
import { SPONSOR } from '../messages'
import { CONTENT_LANGUAGES, contentLanguageOf, type ContentLanguage } from '../qb/api'
import { FileImage } from '../qb/FileImage'
import { QB } from '../qb/messages'
import {
  HEADLINE_LENGTH,
  HANDLE_LENGTH,
  readMySponsorPage,
  saveSponsorPage,
  sponsorPageLink,
  SPONSOR_PAGE,
  STORY_LENGTH,
  submitSponsorPage,
  unpublishSponsorPage,
  WEBSITE_LENGTH,
  type MySponsorPage,
  type SponsorPageInput,
} from '../qb/page'
import { fieldProblem, generalProblem } from '../qb/problems'

export const SPONSOR_PAGE_PATH = '/page'
export const SPONSOR_PAGE_QUERY = ['qb', 'sponsor-page'] as const

const FIELDS = ['handle', 'language', 'headline', 'story', 'logo', 'cover', 'website', 'baseRevision'] as const
const IMAGE_TYPES = 'image/jpeg,image/png'
const IMAGE_BYTES = 5 * 1024 * 1024

/**
 * The sponsor's page (Q60a; docs/quizbuks/plans/Q60-sponsor-page.md): its link name, language, headline, story,
 * logo, cover and website, saved with `QB_SPONSOR_PAGE_SAVE`; published once through a review
 * (`QB_SPONSOR_PAGE_SUBMIT`), after which changes show at once; taken back with `QB_SPONSOR_PAGE_UNPUBLISH`.
 */
export function SponsorPageEditor() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const page = useQuery({ queryKey: SPONSOR_PAGE_QUERY, queryFn: readMySponsorPage })
  // Here, not in the form: the form starts again from each revision a write makes, and the message must outlive it.
  const [done, setDone] = useState<string | null>(null)
  return (
    <section className="flex max-w-xl flex-col gap-6" aria-labelledby="sponsor-page-title" data-testid="sponsor-page">
      <div className="flex flex-col gap-2">
        <h1 id="sponsor-page-title" className="text-xl font-semibold">{t('page.title')}</h1>
        <p className="text-muted-foreground text-sm">{t('page.intro')}</p>
      </div>
      {page.isPending && <PageState kind="loading" />}
      {page.isError && <PageState kind="error" description={tQb('failed')} />}
      {/* Keyed by the revision: what the server holds after a write is what the form starts from. */}
      {page.isSuccess && <PageForm key={page.data?.revision ?? 0} page={page.data} done={done} setDone={setDone} />}
    </section>
  )
}

function PageStatus({ page }: { page: MySponsorPage | null }) {
  const { t } = useTranslation(SPONSOR)
  const status = page?.status ?? 'NONE'
  const variant = status === 'PUBLISHED' ? 'success'
    : status === 'REJECTED' || status === 'TAKEN_DOWN' ? 'destructive' : 'secondary'
  return (
    <div className="flex flex-col gap-2" data-testid="sponsor-page-status" data-status={status}>
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-muted-foreground">{t('page.statusLabel')}</span>
        <Badge variant={variant}>{t(`page.status.${status}`)}</Badge>
        {status === 'PUBLISHED' && page?.publishedAt && (
          <span className="text-muted-foreground">{t('page.publishedAt', { time: formatDateTime(page.publishedAt) })}</span>
        )}
      </div>
      {status === 'REJECTED' && page?.rejectReason && (
        <Alert variant="destructive" data-testid="sponsor-page-reject-reason">
          <AlertTitle>{t('page.rejectReason')}</AlertTitle>
          <AlertDescription className="whitespace-pre-wrap">{page.rejectReason}</AlertDescription>
        </Alert>
      )}
      {page?.reviewRequired && (
        <Alert variant="destructive" data-testid="sponsor-page-takedown">
          <AlertTitle>{t('page.takedownReason')}</AlertTitle>
          <AlertDescription className="flex flex-col gap-1">
            {/* A ban's takedown records a code, not the ban's own reason (as a ban's pause, Q10b review L3). */}
            {page.takedownReason && <span className="whitespace-pre-wrap" data-testid="sponsor-page-takedown-reason">
              {page.takedownReason === 'QB_ACCOUNT_SUSPENDED' ? t('publishing.detail.accountSuspended')
                : page.takedownReason}</span>}
            <span>{t('page.reviewRequired')}</span>
          </AlertDescription>
        </Alert>
      )}
    </div>
  )
}

function PageForm({ page, done, setDone }: {
  page: MySponsorPage | null
  done: string | null
  setDone: (message: string | null) => void
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const id = useId()
  const stored: SponsorPageInput = {
    handle: page?.handle ?? '',
    language: page?.language ?? contentLanguageOf(i18n.language),
    headline: page?.headline ?? '',
    story: page?.story ?? null,
    logo: page?.logo ?? null,
    cover: page?.cover ?? null,
    website: page?.website ?? null,
    baseRevision: page?.revision ?? null,
  }
  const [form, setForm] = useState(stored)
  // Images being uploaded: saving waits for them, so that none is left out (and orphaned) by a save meanwhile.
  const [uploading, setUploading] = useState(0)
  const set = <K extends keyof SponsorPageInput>(key: K, value: SponsorPageInput[K]) =>
    setForm((current) => ({ ...current, [key]: value }))
  const dirty = (Object.keys(stored) as (keyof SponsorPageInput)[]).some((key) => (form[key] ?? '') !== (stored[key] ?? ''))
  const status = page?.status

  const refresh = () => queryClient.invalidateQueries({ queryKey: SPONSOR_PAGE_QUERY })
  const save = useMutation({
    mutationFn: () => saveSponsorPage({
      ...form,
      handle: form.handle.trim().toLowerCase(),
      headline: form.headline.trim(),
      story: form.story?.trim() ? form.story : null,
      website: form.website?.trim() ? form.website.trim() : null,
    }),
    onSuccess: async (output) => {
      setDone(status === 'PENDING' && output.status === 'DRAFT' ? t('page.savedBackToDraft')
        : output.status === 'PUBLISHED' ? t('page.savedLive') : t('page.saved'))
      await refresh()
    },
  })
  const submit = useMutation({
    mutationFn: () => submitSponsorPage(page?.revision ?? null),
    onSuccess: async (output) => {
      setDone(output.status === 'PUBLISHED' ? t('page.publishedNow') : t('page.submitted'))
      await refresh()
    },
  })
  const unpublish = useMutation({
    mutationFn: () => unpublishSponsorPage(page?.revision ?? null),
    onSuccess: async () => {
      setDone(t('page.unpublished'))
      await refresh()
    },
  })
  const busy = save.isPending || submit.isPending || unpublish.isPending || uploading > 0
  const error = save.error ?? submit.error ?? unpublish.error

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    setDone(null)
    submit.reset()
    unpublish.reset()
    save.mutate()
  }
  const problem = (field: string) => fieldProblem(save.error, field)
  const errorId = (field: string) => `${id}-${field}-error`
  const describedBy = (field: string, hint?: string) =>
    [hint, problem(field) ? errorId(field) : undefined].filter(Boolean).join(' ') || undefined
  const invalid = (field: string) => (problem(field) ? true : undefined)
  const errorOf = (field: string) => problem(field)
    && <p id={errorId(field)} className="text-destructive text-sm">{problem(field)}</p>
  const general = generalProblem(error, FIELDS)
  const link = sponsorPageLink(form.handle.trim().toLowerCase() || 'your-name')
  const canSubmit = !!page && (status === 'DRAFT' || status === 'REJECTED' || status === 'TAKEN_DOWN')
  const canUnpublish = status === 'PUBLISHED' || status === 'PENDING'

  const image = (field: 'logo' | 'cover', alt: string, className: string) => (
    <div className="flex flex-col gap-2">
      <Label htmlFor={`${id}-${field}`}>{t(`page.${field}`)}</Label>
      {form[field] && (
        <div className="flex items-center gap-3">
          <FileImage fileId={form[field]!} variant={field === 'logo' ? 'w256' : 'w640'} alt={alt}
            className={className} data-testid={`sponsor-page-${field}-image`} />
          <Button type="button" variant="outline" size="sm" data-testid={`sponsor-page-${field}-remove`}
            onClick={() => set(field, null)}>{t('page.remove')}</Button>
        </div>
      )}
      <FileUpload id={`${id}-${field}`} accept={IMAGE_TYPES} maxSize={IMAGE_BYTES} maxSizeText="5 MB"
        label={t('page.upload')} hint={t('page.imageHint')} aria-invalid={invalid(field)}
        aria-describedby={describedBy(field)} data-testid={`sponsor-page-${field}`}
        onUpload={async (file) => {
          setUploading((count) => count + 1)
          try {
            // A neutral name: the image is public once the page is (its metadata stays private).
            const uploaded = await uploadFile(SPONSOR_PAGE.imagePolicy, file, neutralName('image', file.name))
            set(field, uploaded.fileId ?? null)
          } finally {
            setUploading((count) => count - 1)
          }
        }} />
      {errorOf(field)}
    </div>
  )

  return (
    <>
      <PageStatus page={page} />
      {status === 'PUBLISHED' && page && (
        <div className="flex flex-wrap gap-2">
          <Button asChild variant="outline" size="sm">
            <a href={sponsorPageLink(page.handle)} target="_blank" rel="noreferrer" data-testid="sponsor-page-open">
              <ExternalLink aria-hidden className="size-4" />{t('page.open')}
            </a>
          </Button>
          <Button type="button" variant="outline" size="sm" data-testid="sponsor-page-copy" onClick={async () => {
            try {
              await navigator.clipboard.writeText(sponsorPageLink(page.handle))
              setDone(t('page.copied'))
            } catch {
              // No clipboard here (refused, or not a secure page): the link stays on the "open" button.
              setDone(null)
            }
          }}>
            <Copy aria-hidden className="size-4" />{t('page.copy')}
          </Button>
        </div>
      )}
      <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4" data-testid="sponsor-page-form">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-handle`}>{t('page.handle')}</Label>
          <Input id={`${id}-handle`} value={form.handle} required maxLength={HANDLE_LENGTH} autoCapitalize="none"
            spellCheck={false} aria-invalid={invalid('handle')}
            aria-describedby={describedBy('handle', `${id}-handle-hint`)} data-testid="sponsor-page-handle"
            onChange={(event) => set('handle', event.target.value)} />
          <p id={`${id}-handle-hint`} className="text-muted-foreground text-sm break-all">
            {t('page.handleHint', { link })}
          </p>
          {errorOf('handle')}
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-language`}>{t('page.language')}</Label>
          <Select value={form.language} onValueChange={(next) => set('language', next as ContentLanguage)}>
            <SelectTrigger id={`${id}-language`} className="w-48" aria-describedby={`${id}-language-hint`}
              data-testid="sponsor-page-language">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {CONTENT_LANGUAGES.map((language) => (
                <SelectItem key={language} value={language}>{tQb(`languages.${language}`)}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p id={`${id}-language-hint`} className="text-muted-foreground text-sm">{t('page.languageHint')}</p>
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-headline`}>{t('page.headline')}</Label>
          <Input id={`${id}-headline`} value={form.headline} required maxLength={HEADLINE_LENGTH}
            aria-invalid={invalid('headline')} aria-describedby={describedBy('headline')}
            data-testid="sponsor-page-headline" onChange={(event) => set('headline', event.target.value)} />
          {errorOf('headline')}
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-story`}>{t('page.story')}</Label>
          <Textarea id={`${id}-story`} value={form.story ?? ''} rows={8} maxLength={STORY_LENGTH}
            aria-invalid={invalid('story')} aria-describedby={describedBy('story', `${id}-story-hint`)}
            data-testid="sponsor-page-story" onChange={(event) => set('story', event.target.value)} />
          <p id={`${id}-story-hint`} className="text-muted-foreground text-sm">{t('page.storyHint')}</p>
          {errorOf('story')}
        </div>
        {image('logo', t('page.logoAlt'), 'size-16 rounded-full border object-cover')}
        {image('cover', t('page.coverAlt'), 'aspect-video w-48 border object-cover')}
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-website`}>{t('page.website')}</Label>
          <Input id={`${id}-website`} type="url" value={form.website ?? ''} maxLength={WEBSITE_LENGTH}
            inputMode="url" placeholder="https://" aria-invalid={invalid('website')}
            aria-describedby={describedBy('website', `${id}-website-hint`)} data-testid="sponsor-page-website"
            onChange={(event) => set('website', event.target.value)} />
          <p id={`${id}-website-hint`} className="text-muted-foreground text-sm">{t('page.websiteHint')}</p>
          {errorOf('website')}
        </div>
        {general && (
          <Alert variant="destructive" data-testid="sponsor-page-error">
            <AlertDescription>{general}</AlertDescription>
          </Alert>
        )}
        {done && !busy && (
          <p role="status" className="text-success flex items-center gap-2 text-sm" data-testid="sponsor-page-done">
            <CircleCheck aria-hidden className="size-4" />
            {done}
          </p>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <Button type="submit" disabled={busy} aria-busy={save.isPending || undefined} data-testid="sponsor-page-save">
            {t('page.save')}
          </Button>
          {canSubmit && (
            <Button type="button" variant="secondary" disabled={busy || dirty} aria-busy={submit.isPending || undefined}
              aria-describedby={dirty ? `${id}-submit-hint` : undefined} data-testid="sponsor-page-submit"
              onClick={() => {
                setDone(null)
                save.reset()
                unpublish.reset()
                submit.mutate()
              }}>
              {t('page.submit')}
            </Button>
          )}
          {canUnpublish && (
            <ConfirmDialog title={t('page.unpublish')} description={t('page.unpublishConfirm')}
              confirmLabel={t('page.unpublish')} cancelLabel={t('page.cancel')} onConfirm={() => {
                setDone(null)
                save.reset()
                submit.reset()
                unpublish.mutate()
              }}
              trigger={<Button type="button" variant="outline" disabled={busy} data-testid="sponsor-page-unpublish">
                {t('page.unpublish')}</Button>} />
          )}
        </div>
        {canSubmit && dirty && (
          <p id={`${id}-submit-hint`} className="text-muted-foreground text-sm">{t('page.submitHint')}</p>
        )}
      </form>
    </>
  )
}
