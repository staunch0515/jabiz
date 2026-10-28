import { Api, Refused, Store, type Answer, type Campaign, type Claim, type Started, type Submitted } from './api'
import { format, refusalText, textsOf, type TextKey } from './messages'
import { languageOf } from './opener'
import { STYLES } from './styles'

/**
 * The quiz of the storefront's block (docs/quizbuks/shopify/S2b-ui.md section 2), in quiz-player.js: loaded by the
 * block's element ({@link QuizElement}, quiz.js) when the shopper first opens the quiz, never before (S4c). It adds its
 * dialog to the element's shadow root: the video to play first (loaded only when played), the questions one at a time
 * against the clock, then the result and, for a pass, the discount code with a link that applies it.
 *
 * <p>It is a script of its own, so it has its own copies of the block's {@link Store} and {@link Api}: what it keeps
 * goes to the browser's storage as the element's does (when that storage cannot be used, each keeps its own for the
 * page's life, so a code got then is shown at once but not on the button of a page loaded later).
 */
export class QuizPlayer {
  private readonly host: HTMLElement
  private readonly campaign: Campaign
  private readonly opener: HTMLButtonElement
  private readonly texts: ReturnType<typeof textsOf>
  private readonly api: Api
  private started: Started | null = null
  private answers = new Map<number, number>()
  private index = 0
  private offset = 0
  private timer: number | null = null
  private announced = new Set<number>()
  private readonly dialog: HTMLDialogElement

  /**
   * @param host     the block's element, whose attributes say the language, the app proxy, the storefront's root
   *                 and the signed-in customer
   * @param root     its shadow root, holding the opener and its styles
   * @param campaign the campaign the element read (live, with a publication)
   * @param opener   the element's button, whose text says the code once there is one
   * @param visitor  the visitor key the element used
   */
  constructor(host: HTMLElement, root: ShadowRoot, campaign: Campaign, opener: HTMLButtonElement, visitor: string) {
    this.host = host
    this.campaign = campaign
    this.opener = opener
    this.texts = textsOf(host.getAttribute('locale') ?? document.documentElement.lang)
    this.api = new Api(host.getAttribute('proxy') ?? '/apps/quizbuks')
    Store.signedIn(host.getAttribute('customer'))
    Store.adopt(visitor)
    this.dialog = h('dialog', { 'aria-label': this.t('dialog') }) as HTMLDialogElement
    this.dialog.addEventListener('close', () => this.closed())
    root.append(h('style', {}, STYLES), this.dialog)
  }

  /** Stops the clock: the element left the page. */
  stop() {
    this.stopTimer()
  }

  /** Starts the clock again, if a quiz is under way: the element is back on the page. */
  resume() {
    if (this.started) this.startTimer()
  }

  private t(key: TextKey, values?: Record<string, string | number>): string {
    return format(this.texts[key], values)
  }

  // ---- the dialog -----------------------------------------------------------------------------------------------

  /** Opens the quiz where the visitor left it: their code, the question they were on, or the start. */
  open() {
    const campaign = this.campaign
    const claim = Store.claim(campaign.publicationId!)
    if (claim) this.showCode(claim, null)
    else if (this.started) this.showQuestion()
    else this.showIntro()
    if (typeof this.dialog.showModal === 'function') this.dialog.showModal()
    else this.dialog.setAttribute('open', '')
    this.focusFirst()
  }

  private closed() {
    this.dialog.removeAttribute('open')
    this.opener.focus()
  }

  private close() {
    if (typeof this.dialog.close === 'function') this.dialog.close()
    else this.closed()
  }

  private focusFirst() {
    const target = this.dialog.querySelector<HTMLElement>('[autofocus], h2')
    target?.focus()
  }

  /** Replaces the dialog's content: a heading (focused, so a reader hears where they are), the body, a close. */
  private screen(title: string, ...body: Node[]) {
    const heading = h('h2', { tabindex: '-1' }, title)
    const close = h('button', { type: 'button', class: 'close', 'aria-label': this.t('close') }, '×')
    close.addEventListener('click', () => this.close())
    this.dialog.replaceChildren(close, heading, ...body)
    heading.focus()
  }

  /** A screen that ends the quiz: on the free plan it says whose it is (S3b). */
  private result(title: string, ...body: Node[]) {
    if (this.campaign?.badge) {
      // To the landing page in the shopper's language, in a new tab: the quiz and the product stay where they are.
      const language = languageOf(this.host.getAttribute('locale'))
      body.push(h('p', { class: 'badge' }, h('a', { href: `${__QUIZBUKS_SITE__}/shopify-app/${language === 'en' ? '' : `${language}/`}`,
        target: '_blank', rel: 'noopener' }, 'Powered by QuizBuks')))
    }
    this.screen(title, ...body)
  }

