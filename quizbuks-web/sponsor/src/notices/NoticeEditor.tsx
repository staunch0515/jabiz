import { formatDateTime, i18n } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Checkbox,
  ConfirmDialog,
  Input,
  Label,
  RadioGroup,
  RadioGroupItem,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Eye, Save, Send } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { useRegionLabel } from '../publishing/shared'
import { LANGUAGES } from '../qb/messageKeys'
import { problemText } from '../qb/problems'
import {
  instantOf,
  localOf,
  NOTICE_LIMITS,
  NOTICES_QUERY,
  readPublicationOptions,
  saveNotice,
  sendNotice,
  type NoticeRow,
  type SaveOutput,
} from './api'
import './messages'

const LANGUAGE_NAMES: Record<string, string> = { en: 'English', zh: '中文', ja: '日本語' }

/** The language a notice was written in: its title's (a notice has one), else the interface's. */
function languageOf(row: NoticeRow | null): string {
  const written = row ? Object.keys(row.title)[0] ?? Object.keys(row.body)[0] : undefined
  const base = written ?? i18n.language.split('-')[0]
  return (LANGUAGES as readonly string[]).includes(base) ? base : 'en'
}

/** How the notice will look in a taker's inbox: plain text, the company as sender, the one button. */
function Preview({ title, body }: { title: string; body: string }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <Card data-testid="notice-preview">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm">
          <Eye aria-hidden className="size-4" />
          {t('notices.preview.title')}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        <p className="font-semibold">{title}</p>
        <p className="text-muted-foreground text-xs">{t('notices.preview.from')}</p>
        <p className="text-sm break-words whitespace-pre-wrap">{body}</p>
        <div><Button size="sm" type="button" tabIndex={-1} aria-hidden>{t('notices.preview.viewQuiz')}</Button></div>
      </CardContent>
    </Card>
  )
}

/**
 * Writes a notice (M-60): its language, title and plain text, one of the sponsor's scheduled or live publications,
 * some of its regions, now or at a time. Every save answers with the problems sending would meet (the server's rules,
 * Q7-10); sending is offered once there are none, and asks first. A newer revision elsewhere stops the editor.
 */
