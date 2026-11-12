import {
  Badge,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
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
import { useMutation } from '@tanstack/react-query'
import { Plus, X } from 'lucide-react'
import { useId, useState, type FormEvent, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { formatDuration, parseDuration } from '../content/duration'
import { FileField, formProblem, MarkdownField, ProblemList } from '../content/editing'
import { TextTranslationsFields, type TextsByLanguage } from '../content/TextTranslationsFields'
import { SPONSOR } from '../messages'
import { CONTENT_LANGUAGES, contentLanguageOf, type ContentLanguage } from '../qb/api'
import { QB } from '../qb/messages'
import { fieldProblem } from '../qb/problems'
import type { DigitalDelivery } from '../publishing/api'
import { useDigitalDeliveryLabel, useRewardMethodLabel } from '../publishing/shared'
import {
  ASSET_KINDS,
  bodyLength,
  CONTENT_FIELDS,
  DIGITAL_DELIVERIES,
  EMPTY_CONTENT,
  PRIZE_METHODS,
  saveAsset,
  TEXT_USAGES,
  useAssetKindLabel,
  useTextUsageLabel,
  type Asset,
  type AssetContent,
  type AssetKind,
  type PrizeMethod,
  type TextUsage,
} from './api'
import { ASSET_LIMITS } from './ids'
import './messages'

/** The fields the dialog shows beside their problems (the rest are listed at its end). */
const FIELDS = ['kind', 'name', 'description', 'language', 'tags', ...new Set(Object.values(CONTENT_FIELDS).flat())]

/** The translated texts of a kind: its name and description, and its own text (`AssetRules.sources`). */
function translatedFields(kind: AssetKind): ('name' | 'description' | 'body' | 'prizeDescription')[] {
  if (kind === 'ARTICLE' || kind === 'TEXT') return ['name', 'description', 'body']
  if (kind === 'PRIZE') return ['name', 'description', 'prizeDescription']
  return ['name', 'description']
}

/**
 * A new asset or one being changed (F-C1): its kind first (kept once saved), then only the kind's own content fields
 * (as `AssetKind.contentFields`), with its name, description, language, tags and the translations into the other
 * languages (as the products', Q73c). Saved with `QB_ASSET_SAVE` and the revision read (`baseRevision`); every
 * problem the server finds is shown at once, at its field where the form has one.
 */
export function AssetDialog({ asset, onClose, onSaved }: {
  /** The asset to change; none for a new one. */
  asset: Asset | null
  onClose: () => void
  onSaved: (output: { assetId: string; revision: number }) => void
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const kindLabel = useAssetKindLabel()
  const usageLabel = useTextUsageLabel()
  const methodLabel = useRewardMethodLabel()
  const deliveryLabel = useDigitalDeliveryLabel()
  const [kind, setKind] = useState<AssetKind>(asset?.kind ?? 'IMAGE')
  const [name, setName] = useState(asset?.name ?? '')
  const [description, setDescription] = useState(asset?.description ?? '')
  const [language, setLanguage] = useState<ContentLanguage>(asset?.language ?? contentLanguageOf(i18n.language))
  const [tags, setTags] = useState<string[]>(asset?.tags ?? [])
  const [content, setContent] = useState<AssetContent>(() => ({ ...EMPTY_CONTENT, ...(asset ?? {}) }))
  const [duration, setDuration] = useState(asset?.durationSec != null ? formatDuration(asset.durationSec) : '')
  const [sendDays, setSendDays] = useState(asset?.digitalSendDays != null ? String(asset.digitalSendDays) : '')
  const [local, setLocal] = useState<Record<string, string>>({})
  const [translations, setTranslations] = useState<TextsByLanguage>(asset?.translations ?? {})
  const [confirmed, setConfirmed] = useState<string[]>([])
  const others = CONTENT_LANGUAGES.filter((l) => l !== language)
  const set = <K extends keyof AssetContent>(field: K, value: AssetContent[K]) =>
    setContent((current) => ({ ...current, [field]: value }))

  const save = useMutation({
    mutationFn: () => saveAsset({
      assetId: asset?.assetId ?? null,
      kind,
      name: name.trim(),
      description: description.trim() === '' ? null : description.trim(),
      language,
      tags: tags.map((tag) => tag.trim()),
      ...content,
      url: content.url?.trim() ? content.url.trim() : null,
      body: content.body?.trim() ? content.body : null,
      prizeDescription: content.prizeDescription?.trim() ? content.prizeDescription.trim() : null,
      digitalDelivery: content.prizeMethod === 'DIGITAL_PRIZE' ? content.digitalDelivery : null,
      digitalSendDays: content.prizeMethod === 'DIGITAL_PRIZE' && content.digitalDelivery === 'SPONSOR_SENT'
        && sendDays.trim() !== '' ? Number(sendDays) : null,
      durationSec: parseDuration(duration),
      // The other languages' texts as typed; the base language has none.
      translations: Object.fromEntries(others.map((l) => [l, translations[l] ?? {}])),
      confirmed,
      baseRevision: asset?.revision ?? null,
    }),
    onSuccess: onSaved,
  })
  const problem = (field: string) => local[field] ?? fieldProblem(save.error, field)
  const shown = [...FIELDS, ...tags.map((_, i) => `tags[${i}]`),
    ...others.flatMap((l) => translatedFields(kind).map((f) => `translations.${l}.${f}`))]
  const errorOf = (field: string) => problem(field)
    && <p id={`${id}-${field}-error`} className="text-destructive text-sm" data-testid="asset-field-error"
      data-field={field}>{problem(field)}</p>
  const aria = (field: string, hint?: string) => ({
    'aria-invalid': problem(field) ? true : undefined,
    'aria-describedby': [hint, problem(field) ? `${id}-${field}-error` : undefined].filter(Boolean).join(' ') || undefined,
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const typed: Record<string, string> = {}
    if (kind === 'VIDEO_LINK' && duration.trim() !== '' && parseDuration(duration) == null) {
      typed.durationSec = t('assets.dialog.durationInvalid')
    }
    if (kind === 'PRIZE' && sendDays.trim() !== '' && !Number.isInteger(Number(sendDays))) {
      typed.digitalSendDays = t('assets.dialog.wholeNumber')
    }
    setLocal(typed)
    if (Object.keys(typed).length === 0) save.mutate()
  }

  const fields = CONTENT_FIELDS[kind]
  const has = (field: string) => (fields as readonly string[]).includes(field)
  const body = content.body ?? ''
  return (
    <Dialog open onOpenChange={(open) => !open && !save.isPending && onClose()}>
      <DialogContent className="sm:max-w-2xl" data-testid="asset-dialog">
        <DialogHeader>
          <DialogTitle>{asset ? t('assets.dialog.editTitle') : t('assets.dialog.newTitle')}</DialogTitle>
          <DialogDescription>{t('assets.dialog.hint')}</DialogDescription>
        </DialogHeader>
        <form id={`${id}-form`} noValidate onSubmit={submit} className="flex flex-col gap-5">
          {asset
            ? <p className="text-sm" data-testid="asset-kind-value">{t('assets.dialog.kindValue', { kind: kindLabel(kind) })}</p>
            : (
              <div className="flex flex-col gap-2">
                <span id={`${id}-kind`} className="text-sm font-medium">{t('assets.dialog.kind')}</span>
                <RadioGroup aria-labelledby={`${id}-kind`} aria-describedby={`${id}-kind-hint`} value={kind}
                  className="grid-cols-2 sm:grid-cols-4" data-testid="asset-kind"
                  onValueChange={(value) => setKind(value as AssetKind)}>
                  {ASSET_KINDS.map((code) => (
                    <div key={code} className="flex items-center gap-2">
                      <RadioGroupItem id={`${id}-kind-${code}`} value={code} data-testid={`asset-kind-${code}`} />
                      <Label htmlFor={`${id}-kind-${code}`} className="font-normal">{kindLabel(code)}</Label>
                    </div>
                  ))}
                </RadioGroup>
                <p id={`${id}-kind-hint`} className="text-muted-foreground text-sm">{t('assets.dialog.kindHint')}</p>
                {errorOf('kind')}
              </div>
            )}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-name`}>{t('assets.dialog.name')}</Label>
            <Input id={`${id}-name`} value={name} maxLength={ASSET_LIMITS.name} autoComplete="off" required
              {...aria('name', `${id}-name-hint`)} data-testid="asset-name" onChange={(event) => setName(event.target.value)} />
            <p id={`${id}-name-hint`} className="text-muted-foreground text-sm">{t('assets.dialog.nameHint')}</p>
            {errorOf('name')}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-description`}>{t('assets.dialog.description')}</Label>
            <Textarea id={`${id}-description`} value={description} rows={2} maxLength={ASSET_LIMITS.description}
              {...aria('description')} data-testid="asset-description"
              onChange={(event) => setDescription(event.target.value)} />
            {errorOf('description')}
          </div>
          {has('image') && (
            <FileField kind="image" label={t('assets.dialog.image')} value={content.image}
              uploadLabel={content.image ? t('assets.dialog.replaceImage') : t('assets.dialog.uploadImage')}
              onChange={(value) => set('image', value)} error={problem('image')} testId="asset-image" />
          )}
          {has('pdf') && (
            <FileField kind="pdf" label={t('assets.dialog.pdf')} value={content.pdf}
              uploadLabel={content.pdf ? t('assets.dialog.replacePdf') : t('assets.dialog.uploadPdf')}
              onChange={(value) => set('pdf', value)} error={problem('pdf')} testId="asset-pdf" />
          )}
          {has('audio') && (
            <FileField kind="audio" label={t('assets.dialog.audio')} value={content.audio}
              uploadLabel={content.audio ? t('assets.dialog.replaceAudio') : t('assets.dialog.uploadAudio')}
              onChange={(value) => set('audio', value)} error={problem('audio')} testId="asset-audio" />
          )}
          {has('url') && (
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-url`}>{kind === 'VIDEO_LINK' ? t('assets.dialog.videoUrl') : t('assets.dialog.url')}</Label>
              <Input id={`${id}-url`} type="url" inputMode="url" value={content.url ?? ''} maxLength={ASSET_LIMITS.url}
                autoComplete="off" placeholder="https://"
                {...aria('url', kind === 'VIDEO_LINK' ? `${id}-url-hint` : undefined)} data-testid="asset-url"
                onChange={(event) => set('url', event.target.value)} />
              {kind === 'VIDEO_LINK' && (
                <p id={`${id}-url-hint`} className="text-muted-foreground text-sm">{t('assets.dialog.videoUrlHint')}</p>
              )}
              {errorOf('url')}
            </div>
          )}
          {has('durationSec') && (
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-durationSec`}>{t('assets.dialog.duration')}</Label>
              <Input id={`${id}-durationSec`} value={duration} inputMode="numeric" autoComplete="off" placeholder="2:30"
                className="max-w-32" maxLength={8} {...aria('durationSec', `${id}-duration-hint`)}
                data-testid="asset-duration" onChange={(event) => setDuration(event.target.value)} />
              <p id={`${id}-duration-hint`} className="text-muted-foreground text-sm">{t('assets.dialog.durationHint')}</p>
              {errorOf('durationSec')}
            </div>
          )}
          {has('usage') && (
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-usage`}>{t('assets.dialog.usage')}</Label>
              <Select value={content.usage ?? ''} onValueChange={(value) => set('usage', value as TextUsage)}>
                <SelectTrigger id={`${id}-usage`} className="w-72" {...aria('usage', `${id}-usage-hint`)}
                  data-testid="asset-usage">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {TEXT_USAGES.map((code) => <SelectItem key={code} value={code}>{usageLabel(code)}</SelectItem>)}
                </SelectContent>
              </Select>
              <p id={`${id}-usage-hint`} className="text-muted-foreground text-sm">{t('assets.dialog.usageHint')}</p>
              {errorOf('usage')}
            </div>
          )}
          {has('body') && (kind === 'ARTICLE' || content.usage === 'QUIZ_INTRO'
            ? (
              <MarkdownField label={kind === 'ARTICLE' ? t('assets.dialog.article') : t('assets.dialog.text')}
                value={body} onChange={(value) => set('body', value)} maxLength={bodyLength(kind, content.usage)}
                rows={kind === 'ARTICLE' ? 10 : 6} error={problem('body')} testId="asset-body" />
            )
            : (
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-body`}>{t('assets.dialog.text')}</Label>
                <Textarea id={`${id}-body`} value={body} rows={4} maxLength={bodyLength(kind, content.usage)}
                  {...aria('body')} data-testid="asset-body" onChange={(event) => set('body', event.target.value)} />
                {errorOf('body')}
              </div>
            ))}
          {has('prizeMethod') && (
            <fieldset className="flex flex-col gap-2" aria-describedby={aria('prizeMethod')['aria-describedby']}>
              <legend className="mb-2 text-sm font-medium">{t('assets.dialog.prizeMethod')}</legend>
              <RadioGroup value={content.prizeMethod ?? ''} data-testid="asset-prize-method"
                onValueChange={(value) => set('prizeMethod', value as PrizeMethod)}>
                {PRIZE_METHODS.map((code) => (
                  <div key={code} className="flex items-center gap-2">
                    <RadioGroupItem id={`${id}-method-${code}`} value={code} data-testid={`asset-prize-method-${code}`} />
                    <Label htmlFor={`${id}-method-${code}`} className="font-normal">{methodLabel(code)}</Label>
                  </div>
                ))}
              </RadioGroup>
              {errorOf('prizeMethod')}
            </fieldset>
          )}
          {has('prizeDescription') && content.prizeMethod !== null && (
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-prizeDescription`}>{t('assets.dialog.prizeDescription')}</Label>
              <Textarea id={`${id}-prizeDescription`} value={content.prizeDescription ?? ''} rows={3}
                maxLength={ASSET_LIMITS.prizeDescription} {...aria('prizeDescription')}
                data-testid="asset-prize-description"
                onChange={(event) => set('prizeDescription', event.target.value)} />
              {errorOf('prizeDescription')}
            </div>
          )}
          {has('digitalDelivery') && content.prizeMethod === 'DIGITAL_PRIZE' && (
            <fieldset className="flex flex-col gap-2" aria-describedby={aria('digitalDelivery')['aria-describedby']}>
              <legend className="mb-2 text-sm font-medium">{t('assets.dialog.digitalDelivery')}</legend>
              <RadioGroup value={content.digitalDelivery ?? ''} data-testid="asset-digital-delivery"
                onValueChange={(value) => set('digitalDelivery', value as DigitalDelivery)}>
                {DIGITAL_DELIVERIES.map((code) => (
                  <div key={code} className="flex items-center gap-2">
                    <RadioGroupItem id={`${id}-delivery-${code}`} value={code}
                      data-testid={`asset-digital-delivery-${code}`} />
                    <Label htmlFor={`${id}-delivery-${code}`} className="font-normal">{deliveryLabel(code)}</Label>
                  </div>
                ))}
              </RadioGroup>
              {errorOf('digitalDelivery')}
            </fieldset>
          )}
          {has('digitalSendDays') && content.prizeMethod === 'DIGITAL_PRIZE' && content.digitalDelivery === 'SPONSOR_SENT' && (
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-digitalSendDays`}>{t('assets.dialog.digitalSendDays')}</Label>
              <Input id={`${id}-digitalSendDays`} value={sendDays} inputMode="numeric" className="w-32" autoComplete="off"
                {...aria('digitalSendDays', `${id}-days-hint`)} data-testid="asset-send-days"
                onChange={(event) => setSendDays(event.target.value)} />
              <p id={`${id}-days-hint`} className="text-muted-foreground text-sm">
                {t('assets.dialog.digitalSendDaysHint', { max: ASSET_LIMITS.maxSendDays })}
              </p>
              {errorOf('digitalSendDays')}
            </div>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-language`}>{t('assets.dialog.language')}</Label>
            <Select value={language} onValueChange={(value) => setLanguage(value as ContentLanguage)}>
              <SelectTrigger id={`${id}-language`} className="w-48" {...aria('language', `${id}-language-hint`)}
                data-testid="asset-language">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {CONTENT_LANGUAGES.map((l) => <SelectItem key={l} value={l}>{tQb(`languages.${l}`)}</SelectItem>)}
              </SelectContent>
            </Select>
            <p id={`${id}-language-hint`} className="text-muted-foreground text-sm">{t('assets.dialog.languageHint')}</p>
            {errorOf('language')}
          </div>
          <TagsField tags={tags} onChange={setTags} problemOf={problem} />
          <div className="flex flex-col gap-2">
            <p className="text-sm font-medium">{t('assets.dialog.translations')}</p>
            <p className="text-muted-foreground text-sm">{t('assets.dialog.translationsHint')}</p>
            <TextTranslationsFields id={`${id}-tr`} languages={others} value={translations}
              hashes={asset?.translationHashes ?? {}} confirmed={confirmed} onChange={setTranslations}
              onConfirmed={setConfirmed} problemOf={(path) => fieldProblem(save.error, path)}
              testId="asset-translations"
              fields={translatedFields(kind).map((field) => ({
                field,
                label: field === 'name' ? t('assets.dialog.name') : field === 'description' ? t('assets.dialog.description')
                  : field === 'body' ? (kind === 'ARTICLE' ? t('assets.dialog.article') : t('assets.dialog.text'))
                    : t('assets.dialog.prizeDescription'),
                source: field === 'name' ? name : field === 'description' ? description
                  : field === 'body' ? body : content.prizeDescription ?? '',
                maxLength: field === 'name' ? ASSET_LIMITS.name : field === 'description' ? ASSET_LIMITS.description
                  : field === 'body' ? bodyLength(kind, content.usage) : ASSET_LIMITS.prizeDescription,
                multiline: field !== 'name',
              }))} />
          </div>
          <ProblemList problems={formProblem(save.error, shown)} testId="asset-error" />
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" disabled={save.isPending} onClick={onClose}>{t('assets.cancel')}</Button>
          <Button type="submit" form={`${id}-form`} disabled={save.isPending} aria-busy={save.isPending || undefined}
            data-testid="asset-save">{t('assets.dialog.save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** The asset's tags: each shown with a button to remove it, and a field to add one (Enter adds it too). */
export function TagsField({ tags, onChange, problemOf }: {
  tags: string[]
  onChange: (tags: string[]) => void
  problemOf: (field: string) => string | undefined
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [typed, setTyped] = useState('')
  const add = () => {
    const tag = typed.trim()
    if (tag === '' || tags.some((existing) => existing.toLowerCase() === tag.toLowerCase())) return
    onChange([...tags, tag])
    setTyped('')
  }
  const onKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Enter') return
    event.preventDefault()
    add()
  }
  const problems = [problemOf('tags'), ...tags.map((_, i) => problemOf(`tags[${i}]`))].filter(Boolean)
  return (
    <div role="group" aria-labelledby={`${id}-label`} className="flex flex-col gap-2" data-testid="asset-tags">
      <span id={`${id}-label`} className="text-sm font-medium">{t('assets.dialog.tags')}</span>
      {tags.length > 0 && (
        <ul className="flex flex-wrap gap-2">
          {tags.map((tag, i) => (
            <li key={`${tag}-${i}`}>
              <Badge variant={problemOf(`tags[${i}]`) ? 'destructive' : 'secondary'} className="gap-1"
                data-testid="asset-tag">
                {tag}
                <button type="button" className="focus-visible:ring-ring rounded-sm focus-visible:ring-2 focus-visible:outline-none"
                  aria-label={t('assets.dialog.tagRemove', { tag })} data-testid="asset-tag-remove"
                  onClick={() => onChange(tags.filter((_, j) => j !== i))}>
                  <X aria-hidden className="size-3" />
                </button>
              </Badge>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Label htmlFor={`${id}-new`} className="sr-only">{t('assets.dialog.tagNew')}</Label>
        <Input id={`${id}-new`} value={typed} maxLength={ASSET_LIMITS.tagLength} autoComplete="off" className="max-w-60"
          placeholder={t('assets.dialog.tagNew')} aria-describedby={`${id}-hint`} data-testid="asset-tag-input"
          disabled={tags.length >= ASSET_LIMITS.maxTags} onChange={(event) => setTyped(event.target.value)}
          onKeyDown={onKey} />
        <Button type="button" variant="outline" size="sm" data-testid="asset-tag-add"
          disabled={typed.trim() === '' || tags.length >= ASSET_LIMITS.maxTags} onClick={add}>
          <Plus aria-hidden />
          {t('assets.dialog.tagAdd')}
        </Button>
      </div>
      <p id={`${id}-hint`} className="text-muted-foreground text-sm">
        {t('assets.dialog.tagsHint', { max: ASSET_LIMITS.maxTags, length: ASSET_LIMITS.tagLength })}
      </p>
      {problems.map((text, i) => (
        <p key={i} className="text-destructive text-sm" data-testid="asset-field-error" data-field="tags">{text}</p>
      ))}
    </div>
  )
}
