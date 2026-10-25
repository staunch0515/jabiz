import { Button, EnvironmentBanner, ThemeToggle, cn } from '@jabiz/ui'
import { ChevronRight } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { PrizeArt, QuizArt, RewardArt, Wordmark } from '../art/Illustrations'
import { APP } from '../messages'
import { QB } from '../qb/messages'
import { navigate } from '../qb/router'

const SEEN_KEY = 'qb.intro.seen'

/** Whether this browser saw the introduction (kept on the device; a private window sees it again). */
export function introSeen(): boolean {
  try {
    return window.localStorage.getItem(SEEN_KEY) === '1'
  } catch {
    return true
  }
}

function rememberSeen() {
  try {
    window.localStorage.setItem(SEEN_KEY, '1')
  } catch {
    // Blocked storage: the introduction shows again next time, which is harmless.
  }
}

const SLIDES: { key: 'answer' | 'earn' | 'collect'; art: ReactNode }[] = [
  { key: 'answer', art: <QuizArt className="h-44 w-full" /> },
  { key: 'earn', art: <RewardArt className="h-44 w-full" /> },
  { key: 'collect', art: <PrizeArt className="h-44 w-full" /> },
]

/**
 * The introduction a visitor sees once before signing in (docs/quizbuks/plans/Q32-visual-refresh.md): three slides
 * on what the app is for, with next and skip; both end at the sign-in page and are remembered on the device.
 */
export function IntroPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [index, setIndex] = useState(0)
  const slide = SLIDES[index]
  const last = index === SLIDES.length - 1
  const done = () => {
    rememberSeen()
    navigate('/login', { replace: true })
  }
  return (
    <div className="flex min-h-dvh flex-col" data-testid="intro" data-slide={slide.key}>
      <div className="from-qb-hero-from to-qb-hero-to text-qb-hero-foreground bg-linear-to-b">
        {/* A test system says so on every page (platform 16zd), the introduction too. */}
        <EnvironmentBanner />
        <div className="mx-auto flex max-w-md items-center gap-2 px-4 py-3">
          <Wordmark name={tQb('brand')} compact className="mr-auto" />
          <ThemeToggle className="text-qb-hero-foreground hover:bg-qb-hero-foreground/10 hover:text-qb-hero-foreground" />
          <Button variant="ghost" size="sm" data-testid="intro-skip" onClick={done}
            className="text-qb-hero-foreground hover:bg-qb-hero-foreground/10 hover:text-qb-hero-foreground">
            {t('intro.skip')}
          </Button>
        </div>
        <div className="mx-auto max-w-md px-6 pt-2 pb-8" aria-live="polite">
          {slide.art}
        </div>
      </div>
      <main className="mx-auto flex w-full max-w-md flex-1 flex-col gap-5 px-6 py-6">
        <h1 className="text-2xl font-semibold tracking-tight" data-testid="intro-title">{t(`intro.${slide.key}.title`)}</h1>
        <p className="text-muted-foreground">{t(`intro.${slide.key}.text`)}</p>
        <ol className="flex items-center gap-2" aria-label={t('intro.steps')}>
          {SLIDES.map((s, i) => (
            <li key={s.key}>
              <button type="button" aria-label={t('intro.step', { n: i + 1, total: SLIDES.length })}
                aria-current={i === index ? 'step' : undefined} onClick={() => setIndex(i)}
                className={cn('block h-2 rounded-full transition-all', i === index ? 'bg-primary w-8' : 'bg-border w-2')} />
            </li>
          ))}
        </ol>
        <div className="mt-auto flex flex-col gap-3">
          <Button size="lg" data-testid="intro-next" onClick={() => (last ? done() : setIndex(index + 1))}>
            {last ? t('intro.start') : t('intro.next')}
            {!last && <ChevronRight aria-hidden />}
          </Button>
          <p className="text-muted-foreground text-center text-sm">
            {t('intro.haveAccount')}{' '}
            <button type="button" className="text-primary font-medium underline underline-offset-4"
              data-testid="intro-sign-in" onClick={done}>
              {t('public.signIn')}
            </button>
          </p>
        </div>
      </main>
    </div>
  )
}
