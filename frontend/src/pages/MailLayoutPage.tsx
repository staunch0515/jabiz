import { ApiError, LANGUAGE_NAMES, runProcess, useAuth } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
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
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import {
  APPROVAL_READ,
  CONTROL_CHANGE_PROPOSE,
  CONTROL_PROPOSE,
  fetchMailLayouts,
  fetchPendingChanges,
  fetchVersions,
  MAIL_LAYOUT_DATASET,
  MAIL_LAYOUT_RESET,
  MAIL_LAYOUT_TARGET,
  MAIL_LAYOUT_TEST_SEND,
  MAIL_TEXT_WRITE,
  PREVIEW_DEBOUNCE_MS,
  previewMailLayout,
  type MailLanguageLayout,
  type MailLayoutValues,
} from '../api/mail'
import { PendingChanges, ProposeForm } from '../components/mail/ControlledChange'
import MailPreviewPanel from '../components/mail/MailPreviewPanel'
import { explain, fieldProblems, otherProblems, useDebounced } from '../components/mail/support'
import VersionHistory, { type Values } from '../components/mail/VersionHistory'
import AdminPage from '../layout/AdminPage'
import type { HistoryVersion } from '../meta/types'
import StateBadge from '../components/mail/StateBadge'

type Field = 'displayName' | 'logoUrl' | 'logoAlt' | 'footer'
const FIELDS: Field[] = ['displayName', 'logoUrl', 'logoAlt', 'footer']
type Draft = Record<Field, string>

function valuesOf(layout: MailLayoutValues | undefined): Draft {
  return {
    displayName: layout?.displayName ?? '',
    logoUrl: layout?.logoUrl ?? '',
    logoAlt: layout?.logoAlt ?? '',
    footer: layout?.footer ?? '',
  }
}

/** What the editor starts from: the stored layout, else the default (configuration). */
function initialDraft(language: MailLanguageLayout): Draft {
  return valuesOf(language.state !== 'DEFAULT' && language.layout ? language.layout : language.defaultLayout)
}

/** What mail is sent on now: the stored layout while it is used, else the default. */
function inEffect(language: MailLanguageLayout): Draft {
  const used = language.state === 'CUSTOM' || language.state === 'DEFAULT_UPDATED'
  return valuesOf(used ? language.layout : language.defaultLayout)
}

function same(a: Draft, b: Draft): boolean {
  return FIELDS.every((field) => a[field] === b[field])
}

/** A value the server takes as absent when blank. */
function orNull(value: string): string | null {
  return value.trim() === '' ? null : value
}

interface LanguageLayoutEditorProps {
  language: MailLanguageLayout
  draft: Draft
  onDraft: (draft: Draft) => void
  onReload: () => Promise<void>
  maxFooterLength?: number
  maxLineLength?: number
}

