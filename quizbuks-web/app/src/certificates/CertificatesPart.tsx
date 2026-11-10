import { Button, Input, Label, notify, PageState, Switch } from '@jabiz/ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Copy, ExternalLink } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { AccountSection } from '../qb/AccountSections'
import { DEFAULT_SETTINGS, saveMySettings, type UserSettings } from '../qb/api'
import { problemText } from '../qb/problems'
import { CertificateList } from '../qb/CertificateList'
import { TAKER_CERTIFICATES } from '../qb/certificates'
import { Link } from '../qb/router'
import { SETTINGS_QUERY, useSoundSetting } from '../qb/SoundSettings'
import { appUrl, takerPagePath, verifyPath } from './api'

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
 * The taker's certificates (F-S5b, docs/quizbuks/plans/impl/F-S5.md section 3): those of the nodes they lit, each to
 * download (issued on the first click), to copy the verify link of and to stop sharing, and the Q77 ones still valid;
 * then the public certification page, off until turned on under a name of the taker's own.
 */
export function CertificatesPart() {
  const { t } = useTranslation(APP)
  return (
    <div className="flex flex-col gap-8">
      <AccountSection title={t('certificates.held')} testId="certificates">
        <CertificateList calls={TAKER_CERTIFICATES} queryKey={CERTIFICATIONS_QUERY}
          verifyLink={(key) => appUrl(verifyPath(key))} />
      </AccountSection>
      <AccountSection title={t('certificates.page.title')} testId="certificates-page">
        <PublicPageSettings />
      </AccountSection>
    </div>
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
