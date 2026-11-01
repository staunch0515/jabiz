import { questionProducts, type PublicationProduct } from '../qb/products'
import { readPublicationProducts } from '../share/api'
import { countProductClick } from '../share/counting'
import { ProductCards } from '../qb/view/ProductCards'
import { SponsorVisit } from './SponsorVisit'
import { resultPublication } from './resultPublication'
import { ApiError, formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  notify,
  PageState,
  Textarea,
  cn,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Banknote, CircleCheck, CircleX, Clock, Copy, ExternalLink, Gift, Hourglass, ListChecks, Ticket, Trophy }
  from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ResultInstallOffer } from '../install'
import { CelebrateArt } from '../qb/Illustrations'
import { APP } from '../messages'
import { autoSubmitted, storedResult, storedStart } from '../play/storage'
import { formatKudos } from '../qb/kudos'
import { SoundOnce } from '../qb/SoundSettings'
import { playSound, type SoundCue } from '../qb/sound'
import { QB } from '../qb/messages'
import { confirmCashReward, confirmDigitalPrize, disputeCashReward, disputeDigitalPrize, openDigitalPrize,
  readAttemptResult, readPublication, type AttemptResult, type CashView, type DigitalView, type QuestionResult, type QuestionView,
  type RedeemView, type RewardStatus, type StartOutput, type SubmitOutput } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link, Redirect } from '../qb/router'
import { letter } from './PlayPage'

/** How often the page asks again while the server finishes an attempt whose time ran out. */
export const FINISHING_POLL_MS = 5_000
/** How often a prize claim waiting for the sponsor is read again. */
const CLAIM_POLL_MS = 4_000

/**
 * The submission's own answer, with this tab's copy of the start, as a result: what the page shows when the server's
 * result cannot be read right after finishing (a phone's connection dropped, a busy quiz).
 */
function fromSubmission(submitted: SubmitOutput, start: StartOutput | null): AttemptResult {
  return { attemptId: submitted.attemptId, publicationId: start?.publicationId ?? '', name: null,
    status: submitted.status, kind: submitted.kind, deadline: null, serverTime: '', score: submitted.score,
    fullScore: submitted.fullScore, passScore: submitted.kind === 'SURVEY' ? null : submitted.passScore,
    passed: submitted.passed, reward: submitted.reward, rewardStatus: submitted.rewardStatus,
    questions: start?.questions ?? [], result: submitted.questions,
    rewardMethod: submitted.rewardStatus === 'CASH' ? 'SPONSOR_CASH'
      : submitted.rewardStatus === 'REDEEM' ? 'OFFLINE_REDEEM'
        : submitted.rewardStatus === 'DIGITAL' ? 'DIGITAL_PRIZE' : 'PLATFORM', cash: null, redeem: null, digital: null }
}

/** A finished attempt as the page shows it, from the server's result. */
export interface Outcome {
  status: string
  score: number
  fullScore: number
  passScore: number | null
  passed: boolean
  reward: unknown
  rewardStatus: RewardStatus
  /** The server's notice of a pending reward, in the caller's language (the submission's answer, this tab only). */
  message: string | null
  questions: QuestionResult[]
  /** Q22: the cash reward's or the prize's record, once the server has it. */
  cash: CashView | null
  redeem: RedeemView | null
  /** Q33: the digital prize's record, once the server has it. */
  digital: DigitalView | null
}

/** The content as a web address to open, when it is one (http or https only, as the materials' links); else null. */
export function prizeLink(content: string): string | null {
  const text = content.trim()
  if (!/^https?:\/\/\S+$/i.test(text)) return null
  try {
    new URL(text)
    return text
  } catch {
    return null
  }
}

/** The texts of a question's options, in the order they were shown (letters), for the chosen numbers. */
function optionTexts(question: QuestionView | undefined, numbers: number[]): string[] {
  if (!question) return []
  return question.options
    .map((option, i) => ({ option, label: letter(i) }))
    .filter(({ option }) => numbers.includes(option.no))
    .map(({ option, label }) => (option.text ? `${label}. ${option.text}` : label))
}

