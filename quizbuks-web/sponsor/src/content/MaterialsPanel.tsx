import {
  Badge,
  Button,
  Card,
  CardContent,
  Checkbox,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  FilePreview,
  Input,
  Label,
  MarkdownView,
  notify,
  RadioGroup,
  RadioGroupItem,
  Textarea,
} from '@jabiz/ui'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, ExternalLink, Pencil, Plus, Trash2, X } from 'lucide-react'
import { useId, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import type { ContentLanguage } from '../qb/api'
import { QB } from '../qb/messages'
import { ShownAt } from './ShownAt'
import { fieldProblem } from '../qb/problems'
import {
  contentFieldOf,
  deleteMaterial,
  MATERIAL_KINDS,
  moved,
  readMaterialKinds,
  reorderMaterials,
  safeHttpUrl,
  saveMaterial,
  type Material,
  type MaterialInput,
  type MaterialKind,
} from './api'
import {
  failedRemoval,
  FileField,
  formProblem,
  MarkdownField,
  newKey,
  ProblemList,
  textOrNull,
  useEditing,
} from './editing'
import { ContentImage } from './ContentImage'
import { DOCUMENT_FIELD, LIMITS } from './limits'
import { useMoveFocus } from './moving'

/** The labels of the material kinds, from the server's dictionary (the code until it answered). */
export function useKindLabel() {
  const kinds = useQuery({ queryKey: ['qb', 'dict', 'material-kind'], queryFn: readMaterialKinds, staleTime: Infinity })
  return (code: string) => kinds.data?.find((item) => item.code === code)?.label ?? code
}

/** A link a taker opens in a new tab (videos are links too, Q-3): only http(s), never as the page's own. */
export function ExternalAnchor({ url }: { url: string | null }) {
  const { t } = useTranslation(SPONSOR)
  const href = safeHttpUrl(url)
  if (!href) return url ? <span className="break-all">{url}</span> : null
  return (
    <a href={href} target="_blank" rel="noopener noreferrer"
      className="text-primary inline-flex items-center gap-1 break-all underline underline-offset-4">
      {url}
      <ExternalLink aria-hidden className="size-3 shrink-0" />
      <span className="sr-only">{t('materials.newTab')}</span>
    </a>
  )
}

interface ImageDraft {
  key: string
  imageId: string | null
  image: string
  caption: string
}

const FIELDS = ['kind', 'title', 'description', 'body', 'url', 'pdf', 'audio', 'images', 'watchToAnswer'] as const

/**
 * One reference material (M-23) with its content, saved with `QB_MATERIAL_SAVE`: a Markdown article, a link, a
 * video link, a PDF, an image group with captions (the text alternatives) or an audio file. The kind is chosen once;
 * only its content field is sent.
 */
export function MaterialDialog({ material, number, open, onOpenChange, variant }: {
  material: Material | null
  number: number
  open: boolean
  onOpenChange: (open: boolean) => void
  /**
   * Q73a: the material of another language standing for `base` (of its kind; the video to watch is the base's
   * choice, so it has no such box).
   */
  variant?: { base: Material; language: ContentLanguage }
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const editing = useEditing()
  const kindLabel = useKindLabel()
  const id = useId()
  const [kind, setKind] = useState<MaterialKind>(material?.kind ?? variant?.base.kind ?? 'ARTICLE')
  const [title, setTitle] = useState(material?.title ?? '')
  const [description, setDescription] = useState(material?.description ?? '')
  const [body, setBody] = useState(material?.body ?? '')
  const [url, setUrl] = useState(material?.url ?? '')
  const [watch, setWatch] = useState(material?.watchToAnswer ?? false)
  const [pdf, setPdf] = useState<string | null>(material?.pdf ?? null)
  const [audio, setAudio] = useState<string | null>(material?.audio ?? null)
  const [images, setImages] = useState<ImageDraft[]>(() => (material?.images ?? []).map((i) => ({
    key: i.imageId, imageId: i.imageId, image: i.image, caption: i.caption ?? '' })))
  const imagesRef = useRef<HTMLOListElement>(null)

  const input = (): MaterialInput => {
    const base: MaterialInput = {
      quizId: editing.quizId,
      materialId: material?.materialId ?? null,
      kind,
      title: textOrNull(title),
      description: textOrNull(description),
      baseRevision: editing.revision(),
      ...(variant ? { variantOf: variant.base.materialId, language: variant.language } : {}),
    }
    switch (contentFieldOf(kind)) {
      case 'body':
        return { ...base, body: textOrNull(body) }
      case 'url':
        return { ...base, url: url.trim() === '' ? null : url.trim(),
          ...(kind === 'VIDEO_LINK' && !variant ? { watchToAnswer: watch } : {}) }
      case 'pdf':
        return { ...base, pdf }
      case 'audio':
        return { ...base, audio }
      case 'images':
        return { ...base, images: images.map((i) => ({ imageId: i.imageId, image: i.image, caption: textOrNull(i.caption) })) }
    }
  }

  const save = useMutation({
    mutationFn: () => saveMaterial(input()),
    onSuccess: async (output) => {
      onOpenChange(false)
      notify.success(t('materials.saved', { number }))
      await editing.edited(output)
    },
    onError: editing.failed,
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate()
  }
  const problem = (field: string) => fieldProblem(save.error, field)
  const focusCaption = (index: number) => requestAnimationFrame(() =>
    imagesRef.current?.querySelectorAll<HTMLInputElement>('input[data-caption]')[index]?.focus())
  const changeImage = (key: string, patch: Partial<ImageDraft>) =>
    setImages((list) => list.map((i) => (i.key === key ? { ...i, ...patch } : i)))

  const field = contentFieldOf(kind)
  return (
    <Dialog open={open} onOpenChange={(next) => {
      if (!save.isPending) onOpenChange(next)
    }}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl" data-testid="material-dialog">
        <form noValidate onSubmit={submit} className="flex flex-col gap-5">
          <DialogHeader>
            <DialogTitle>
              {variant ? t('translations.materialTitle', { number, language: tQb(`languages.${variant.language}`) })
                : material ? t('materials.editTitle', { number }) : t('materials.newTitle', { number })}
            </DialogTitle>
            <DialogDescription>
              {variant ? t('translations.materialHint') : t('materials.dialogHint')}
            </DialogDescription>
          </DialogHeader>
          {material || variant
            ? <p className="text-sm">{t('materials.kindValue', { kind: kindLabel(kind) })}</p>
            : (
              <div className="flex flex-col gap-2">
                <span id={`${id}-kind`} className="text-sm font-medium">{t('materials.kind')}</span>
                <RadioGroup aria-labelledby={`${id}-kind`} value={kind} className="grid-cols-2 sm:grid-cols-3"
                  data-testid="material-kind" onValueChange={(value) => setKind(value as MaterialKind)}>
                  {MATERIAL_KINDS.map((code) => (
                    <div key={code} className="flex items-center gap-2">
                      <RadioGroupItem id={`${id}-kind-${code}`} value={code} data-testid={`material-kind-${code}`} />
                      <Label htmlFor={`${id}-kind-${code}`}>{kindLabel(code)}</Label>
                    </div>
                  ))}
                </RadioGroup>
                <p className="text-muted-foreground text-sm">{t('materials.kindHint')}</p>
              </div>
            )}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-title`}>{t('materials.title')}</Label>
            <Input id={`${id}-title`} value={title} maxLength={LIMITS.materialTitle} autoComplete="off"
              aria-invalid={problem('title') ? true : undefined}
              aria-describedby={problem('title') ? `${id}-title-error` : undefined}
              data-testid="material-title" onChange={(event) => setTitle(event.target.value)} />
            {problem('title') && <p id={`${id}-title-error`} className="text-destructive text-sm">{problem('title')}</p>}
            <ShownAt field="materialTitle" />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-description`}>{t('materials.description')}</Label>
            <Textarea id={`${id}-description`} value={description} rows={2} maxLength={LIMITS.materialDescription}
              aria-invalid={problem('description') ? true : undefined}
              aria-describedby={problem('description') ? `${id}-description-error` : undefined}
              data-testid="material-description" onChange={(event) => setDescription(event.target.value)} />
            {problem('description') && (
              <p id={`${id}-description-error`} className="text-destructive text-sm">{problem('description')}</p>
            )}
            <ShownAt field="materialDescription" />
          </div>
          {field === 'body' && (
            <div className="flex flex-col gap-2">
              <MarkdownField label={t('materials.body')} value={body} onChange={setBody} maxLength={LIMITS.materialBody}
                rows={10} error={problem('body')} testId="material-body" />
              <ShownAt field="materialBody" />
            </div>
          )}
          {field === 'url' && (
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-url`}>{kind === 'VIDEO_LINK' ? t('materials.videoUrl') : t('materials.url')}</Label>
              <Input id={`${id}-url`} type="url" inputMode="url" value={url} maxLength={LIMITS.url} autoComplete="off"
                aria-invalid={problem('url') ? true : undefined}
                aria-describedby={[`${id}-url-hint`, problem('url') ? `${id}-url-error` : ''].join(' ').trim()}
                data-testid="material-url" onChange={(event) => setUrl(event.target.value)} />
              <p id={`${id}-url-hint`} className="text-muted-foreground text-sm">{t('materials.urlHint')}</p>
              {problem('url') && <p id={`${id}-url-error`} className="text-destructive text-sm">{problem('url')}</p>}
              {kind === 'VIDEO_LINK' && !variant && (
                <div className="flex flex-col gap-1">
                  <div className="flex items-center gap-2">
                    <Checkbox id={`${id}-watch`} checked={watch} data-testid="material-watch"
                      aria-describedby={[`${id}-watch-hint`, problem('watchToAnswer') ? `${id}-watch-error` : '']
                        .join(' ').trim()}
                      onCheckedChange={(checked) => setWatch(checked === true)} />
                    <Label htmlFor={`${id}-watch`}>{t('materials.watchToAnswer')}</Label>
                  </div>
                  <p id={`${id}-watch-hint`} className="text-muted-foreground text-sm">{t('materials.watchHint')}</p>
                  {problem('watchToAnswer') && (
                    <p id={`${id}-watch-error`} className="text-destructive text-sm">{problem('watchToAnswer')}</p>
                  )}
                </div>
              )}
            </div>
          )}
          {field === 'pdf' && (
            <FileField kind="pdf" label={t('materials.pdf')}
              uploadLabel={pdf ? t('materials.replacePdf') : t('materials.uploadPdf')}
              value={pdf} onChange={setPdf} error={problem('pdf')} testId="material-pdf" />
          )}
          {field === 'audio' && (
            <FileField kind="audio" label={t('materials.audio')}
              uploadLabel={audio ? t('materials.replaceAudio') : t('materials.uploadAudio')}
              value={audio} onChange={setAudio} error={problem('audio')} testId="material-audio" />
          )}
          {field === 'images' && (
            <fieldset className="flex flex-col gap-3">
              <legend className="mb-2 text-sm font-medium">{t('materials.images')}</legend>
              <p className="text-muted-foreground text-sm">{t('materials.imagesHint', { max: LIMITS.images })}</p>
              <ol ref={imagesRef} className="flex flex-col gap-3">
                {images.map((image, index) => (
                  <li key={image.key} className="flex flex-col gap-2 rounded-md border p-3" data-testid="material-image">
                    <ContentImage fileId={image.image} width={160} />
                    <Label htmlFor={`${id}-${image.key}-caption`}>{t('materials.caption', { number: index + 1 })}</Label>
                    <Input id={`${id}-${image.key}-caption`} value={image.caption} maxLength={LIMITS.caption}
                      autoComplete="off" data-caption data-testid="material-image-caption"
                      onChange={(event) => changeImage(image.key, { caption: event.target.value })} />
                    <div className="flex flex-wrap gap-2">
                      <Button type="button" variant="outline" size="sm" disabled={index === 0}
                        aria-label={t('materials.imageUp', { number: index + 1 })}
                        onClick={() => {
                          setImages((list) => moved(list, index, -1))
                          focusCaption(index - 1)
                        }}>
                        <ArrowUp aria-hidden />
                      </Button>
                      <Button type="button" variant="outline" size="sm" disabled={index === images.length - 1}
                        aria-label={t('materials.imageDown', { number: index + 1 })}
                        onClick={() => {
                          setImages((list) => moved(list, index, 1))
                          focusCaption(index + 1)
                        }}>
                        <ArrowDown aria-hidden />
                      </Button>
                      <Button type="button" variant="outline" size="sm"
                        aria-label={t('materials.imageRemove', { number: index + 1 })}
                        onClick={() => {
                          setImages((list) => list.filter((i) => i.key !== image.key))
                          focusCaption(Math.max(0, index - 1))
                        }}>
                        <X aria-hidden />
                        {t('editor.remove')}
                      </Button>
                    </div>
                  </li>
                ))}
              </ol>
              {images.length < LIMITS.images && (
                <FileField kind="image" label={t('materials.addImage')} uploadLabel={t('materials.uploadImage')}
                  value={null} testId="material-image-add" error={problem('images')}
                  onChange={(fileId) => {
                    if (fileId) setImages((list) => [...list, { key: newKey(), imageId: null, image: fileId, caption: '' }])
                    focusCaption(images.length)
                  }} />
              )}
            </fieldset>
          )}
          <ProblemList problems={formProblem(save.error, FIELDS)} testId="material-error" />
          <DialogFooter>
            <Button type="button" variant="outline" disabled={save.isPending} onClick={() => onOpenChange(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={save.isPending || editing.stale} aria-busy={save.isPending || undefined}
              data-testid="material-save">
              {t('materials.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** What a material holds, as takers will see it: the article rendered, the link, the file, the images. */
export function MaterialContent({ material }: { material: Pick<Material, 'kind' | 'body' | 'url' | 'pdf' | 'audio'>
  & { images: { image: string; caption: string | null }[] } }) {
  const { t } = useTranslation(SPONSOR)
  switch (contentFieldOf(material.kind)) {
    case 'body':
      return material.body
        ? (
          <details className="text-sm">
            <summary className="cursor-pointer">{t('materials.showText')}</summary>
            <div className="mt-2"><MarkdownView text={material.body} /></div>
          </details>
        )
        : <p className="text-muted-foreground text-sm">{t('materials.noContent')}</p>
    case 'url':
      return material.url ? <ExternalAnchor url={material.url} />
        : <p className="text-muted-foreground text-sm">{t('materials.noContent')}</p>
    case 'pdf':
      return material.pdf ? <FilePreview fileId={material.pdf} field={DOCUMENT_FIELD} width={160} />
        : <p className="text-muted-foreground text-sm">{t('materials.noContent')}</p>
    case 'audio':
      return material.audio ? <FilePreview fileId={material.audio} field={DOCUMENT_FIELD} width={160} />
        : <p className="text-muted-foreground text-sm">{t('materials.noContent')}</p>
    case 'images':
      return material.images.length === 0
        ? <p className="text-muted-foreground text-sm">{t('materials.noContent')}</p>
        : (
          <ul className="flex flex-wrap gap-3">
            {material.images.map((image, index) => (
              <li key={`${image.image}-${index}`} className="flex flex-col gap-1 text-sm">
                <ContentImage fileId={image.image} width={96} />
                <span className="text-muted-foreground max-w-40 truncate">
                  {image.caption ?? t('materials.noCaption')}
                </span>
              </li>
            ))}
          </ul>
        )
  }
}

/** The reference materials of the quiz in their order (M-23): added, changed, moved and deleted. */
export function MaterialsPanel({ materials }: { materials: Material[] }) {
  const { t } = useTranslation(SPONSOR)
  const editing = useEditing()
  const kindLabel = useKindLabel()
  const [editingMaterial, setEditingMaterial] = useState<{ material: Material | null; number: number } | null>(null)
  const addRef = useRef<HTMLButtonElement>(null)

  const reorder = useMutation({
    mutationFn: ({ ids }: { ids: string[]; id: string; to: number; by: -1 | 1 }) =>
      reorderMaterials(editing.quizId, ids, editing.revision()),
    onSuccess: async (output, { id, to, by }) => {
      await editing.edited(output)
      afterMove(id, by, t('materials.moved', { number: to + 1 }))
    },
    onError: editing.failed,
  })
  const { announcement, moved: afterMove } = useMoveFocus(reorder.isPending)
  const remove = (material: Material, number: number) =>
    deleteMaterial(editing.quizId, material.materialId, editing.revision()).then(
      async (output) => {
        notify.success(t('materials.deleted', { number }))
        await editing.edited(output)
      },
      (error: unknown) => failedRemoval(editing, error),
    )
  const move = (index: number, by: -1 | 1) => {
    const ids = moved(materials.map((m) => m.materialId), index, by)
    reorder.mutate({ ids, id: materials[index].materialId, to: index + by, by })
  }

  return (
    <div className="flex flex-col gap-4" data-testid="materials-panel">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-muted-foreground text-sm">{t('materials.hint', { max: LIMITS.materials })}</p>
        <Button ref={addRef} disabled={materials.length >= LIMITS.materials || editing.stale}
          data-testid="material-add" onClick={() => setEditingMaterial({ material: null, number: materials.length + 1 })}>
          <Plus aria-hidden />
          {t('materials.add')}
        </Button>
      </div>
      <p role="status" className="sr-only">{announcement}</p>
      <ProblemList problems={formProblem(reorder.error, [])} />
      {materials.length === 0
        ? <p className="text-muted-foreground text-sm" data-testid="materials-empty">{t('materials.none')}</p>
        : (
          <ol className="flex flex-col gap-3">
            {materials.map((material, index) => (
              <li key={material.materialId} data-testid="material" data-material={material.materialId}
                data-kind={material.kind}>
                <Card>
                  <CardContent className="flex flex-col gap-3">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <h2 className="font-medium">
                        {material.title ? t('materials.numberTitled', { number: index + 1, title: material.title })
                          : t('materials.number', { number: index + 1 })}
                      </h2>
                      <Badge variant="outline">{kindLabel(material.kind)}</Badge>
                      {material.watchToAnswer && (
                        <Badge variant="secondary" data-testid="material-watch-badge">{t('materials.watchBadge')}</Badge>
                      )}
                    </div>
                    {material.description && <p className="text-muted-foreground text-sm">{material.description}</p>}
                    <MaterialContent material={material} />
                    <div className="flex flex-wrap gap-2">
                      <Button variant="outline" size="sm" disabled={editing.stale} data-testid="material-edit"
                        aria-label={t('materials.edit', { number: index + 1 })}
                        onClick={() => setEditingMaterial({ material, number: index + 1 })}>
                        <Pencil aria-hidden />
                        {t('editor.edit')}
                      </Button>
                      <Button variant="outline" size="sm" disabled={index === 0 || reorder.isPending || editing.stale}
                        aria-label={t('materials.up', { number: index + 1 })} data-move={`${material.materialId}:-1`}
                        data-testid="material-up" onClick={() => move(index, -1)}>
                        <ArrowUp aria-hidden />
                      </Button>
                      <Button variant="outline" size="sm"
                        disabled={index === materials.length - 1 || reorder.isPending || editing.stale}
                        aria-label={t('materials.down', { number: index + 1 })} data-move={`${material.materialId}:1`}
                        data-testid="material-down" onClick={() => move(index, 1)}>
                        <ArrowDown aria-hidden />
                      </Button>
                      <ConfirmDialog destructive title={t('materials.deleteTitle', { number: index + 1 })}
                        description={t('materials.deleteText')} confirmLabel={t('editor.delete')}
                        cancelLabel={t('common.cancel')} returnFocus={() => addRef.current}
                        onConfirm={() => remove(material, index + 1)}
                        trigger={
                          <Button variant="outline" size="sm" disabled={editing.stale} data-testid="material-delete"
                            aria-label={t('materials.delete', { number: index + 1 })}>
                            <Trash2 aria-hidden />
                            {t('editor.delete')}
                          </Button>
                        } />
                    </div>
                  </CardContent>
                </Card>
              </li>
            ))}
          </ol>
        )}
      {editingMaterial && (
        <MaterialDialog key={editingMaterial.material?.materialId ?? 'new'} material={editingMaterial.material}
          number={editingMaterial.number} open onOpenChange={(open) => {
            if (!open) setEditingMaterial(null)
          }} />
      )}
    </div>
  )
}
