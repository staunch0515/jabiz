import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Alert,
  AlertDescription,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Label,
  PageState,
  RadioGroup,
  RadioGroupItem,
} from '@jabiz/ui'
import { SPONSOR } from '../messages'
import { publicQuery } from '../qb/legal'
import { hrefOf, navigate } from '../qb/router'

/** The public template of the trials that went out (backend `qb.public.prospect-trial`, Q48c). */
export const TRIAL_TEMPLATE = 'qb.public.prospect-trial'
/** The trial page's address: `/try/<key>`, the random key of the prospecting mail's link. */
export const TRIAL_PREFIX = '/try/'

/** The key of a trial page's address; null for any other page. */
export function trialKeyOf(path: string): string | null {
  if (!path.startsWith(TRIAL_PREFIX)) return null
  const key = path.slice(TRIAL_PREFIX.length)
  return /^[0-9a-f]{48}$/.test(key) ? key : null
}

interface TrialOption { text: string; correct: boolean }
interface Trial { title: string; intro: string | null; questions: { stem: string; options: TrialOption[] }[] }
interface TrialRow { trialKey: string; company: string | null; language: string | null; trial: string | null }

async function readTrial(key: string): Promise<{ company: string | null; language: string | null; trial: Trial } | null> {
  const row = (await publicQuery<TrialRow>(TRIAL_TEMPLATE, { key }, 1))[0]
  if (!row?.trial) return null
  return { company: row.company, language: row.language, trial: JSON.parse(row.trial) as Trial }
}

/**
 * The trial quiz a prospecting mail invites a business to (Q48c): made from its own site, answered here without an
 * account, checked in the page (nothing is sent or kept), then an invitation to sign up as a sponsor. The sign-up's
 * link carries the key (`?src=`), so the platform can tell which mail brought it.
 */
export default function TrialPage({ trialKey }: { trialKey: string }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const trial = useQuery({ queryKey: ['qb', 'trial', trialKey], queryFn: () => readTrial(trialKey), retry: false })
  const [chosen, setChosen] = useState<Record<number, number>>({})
  const [checked, setChecked] = useState(false)
  if (trial.isPending) return <PageState kind="loading" />
  if (trial.isError) return <Alert variant="destructive"><AlertDescription>{t('trial.failed')}</AlertDescription></Alert>
  if (!trial.data) return <p data-testid="trial-missing">{t('trial.missing')}</p>
  const { trial: quiz, company, language } = trial.data
  const total = quiz.questions.length
  const right = quiz.questions.filter((q, i) => q.options[chosen[i]]?.correct).length
  const signUp = `/signup?src=${trialKey}`
  return (
    <div className="flex flex-col gap-6" data-testid="trial-page">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold">{t('trial.title')}</h1>
        <p className="text-muted-foreground">{t('trial.intro')}</p>
        {company && <p className="text-muted-foreground text-sm">{t('trial.madeFor', { company })}</p>}
      </header>
      <section className="flex flex-col gap-4" lang={language ?? undefined}>
        <h2 className="text-xl font-semibold">{quiz.title}</h2>
        {quiz.intro && <p>{quiz.intro}</p>}
        {quiz.questions.map((question, qi) => {
          const answer = question.options.find((option) => option.correct)
          const mine = chosen[qi]
          return (
            <Card key={qi} data-testid="trial-question">
              <CardHeader>
                <CardTitle>
                  <span className="text-muted-foreground block text-sm font-normal" lang={i18n.language}>
                    {t('trial.question', { n: qi + 1, total })}
                  </span>
                  <span id={`trial-q${qi}`}>{question.stem}</span>
                </CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-2">
                <RadioGroup aria-labelledby={`trial-q${qi}`} value={mine == null ? '' : String(mine)} disabled={checked}
                  onValueChange={(value) => setChosen((current) => ({ ...current, [qi]: Number(value) }))}>
                  {question.options.map((option, oi) => (
                    <div key={oi} className="flex items-center gap-2">
                      <RadioGroupItem value={String(oi)} id={`trial-q${qi}-o${oi}`} />
                      <Label htmlFor={`trial-q${qi}-o${oi}`} className="font-normal">{option.text}</Label>
                    </div>
                  ))}
                </RadioGroup>
                {checked && (
                  <p data-testid="trial-feedback" className={question.options[mine]?.correct ? 'text-primary' : ''}>
                    {question.options[mine]?.correct ? t('trial.right') : t('trial.wrong', { answer: answer?.text ?? '' })}
                  </p>
                )}
              </CardContent>
            </Card>
          )
        })}
        {checked
          ? (
            <div className="flex flex-wrap items-center gap-4">
              <p className="font-medium" role="status" data-testid="trial-score">{t('trial.score', { right, total })}</p>
              <Button variant="outline" onClick={() => {
                setChosen({})
                setChecked(false)
              }}>{t('trial.again')}</Button>
            </div>
          )
          : (
            <div>
              <Button data-testid="trial-check" disabled={Object.keys(chosen).length < total}
                onClick={() => setChecked(true)}>{t('trial.check')}</Button>
            </div>
          )}
      </section>
      <Card>
        <CardHeader><CardTitle>{t('trial.ctaTitle')}</CardTitle></CardHeader>
        <CardContent className="flex flex-col gap-4">
          <p>{t('trial.cta')}</p>
          <div>
            <Button asChild>
              <a href={hrefOf(signUp)} data-testid="trial-signup" onClick={(event) => {
                event.preventDefault()
                navigate(signUp)
              }}>{t('trial.signUp')}</a>
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