/** A survey's question (Q12): what was chosen, never right or wrong. */
function SurveyRow({ result, question, index, product }: { result: QuestionResult; question?: QuestionView; index: number;
  product?: PublicationProduct }) {
  const { t } = useTranslation(APP)
  const mine = optionTexts(question, result.selected)
  return (
    <li className="flex flex-col gap-1 rounded-lg border p-3" data-testid="result-question">
      <p className="font-medium">{t('result.question', { n: index + 1 })}</p>
      {question?.stem && <p className="text-sm">{question.stem}</p>}
      {question && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">{t('result.yourAnswer')}</dt>
          <dd data-testid="result-mine">{mine.length > 0 ? mine.join(', ') : t('result.noAnswer')}</dd>
        </dl>
      )}
      {product && <ProductCards products={[product]} testId="result-product" onOpen={countProductClick} />}
    </li>
  )
}

function QuestionRow({ result, question, index, product }: { result: QuestionResult; question?: QuestionView;
  index: number; product?: PublicationProduct }) {
  const { t } = useTranslation(APP)
  // Scoring's rule: right when the options chosen are exactly the correct ones.
  const right = result.correct.length > 0 && [...result.selected].sort().join() === [...result.correct].sort().join()
  const Icon = right ? CircleCheck : CircleX
  const mine = optionTexts(question, result.selected)
  const correct = optionTexts(question, result.correct)
  return (
    <li className="flex flex-col gap-1 rounded-lg border p-3" data-testid="result-question"
      data-correct={right ? 'true' : 'false'}>
      <p className="flex items-center gap-2 font-medium">
        <Icon aria-hidden className={cn('size-5 shrink-0', right ? 'text-success' : 'text-destructive')} />
        <span className="flex-1">{t('result.question', { n: index + 1 })}</span>
        <span className="text-sm">{right ? t('result.right') : t('result.wrong')}</span>
      </p>
      {question?.stem && <p className="text-sm">{question.stem}</p>}
      {question && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">{t('result.yourAnswer')}</dt>
          <dd data-testid="result-mine">{mine.length > 0 ? mine.join(', ') : t('result.noAnswer')}</dd>
          {/* Q65: the right answer only where it differs from the taker's. */}
          {!right && (
            <>
              <dt className="text-muted-foreground">{t('result.correctAnswer')}</dt>
              <dd data-testid="result-correct">{correct.join(', ')}</dd>
            </>
          )}
        </dl>
      )}
      <p className="text-muted-foreground text-xs">{t('result.earned', { earned: result.earned, points: result.points })}</p>
      {/* Q60b: the sponsor's product for this question, after its answer is shown. */}
      {product && <ProductCards products={[product]} testId="result-product" onOpen={countProductClick} />}
    </li>
  )
}