  // ---- the intro ------------------------------------------------------------------------------------------------

  private showIntro() {
    const campaign = this.campaign
    const body: Node[] = [h('p', {}, this.t('intro', { n: campaign.questionCount, s: campaign.timeLimitSec,
      k: campaign.passScore }))]
    if (campaign.video) {
      const play = h('button', { type: 'button', class: 'video' }, this.t('watch'))
      play.addEventListener('click', () => {
        // Loaded only now: nothing of the video's service before the visitor asks for it.
        const frame = h('iframe', { src: autoplay(campaign.video!), title: this.t('video'),
          allow: 'autoplay; encrypted-media; picture-in-picture', allowfullscreen: '' })
        play.replaceWith(frame)
      })
      body.push(play)
    }
    const start = h('button', { type: 'button', class: 'primary' }, this.t('start'))
    start.addEventListener('click', () => void this.start(start as HTMLButtonElement))
    body.push(start, this.status())
    this.screen(campaign.name ?? this.t('dialog'), ...body)
  }

  private status(): HTMLElement {
    return h('p', { class: 'status', role: 'status' })
  }

  private say(text: string) {
    const status = this.dialog.querySelector<HTMLElement>('.status')
    if (status) status.textContent = text
  }

  private async start(button: HTMLButtonElement) {
    const publicationId = this.campaign.publicationId!
    button.disabled = true
    try {
      this.started = await this.api.start(publicationId)
      Store.rememberAttempt(publicationId, this.started.attemptId)
      this.offset = Date.parse(this.started.serverTime) - Date.now()
      this.answers = new Map(this.started.answers.filter((a) => a.selected.length > 0)
        .map((a) => [a.questionNo, a.selected[0]]))
      this.index = Math.min(this.started.currentIndex, this.started.questions.length - 1)
      this.startTimer()
      this.showQuestion()
    } catch (error) {
      button.disabled = false
      if (error instanceof Refused && error.code === 'QB_ATTEMPT_ALREADY_TAKEN') {
        // Answered before: its code, if this browser started it.
        const attemptId = Store.attempt(publicationId)
        if (attemptId) return void this.submit(attemptId, null)
      }
      this.say(refusalText(this.texts, error instanceof Refused ? error.code : null))
    }
  }

  // ---- the questions --------------------------------------------------------------------------------------------

  private showQuestion() {
    const started = this.started!
    const question = started.questions[this.index]
    const name = `q${question.no}`
    const options = question.options.map((option) => {
      const input = h('input', { type: 'radio', name, value: String(option.no) }) as HTMLInputElement
      input.checked = this.answers.get(question.no) === option.no
      input.addEventListener('change', () => {
        this.answers.set(question.no, option.no)
        next.disabled = false
      })
      return h('label', { class: 'option' }, input, h('span', {}, option.text))
    })
    const fieldset = h('fieldset', {}, h('legend', {}, question.stem), ...options)
    const last = this.index === started.questions.length - 1
    const back = h('button', { type: 'button' }, this.t('back')) as HTMLButtonElement
    back.disabled = this.index === 0
    back.addEventListener('click', () => {
      this.index -= 1
      this.showQuestion()
    })
    const next = h('button', { type: 'button', class: 'primary' }, last ? this.t('submit') : this.t('next')) as
      HTMLButtonElement
    next.disabled = !this.answers.has(question.no)
    next.addEventListener('click', () => {
      if (last) {
        void this.submit(started.attemptId, this.answerList())
      } else {
        this.index += 1
        this.showQuestion()
      }
    })
    const clock = h('p', { class: 'clock', 'aria-hidden': 'true' })
    const alert = h('p', { class: 'visually-hidden', 'aria-live': 'polite', 'data-clock': '' })
    this.screen(this.t('question', { i: this.index + 1, n: started.questions.length }), clock, alert, fieldset,
      h('div', { class: 'actions' }, back, next), this.status())
    this.tick()
  }

  private answerList(): Answer[] {
    return [...this.answers.entries()].map(([questionNo, option]) => ({ questionNo, selected: [option] }))
  }

  private startTimer() {
    this.stopTimer()
    this.announced.clear()
    this.timer = window.setInterval(() => this.tick(), 1000)
  }

  private stopTimer() {
    if (this.timer !== null) window.clearInterval(this.timer)
    this.timer = null
  }