function LanguageLayoutEditor({ language, draft, onDraft, onReload, maxFooterLength, maxLineLength }: LanguageLayoutEditorProps) {
  const { t } = useTranslation()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const locale = language.locale ?? ''
  const id = useId()
  const [error, setError] = useState<unknown>(null)
  const [conflict, setConflict] = useState(false)
  const [testing, setTesting] = useState(false)
  const [confirmReset, setConfirmReset] = useState(false)
  const mayWrite = can(MAIL_TEXT_WRITE)
  const mayPropose = mayWrite && can(CONTROL_PROPOSE)
  const initial = initialDraft(language)
  const dirty = !same(draft, initial)
  const current = inEffect(language)
  const labels: Record<Field, string> = {
    displayName: t('mail.layout.displayName'),
    logoUrl: t('mail.layout.logoUrl'),
    logoAlt: t('mail.layout.logoAlt'),
    footer: t('mail.layout.footer'),
  }
  const layout = (values: Draft) => ({
    displayName: orNull(values.displayName),
    logoUrl: orNull(values.logoUrl),
    logoAlt: orNull(values.logoAlt),
    footer: orNull(values.footer),
  })

  const settled = useDebounced(draft, PREVIEW_DEBOUNCE_MS)
  const preview = useQuery({
    queryKey: ['mail', 'layout-preview', locale, settled],
    queryFn: () => previewMailLayout(locale, layout(settled) as MailLayoutValues),
    placeholderData: keepPreviousData,
    retry: false,
  })
  const pending = useQuery({
    queryKey: ['mail', 'pending', MAIL_LAYOUT_TARGET, locale],
    enabled: can(APPROVAL_READ),
    queryFn: () => fetchPendingChanges(MAIL_LAYOUT_TARGET, locale),
  })
  const versions = useQuery({
    queryKey: ['mail', 'versions', MAIL_LAYOUT_TARGET, locale],
    queryFn: () => fetchVersions(MAIL_LAYOUT_DATASET, { locale }),
  })

  const setField = (field: Field, value: string) => {
    setError(null)
    onDraft({ ...draft, [field]: value })
  }

  const propose = async (reason: string) => {
    setError(null)
    try {
      await runProcess(CONTROL_CHANGE_PROPOSE, {
        targetEntity: MAIL_LAYOUT_TARGET,
        values: { locale, ...layout(draft) },
        reason,
      })
      notify.success(t('mail.propose.done'))
      await queryClient.invalidateQueries({ queryKey: ['mail', 'pending', MAIL_LAYOUT_TARGET] })
    } catch (e) {
      setError(e)
      notify.error(explain(e, t))
      throw e
    }
  }

  const testSend = async () => {
    setTesting(true)
    try {
      const output = await runProcess<{ address?: string }>(MAIL_LAYOUT_TEST_SEND, { locale, ...layout(draft) })
      notify.success(t('mail.test.sent', { address: output?.address ?? '' }))
    } catch (e) {
      notify.error(explain(e, t))
    } finally {
      setTesting(false)
    }
  }

  const reset = async () => {
    try {
      await runProcess(MAIL_LAYOUT_RESET, { locale, baseVersionNo: language.versionNo ?? null })
      notify.success(t('mail.reset.done'))
      await queryClient.invalidateQueries({ queryKey: ['mail', 'versions', MAIL_LAYOUT_TARGET, locale] })
      await onReload()
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setConflict(true)
        return
      }
      notify.error(explain(e, t))
      throw e
    }
  }

  const general = otherProblems(error, FIELDS)
  const field = (name: Field, multiline = false) => {
    const problems = fieldProblems(error, name)
    const inputId = `${id}-${name}`
    const common = {
      id: inputId,
      value: draft[name],
      readOnly: !mayWrite,
      'aria-invalid': problems.length > 0 || undefined,
      'aria-describedby': problems.length > 0 ? `${inputId}-problems` : undefined,
      'data-testid': `mail-layout-${name}`,
    }
    return (
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={inputId}>{labels[name]}</Label>
        {multiline ? (
          <Textarea {...common} rows={6} maxLength={maxFooterLength} className="font-mono text-sm"
            onChange={(event) => setField(name, event.target.value)} />
        ) : (
          <Input {...common} maxLength={maxLineLength} onChange={(event) => setField(name, event.target.value)} />
        )}
        {problems.length > 0 && (
          <ul id={`${inputId}-problems`} className="text-destructive text-sm">
            {problems.map((message, index) => <li key={index}>{message}</li>)}
          </ul>
        )}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <StateBadge locale={locale} state={pending.data && pending.data.length > 0 ? 'PENDING' : (language.state ?? 'DEFAULT')} />
        <span className="text-muted-foreground">{t(`mail.layout.stateHint.${language.state ?? 'DEFAULT'}`)}</span>
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
              <Button size="sm" variant="outline" onClick={() => { setConflict(false); void onReload() }}>
                {t('mail.editor.reload')}
              </Button>
            </div>
          </AlertDescription>
        </Alert>
      )}
      <PendingChanges
        changes={pending.data ?? []}
        fields={FIELDS.map((name) => ({ name, label: labels[name] }))}
        current={current}
        onWithdrawn={() => void queryClient.invalidateQueries({ queryKey: ['mail', 'pending', MAIL_LAYOUT_TARGET] })}
      />
      <div className="grid items-start gap-4 lg:grid-cols-[1fr_1fr]">
        <Card>
          <CardContent className="flex flex-col gap-4">
            {field('displayName')}
            {field('logoUrl')}
            <p className="text-muted-foreground -mt-2 text-xs">{t('mail.layout.logoHint')}</p>
            {field('logoAlt')}
            {field('footer', true)}
            <p className="text-muted-foreground -mt-2 text-xs">{t('mail.layout.footerHint', { max: maxFooterLength ?? '' })}</p>
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
                <Button variant="outline" onClick={() => void testSend()} disabled={testing} data-testid="mail-test-send">
                  {testing && <Spinner className="text-current" />}
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
            <Alert data-testid="mail-protected">
              <Info aria-hidden />
              <AlertDescription className="text-foreground">{t('mail.layout.protected')}</AlertDescription>
            </Alert>
            {mayPropose && <ProposeForm onPropose={propose} disabled={!dirty} />}
            {mayWrite && !mayPropose && <p className="text-muted-foreground text-sm">{t('mail.propose.noPermission')}</p>}
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
      <Card>
        <CardContent>
          <VersionHistory
            versions={versions.data ?? []}
            loading={versions.isLoading}
            error={versions.error ? explain(versions.error, t) : null}
            fields={FIELDS.map((name) => ({ name, label: labels[name] }))}
            current={current}
            valuesOf={(version: HistoryVersion): Values =>
              version.attributes?.kind === 'CUSTOM'
                ? valuesOf(version.attributes as MailLayoutValues)
                : valuesOf(language.defaultLayout)}
            onUseAsDraft={mayWrite ? (values) => {
              onDraft(valuesOf(values))
              notify.success(t('mail.history.usedAsDraft'))
            } : undefined}
          />
        </CardContent>
      </Card>
      <ConfirmDialog
        open={confirmReset}
        onOpenChange={setConfirmReset}
        title={t('mail.layout.resetTitle')}
        description={t('mail.layout.resetDescription')}
        confirmLabel={t('mail.reset.confirm')}
        destructive
        onConfirm={reset}
      />
    </div>
  )
}

