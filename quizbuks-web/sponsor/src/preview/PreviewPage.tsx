import {
  Alert,
  AlertDescription,
  Button,
  PageHeader,
  PageState,
  SegmentedControl,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Eye, Play } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { readDraft, readVersion } from '../content/api'
import { draftQuery, quizPath } from '../content/paths'
import { SPONSOR } from '../messages'
import { readPublication } from '../publishing/api'
import { publicationPath, publicationQuery } from '../publishing/paths'
import { SPONSOR_QUERY } from '../pages/OnboardingPage'
import { readMySponsor } from '../qb/api'
import { FileImage } from '../qb/FileImage'
import { QB } from '../qb/messages'
import { Link } from '../qb/router'
import { FeedCardView } from '../qb/view/FeedCard'
import { PublicationContent } from '../qb/view/PublicationContent'
import { draftIn, draftPreview, publicationPreview, snapshotIn, type Preview, type PreviewQuestion } from './model'
import { CONTENT_LANGUAGES, type ContentLanguage } from '../qb/api'
import type { PreviewTarget } from './paths'

type Screen = 'home' | 'page' | 'questions'

/** One question as a taker reads it: no timer, nothing to choose, no right answer shown. */
function QuestionScreen({ questions }: { questions: PreviewQuestion[] }) {
  const { t } = useTranslation(SPONSOR)
  const [index, setIndex] = useState(0)
  if (questions.length === 0) return <p className="text-muted-foreground text-sm">{t('preview.noQuestions')}</p>
  const q = questions[Math.min(index, questions.length - 1)]
  const letter = (i: number) => String.fromCharCode(65 + i)
  return (
    <section className="flex flex-col gap-3" aria-labelledby="preview-question" data-testid="preview-question">
      <p className="text-muted-foreground text-sm" data-testid="preview-progress">
        {t('preview.progress', { n: index + 1, total: questions.length })}
      </p>
      <div className="bg-card flex flex-col gap-3 border p-4">
        <h2 id="preview-question" className="text-base font-semibold whitespace-pre-line">{q.stem}</h2>
        {q.image && <FileImage fileId={q.image} variant="w640" alt={t('preview.questionImage')} className="w-full" />}
        <ul className="flex flex-col gap-2">
          {q.options.map((o, i) => (
            <li key={o.no} className="flex items-center gap-3 border px-3 py-2" data-testid="preview-option">
              <span aria-hidden className="bg-muted flex size-7 shrink-0 items-center justify-center text-sm font-semibold">
                {letter(i)}
              </span>
              <span className="flex min-w-0 flex-1 flex-col gap-1">
                {o.text && <span>{o.text}</span>}
                {o.image && <FileImage fileId={o.image} variant="w320" alt={t('preview.optionImage', { letter: letter(i) })}
                  className="max-h-32 w-auto self-start" />}
              </span>
            </li>
          ))}
        </ul>
        <p className="text-muted-foreground text-xs" data-testid="preview-choice" data-choice={q.choice ?? 'none'}>
          {q.choice && <>{t(`questions.selections.${q.choice}`)} · </>}{t('preview.points', { count: q.points })}
        </p>
      </div>
      <div className="flex items-center justify-between">
        <Button variant="outline" disabled={index === 0} onClick={() => setIndex(index - 1)} data-testid="preview-prev">
          <ChevronLeft aria-hidden />{t('preview.previous')}
        </Button>
        <Button variant="outline" disabled={index + 1 >= questions.length} onClick={() => setIndex(index + 1)}
          data-testid="preview-next">
          {t('preview.next')}<ChevronRight aria-hidden />
        </Button>
      </div>
    </section>
  )
}

/** The three screens in a phone-wide frame, as the taker app lays them out. */
function Screens({ preview }: { preview: Preview }) {
  const { t } = useTranslation(SPONSOR)
  const [screen, setScreen] = useState<Screen>('page')
  return (
    <div className="flex flex-col items-center gap-4">
      <SegmentedControl<Screen> value={screen} onChange={setScreen} aria-label={t('preview.screens')}
        data-testid="preview-screens" options={[
          { value: 'home', label: t('preview.home') },
          { value: 'page', label: t('preview.page') },
          { value: 'questions', label: t('preview.questions') },
        ]} />
      {/* The taker's phone (and the phone-sized window): 468 pixels wide at most. */}
      <div className="bg-background w-full max-w-[468px] border p-4 shadow-sm" data-testid="preview-frame"
        data-screen={screen}>
        {screen === 'home' && (
          // Q63: one card a row, as on the app's home; the video's play mark, the video not started.
          <ul className="flex flex-col" aria-label={t('preview.home')}>
            <FeedCardView card={{ ...preview.view, video: preview.video }} href={null} />
          </ul>
        )}
        {screen === 'page' && (
          <article className="flex flex-col gap-3" aria-labelledby="detail-title" data-testid="detail">
            <PublicationContent publication={preview.view} tiers={preview.tiers} titleAs="h2" draft={preview.draft} />
            <Button size="lg" className="w-full" disabled data-testid="preview-start">
              <Play aria-hidden />{t('preview.start')}
            </Button>
          </article>
        )}
        {screen === 'questions' && <QuestionScreen questions={preview.questions} />}
      </div>
    </div>
  )
}