  /** Shows the time left; says it at 30 and 10 seconds; submits what is answered when it runs out. */
  private tick() {
    if (!this.started) return
    const left = Math.max(0, Math.ceil((Date.parse(this.started.deadline) - (Date.now() + this.offset)) / 1000))
    const clock = this.dialog.querySelector<HTMLElement>('.clock')
    if (clock) clock.textContent = this.t('left', { t: `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}` })
    for (const mark of [30, 10]) {
      if (left <= mark && left > 0 && !this.announced.has(mark)) {
        this.announced.add(mark)
        const alert = this.dialog.querySelector<HTMLElement>('[data-clock]')
        if (alert) alert.textContent = this.t('soon', { t: mark })
      }
    }
    if (left === 0 && this.timer !== null) {
      this.stopTimer()
      void this.submit(this.started.attemptId, this.answerList())
    }
  }

  // ---- the result -----------------------------------------------------------------------------------------------

  private async submit(attemptId: string, answers: Answer[] | null) {
    const publicationId = this.campaign.publicationId!
    this.dialog.querySelectorAll<HTMLButtonElement>('button:not(.close)').forEach((b) => { b.disabled = true })
    this.say(this.t('sending'))
    let result: Submitted
    try {
      result = await this.api.submit(publicationId, attemptId, answers)
    } catch (error) {
      const code = error instanceof Refused ? error.code : null
      if (code === 'QB_ATTEMPT_FINISHED' || code === 'QB_ATTEMPT_EXPIRED') {
        this.stopTimer()
        this.started = null
        this.result(this.t('dialog'), h('p', {}, refusalText(this.texts, code)))
        return
      }
      // Back to where the visitor was, its buttons as they were (the first question has no way back).
      if (this.started) this.showQuestion()
      else this.dialog.querySelectorAll<HTMLButtonElement>('button').forEach((b) => { b.disabled = false })
      this.say(refusalText(this.texts, code))
      return
    }
    this.stopTimer()
    this.started = null
    if (result.discountCode) {
      const claim = { code: result.discountCode, percent: result.percent ?? this.campaign.percent,
        expiresAt: result.expiresAt }
      Store.rememberClaim(publicationId, claim)
      this.opener.textContent = this.t('openClaimed', { p: claim.percent })
      this.showCode(claim, result)
      return
    }
    const score = result.score !== null && result.fullScore !== null
      ? h('p', {}, this.t('score', { score: result.score, full: result.fullScore })) : null
    const text = { NOT_PASSED: 'failed', DAY_FULL: 'dayFull', NONE_LEFT: 'noneLeft', CAMPAIGN_OVER: 'over',
      ISSUED: 'error', AGAIN: 'error' }[result.outcome] as TextKey
    this.result(result.passed ? this.t('passed') : this.t('failed'), ...(score ? [score] : []),
      ...(result.passed ? [h('p', {}, this.t(text))] : []))
  }

  private showCode(claim: Claim, result: Submitted | null) {
    const code = h('p', { class: 'code' }, claim.code)
    const copy = h('button', { type: 'button' }, this.t('copy'))
    copy.addEventListener('click', () => {
      void navigator.clipboard?.writeText(claim.code).then(() => {
        copy.textContent = this.t('copied')
      }, () => undefined)
    })
    const root = (this.host.getAttribute('root') ?? '/').replace(/\/+$/, '')
    const use = h('a', { class: 'primary', href: `${root}/discount/${encodeURIComponent(claim.code)}?redirect=${
      encodeURIComponent(location.pathname)}` }, this.t('use'))
    const body: Node[] = []
    if (result?.score != null && result.fullScore != null) {
      body.push(h('p', {}, this.t('score', { score: result.score, full: result.fullScore })))
    }
    body.push(h('p', {}, this.t('code', { p: claim.percent })), code, h('div', { class: 'actions' }, copy, use))
    if (claim.expiresAt) {
      body.push(h('p', {}, this.t('until', { date: new Date(claim.expiresAt).toLocaleDateString(
        this.host.getAttribute('locale') ?? undefined) })))
    }
    this.result(this.t('passed'), ...body)
  }
}

/** The player's address, playing at once (the visitor asked for it). */
function autoplay(player: string): string {
  return player + (player.includes('?') ? '&' : '?') + 'autoplay=1'
}

/** An element with attributes and children (text is always text: nothing here is parsed as HTML). */
function h(tag: string, attributes: Record<string, string>, ...children: (Node | string | null)[]): HTMLElement {
  const element = document.createElement(tag)
  for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, value)
  for (const child of children) {
    if (child !== null) element.append(child)
  }
  return element
}

declare global {
  interface Window {
    /** The player, once quiz-player.js has run (quiz.ts loads it on the first opening). */
    QuizbuksPlayer?: typeof QuizPlayer
  }
}

window.QuizbuksPlayer = QuizPlayer