export function NoticeEditor({ row, onClose }: { row: NoticeRow | null; onClose: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const regionLabel = useRegionLabel()
  const publications = useQuery({ queryKey: [...NOTICES_QUERY, 'publications'], queryFn: readPublicationOptions })
  const [language, setLanguage] = useState(() => languageOf(row))
  const [title, setTitle] = useState(() => (row ? Object.values(row.title)[0] ?? '' : ''))
  const [body, setBody] = useState(() => (row ? Object.values(row.body)[0] ?? '' : ''))
  const [publicationId, setPublicationId] = useState<string | null>(row?.publicationId ?? null)
  const [regions, setRegions] = useState<string[]>(row?.regions ?? [])
  const [later, setLater] = useState(!!row?.scheduledAt)
  const [at, setAt] = useState(() => localOf(row?.scheduledAt ?? null))
  const [saved, setSaved] = useState<{ broadcastId: string; revision: number } | null>(
    row ? { broadcastId: row.broadcastId, revision: row.revision } : null)
  const [output, setOutput] = useState<SaveOutput | null>(null)
  const [note, setNote] = useState('')
  // A new notice of a sponsor with one publication to choose is about that one.
  const only = !row && publicationId == null && publications.data?.length === 1 ? publications.data[0] : null
  if (only) {
    setPublicationId(only.publicationId)
    setRegions(only.regions)
  }
  const publication = publications.data?.find((option) => option.publicationId === publicationId) ?? null

  const save = useMutation({
    mutationFn: () => saveNotice({
      broadcastId: saved?.broadcastId ?? null,
      baseRevision: saved?.revision ?? null,
      title: title.trim() ? { [language]: title } : {},
      body: body.trim() ? { [language]: body } : {},
      publicationId,
      regions,
      scheduledAt: later ? instantOf(at) : null,
    }),
    onSuccess: (result) => {
      setSaved({ broadcastId: result.broadcastId, revision: result.revision })
      setOutput(result)
      setNote(t('notices.editor.saved'))
      void queryClient.invalidateQueries({ queryKey: NOTICES_QUERY })
    },
  })
  const send = useMutation({
    mutationFn: () => sendNotice(saved!.broadcastId, saved!.revision),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: NOTICES_QUERY })
      setNote(result.status === 'SCHEDULED' ? t('notices.editor.scheduled') : t('notices.editor.sent'))
      onClose()
    },
  })
  const failed = save.error ?? send.error
  const changed = failed && JSON.stringify(failed).includes('QB_BROADCAST_CHANGED')
  // Sending is what was saved: offered only for a save without problems and nothing changed since.
  const [savedAs, setSavedAs] = useState('')
  const current = JSON.stringify({ language, title, body, publicationId, regions, later, at })
  const dirty = savedAs !== current
  const ready = !!output && output.problems.length === 0 && !dirty
  const choose = (next: string) => {
    // Radix's select reports an empty value when its value is set from outside before its items exist.
    if (!next) return
    setPublicationId(next)
    const option = publications.data?.find((item) => item.publicationId === next)
    // The publication's regions to start with; the sponsor may leave some out.
    setRegions(option ? option.regions : [])
  }
  return (
    <Card data-testid="notice-editor">
      <CardHeader>
        <CardTitle>{row ? t('notices.editor.title') : t('notices.editor.newTitle')}</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="grid gap-4 lg:grid-cols-[1fr_18rem]" onSubmit={(event) => {
          event.preventDefault()
          setSavedAs(current)
          save.mutate()
        }}>
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-language`}>{t('notices.editor.language')}</Label>
              <Select value={language} onValueChange={setLanguage}>
                <SelectTrigger id={`${id}-language`} className="w-48" data-testid="notice-language">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {LANGUAGES.map((code) => <SelectItem key={code} value={code}>{LANGUAGE_NAMES[code]}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-title`}>{t('notices.editor.heading')}</Label>
              <Input id={`${id}-title`} value={title} maxLength={NOTICE_LIMITS.title} lang={language}
                data-testid="notice-title" onChange={(event) => setTitle(event.target.value)} />
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-body`}>{t('notices.editor.text')}</Label>
              <Textarea id={`${id}-body`} value={body} rows={5} maxLength={NOTICE_LIMITS.body} lang={language}
                aria-describedby={`${id}-body-hint`} data-testid="notice-body"
                onChange={(event) => setBody(event.target.value)} />
              <p id={`${id}-body-hint`} className="text-muted-foreground text-xs">
                {t('notices.editor.textHint', { max: NOTICE_LIMITS.body })}
              </p>
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-publication`}>{t('notices.editor.publication')}</Label>
              <Select value={publicationId ?? undefined} onValueChange={choose}>
                <SelectTrigger id={`${id}-publication`} className="w-full max-w-md" data-testid="notice-publication">
                  <SelectValue placeholder={t('notices.editor.choosePublication')} />
                </SelectTrigger>
                <SelectContent>
                  {(publications.data ?? []).map((option) => (
                    <SelectItem key={option.publicationId} value={option.publicationId}>
                      {t('notices.editor.publicationNotices', { name: option.name, count: option.notices })}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {publications.data?.length === 0 && (
                <p className="text-muted-foreground text-sm">{t('notices.noPublication')}</p>
              )}
            </div>
            {publication && (
              <fieldset className="flex flex-col gap-2" aria-describedby={`${id}-regions-hint`}>
                <legend className="text-sm font-medium">{t('notices.editor.regions')}</legend>
                <p id={`${id}-regions-hint`} className="text-muted-foreground text-xs">
                  {t('notices.editor.regionsHint')}
                </p>
                <div className="flex flex-wrap gap-3">
                  {publication.regions.map((region) => (
                    <div key={region} className="flex items-center gap-2">
                      <Checkbox id={`${id}-region-${region}`} checked={regions.includes(region)}
                        data-testid={`notice-region-${region}`}
                        onCheckedChange={(on) => setRegions((current) => on === true
                          ? publication.regions.filter((r) => r === region || current.includes(r))
                          : current.filter((r) => r !== region))} />
                      <Label htmlFor={`${id}-region-${region}`}>{regionLabel(region)}</Label>
                    </div>
                  ))}
                </div>
              </fieldset>
            )}
            <fieldset className="flex flex-col gap-2">
              <legend className="text-sm font-medium">{t('notices.editor.when')}</legend>
              <RadioGroup value={later ? 'later' : 'now'} onValueChange={(value) => setLater(value === 'later')}
                className="flex flex-wrap gap-4">
                <div className="flex items-center gap-2">
                  <RadioGroupItem id={`${id}-now`} value="now" data-testid="notice-now" />
                  <Label htmlFor={`${id}-now`}>{t('notices.editor.now')}</Label>
                </div>
                <div className="flex items-center gap-2">
                  <RadioGroupItem id={`${id}-later`} value="later" data-testid="notice-later" />
                  <Label htmlFor={`${id}-later`}>{t('notices.editor.later')}</Label>
                </div>
              </RadioGroup>
              {later && (
                <div className="flex flex-col gap-1">
                  <Label htmlFor={`${id}-at`}>{t('notices.editor.at')}</Label>
                  <Input id={`${id}-at`} type="datetime-local" className="w-60" value={at} data-testid="notice-at"
                    onChange={(event) => setAt(event.target.value)} />
                </div>
              )}
            </fieldset>
            <p role="status" className="text-muted-foreground text-sm empty:hidden" data-testid="notice-note">
              {note}
            </p>
            {failed && (
              <Alert variant="destructive" data-testid="notice-error">
                <AlertDescription>{changed ? t('notices.editor.changed') : problemText(failed)}</AlertDescription>
              </Alert>
            )}
            {output && !dirty && (output.problems.length > 0 ? (
              <Alert variant="warning" data-testid="notice-problems">
                <AlertDescription>
                  <p className="font-medium">{t('notices.editor.problems')}</p>
                  <ul className="list-disc pl-5">
                    {output.problems.map((problem) => (
                      <li key={`${problem.field}:${problem.code}`} data-code={problem.code}>{problem.message}</li>
                    ))}
                  </ul>
                </AlertDescription>
              </Alert>
            ) : (
              <p className="text-sm" data-testid="notice-ready">
                {t('notices.editor.ready')}{' '}
                {output.expiresAt && t('notices.editor.expires', { time: formatDateTime(output.expiresAt) })}
              </p>
            ))}
            <div className="flex flex-wrap gap-2">
              <Button type="submit" variant="outline" disabled={save.isPending || !!changed} data-testid="notice-save">
                <Save aria-hidden />
                {t('notices.editor.save')}
              </Button>
              <ConfirmDialog title={later ? t('notices.editor.schedule') : t('notices.editor.send')}
                description={t('notices.editor.sendText')} confirmLabel={t('notices.actions.confirm')}
                cancelLabel={t('notices.actions.cancel')} onConfirm={() => send.mutateAsync()}
                trigger={
                  <Button type="button" disabled={!ready || send.isPending} data-testid="notice-send">
                    <Send aria-hidden />
                    {later ? t('notices.editor.schedule') : t('notices.editor.send')}
                  </Button>
                } />
              <Button type="button" variant="ghost" onClick={onClose} data-testid="notice-close">
                {t('notices.editor.close')}
              </Button>
            </div>
          </div>
          <Preview title={title} body={body} />
        </form>
      </CardContent>
    </Card>
  )
}
