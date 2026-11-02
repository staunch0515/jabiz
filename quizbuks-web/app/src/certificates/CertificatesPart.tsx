import { formatDate, i18n } from '@jabiz/client'
import { Button, Input, Label, notify, PageState, Switch } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Award, Copy, Download, ExternalLink } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { AccountSection } from '../qb/AccountSections'
import { DEFAULT_SETTINGS, saveMySettings, type UserSettings } from '../qb/api'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { SETTINGS_QUERY, useSoundSetting } from '../qb/SoundSettings'
import {
  appUrl,
  downloadCertificate,
  issueCertificate,
  readMyCertifications,
  takerPagePath,
  verifyPath,
  type HeldCertification,
} from './api'

export const CERTIFICATIONS_QUERY = ['qb', 'my-certifications']

/** Copies a text, or says it could not (the text stays on screen to copy by hand). */
async function copy(text: string, done: string, failed: string) {
  try {
    await navigator.clipboard.writeText(text)
    notify.success(done)
  } catch {
    notify.error(failed)
  }
}

/**
 * The taker's certifications and certificates (Q77, docs/quizbuks/plans/Q77-certificates.md): each certification held
 * now, with its certificate to issue, download and share; then the public certification page, off until turned on
 * under a name of the taker's own. Issuing makes a certificate public (D-Q77-5): the page lists the issued ones.
 */
export function CertificatesPart() {
  const { t } = useTranslation(APP)
  const held = useQuery({ queryKey: CERTIFICATIONS_QUERY, queryFn: readMyCertifications })
  return (
    <div className="flex flex-col gap-8">
      <AccountSection title={t('certificates.held')} testId="certificates">
        {held.isPending && <PageState kind="loading" />}
        {held.isError && <PageState kind="error" description={problemText(held.error)} />}
        {held.isSuccess && held.data.length === 0 && (
          <p className="text-muted-foreground text-sm" data-testid="certificates-none">{t('certificates.none')}</p>
        )}
        {held.isSuccess && held.data.length > 0 && (
          <ul className="flex flex-col gap-3">
            {held.data.map((h) => <HeldItem key={h.certification} held={h} />)}
          </ul>
        )}
        <p className="text-muted-foreground text-sm">{t('certificates.hint')}</p>
      </AccountSection>
      <AccountSection title={t('certificates.page.title')} testId="certificates-page">
        <PublicPageSettings />
      </AccountSection>
    </div>
  )
}

function HeldItem({ held }: { held: HeldCertification }) {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const issue = useMutation({
    mutationFn: () => issueCertificate(held.certification, i18n.language),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CERTIFICATIONS_QUERY }),
    onError: (error) => notify.error(problemText(error)),
  })
  const download = useMutation({
    mutationFn: () => downloadCertificate(held.certificateId!, held.verifyKey!),
    onError: (error) => notify.error(problemText(error)),
  })
  const link = held.verifyKey ? appUrl(verifyPath(held.verifyKey)) : null
  return (
    <li className="flex flex-col gap-2 rounded-lg border p-3" data-testid={`certificate-${held.certification}`}>
      <div className="flex items-start gap-2">
        <Award aria-hidden className="text-primary mt-0.5 size-5 shrink-0" />
        <div className="min-w-0">
          <p className="font-medium">{t(`certificates.names.${held.certification}`,
            { defaultValue: held.certification })}</p>
          <p className="text-muted-foreground text-sm">
            {t(`certificates.source.${held.source === 'GRANTED' ? 'GRANTED' : 'PASSED'}`)}
            {' · '}
            {t('certificates.period', { from: formatDate(held.heldFrom), until: formatDate(held.heldUntil) })}
          </p>
        </div>
      </div>
      {!held.certificateId && (
        <Button size="sm" className="w-fit" disabled={issue.isPending} onClick={() => issue.mutate()}
          data-testid={`certificate-issue-${held.certification}`}>
          {t('certificates.issue')}
        </Button>
      )}
      {held.certificateId && link && (
        <>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="outline" disabled={download.isPending} onClick={() => download.mutate()}
              data-testid={`certificate-download-${held.certification}`}>
              <Download aria-hidden />{t('certificates.download')}
            </Button>
            <Button size="sm" variant="outline" data-testid={`certificate-copy-${held.certification}`}
              onClick={() => void copy(link, t('certificates.copied'), t('certificates.copyFailed'))}>
              <Copy aria-hidden />{t('certificates.copy')}
            </Button>
          </div>
          <p className="text-muted-foreground text-xs break-all" data-testid={`certificate-link-${held.certification}`}>
            {link}
          </p>
        </>
      )}
    </li>
  )
}

/** The public page's name and switch (both saved to the account's settings). */
function PublicPageSettings() {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  // The same query as the sound's and the quiz languages': one read of the settings.
  const settings = useSoundSetting()
  const saved = settings.data?.publicHandle ?? ''
  const [typed, setTyped] = useState<string | null>(null)
  const handle = typed ?? saved
  const save = useMutation({
    mutationFn: (input: Partial<UserSettings>) => saveMySettings(input),
    onSuccess: (next) => {
      queryClient.setQueryData<UserSettings>(SETTINGS_QUERY, (before) => ({ ...(before ?? DEFAULT_SETTINGS), ...next }))
      setTyped(null)
      notify.success(t('certificates.page.saved'))
    },
    onError: (error) => notify.error(problemText(error)),
  })
  if (settings.isPending) return <PageState kind="loading" />
  const on = settings.data?.publicPage === true
  const submit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate({ publicHandle: handle.trim() === '' ? null : handle })
  }
  const pageLink = saved ? appUrl(takerPagePath(saved)) : null
  return (
    <div className="flex flex-col gap-3">
      <form className="flex flex-col gap-2" onSubmit={submit} data-testid="certificates-handle-form">
        <Label htmlFor="certificates-handle">{t('certificates.page.handle')}</Label>
        <div className="flex gap-2">
          <Input id="certificates-handle" value={handle} maxLength={30} autoCapitalize="none" autoCorrect="off"
            spellCheck={false} onChange={(event) => setTyped(event.target.value)}
            data-testid="certificates-handle" aria-describedby="certificates-handle-hint" />
          <Button type="submit" variant="outline" disabled={save.isPending || handle === saved}
            data-testid="certificates-handle-save">
            {t('certificates.page.save')}
          </Button>
        </div>
        <p id="certificates-handle-hint" className="text-muted-foreground text-sm">{t('certificates.page.handleHint')}</p>
      </form>
      <div className="flex items-center gap-2">
        <Switch id="certificates-page-on" checked={on} disabled={save.isPending || (!on && !saved)}
          onCheckedChange={(checked) => save.mutate({ publicPage: checked })} data-testid="certificates-page-on" />
        <Label htmlFor="certificates-page-on">{t('certificates.page.on')}</Label>
      </div>
      <p className="text-muted-foreground text-sm">{t('certificates.page.hint')}</p>
      {on && pageLink && (
        <div className="flex flex-wrap items-center gap-2">
          <Link to={takerPagePath(saved)} className="text-primary inline-flex items-center gap-1 text-sm underline"
            data-testid="certificates-page-link">
            <ExternalLink aria-hidden className="size-4" />{t('certificates.page.open')}
          </Link>
          <Button size="sm" variant="ghost" data-testid="certificates-page-copy"
            onClick={() => void copy(pageLink, t('certificates.copied'), t('certificates.copyFailed'))}>
            <Copy aria-hidden />{t('certificates.copy')}
          </Button>
        </div>
      )}
    </div>
  )
}