/** The taker says what is wrong with the cash reward (Q22); an administrator decides. */
function DisputeDialog({ cash, onDone }: { cash: CashView; onDone: () => void }) {
  const { t } = useTranslation(APP)
  const id = useId()
  const [note, setNote] = useState('')
  const dispute = useMutation({
    mutationFn: () => disputeCashReward(cash.cashRewardId, note),
    onSuccess: () => {
      notify.success(t('cash.disputed'))
      onDone()
    },
  })
  return (
    <Dialog open onOpenChange={(open) => {
      if (!open && !dispute.isPending) onDone()
    }}>
      <DialogContent data-testid="cash-dispute-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={(event: FormEvent) => {
          event.preventDefault()
          if (note.trim() && !dispute.isPending) dispute.mutate()
        }}>
          <DialogHeader>
            <DialogTitle>{t('cash.disputeTitle', { code: cash.code })}</DialogTitle>
            <DialogDescription>{t('cash.disputeText')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-note`}>{t('cash.disputeNote')}</Label>
            <Textarea id={`${id}-note`} value={note} rows={3} maxLength={500} required data-testid="cash-dispute-note"
              onChange={(event) => setNote(event.target.value)} />
          </div>
          {dispute.isError && <p className="text-destructive text-sm" role="alert">{problemText(dispute.error)}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={dispute.isPending} onClick={onDone}>{t('cash.cancel')}</Button>
            <Button type="submit" variant="destructive" disabled={dispute.isPending || !note.trim()}
              aria-busy={dispute.isPending || undefined} data-testid="cash-dispute-send">
              {t('cash.disputeSend')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * A reward the sponsor pays in cash (Q22): the code to show, where it stands, and the taker's confirm or report once
 * the sponsor marked it paid (or before, when they say nothing is coming).
 */
export function CashReward({ cash, attemptId, outcome }: { cash: CashView | null; attemptId: string; outcome: Outcome }) {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const [disputing, setDisputing] = useState(false)
  const confirm = useMutation({
    mutationFn: () => confirmCashReward(cash!.cashRewardId),
    onSuccess: async () => {
      notify.success(t('cash.confirmed'))
      playSound('reward')
      await queryClient.invalidateQueries({ queryKey: ['qb', 'attempt-result', attemptId] })
      await queryClient.invalidateQueries({ queryKey: ['qb', 'cash-rewards'] })
    },
  })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['qb', 'attempt-result', attemptId] })
    void queryClient.invalidateQueries({ queryKey: ['qb', 'cash-rewards'] })
  }
  const status = cash?.status ?? 'DUE'
  const variant = status === 'CONFIRMED' ? 'success' : status === 'OVERDUE' || status === 'DISPUTED' ? 'warning' : 'default'
  const canConfirm = status === 'MARKED_PAID' || status === 'OVERDUE'
  const canDispute = status === 'DUE' || status === 'MARKED_PAID' || status === 'OVERDUE'
  return (
    <Alert variant={variant} data-testid="result-reward" data-status="CASH" data-cash-status={status}>
      <Banknote aria-hidden />
      <AlertTitle>{t('cash.title', { amount: formatKudos(outcome.reward) })}</AlertTitle>
      <AlertDescription className="text-current flex flex-col gap-2">
        <span>{t(`cash.status.${status}`, { sponsor: cash?.sponsorCompany ?? t('cash.theSponsor'),
          time: cash?.confirmBy ? formatDateTime(cash.confirmBy) : '' })}</span>
        {cash && (
          <span className="flex items-center gap-2">
            <span>{t('cash.code')}</span>
            <span className="rounded bg-muted px-2 py-0.5 font-mono text-base font-semibold" data-testid="cash-code">
              {cash.code}
            </span>
          </span>
        )}
        {outcome.message && !cash && <span>{outcome.message}</span>}
        {cash && (canConfirm || canDispute) && (
          <span className="flex flex-wrap gap-2">
            {canConfirm && (
              <Button size="sm" disabled={confirm.isPending} aria-busy={confirm.isPending || undefined}
                data-testid="cash-confirm" onClick={() => confirm.mutate()}>
                <CircleCheck aria-hidden />
                {t('cash.confirm')}
              </Button>
            )}
            {canDispute && (
              <Button size="sm" variant="outline" data-testid="cash-dispute" onClick={() => setDisputing(true)}>
                {t('cash.dispute')}
              </Button>
            )}
          </span>
        )}
        {confirm.isError && <span className="text-destructive" role="alert">{problemText(confirm.error)}</span>}
        {disputing && cash && <DisputeDialog cash={cash} onDone={() => {
          setDisputing(false)
          refresh()
        }} />}
      </AlertDescription>
    </Alert>
  )
}

/**
 * A prize to collect at the sponsor's place (Q22): what it is, until when, and the code; collected by scanning the
 * sponsor's code (Q22b), or claimed by their printed one and waiting for them (the page asks again while it waits).
 */
export function Redemption({ redeem, outcome }: { redeem: RedeemView | null; outcome: Outcome }) {
  const { t } = useTranslation(APP)
  const status = redeem?.status ?? 'DUE'
  const variant = status === 'REDEEMED' ? 'success' : status === 'EXPIRED' || status === 'VOID' ? 'default'
    : status === 'CLAIMED' ? 'warning' : 'success'
  return (
    <Alert variant={variant} data-testid="result-reward" data-status="REDEEM" data-redeem-status={status}>
      <Gift aria-hidden />
      <AlertTitle>{t('redeem.title', { amount: formatKudos(outcome.reward) })}</AlertTitle>
      <AlertDescription className="text-current flex flex-col gap-1">
        <span>{t(`redeem.status.${status}`, { sponsor: redeem?.sponsorCompany ?? t('cash.theSponsor'),
          time: formatDateTime(status === 'CLAIMED' ? redeem?.confirmBy ?? '' : status === 'REDEEMED'
            ? redeem?.redeemedAt ?? '' : redeem?.expiresAt ?? '') })}</span>
        {redeem?.description && <span className="whitespace-pre-line font-medium" data-testid="redeem-prize">{redeem.description}</span>}
        {redeem && (status === 'DUE' || status === 'CLAIMED') && (
          <span className="flex items-center gap-2">
            <span>{t('cash.code')}</span>
            <span className="rounded bg-muted px-2 py-0.5 font-mono text-base font-semibold" data-testid="redeem-code">
              {redeem.code}
            </span>
          </span>
        )}
        {status === 'DUE' && <span className="text-xs">{t('redeem.scanHint')}</span>}
        {outcome.message && !redeem && <span>{outcome.message}</span>}
      </AlertDescription>
    </Alert>
  )
}

/** The winner says what is wrong with the digital prize (Q33): nothing came, the code does not work; an administrator decides. */
function DigitalDisputeDialog({ digital, onDone }: { digital: DigitalView; onDone: () => void }) {
  const { t } = useTranslation(APP)
  const id = useId()
  const [note, setNote] = useState('')
  const dispute = useMutation({
    mutationFn: () => disputeDigitalPrize(digital.digitalPrizeId, note),
    onSuccess: () => {
      notify.success(t('digital.disputed'))
      onDone()
    },
  })
  return (
    <Dialog open onOpenChange={(open) => {
      if (!open && !dispute.isPending) onDone()
    }}>
      <DialogContent data-testid="digital-dispute-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={(event: FormEvent) => {
          event.preventDefault()
          if (note.trim() && !dispute.isPending) dispute.mutate()
        }}>
          <DialogHeader>
            <DialogTitle>{t('digital.disputeTitle')}</DialogTitle>
            <DialogDescription>{t('digital.disputeText')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-note`}>{t('digital.disputeNote')}</Label>
            <Textarea id={`${id}-note`} value={note} rows={3} maxLength={500} required data-testid="digital-dispute-note"
              onChange={(event) => setNote(event.target.value)} />
          </div>
          {dispute.isError && <p className="text-destructive text-sm" role="alert">{problemText(dispute.error)}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={dispute.isPending} onClick={onDone}>{t('cash.cancel')}</Button>
            <Button type="submit" variant="destructive" disabled={dispute.isPending || !note.trim()}
              aria-busy={dispute.isPending || undefined} data-testid="digital-dispute-send">
              {t('digital.disputeSend')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * A digital prize (Q33): what it is and where it stands, and once it is delivered the winner's "Show prize". The
 * content is read only then (`QB_DIGITAL_PRIZE_OPEN`, which records the opening; D-Q33-3), never with the result,
 * and lives in this component alone: shown for the rest of the page's life, never kept in storage. While delivered
 * the winner confirms it works or reports a problem; while due or overdue they can only report.
 */
export function DigitalPrize({ digital, attemptId, outcome }: { digital: DigitalView | null; attemptId: string;
  outcome: Outcome }) {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const [disputing, setDisputing] = useState(false)
  const [content, setContent] = useState<string | null>(null)
  const open = useMutation({
    mutationFn: () => openDigitalPrize(digital!.digitalPrizeId),
    onSuccess: (opened) => setContent(opened.prizeContent),
  })
  const confirm = useMutation({
    mutationFn: () => confirmDigitalPrize(digital!.digitalPrizeId),
    onSuccess: async () => {
      notify.success(t('digital.confirmed'))
      playSound('reward')
      await queryClient.invalidateQueries({ queryKey: ['qb', 'attempt-result', attemptId] })
    },
  })
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['qb', 'attempt-result', attemptId] })
  // The clipboard is denied in some browsers and insecure contexts: the content stays on screen to copy by hand.
  const copy = async () => {
    if (content === null) return
    try {
      await navigator.clipboard.writeText(content)
      notify.success(t('digital.copied'))
    } catch {
      notify.error(t('digital.copyFailed'))
    }
  }
  const status = digital?.status ?? null
  const variant = status === 'CONFIRMED' || status === 'DELIVERED' ? 'success'
    : status === 'OVERDUE' || status === 'DISPUTED' ? 'warning' : 'default'
  const canShow = status === 'DELIVERED' || status === 'CONFIRMED' || status === 'DISPUTED'
  const canConfirm = status === 'DELIVERED'
  const canDispute = status === 'DUE' || status === 'DELIVERED' || status === 'OVERDUE'
  const link = content === null ? null : prizeLink(content)
  return (
    <Alert variant={variant} data-testid="result-reward" data-status="DIGITAL" data-digital-status={status ?? ''}>
      <Ticket aria-hidden />
      <AlertTitle>{t('digital.title', { amount: formatKudos(outcome.reward) })}</AlertTitle>
      <AlertDescription className="text-current flex flex-col gap-2">
        {digital?.description && (
          <span className="whitespace-pre-line font-medium" data-testid="digital-prize">{digital.description}</span>
        )}
        {digital && status && (
          <span>{t(`digital.status.${status}`, { sponsor: digital.sponsorCompany ?? t('cash.theSponsor'),
            time: formatDateTime(status === 'CONFIRMED' ? digital.confirmedAt ?? '' : digital.sendBy ?? '') })}</span>
        )}
        {digital?.hint && content === null && canShow && (
          <span className="text-xs" data-testid="digital-hint">{t('digital.hint', { hint: digital.hint })}</span>
        )}
        {digital && canShow && content === null && (
          <span className="flex flex-wrap gap-2">
            <Button size="sm" disabled={open.isPending} aria-busy={open.isPending || undefined} data-testid="digital-show"
              onClick={() => open.mutate()}>
              <Ticket aria-hidden />
              {t('digital.show')}
            </Button>
          </span>
        )}
        {open.isError && <span className="text-destructive" role="alert">{problemText(open.error)}</span>}
        {content !== null && (
          <span className="flex flex-col gap-2">
            <span className="text-xs">{t('digital.shown')}</span>
            <span className="rounded bg-muted px-2 py-1 font-mono text-base font-semibold break-all select-all"
              data-testid="digital-content">
              {content}
            </span>
            <span className="flex flex-wrap gap-2">
              <Button size="sm" variant="outline" data-testid="digital-copy" onClick={() => void copy()}>
                <Copy aria-hidden />
                {t('digital.copy')}
              </Button>
              {link && (
                <Button asChild size="sm" variant="outline">
                  <a href={link} target="_blank" rel="noopener noreferrer" data-testid="digital-link">
                    <ExternalLink aria-hidden />
                    {t('digital.openLink')}
                  </a>
                </Button>
              )}
            </span>
          </span>
        )}
        {outcome.message && !digital && <span>{outcome.message}</span>}
        {digital && (canConfirm || canDispute) && (
          <span className="flex flex-wrap gap-2">
            {canConfirm && (
              <Button size="sm" disabled={confirm.isPending} aria-busy={confirm.isPending || undefined}
                data-testid="digital-confirm" onClick={() => confirm.mutate()}>
                <CircleCheck aria-hidden />
                {t('digital.confirm')}
              </Button>
            )}
            {canDispute && (
              <Button size="sm" variant="outline" data-testid="digital-dispute" onClick={() => setDisputing(true)}>
                {t('digital.dispute')}
              </Button>
            )}
          </span>
        )}
        {confirm.isError && <span className="text-destructive" role="alert">{problemText(confirm.error)}</span>}
        {disputing && digital && <DigitalDisputeDialog digital={digital} onDone={() => {
          setDisputing(false)
          refresh()
        }} />}
      </AlertDescription>
    </Alert>
  )
}

