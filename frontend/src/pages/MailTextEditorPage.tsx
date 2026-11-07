import { ApiError, LANGUAGE_NAMES, runProcess, useAuth } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Card,
  CardContent,
  ConfirmDialog,
  Input,
  Label,
  notify,
  PageState,
  Spinner,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  Textarea,
} from '@jabiz/ui'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { Info, TriangleAlert } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import {
  APPROVAL_READ,
  CONTROL_CHANGE_PROPOSE,
  CONTROL_PROPOSE,
  fetchMailTexts,
  fetchPendingChanges,
  fetchVersions,
  MAIL_TEXT_DATASET,
  MAIL_TEXT_RESET,
  MAIL_TEXT_SAVE,
  MAIL_TEXT_TARGET,
  MAIL_TEXT_TEST_SEND,
  MAIL_TEXT_WRITE,
  PREVIEW_DEBOUNCE_MS,
  previewMailText,
  textTargetKey,
  type MailLanguageTexts,
  type MailTemplateTexts,
} from '../api/mail'
import { PendingChanges, ProposeForm } from '../components/mail/ControlledChange'
import MailPreviewPanel from '../components/mail/MailPreviewPanel'
import { explain, fieldProblems, otherProblems, useDebounced } from '../components/mail/support'
import VersionHistory, { type Values } from '../components/mail/VersionHistory'
import AdminPage from '../layout/AdminPage'
import { missingRequired } from '../meta/mailText'
import type { HistoryVersion } from '../meta/types'
import StateBadge from '../components/mail/StateBadge'
import { mailPaths } from './paths'

interface Draft {
  subject: string
  body: string
}

type Field = keyof Draft
const FIELDS: Field[] = ['subject', 'body']

/** Whether a language's stored override is what the editor starts from (else the default). */
function hasOverride(language: MailLanguageTexts): boolean {
  return language.state !== undefined && language.state !== 'DEFAULT' && language.subject != null
}

/** What the editor starts from: the stored override, else the default. */
function initialDraft(language: MailLanguageTexts): Draft {
  return hasOverride(language)
    ? { subject: language.subject ?? '', body: language.body ?? '' }
    : { subject: language.defaultSubject ?? '', body: language.defaultBody ?? '' }
}

/** What is sent now: the override while it is sent, else the default. */
function inEffect(language: MailLanguageTexts): Values {
  const sent = language.state === 'CUSTOM' || language.state === 'DEFAULT_UPDATED'
  return sent
    ? { subject: language.subject ?? '', body: language.body ?? '' }
    : { subject: language.defaultSubject ?? '', body: language.defaultBody ?? '' }
}

interface LanguageEditorProps {
  template: MailTemplateTexts
  language: MailLanguageTexts
  draft: Draft
  onDraft: (draft: Draft) => void
  /** Drops the draft and reads the texts again. */
  onReload: () => Promise<void>
  maxSubjectLength?: number
  maxBodyLength?: number
}

