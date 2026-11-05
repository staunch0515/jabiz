import {
  Alert,
  AlertDescription,
  Button,
  Combobox,
  DateTimePicker,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, Upload } from 'lucide-react'
import { useId, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { readVersions, searchQuizzes } from '../content/api'
import { QUIZZES_QUERY, versionsQuery } from '../content/paths'
import { download, readJsonFile } from '../content/transfer'
import { reasonsOf } from '../content/QuizTransfer'
import { ProblemList } from '../content/editing'
import { SPONSOR } from '../messages'
import { navigate } from '../qb/router'
import { PUBLISHING_LIMITS, type Publication } from './api'
import { publicationPath, PUBLICATIONS_QUERY } from './paths'
import { durationDays, endAfter, nextHour, parseSettings, settingsFile, type PublicationSettings } from './settings'
import { copyOnto, exportSettings, importSettings, type Placed } from './transfer'
import { useDebounced, useVersionOf } from './shared'

/** The longest run a copy or an import asks for, in days (the server checks the times themselves). */
const MAX_DAYS = 3_650

/** Where a copy or an imported draft goes, as typed. */
export interface PlacementValue {
  quizId: string | null
  versionId: string | null
  startAt: string | null
  days: string
  name: string
}

/** The times typed: the end is the start plus the days; neither when the start is not given. */
export function timesOf(value: PlacementValue): { startAt: string | null; endAt: string | null } | null {
  if (!value.startAt) return { startAt: null, endAt: null }
  const days = Number(value.days)
  if (value.days.trim() === '' || !Number.isInteger(days) || days < 1 || days > MAX_DAYS) return null
  return { startAt: value.startAt, endAt: endAfter(value.startAt, days) }
}

/** The quiz, version, start, days and name of a copy or an import (Q102). */
function PlacementFields({ value, update, nameHint, disabled }: {
  value: PlacementValue
  /** Changes the value from the latest one (a version found later never undoes a quiz chosen meanwhile). */
  update: (change: (current: PlacementValue) => PlacementValue) => void
  nameHint: ReactNode
  disabled: boolean
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const q = useDebounced(search, 300)
  const quizzes = useQuery({
    queryKey: [...QUIZZES_QUERY, 'choices', q],
    queryFn: () => searchQuizzes({ q, offset: 0, limit: 20 }),
    placeholderData: keepPreviousData,
  })
  const { versions, version } = useVersionOf(value.quizId, value.versionId)
  const quizTitle = version?.title ?? versions.data?.[0]?.title
    ?? quizzes.data?.items.find((quiz) => quiz.quizId === value.quizId)?.title
  const daysBad = value.startAt !== null && timesOf(value) === null

  const chooseQuiz = async (quizId: string | null) => {
    update((v) => ({ ...v, quizId, versionId: null }))
    if (!quizId) return
    // The newest version, as in the wizard, unless another quiz or a version was chosen meanwhile.
    const found = await queryClient.fetchQuery({ queryKey: versionsQuery(quizId), queryFn: () => readVersions(quizId) })
    update((v) => (v.quizId === quizId && !v.versionId ? { ...v, versionId: found[0]?.versionId ?? null } : v))
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-quiz`}>{t('publishing.transfer.quiz')}</Label>
        <Combobox id={`${id}-quiz`} value={value.quizId} selectedLabel={quizTitle ?? undefined}
          onChange={(quizId) => void chooseQuiz(quizId)} onSearch={setSearch} loading={quizzes.isPending}
          options={(quizzes.data?.items ?? []).map((quiz) => ({
            value: quiz.quizId,
            label: quiz.title,
            description: quiz.versionLabel ?? t('publishing.field.quizNoVersion'),
            disabled: quiz.latestVersionNo === 0,
          }))}
          placeholder={t('publishing.field.quizPlaceholder')} searchPlaceholder={t('publishing.field.quizSearch')}
          emptyText={t('publishing.field.quizNone')} disabled={disabled} className="w-full"
          data-testid="transfer-quiz" />
      </div>
      {value.quizId && (
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-version`}>{t('publishing.field.versionId')}</Label>
          <Select value={value.versionId ?? ''} disabled={disabled || !versions.data?.length}
            onValueChange={(versionId) => {
              if (versionId) update((v) => ({ ...v, versionId }))
            }}>
            <SelectTrigger id={`${id}-version`} className="w-full" data-testid="transfer-version">
              <SelectValue placeholder={t('publishing.field.versionPlaceholder')} />
            </SelectTrigger>
            <SelectContent>
              {(versions.data ?? []).map((v) => (
                <SelectItem key={v.versionId} value={v.versionId}>
                  {t('publishing.field.versionValue', { label: v.label, questions: v.questionCount,
                    fullScore: v.fullScore })}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
      <div className="flex flex-wrap gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-start`}>{t('publishing.transfer.startAt')}</Label>
          <DateTimePicker id={`${id}-start`} value={value.startAt} clearable disabled={disabled}
            data-testid="transfer-start" onChange={(startAt) => update((v) => ({ ...v, startAt }))} />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-days`}>{t('publishing.transfer.days')}</Label>
          <Input id={`${id}-days`} inputMode="numeric" className="w-28" value={value.days} disabled={disabled}
            aria-invalid={daysBad ? true : undefined} aria-describedby={`${id}-times-hint`}
            data-testid="transfer-days" onChange={(event) => {
              const days = event.target.value
              update((v) => ({ ...v, days }))
            }} />
        </div>
      </div>
      <p id={`${id}-times-hint`} className={daysBad ? 'text-destructive text-sm' : 'text-muted-foreground text-sm'}>
        {daysBad ? t('publishing.transfer.daysBad', { max: MAX_DAYS }) : t('publishing.transfer.timesHint')}
      </p>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-name`}>{t('publishing.transfer.name')}</Label>
        <Input id={`${id}-name`} value={value.name} maxLength={PUBLISHING_LIMITS.name} autoComplete="off"
          disabled={disabled} aria-describedby={`${id}-name-hint`} data-testid="transfer-name"
          onChange={(event) => {
            const name = event.target.value
            update((v) => ({ ...v, name }))
          }} />
        <p id={`${id}-name-hint`} className="text-muted-foreground text-sm">{nameHint}</p>
      </div>
    </div>
  )
}

/** After a copy or an import: says what happened, and opens the draft at its last step (every problem listed). */
function useOpenPlaced() {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  return async (placed: Placed, done: string) => {
    notify.success(done)
    if (placed.rescaled) notify.info(t('publishing.transfer.rescaled', placed.rescaled))
    await queryClient.invalidateQueries({ queryKey: PUBLICATIONS_QUERY })
    navigate(`${publicationPath(placed.output.publicationId)}?step=3`)
  }
}

/**
 * A copy of a publication as a new draft (M-36, Q102): onto the same or another quiz version, from a new start for as
 * many days as the source ran, under a name of the sponsor's choice or the server's.
 */
export function CopyDialog({ publication, open, onOpenChange }: {
  publication: Publication
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const openPlaced = useOpenPlaced()
  const [value, setValue] = useState<PlacementValue>(() => ({
    quizId: publication.quizId,
    versionId: publication.versionId,
    startAt: nextHour(new Date()),
    days: String(durationDays(publication.startAt, publication.endAt) ?? 7),
    name: '',
  }))
  const copy = useMutation({
    mutationFn: () => {
      const times = timesOf(value)!
      return copyOnto(publication, { quizId: value.quizId!, versionId: value.versionId!, ...times, name: value.name })
    },
    onSuccess: async (placed) => {
      onOpenChange(false)
      await openPlaced(placed, t('publishing.actions.copied'))
    },
  })
  const ready = value.versionId !== null && timesOf(value) !== null
  return (
    <Dialog open={open} onOpenChange={(next) => {
      if (!copy.isPending) onOpenChange(next)
    }}>
      <DialogContent data-testid="publication-copy-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={(event) => {
          event.preventDefault()
          if (ready) copy.mutate()
        }}>
          <DialogHeader>
            <DialogTitle>{t('publishing.actions.copyTitle')}</DialogTitle>
            <DialogDescription>{t('publishing.actions.copyText')}</DialogDescription>
          </DialogHeader>
          <PlacementFields value={value} update={setValue} disabled={copy.isPending}
            nameHint={t('publishing.actions.copyNameHint')} />
          {copy.isError && <ProblemList problems={reasonsOf(copy.error, t)} />}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={copy.isPending} onClick={() => onOpenChange(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={!ready || copy.isPending} aria-busy={copy.isPending || undefined}
              data-testid="publication-copy-confirm">
              {t('publishing.actions.copy')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** "Export settings" (Q102): the publication's settings into a JSON file the browser downloads. */
export function ExportSettingsButton({ publicationId }: { publicationId: string }) {
  const { t } = useTranslation(SPONSOR)
  const run = useMutation({
    mutationFn: () => exportSettings(publicationId),
    onSuccess: (settings) => {
      if (!settings) {
        notify.error(t('publishing.transfer.exportFailed'))
        return
      }
      download(settingsFile(settings))
      notify.success(t('publishing.transfer.exported'))
    },
    onError: () => notify.error(t('publishing.transfer.exportFailed')),
  })
  return (
    <Button variant="outline" disabled={run.isPending} aria-busy={run.isPending || undefined}
      data-testid="publication-export" onClick={() => run.mutate()}>
      <Download aria-hidden />
      {t('publishing.transfer.export')}
    </Button>
  )
}

/**
 * "Import settings" (Q102): a settings file, then the quiz version, start, days and name, make a new draft saved as
 * one typed by hand (every rule applies, every problem is listed); the draft opens at its last step.
 */
export function ImportSettingsButton() {
  const { t } = useTranslation(SPONSOR)
  const openPlaced = useOpenPlaced()
  const input = useRef<HTMLInputElement>(null)
  const [settings, setSettings] = useState<PublicationSettings | null>(null)
  const [refused, setRefused] = useState<string | null>(null)
  const [value, setValue] = useState<PlacementValue | null>(null)

  const chosen = async (file: File) => {
    setRefused(null)
    let parsed: ReturnType<typeof parseSettings>
    try {
      parsed = parseSettings(await readJsonFile(file))
    } catch {
      parsed = { problem: 'notSettings' }
    }
    if ('problem' in parsed) {
      setSettings(null)
      setRefused(t(`publishing.transfer.${parsed.problem}`))
      setValue({ quizId: null, versionId: null, startAt: null, days: '', name: '' })
      return
    }
    setSettings(parsed.settings)
    setValue({ quizId: null, versionId: null, startAt: nextHour(new Date()),
      days: String(parsed.settings.durationDays ?? 7), name: parsed.settings.name })
  }

  const run = useMutation({
    mutationFn: () => {
      const times = timesOf(value!)!
      return importSettings(settings!, { quizId: value!.quizId!, versionId: value!.versionId!, ...times,
        name: value!.name })
    },
    onSuccess: async (placed) => {
      close()
      if (placed.conditionsFailed) notify.error(t('publishing.transfer.conditionsFailed'))
      await openPlaced(placed, t('publishing.transfer.imported'))
    },
  })

  const close = () => {
    setSettings(null)
    setRefused(null)
    setValue(null)
  }
  const ready = !!settings && !!value && value.versionId !== null && value.name.trim() !== '' && timesOf(value) !== null

  return (
    <>
      <input ref={input} type="file" accept="application/json,.json" className="sr-only" tabIndex={-1}
        aria-hidden data-testid="publication-import-file" onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file) void chosen(file)
        }} />
      <Button variant="outline" data-testid="publication-import" onClick={() => input.current?.click()}>
        <Upload aria-hidden />
        {t('publishing.transfer.import')}
      </Button>
      <Dialog open={value !== null} onOpenChange={(open) => {
        if (!open && !run.isPending) close()
      }}>
        <DialogContent data-testid="publication-import-dialog">
          <form noValidate className="flex flex-col gap-4" onSubmit={(event) => {
            event.preventDefault()
            if (ready) run.mutate()
          }}>
            <DialogHeader>
              <DialogTitle>{t('publishing.transfer.importTitle')}</DialogTitle>
              <DialogDescription>{t('publishing.transfer.importText')}</DialogDescription>
            </DialogHeader>
            {refused && (
              <Alert variant="destructive" data-testid="publication-import-refused">
                <AlertDescription>{refused}</AlertDescription>
              </Alert>
            )}
            {settings && value && (
              <>
                <p className="text-sm" data-testid="publication-import-summary">
                  {t('publishing.transfer.summary', { name: settings.name, tiers: settings.tiers.length,
                    conditions: settings.conditions.length })}
                </p>
                <PlacementFields value={value} update={(change) => setValue((v) => (v ? change(v) : v))}
                  disabled={run.isPending}
                  nameHint={t('publishing.transfer.nameHint')} />
              </>
            )}
            {run.isError && <ProblemList problems={reasonsOf(run.error, t)} />}
            <DialogFooter>
              <Button type="button" variant="outline" disabled={run.isPending} onClick={close}>
                {t('common.cancel')}
              </Button>
              {settings && (
                <Button type="submit" disabled={!ready || run.isPending} aria-busy={run.isPending || undefined}
                  data-testid="publication-import-confirm">
                  {t('publishing.transfer.importConfirm')}
                </Button>
              )}
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  )
}