function Reward({ outcome, survey, attemptId }: { outcome: Outcome; survey: boolean; attemptId: string }) {
  const { t } = useTranslation(APP)
  if (outcome.rewardStatus === 'CASH') return <CashReward cash={outcome.cash} attemptId={attemptId} outcome={outcome} />
  if (outcome.rewardStatus === 'REDEEM') return <Redemption redeem={outcome.redeem} outcome={outcome} />
  if (outcome.rewardStatus === 'DIGITAL') {
    return <DigitalPrize digital={outcome.digital} attemptId={attemptId} outcome={outcome} />
  }
  if (outcome.rewardStatus === 'PENDING') {
    return (
      <Alert variant="warning" data-testid="result-reward" data-status="PENDING">
        <Hourglass aria-hidden />
        <AlertTitle>{t('result.pendingTitle', { amount: formatKudos(outcome.reward) })}</AlertTitle>
        <AlertDescription className="text-current">{outcome.message ?? t('result.pending')}</AlertDescription>
      </Alert>
    )
  }
  if (outcome.rewardStatus === 'PAID') {
    return (
      <Alert variant="success" data-testid="result-reward" data-status="PAID">
        <Trophy aria-hidden />
        <AlertTitle>{t('result.paid', { amount: formatKudos(outcome.reward) })}</AlertTitle>
        <AlertDescription className="text-current">{t('result.paidHint')}</AlertDescription>
      </Alert>
    )
  }
  return (
    <Alert data-testid="result-reward" data-status="NONE">
      <AlertDescription>{t(survey ? 'result.surveyIncomplete' : 'result.none')}</AlertDescription>
    </Alert>
  )
}