function LanguageEditor({ template, language, draft, onDraft, onReload, maxSubjectLength, maxBodyLength }: LanguageEditorProps) {
  const { t } = useTranslation()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const name = template.template ?? ''
  const locale = language.locale ?? ''
  const subjectId = useId()
  const bodyId = useId()
  const subjectRef = useRef<HTMLInputElement>(null)
  const bodyRef = useRef<HTMLTextAreaElement>(null)
  const lastFocused = useRef<Field>('body')
  const [error, setError] = useState<unknown>(null)
  const [conflict, setConflict] = useState(false)
  const [busy, setBusy] = useState<'save' | 'test' | null>(null)
  const [confirmReset, setConfirmReset] = useState(false)
  const mayWrite = can(MAIL_TEXT_WRITE)
  const mayPropose = mayWrite && can(CONTROL_PROPOSE)
  const protectedTemplate = !!template.needsApproval
  const initial = initialDraft(language)
  const dirty = draft.subject !== initial.subject || draft.body !== initial.body
  const missing = missingRequired(template.required ?? [], draft)
  const current = inEffect(language)

  const settled = useDebounced(draft, PREVIEW_DEBOUNCE_MS)
  const preview = useQuery({
    queryKey: ['mail', 'preview', name, locale, settled.subject, settled.body],
    queryFn: () => previewMailText(name, locale, settled.subject, settled.body),
    placeholderData: keepPreviousData,
    retry: false,
  })
  const pending = useQuery({
    queryKey: ['mail', 'pending', MAIL_TEXT_TARGET, name, locale],
    enabled: protectedTemplate && can(APPROVAL_READ),
    queryFn: () => fetchPendingChanges(MAIL_TEXT_TARGET, textTargetKey(name, locale)),
  })
  const versions = useQuery({
    queryKey: ['mail', 'versions', MAIL_TEXT_TARGET, name, locale],
    queryFn: () => fetchVersions(MAIL_TEXT_DATASET, { template: name, locale }),
  })

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['mail', 'versions', MAIL_TEXT_TARGET, name, locale] })
    await queryClient.invalidateQueries({ queryKey: ['mail', 'pending', MAIL_TEXT_TARGET] })
    await onReload()
  }

  const setField = (field: Field, value: string) => {
    setError(null)
    onDraft({ ...draft, [field]: value })
  }

  /** Puts `{name}` where the cursor was in the field last edited (the body when none was). */
  const insert = (placeholder: string) => {
    const field = lastFocused.current
    const element = field === 'subject' ? subjectRef.current : bodyRef.current
    const value = draft[field]
    const token = `{${placeholder}}`
    const start = element?.selectionStart ?? value.length
    const end = element?.selectionEnd ?? value.length
    setField(field, value.slice(0, start) + token + value.slice(end))
    requestAnimationFrame(() => {
      element?.focus()
      element?.setSelectionRange(start + token.length, start + token.length)
    })
  }

  const failed = (e: unknown) => {
    if (e instanceof ApiError && e.status === 409) {
      setConflict(true)
      return
    }
    setError(e)
    notify.error(explain(e, t))
  }

  const save = async () => {
    setBusy('save')
    setError(null)
    try {
      await runProcess(MAIL_TEXT_SAVE, {
        template: name, locale, subject: draft.subject, body: draft.body, baseVersionNo: language.versionNo ?? null,
      })
      notify.success(t('mail.editor.saved'))
      await refresh()
    } catch (e) {
      failed(e)
    } finally {
      setBusy(null)
    }
  }

  const propose = async (reason: string) => {
    setError(null)
    try {
      await runProcess(CONTROL_CHANGE_PROPOSE, {
        targetEntity: MAIL_TEXT_TARGET,
        values: { template: name, locale, subject: draft.subject, body: draft.body },
        reason,
      })
      notify.success(t('mail.propose.done'))
      await queryClient.invalidateQueries({ queryKey: ['mail', 'pending', MAIL_TEXT_TARGET] })
    } catch (e) {
      failed(e)
      throw e
    }
  }

  const testSend = async () => {
    setBusy('test')
    try {
      const output = await runProcess<{ address?: string }>(MAIL_TEXT_TEST_SEND, {
        template: name, locale, subject: draft.subject, body: draft.body,
      })
      notify.success(t('mail.test.sent', { address: output?.address ?? '' }))
    } catch (e) {
      notify.error(explain(e, t))
    } finally {
      setBusy(null)
    }
  }

  const reset = async () => {
    try {
      await runProcess(MAIL_TEXT_RESET, { template: name, locale, baseVersionNo: language.versionNo ?? null })
      notify.success(t('mail.reset.done'))
      await refresh()
    } catch (e) {
      failed(e)
      // A conflict is said on the page (with the reload): the question is closed; other errors leave it open.
      if (!(e instanceof ApiError && e.status === 409)) throw e
    }
  }

  const problemsOf = (field: Field) => fieldProblems(error, field)
  const general = otherProblems(error, FIELDS)

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <StateBadge locale={locale} state={pending.data && pending.data.length > 0 ? 'PENDING' : (language.state ?? 'DEFAULT')} />
        <span className="text-muted-foreground">{t(`mail.stateHint.${language.state ?? 'DEFAULT'}`)}</span>
      </div>
      {(language.problems ?? []).length > 0 && (
        <Alert variant="destructive" data-testid="mail-stored-problems">
          <TriangleAlert aria-hidden />
          <AlertDescription>
            <ul className="list-disc pl-4">
              {(language.problems ?? []).map((problem, index) => <li key={index}>{problem.message}</li>)}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      {conflict && (
        <Alert variant="destructive" data-testid="mail-conflict">
          <TriangleAlert aria-hidden />
          <AlertDescription className="flex flex-col gap-2">
            <p>{t('mail.errors.conflict')}</p>
            <div>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setConflict(false)
                  void onReload()
                }}
                data-testid="mail-conflict-reload"
              >
                {t('mail.editor.reload')}
              </Button>
            </div>
          </AlertDescription>
        </Alert>
      )}
      <PendingChanges
        changes={pending.data ?? []}
        fields={[{ name: 'subject', label: t('mail.editor.subject') }, { name: 'body', label: t('mail.editor.body') }]}
        current={current}
        onWithdrawn={() => void queryClient.invalidateQueries({ queryKey: ['mail', 'pending', MAIL_TEXT_TARGET] })}
      />
      <div className="grid items-start gap-4 lg:grid-cols-[3fr_2fr]">
        <Card>
          <CardContent className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={subjectId}>{t('mail.editor.subject')}</Label>
              <Input
                id={subjectId}
                ref={subjectRef}
                value={draft.subject}
                readOnly={!mayWrite}
                maxLength={maxSubjectLength}
                aria-invalid={problemsOf('subject').length > 0 || undefined}
                aria-describedby={problemsOf('subject').length > 0 ? `${subjectId}-problems` : undefined}
                onFocus={() => (lastFocused.current = 'subject')}
                onChange={(event) => setField('subject', event.target.value)}
                data-testid="mail-subject"
              />
              {problemsOf('subject').length > 0 && (
                <ul id={`${subjectId}-problems`} className="text-destructive text-sm" data-testid="mail-subject-problems">
                  {problemsOf('subject').map((message, index) => <li key={index}>{message}</li>)}
                </ul>
              )}
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={bodyId}>{t('mail.editor.body')}</Label>
              <Textarea
                id={bodyId}
                ref={bodyRef}
                value={draft.body}
                readOnly={!mayWrite}
                rows={14}
                maxLength={maxBodyLength}
                className="font-mono text-sm"
                aria-invalid={problemsOf('body').length > 0 || undefined}
                aria-describedby={`${bodyId}-hint${problemsOf('body').length > 0 ? ` ${bodyId}-problems` : ''}`}
                onFocus={() => (lastFocused.current = 'body')}
                onChange={(event) => setField('body', event.target.value)}
                data-testid="mail-body"
              />
              <p id={`${bodyId}-hint`} className="text-muted-foreground text-xs">
                {t('mail.editor.bodyHint', { count: draft.body.length, max: maxBodyLength ?? '' })}
              </p>
              {problemsOf('body').length > 0 && (
                <ul id={`${bodyId}-problems`} className="text-destructive text-sm" data-testid="mail-body-problems">
                  {problemsOf('body').map((message, index) => <li key={index}>{message}</li>)}
                </ul>
              )}
            </div>
            {general.length > 0 && (
              <Alert variant="destructive">
                <TriangleAlert aria-hidden />
                <AlertDescription>
                  <ul className="list-disc pl-4">{general.map((message, index) => <li key={index}>{message}</li>)}</ul>
                </AlertDescription>
              </Alert>
            )}
            {mayWrite && (
              <div className="flex flex-wrap gap-2">
                {!protectedTemplate && (
                  <Button onClick={() => void save()} disabled={!dirty || busy !== null} data-testid="mail-save">
                    {busy === 'save' && <Spinner className="text-current" />}
                    {t('mail.editor.save')}
                  </Button>
                )}
                <Button variant="outline" onClick={() => void testSend()} disabled={busy !== null} data-testid="mail-test-send">
                  {busy === 'test' && <Spinner className="text-current" />}
                  {t('mail.test.send')}
                </Button>
                {dirty && (
                  <Button variant="ghost" onClick={() => onDraft(initial)} data-testid="mail-discard">
                    {t('mail.editor.discard')}
                  </Button>
                )}
                {language.state !== 'DEFAULT' && language.versionNo != null && (
                  <Button variant="outline" onClick={() => setConfirmReset(true)} data-testid="mail-reset">
                    {t('mail.reset.button')}
                  </Button>
                )}
              </div>
            )}
            {mayWrite && <p className="text-muted-foreground text-xs">{t('mail.test.hint')}</p>}
            {protectedTemplate && (
              <Alert data-testid="mail-protected">
                <Info aria-hidden />
                <AlertDescription className="text-foreground">{t('mail.editor.protected')}</AlertDescription>
              </Alert>
            )}
            {protectedTemplate && mayPropose && <ProposeForm onPropose={propose} disabled={!dirty} />}
            {protectedTemplate && mayWrite && !mayPropose && (
              <p className="text-muted-foreground text-sm">{t('mail.propose.noPermission')}</p>
            )}
          </CardContent>
        </Card>
        <div className="flex flex-col gap-4">
          <Card>
            <CardContent className="flex flex-col gap-3">
              <section aria-labelledby={`${bodyId}-placeholders`} className="flex flex-col gap-2">
                <h2 id={`${bodyId}-placeholders`} className="text-base font-semibold">{t('mail.editor.placeholders')}</h2>
                <p className="text-muted-foreground text-xs">{t('mail.editor.placeholdersHint')}</p>
                <div className="flex flex-wrap gap-1">
                  {(template.placeholders ?? []).map((placeholder) => (
                    <Button
                      key={placeholder}
                      variant="outline"
                      size="sm"
                      className="font-mono"
                      disabled={!mayWrite}
                      aria-label={t('mail.editor.insert', { placeholder: `{${placeholder}}` })}
                      onClick={() => insert(placeholder)}
                      data-testid={`mail-insert-${placeholder}`}
                    >
                      {`{${placeholder}}`}
                    </Button>
                  ))}
                </div>
              </section>
              {(template.required ?? []).length > 0 && (
                <section aria-labelledby={`${bodyId}-required`} className="flex flex-col gap-2">
                  <h3 id={`${bodyId}-required`} className="text-sm font-semibold">{t('mail.editor.required')}</h3>
                  <ul className="flex flex-wrap gap-1" data-testid="mail-required">
                    {(template.required ?? []).map((placeholder) => {
                      const lacking = missing.includes(placeholder)
                      return (
                        <li key={placeholder}>
                          <Badge
                            variant={lacking ? 'destructive' : 'outline'}
                            className="font-mono"
                            data-testid={`mail-required-${placeholder}`}
                            data-missing={lacking || undefined}
                          >
                            {`{${placeholder}}`}
                            {lacking && <span>· {t('mail.editor.missing')}</span>}
                          </Badge>
                        </li>
                      )
                    })}
                  </ul>
                  {missing.length > 0 && (
                    <p className="text-destructive text-sm" role="status" data-testid="mail-missing">
                      {t('mail.editor.missingHint', { names: missing.map((m) => `{${m}}`).join(' ') })}
                    </p>
                  )}
                </section>
              )}
              {(template.secrets ?? []).length > 0 && (
                <p className="text-muted-foreground text-xs" data-testid="mail-secrets">
                  {t('mail.editor.secrets', { names: (template.secrets ?? []).map((s) => `{${s}}`).join(' ') })}
                </p>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardContent>
              <MailPreviewPanel
                preview={preview.data}
                loading={preview.isFetching}
                error={preview.error ? explain(preview.error, t) : null}
              />
            </CardContent>
          </Card>
        </div>
      </div>
      <Card>
        <CardContent>
          <VersionHistory
            versions={versions.data ?? []}
            loading={versions.isLoading}
            error={versions.error ? explain(versions.error, t) : null}
            fields={[{ name: 'subject', label: t('mail.editor.subject') }, { name: 'body', label: t('mail.editor.body') }]}
            current={current}
            valuesOf={(version: HistoryVersion) =>
              version.attributes?.kind === 'CUSTOM'
                ? { subject: String(version.attributes?.subject ?? ''), body: String(version.attributes?.body ?? '') }
                : { subject: language.defaultSubject ?? '', body: language.defaultBody ?? '' }}
            onUseAsDraft={mayWrite ? (values) => {
              onDraft({ subject: values.subject ?? '', body: values.body ?? '' })
              notify.success(t('mail.history.usedAsDraft'))
            } : undefined}
          />
        </CardContent>
      </Card>
      <ConfirmDialog
        open={confirmReset}
        onOpenChange={setConfirmReset}
        title={t('mail.reset.title')}
        description={protectedTemplate ? t('mail.reset.descriptionProtected') : t('mail.reset.description')}
        confirmLabel={t('mail.reset.confirm')}
        destructive
        onConfirm={reset}
      />
    </div>
  )
}

