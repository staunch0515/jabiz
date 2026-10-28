import { Api, Store, type Campaign, type Claim } from './api'
import { OPENER_TEXTS, format, languageOf } from './opener'
import type { QuizPlayer } from './player'
import { OPENER_STYLES } from './styles'

/**
 * The storefront's quiz block, {@code <quizbuks-quiz>} (docs/quizbuks/shopify/S2b-ui.md section 2), in quiz.js: the
 * Theme App Extension's Liquid puts it on a product page with the storefront's language ({@code locale}), its root
 * ({@code root}: {@code /} or {@code /fr}), the app proxy's path ({@code proxy}) and the address of the quiz itself
 * ({@code player}: quiz-player.js among the extension's assets). It reads the shop's live campaign and, when there is
 * one with codes today (or the visitor got its code already), shows a button that opens the quiz. The quiz
 * ({@link QuizPlayer}) is loaded on the first opening only (S4c): every product page loads this script, which Shopify
 * keeps under 10 000 bytes, and nothing more until the shopper asks. Everything is in its shadow root: the theme's
 * styles do not reach it, and its own do not leak; fonts and the text colour are the theme's.
 */
export class QuizElement extends HTMLElement {
  private readonly root: ShadowRoot
  private campaign: Campaign | null = null
  private player: QuizPlayer | null = null

  constructor() {
    super()
    this.root = this.attachShadow({ mode: 'open' })
  }

  async connectedCallback() {
    const api = new Api(this.getAttribute('proxy') ?? '/apps/quizbuks')
    Store.signedIn(this.getAttribute('customer'))
    try {
      this.campaign = await api.campaign()
    } catch {
      return
    }
    const campaign = this.campaign
    if (!campaign?.live || !campaign.publicationId) return
    const claim = Store.claim(campaign.publicationId)
    if (!campaign.codesToday && !claim) return
    this.renderButton(claim)
  }

  disconnectedCallback() {
    this.player?.stop()
  }

  private renderButton(claim: Claim | null) {
    const texts = OPENER_TEXTS[languageOf(this.getAttribute('locale') ?? document.documentElement.lang)]
    const opener = document.createElement('button')
    opener.type = 'button'
    opener.className = 'open'
    opener.setAttribute('part', 'button')
    opener.textContent = claim ? format(texts.openClaimed, { p: claim.percent })
      : format(texts.open, { p: this.campaign!.percent })
    const status = document.createElement('p')
    status.className = 'opener-status'
    status.setAttribute('role', 'status')
    opener.addEventListener('click', () => void this.open(opener, status, texts.loadFailed))
    const style = document.createElement('style')
    style.textContent = OPENER_STYLES
    this.root.replaceChildren(style, opener, status)
  }

  /** Opens the quiz, loading it the first time; says so when it cannot be loaded (the button stays to try again). */
  private async open(opener: HTMLButtonElement, status: HTMLElement, failed: string) {
    if (!this.player) {
      // Loaded already (another block on the page opened it): at once, as the click.
      let Player = window.QuizbuksPlayer
      if (!Player) {
        opener.disabled = true
        try {
          Player = await loadPlayer(this.getAttribute('player'))
          status.textContent = ''
        } catch {
          status.textContent = failed
          return
        } finally {
          opener.disabled = false
        }
      }
      this.player = new Player(this, this.root, this.campaign!, opener)
    }
    this.player.open()
  }
}

let loading: Promise<typeof QuizPlayer> | null = null

/** The quiz's script, added to the page once (every block on it shares it); tried again after a failure. */
export function loadPlayer(src: string | null): Promise<typeof QuizPlayer> {
  if (window.QuizbuksPlayer) return Promise.resolve(window.QuizbuksPlayer)
  if (!src) return Promise.reject(new Error('The block has no player address'))
  loading ??= new Promise<typeof QuizPlayer>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = src
    script.async = true
    const fail = () => {
      loading = null
      script.remove()
      reject(new Error(`Could not load ${src}`))
    }
    script.addEventListener('load', () => (window.QuizbuksPlayer ? resolve(window.QuizbuksPlayer) : fail()))
    script.addEventListener('error', fail)
    document.head.append(script)
  })
  return loading
}

if (!customElements.get('quizbuks-quiz')) customElements.define('quizbuks-quiz', QuizElement)