/**
 * "Mail layout" (decision D72 item 6; phase 17zh-b/c): per language the header every mail shares (a display name, an
 * optional logo of this site with its alternative text) and the footer (Markdown); a sample mail on the draft
 * (debounced, sandboxed frame), a test mail to oneself, restoring the default (configuration) at once, and changes
 * only as a controlled change another administrator publishes; the pending change and the history with comparison.
 */
export default function MailLayoutPage() {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const [params, setParams] = useSearchParams()
  const layouts = useQuery({ queryKey: ['mail', 'layouts'], queryFn: fetchMailLayouts })
  const [drafts, setDrafts] = useState<Record<string, Draft>>({})

  if (layouts.isLoading) return <PageState kind="loading" />
  if (layouts.error) {
    return <PageState kind="error" titleAs="h1" title={layouts.error instanceof ApiError ? layouts.error.display : t('app.error')} />
  }
  const languages = layouts.data?.languages ?? []
  const locales = languages.map((language) => language.locale ?? '')
  const asked = params.get('locale')
  const locale = asked && locales.includes(asked) ? asked : locales.includes(i18n.language) ? i18n.language : locales[0]

  const drop = (forLocale: string) =>
    setDrafts((all) => Object.fromEntries(Object.entries(all).filter(([key]) => key !== forLocale)))

  return (
    <AdminPage title={t('mail.layout.title')} description={t('mail.layout.hint')}>
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
              <LanguageLayoutEditor
                language={language}
                draft={drafts[locale] ?? initialDraft(language)}
                onDraft={(draft) => {
                  if (same(draft, initialDraft(language))) drop(locale)
                  else setDrafts((all) => ({ ...all, [locale]: draft }))
                }}
                onReload={async () => {
                  drop(locale)
                  await queryClient.invalidateQueries({ queryKey: ['mail', 'layouts'] })
                }}
                maxFooterLength={layouts.data?.maxFooterLength}
                maxLineLength={layouts.data?.maxLineLength}
              />
            </TabsContent>
          ))}
      </Tabs>
    </AdminPage>
  )
}