function usePreview(target: PreviewTarget, chosen: ContentLanguage | null) {
  const sponsor = useQuery({ queryKey: SPONSOR_QUERY, queryFn: readMySponsor })
  const publication = useQuery({ queryKey: publicationQuery(target.id), queryFn: () => readPublication(target.id),
    enabled: target.kind === 'publication' })
  const versionId = publication.data?.publication.versionId ?? null
  const version = useQuery({ queryKey: ['qb', 'quiz-version', versionId], queryFn: () => readVersion(versionId!),
    enabled: !!versionId })
  const draft = useQuery({ queryKey: draftQuery(target.id), queryFn: () => readDraft(target.id),
    enabled: target.kind === 'quiz' })
  if (target.kind === 'quiz') {
    if (draft.isPending || sponsor.isPending) return { state: 'loading' as const }
    if (draft.isError) return { state: 'error' as const, retry: () => void draft.refetch() }
    if (!draft.data) return { state: 'missing' as const }
    // Q73a: the draft in any of its languages.
    const languages = [draft.data.quiz.language, ...draft.data.quiz.translationLanguages]
    const language = chosen && languages.includes(chosen) ? chosen : languages[0]
    return { state: 'ready' as const, preview: draftPreview(draftIn(draft.data, language), sponsor.data ?? null),
      title: draft.data.quiz.title, back: quizPath(target.id), languages, language }
  }
  if (publication.isPending || sponsor.isPending || (versionId && version.isPending)) return { state: 'loading' as const }
  if (publication.isError || version.isError) {
    return { state: 'error' as const, retry: () => { void publication.refetch(); void version.refetch() } }
  }
  if (!publication.data) return { state: 'missing' as const }
  const { publication: p, tiers } = publication.data
  // Q73a: the version in any of its languages.
  const content = version.data?.content ?? null
  const base = version.data?.language
  const languages = base ? [base, ...CONTENT_LANGUAGES.filter((l) => l !== base && content?.translations?.[l])]
    : []
  const language = chosen && languages.includes(chosen) ? chosen : languages[0] ?? null
  return { state: 'ready' as const,
    preview: publicationPreview(p, tiers, content && language ? snapshotIn(content, language) : content,
      sponsor.data ?? null),
    title: p.name, back: publicationPath(target.id), languages, language }
}

/**
 * The preview of what takers see (Q34b): the home's card, the quiz's page and the questions, drawn by the taker app's
 * own components from the sponsor's data. Nothing is started or counted; the start button does nothing.
 */
export function PreviewPage({ target }: { target: PreviewTarget }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const [chosen, setChosen] = useState<ContentLanguage | null>(null)
  const result = usePreview(target, chosen)
  if (result.state === 'loading') return <PageState kind="loading" />
  if (result.state === 'error') {
    return <PageState kind="error" titleAs="h1" description={tQb('failed')}
      action={<Button variant="outline" onClick={result.retry}>{tQb('retry')}</Button>} />
  }
  if (result.state === 'missing') return <PageState kind="notFound" titleAs="h1" description={t('preview.missing')} />
  return (
    <section className="flex flex-col gap-4" data-testid="preview">
      <PageHeader title={t('preview.title', { name: result.title })}
        description={<span className="flex items-center gap-1.5"><Eye aria-hidden className="size-4" />{t('preview.intro')}</span>}
        actions={<Button asChild variant="outline"><Link to={result.back}>{t('preview.back')}</Link></Button>} />
      {result.preview.draft && (
        <Alert data-testid="preview-draft"><AlertDescription>{t('preview.draftNote')}</AlertDescription></Alert>
      )}
      {result.languages.length > 1 && result.language && (
        <SegmentedControl<ContentLanguage> value={result.language} onChange={setChosen}
          aria-label={t('translations.choose')} data-testid="preview-language"
          options={result.languages.map((l) => ({ value: l, label: tQb(`languages.${l}`) }))} />
      )}
      <Screens preview={result.preview} />
    </section>
  )
}
