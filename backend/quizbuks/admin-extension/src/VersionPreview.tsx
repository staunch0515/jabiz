import { Check, ExternalLink } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Badge, EXTENSION_NAMESPACE, FilePreview, MarkdownView, SegmentedControl } from '@jabiz/admin'
import { CONTENT_IMAGE_VARIANTS, DICTIONARY } from './ids'
import { useLabels } from './hooks'
import { safeHttpUrl, snapshotIn, type Snapshot, type SnapshotMaterial } from './snapshot'

/** The language a version is written in: the base, whose code the snapshot does not hold. */
const BASE = 'base'

const IMAGE = { image: true, variants: CONTENT_IMAGE_VARIANTS }
/** PDFs and sounds are downloaded, not played or shown inline (the admin's content security policy stays as it is). */
const DOCUMENT = { image: false }

/** A link opened in a new tab, never as the page's own; anything but http(s) is shown as text only. */
function ExternalAnchor({ url }: { url: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const href = safeHttpUrl(url)
  if (!href) return <span className="break-all">{url}</span>
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" data-testid="material-link"
      className="text-primary inline-flex items-center gap-1 break-all underline underline-offset-4">
      {url}
      <ExternalLink aria-hidden className="size-3 shrink-0" />
      <span className="sr-only">{t('preview.newTab')}</span>
    </a>
  )
}

function MaterialContent({ material }: { material: SnapshotMaterial }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const none = <p className="text-muted-foreground text-sm">{t('preview.noContent')}</p>
  switch (material.kind) {
    case 'ARTICLE':
      return material.body
        ? (
          <details className="text-sm">
            <summary className="cursor-pointer">{t('preview.showText')}</summary>
            <div className="mt-2"><MarkdownView text={material.body} /></div>
          </details>
        )
        : none
    case 'LINK':
    case 'VIDEO_LINK':
      return material.url ? <ExternalAnchor url={material.url} /> : none
    case 'PDF':
      return material.pdf ? <FilePreview fileId={material.pdf} field={DOCUMENT} width={160} /> : none
    case 'AUDIO':
      return material.audio ? <FilePreview fileId={material.audio} field={DOCUMENT} width={160} /> : none
    case 'IMAGES':
      return material.images.length === 0 ? none : (
        <ul className="flex flex-wrap gap-3">
          {material.images.map((image) => (
            <li key={image.no} className="flex flex-col gap-1 text-sm">
              <FilePreview fileId={image.image} field={IMAGE} width={120} />
              <span className="text-muted-foreground max-w-40">{image.caption ?? t('preview.noCaption')}</span>
            </li>
          ))}
        </ul>
      )
    default:
      return none
  }
}

/** A scored question's kind (D-Q5-2): one correct option makes a single choice, several a multiple one. */
function choiceKey(question: { options: { correct: boolean }[] }): 'SINGLE' | 'MULTIPLE' | null {
  const correct = question.options.filter((option) => option.correct).length
  return correct === 0 ? null : correct === 1 ? 'SINGLE' : 'MULTIPLE'
}

/**
 * A quiz version's whole frozen content, as the reviewer must see it before deciding: the cover and introduction, every
 * material (links open in a new tab; PDFs and sounds are downloads; article texts are folded), and every question with
 * its options, the correct ones marked in words (a survey's, Q12, have none: single or multiple choice instead).
 * Questions are folded sections,
 * open to begin with. Markdown goes only through `MarkdownView` (no raw HTML).
 */