/**
 * The result (U-33, U-34): score, pass mark, passed or not, each question with the answer chosen and the correct
 * one, and the reward paid, pending (the sponsor's budget ran out: paid once they top up) or none. Everything comes
 * from the server (`QB_ATTEMPT_RESULT`, docs/quizbuks/plans/Q20-result-review.md): the questions as this attempt
 * showed them and its stored result, so the page reads the same from the history, another device or after the tab
 * closed. Only the pending reward's notice is the submission's own (this tab), and the submission's answer stands in
 * when the server cannot be read right after finishing. An attempt whose time ran out and is not finished yet is
 * waited for: the server finishes it with its saved answers. A survey's result (Q12) says whether it was complete, the
 * reward, and the answers chosen, never right or wrong.
 */
export function ResultPage({ attemptId }: { attemptId: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [submitted] = useState(() => storedResult(attemptId))
  const result = useQuery({
    queryKey: ['qb', 'attempt-result', attemptId],
    queryFn: () => readAttemptResult(attemptId),
    // A copy of this attempt in progress may be cached from before it was finished: always read again on arrival.
    refetchOnMount: 'always',
    // Still finishing, or a prize claim waiting for the sponsor (Q22b): asked again until it settles.
    refetchInterval: (query) => (query.state.data?.status === 'IN_PROGRESS' ? FINISHING_POLL_MS
      : query.state.data?.redeem?.status === 'CLAIMED' ? CLAIM_POLL_MS : false),
  })
  // Q60b: the products the sponsor shows with the questions, while the publication is live (public, so it may fail
  // quietly: the result stands without them).
  const publicationId = result.data?.publicationId
  if (publicationId) resultPublication.remember(attemptId, publicationId)
  const products = useQuery({ queryKey: ['qb', 'publication-products', publicationId],
    queryFn: () => readPublicationProducts(publicationId!), enabled: !!publicationId, retry: false })
  // Q64: the sponsor's page, from the publication (found by the caller's attempt whatever its state now).
  const [at] = useState(() => new Date().toISOString())
  const publication = useQuery({ queryKey: ['qb', 'result-publication', publicationId],
    queryFn: () => readPublication({ publicationId: publicationId!, at, country: null }), enabled: !!publicationId,
    retry: false })

  if (result.isPending) return <PageState kind="loading" />
  // The submission's own answer stands in only when the server could not answer (not when it refused: a 404 or 403 is
  // another user's or no attempt, and must not show what this tab kept).
  const refused = result.error instanceof ApiError && result.error.status < 500
  const data = result.data ?? (submitted && !refused ? fromSubmission(submitted, storedStart(attemptId)) : null)
  if (!data) {
    if (result.error instanceof ApiError && result.error.status === 404) {
      return <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />
    }
    return <PageState kind="error" description={problemText(result.error)}
      action={<Button variant="outline" onClick={() => void result.refetch()}>{tQb('retry')}</Button>} />
  }
  if (data.status === 'IN_PROGRESS') {
    // What is cached may be stale (the attempt was finished since): wait for the read under way before deciding.
    if (result.isFetching) return <PageState kind="loading" />
    // Still in time by the server's clock: back to answering; else the server is finishing it with the saved answers.
    const inTime = !!data.deadline && Date.parse(data.deadline) > Date.parse(data.serverTime)
    if (inTime) return <Redirect to={`/play/${attemptId}`} />
    return (
      <PageState kind="loading" title={t('result.finishing')} data-testid="result-finishing" />
    )
  }
  const outcome: Outcome = {
    status: data.status,
    score: data.score ?? 0,
    fullScore: data.fullScore ?? 0,
    passScore: data.passScore,
    passed: data.passed === true,
    reward: data.reward,
    rewardStatus: data.rewardStatus ?? 'NONE',
    message: submitted?.message ?? null,
    questions: data.result,
    cash: data.cash ?? null,
    redeem: data.redeem ?? null,
    digital: data.digital ?? null,
  }
  const automatic = outcome.status === 'EXPIRED' || autoSubmitted(attemptId)
  const byNo = new Map(data.questions.map((q) => [q.no, q]))
  // Q60b: one product per question, by its number (the quiz's own, number 0, show on its page).
  const productOf = questionProducts(products.data)
  const name = data.name
  // A survey (Q12) has no score to show: complete (every question answered) or not, and what was chosen.
  const survey = data.kind === 'SURVEY'

  // Q57: a sound for the result just submitted in this tab (not for an old result opened from the history).
  const cue: SoundCue | null = !submitted ? null
    : survey ? (outcome.passed ? 'success' : null)
      : !outcome.passed ? 'wrong' : outcome.rewardStatus !== 'NONE' ? 'reward' : 'success'

  return (
    <section className="flex flex-col gap-4" aria-labelledby="result-title" data-testid="result">
      <SoundOnce cue={cue} />
      <header className="flex flex-col gap-1">
        <h1 id="result-title" className="text-xl font-semibold">{t('result.title')}</h1>
        {name && <p className="text-muted-foreground" data-testid="result-name">{name}</p>}
      </header>
      {automatic && (
        <Alert data-testid="result-automatic">
          <Clock aria-hidden />
          <AlertDescription>{t('result.automatic')}</AlertDescription>
        </Alert>
      )}
      {survey ? (
        <div className="flex items-center gap-3 rounded-lg border p-4" data-testid="result-survey"
          data-complete={outcome.passed ? 'true' : 'false'}>
          <ListChecks aria-hidden className="size-6 shrink-0" />
          <p className="font-medium">{outcome.passed ? t('result.surveyDone') : t('result.surveyIncomplete')}</p>
        </div>
      ) : (
      <div className={outcome.passed ? 'bg-accent flex items-center gap-4 rounded-xl border p-4' : 'flex items-center gap-4 rounded-xl border p-4'}>
        {outcome.passed && <CelebrateArt className="h-20 w-24 shrink-0" />}
        <p className="flex flex-col">
          <span className="text-muted-foreground text-sm">{t('result.score')}</span>
          <span className="text-3xl font-bold tabular-nums" data-testid="result-score">
            {t('result.scoreOf', { score: outcome.score, full: outcome.fullScore })}
          </span>
          {outcome.passScore !== null && (
            <span className="text-muted-foreground text-sm" data-testid="result-pass-score">
              {t('result.passScore', { score: outcome.passScore })}
            </span>
          )}
        </p>
        <span className="flex-1" />
        <Badge variant={outcome.passed ? 'success' : 'secondary'} data-testid="result-passed"
          data-passed={outcome.passed ? 'true' : 'false'}>
          {outcome.passed ? t('result.passed') : t('result.failed')}
        </Badge>
      </div>
      )}
      <Reward outcome={outcome} survey={survey} attemptId={attemptId} />
      <ResultInstallOffer />
      {outcome.questions.length > 0 && (
        <section aria-labelledby="result-questions-title" className="flex flex-col gap-2">
          <h2 id="result-questions-title" className="text-lg font-semibold">
            {survey ? t('result.surveyAnswers') : t('result.questions')}
          </h2>
          <ol className="flex flex-col gap-2">
            {outcome.questions.map((result, i) => survey
              ? <SurveyRow key={result.no} result={result} question={byNo.get(result.no)} index={i}
                product={productOf.get(result.no)} />
              : <QuestionRow key={result.no} result={result} question={byNo.get(result.no)} index={i}
                product={productOf.get(result.no)} />)}
          </ol>
        </section>
      )}
      {publication.data && (
        <SponsorVisit handle={publication.data.sponsorHandle} avatar={publication.data.sponsorAvatar}
          sponsor={publication.data.sponsorCompany ?? publication.data.sponsorName} products={false} />
      )}
      <div className="flex gap-2">
        {/* Q65: back to the quiz's own page (its introduction and materials), else to the history. */}
        {data.publicationId
          ? <Button asChild variant="outline" className="flex-1">
            <Link to={`/p/${data.publicationId}`} data-testid="result-publication">{t('result.toQuiz')}</Link>
          </Button>
          : <Button asChild variant="outline" className="flex-1"><Link to="/history">{t('nav.history')}</Link></Button>}
        <Button asChild className="flex-1"><Link to="/">{t('result.more')}</Link></Button>
      </div>
    </section>
  )
}