/**
 * The editor of one mail template (decision D72; phase 17zh-c): per language the subject and body, the placeholders
 * (inserted at the cursor) and those that must stay, a live preview (debounced, in a sandboxed frame), a test mail to
 * oneself, restoring the default, saving (ordinary templates, with the version read: 409 when someone changed it
 * since) or proposing a change another administrator publishes (templates whose changes need approval), the pending
 * change, and the history with comparison.
 */
export default function MailTextEditorPage() {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const [params, setParams] = useSearchParams()
  const templateName = params.get('template') ?? ''
  const texts = useQuery({ queryKey: ['mail', 'texts'], queryFn: fetchMailTexts })
  const [drafts, setDrafts] = useState<Record<string, Draft>>({})

  if (texts.isLoading) return <PageState kind="loading" />
  if (texts.error) {
    return <PageState kind="error" titleAs="h1" title={texts.error instanceof ApiError ? texts.error.display : t('app.error')} />
  }
  const template = texts.data?.templates?.find((candidate) => candidate.template === templateName)
  if (!template) return <PageState kind="notFound" titleAs="h1" title={templateName || t('mail.texts.title')} />

  const languages = template.languages ?? []
  const locales = languages.map((language) => language.locale ?? '')
  const asked = params.get('locale')
  const locale = asked && locales.includes(asked) ? asked : locales.includes(i18n.language) ? i18n.language : locales[0]

  const drop = (forLocale: string) =>
    setDrafts((all) => Object.fromEntries(Object.entries(all).filter(([key]) => key !== forLocale)))
  const reload = (forLocale: string) => async () => {
    drop(forLocale)
    await queryClient.invalidateQueries({ queryKey: ['mail', 'texts'] })
  }

  return (
    <AdminPage
      title={template.name ?? template.template}
      description={
        <span className="flex flex-wrap items-center gap-2">
          <code className="font-mono">{template.template}</code>
          <span>· {t(`mail.category.${template.category ?? 'TRANSACTIONAL'}`, { defaultValue: template.category })}</span>
          {template.needsApproval && <Badge variant="warning">{t('mail.texts.needsApproval')}</Badge>}
        </span>
      }
      breadcrumb={[{ label: t('mail.texts.title'), to: mailPaths.texts }, { label: template.name ?? template.template }]}
    >
      <Tabs
        value={locale}
        onValueChange={(next) => {
          const updated = new URLSearchParams(params)
          updated.set('locale', next)
          setParams(updated, { replace: true })
        }}
      >
        <TabsList aria-label={t('mail.editor.languages')}>
          {languages.map((language) => (
            <TabsTrigger key={language.locale} value={language.locale ?? ''} data-testid={`mail-tab-${language.locale}`}>
              <span lang={language.locale}>{LANGUAGE_NAMES[language.locale as keyof typeof LANGUAGE_NAMES] ?? language.locale}</span>
              {drafts[language.locale ?? ''] && (
                <>
                  <span aria-hidden>•</span>
                  <span className="sr-only">{t('mail.editor.edited')}</span>
                </>
              )}
            </TabsTrigger>
          ))}
        </TabsList>
        {languages
          .filter((language) => language.locale === locale)
          .map((language) => (
            <TabsContent key={language.locale} value={language.locale ?? ''}>
              <LanguageEditor
                template={template}
                language={language}
                draft={drafts[locale] ?? initialDraft(language)}
                onDraft={(draft) => {
                  const initial = initialDraft(language)
                  if (draft.subject === initial.subject && draft.body === initial.body) drop(locale)
                  else setDrafts((all) => ({ ...all, [locale]: draft }))
                }}
                onReload={reload(locale)}
                maxSubjectLength={texts.data?.maxSubjectLength}
                maxBodyLength={texts.data?.maxBodyLength}
              />
            </TabsContent>
          ))}
      </Tabs>
    </AdminPage>
  )
}