export function VersionPreview({ content: version }: { content: Snapshot }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const kindLabel = useLabels(DICTIONARY.materialKind)
  const id = useId()
  // Q73a: a version with other languages is read in each of them; the base first.
  const languages = Object.keys(version.translations)
  const [language, setLanguage] = useState<string>(BASE)
  const content = language === BASE ? version : snapshotIn(version, language)
  return (
    <div className="flex flex-col gap-6" data-testid="version-preview">
      {languages.length > 0 && (
        <SegmentedControl<string> value={language} onChange={setLanguage} aria-label={t('preview.language')}
          data-testid="preview-language" options={[{ value: BASE, label: t('preview.baseLanguage') },
            ...languages.map((l) => ({ value: l, label: t(`preview.languages.${l}`, { defaultValue: l }) }))]} />
      )}
      <div className="flex flex-col gap-2">
        <h3 className="flex flex-wrap items-center gap-2 text-lg font-semibold" data-testid="preview-title">
          {content.title}
          {content.kind === 'SURVEY' && <Badge variant="secondary" data-testid="preview-survey">{t('preview.survey')}</Badge>}
          {content.aiGenerated && <Badge variant="outline">{t('preview.aiGenerated')}</Badge>}
        </h3>
        {content.cover && (
          <div className="flex flex-col gap-1">
            <span className="text-muted-foreground text-sm">{t('preview.cover')}</span>
            <FilePreview fileId={content.cover} field={IMAGE} width={240} />
          </div>
        )}
        {content.intro && (
          <div className="flex flex-col gap-1" data-testid="preview-intro">
            <span className="text-muted-foreground text-sm">{t('preview.intro')}</span>
            <MarkdownView text={content.intro} />
          </div>
        )}
      </div>

      <section className="flex flex-col gap-3" aria-labelledby={`${id}-materials`}>
        <h3 id={`${id}-materials`} className="font-semibold">
          {t('preview.materials', { n: content.materials.length })}
        </h3>
        {content.materials.length > 0 && (
          <ol className="flex flex-col gap-3">
            {content.materials.map((material) => (
              <li key={material.no} className="flex flex-col gap-2 rounded-md border p-3" data-testid="preview-material"
                data-kind={material.kind}>
                <p className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">
                    {material.title
                      ? t('preview.materialTitled', { number: material.no, title: material.title })
                      : t('preview.material', { number: material.no })}
                  </span>
                  <Badge variant="outline">{kindLabel(material.kind)}</Badge>
                </p>
                {material.description && <p className="text-muted-foreground text-sm">{material.description}</p>}
                <MaterialContent material={material} />
              </li>
            ))}
          </ol>
        )}
      </section>

      <section className="flex flex-col gap-3" aria-labelledby={`${id}-questions`}>
        <h3 id={`${id}-questions`} className="font-semibold">
          {t('preview.questions', { n: content.questions.length })}
        </h3>
        {content.questions.length === 0
          ? <p className="text-muted-foreground text-sm">{t('preview.noQuestions')}</p>
          : (
            <ol className="flex flex-col gap-3">
              {content.questions.map((question) => (
                <li key={question.no} data-testid="preview-question">
                  <details open className="rounded-md border p-3">
                    <summary className="flex cursor-pointer flex-wrap items-center justify-between gap-2">
                      <span className="font-medium">{t('preview.question', { number: question.no })}</span>
                      <Badge variant="outline">
                        {question.selection ? t(`preview.selections.${question.selection}`)
                          : [choiceKey(question) && t(`preview.selections.${choiceKey(question)}`),
                            t('preview.points', { points: question.points })]
                            .filter(Boolean).join(' · ')}
                      </Badge>
                    </summary>
                    <div className="mt-2 flex flex-col gap-2">
                      <p className="whitespace-pre-wrap" data-testid="preview-stem">{question.stem}</p>
                      {question.image && <FilePreview fileId={question.image} field={IMAGE} width={200} />}
                      <ol className="flex flex-col gap-1">
                        {question.options.map((option) => (
                          <li key={option.no} className="flex flex-wrap items-center gap-2"
                            data-testid="preview-option" data-correct={option.correct}>
                            {option.correct
                              ? <Check aria-hidden className="text-success size-4 shrink-0" />
                              : <span aria-hidden className="size-4 shrink-0" />}
                            <span>{option.no}.</span>
                            <span className={option.correct ? 'font-medium' : undefined}>
                              {option.text ?? t('preview.imageOnly')}
                            </span>
                            {option.correct && <Badge variant="success">{t('preview.correct')}</Badge>}
                            {option.image && <FilePreview fileId={option.image} field={IMAGE} width={96} />}
                          </li>
                        ))}
                      </ol>
                    </div>
                  </details>
                </li>
              ))}
            </ol>
          )}
      </section>
    </div>
  )
}
