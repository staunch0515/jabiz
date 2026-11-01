import type { Material, PublicationView, Tier } from '../qb/play'
import { videoPlayer } from '../qb/video'
import type { Sponsor } from '../qb/api'
import { choiceOf, type Draft, type Selection, type Snapshot } from '../content/api'
import { optionImageKey, optionTextKey, questionImageKey, stemKey, translationsIn, variantOf } from '../content/languages'
import type { ContentLanguage } from '../qb/api'
import type { Publication, Tier as SponsorTier } from '../publishing/api'

/** One question as the preview shows it: what a taker reads, never which options are right. */
export interface PreviewQuestion {
  no: number
  stem: string | null
  image: string | null
  points: number
  /** Single or multiple choice, as the taker is told (a scored question by its correct options, D-Q5-2). */
  choice: Selection | null
  options: { no: number; text: string | null; image: string | null }[]
}

/** What the preview draws: the publication as a taker's page reads it, its tiers, and the questions. */
export interface Preview {
  view: PublicationView
  /** The player of the video to watch (Q19), which makes the home's card a video card, as the server's card has it. */
  video: string | null
  tiers: Tier[]
  questions: PreviewQuestion[]
  /** A quiz's draft has no publication yet: no reward, period or counts to show. */
  draft: boolean
}

type SnapshotMaterial = Snapshot['materials'][number]

function material(m: SnapshotMaterial | Draft['materials'][number], no: number): Material {
  return {
    no, kind: m.kind, title: m.title, description: m.description, body: m.body, url: m.url, pdf: m.pdf,
    audio: m.audio, images: m.images.map((image, index) => ({ no: index + 1, image: image.image, caption: image.caption })),
    watchToAnswer: (m as { watchToAnswer?: boolean }).watchToAnswer === true,
  }
}

/**
 * The home card's video, as the server's card has it (Q63, `PublicationEditing.cardVideo`): the video watching which
 * is enough to answer, else the first the app plays.
 */
function videoOf(materials: { kind: string; url: string | null; watchToAnswer?: boolean }[]): string | null {
  const playable = (m: (typeof materials)[number]) => (m.kind === 'VIDEO_LINK' ? videoPlayer(m.url) : null)
  const watch = materials.find((m) => m.watchToAnswer && playable(m)) ?? materials.find((m) => playable(m))
  return watch ? playable(watch) : null
}

function base(given: Sponsor | null) {
  // Takers see the sponsor's name, picture and introduction only once the sponsor is approved (qb.app.publication).
  const sponsor = given?.reviewStatus === 'APPROVED' ? given : null
  return {
    sponsorName: sponsor?.contactName ?? null, sponsorCompany: sponsor?.company ?? null,
    sponsorIntro: sponsor?.intro ?? null, sponsorAvatar: sponsor?.avatar ?? null, sponsorCountry: null,
    completions: 0, last1h: 0, last24h: 0, last7d: 0, last30d: 0, statsAt: null,
    canStart: false, matched: true, heldUntil: null, referral: null, myReferralStatus: null, myReferralExpiresAt: null, myAttemptId: null, myStatus: null, myDeadline: null, invited: false,
  }
}

/** A publication's preview from the sponsor's own rows: its fields, its version's snapshot and the sponsor's profile. */
export function publicationPreview(p: Publication, tiers: SponsorTier[], snapshot: Snapshot | null,
  sponsor: Sponsor | null, now: Date = new Date()): Preview {
  const questions = snapshot?.questions ?? []
  return {
    draft: false,
    video: videoOf(snapshot?.materials ?? []),
    tiers: tiers.map((tier, index) => ({ seq: index + 1, fromScore: tier.fromScore, toScore: tier.toScore,
      reward: tier.reward })),
    questions: questions.map((q) => ({ no: q.no, stem: q.stem, image: q.image, points: q.points,
      choice: q.selection ?? choiceOf(q.options.filter((o) => o.correct).length),
      options: q.options.map((o) => ({ no: o.no, text: o.text, image: o.image })) })),
    view: {
      ...base(sponsor),
      publicationId: p.publicationId,
      name: p.name,
      quizTitle: snapshot?.title ?? '',
      // Q64: the publication's own cover, as its card shows it; else the version's.
      cover: p.cover ?? snapshot?.cover ?? null,
      kind: snapshot?.kind === 'SURVEY' ? 'SURVEY' : 'QUIZ',
      intro: snapshot?.intro ?? null,
      materials: (snapshot?.materials ?? []).map((m) => material(m, m.no)),
      status: p.status,
      startAt: p.startAt ?? now.toISOString(),
      endAt: p.endAt ?? now.toISOString(),
      timeLimitSec: p.timeLimitSec ?? snapshot?.timeLimitSec ?? 0,
      questionCount: questions.length,
      fullScore: p.fullScore ?? questions.reduce((sum, q) => sum + q.points, 0),
      passScore: p.passScore ?? 0,
      maxReward: p.maxReward ?? 0,
      rewardMethod: p.rewardMethod,
      redeemDescription: p.redeemDescription,
      digitalDelivery: p.digitalDelivery,
      digitalDescription: p.digitalDescription,
      audience: p.audience,
    },
  }
}

/** A quiz's draft as it would read once published: what the quiz itself says, with no reward yet. */
export function draftPreview(draft: Draft, sponsor: Sponsor | null, now: Date = new Date()): Preview {
  const q = draft.quiz
  return {
    draft: true,
    video: videoOf(draft.materials),
    tiers: [],
    questions: draft.questions.map((question, index) => ({ no: index + 1, stem: question.stem, image: question.image,
      points: question.points,
      choice: question.selection ?? choiceOf(question.options.filter((o) => o.correct).length),
      options: question.options.map((o, i) => ({ no: i + 1, text: o.text, image: o.image })) })),
    view: {
      ...base(sponsor),
      publicationId: q.quizId,
      name: q.title,
      quizTitle: q.title,
      cover: q.cover,
      kind: q.kind,
      intro: q.intro,
      materials: draft.materials.map((m, index) => material(m, index + 1)),
      status: 'DRAFT',
      startAt: now.toISOString(),
      endAt: now.toISOString(),
      timeLimitSec: q.timeLimitSec ?? 0,
      questionCount: draft.questions.length,
      fullScore: draft.questions.reduce((sum, question) => sum + question.points, 0),
      passScore: 0,
      maxReward: 0,
      rewardMethod: 'PLATFORM',
      redeemDescription: null,
      digitalDelivery: null,
      digitalDescription: null,
      audience: 'PUBLIC',
    },
  }
}

/**
 * A version's content in one of its languages (Q73a, as `QuizSnapshots.in`): the same structure with that language's
 * texts, its own images and materials where it has them; the base where it has not (or for its base language).
 */
export function snapshotIn(snapshot: Snapshot, language: ContentLanguage): Snapshot {
  const t = snapshot.translations?.[language]
  if (!t) return snapshot
  const text = (key: string, base: string | null) => t.texts[key] ?? base
  const image = (key: string, base: string | null) => t.images[key] ?? base
  return {
    ...snapshot,
    translations: undefined,
    title: text('title', snapshot.title) ?? snapshot.title,
    intro: text('intro', snapshot.intro),
    materials: snapshot.materials.map((m) => {
      const own = t.materials.find((o) => o.no === m.no)
      return own ? { ...own, no: m.no, kind: m.kind, watchToAnswer: m.watchToAnswer } : m
    }),
    questions: snapshot.questions.map((q) => ({
      ...q,
      stem: text(`q${q.no}.stem`, q.stem),
      image: image(`q${q.no}.image`, q.image),
      options: q.options.map((o) => ({ ...o, text: text(`q${q.no}.o${o.no}.text`, o.text),
        image: image(`q${q.no}.o${o.no}.image`, o.image) })),
    })),
  }
}

/** A draft in one of its other languages, as it would read there: what the language has, else the base's. */
export function draftIn(draft: Draft, language: ContentLanguage): Draft {
  if (language === draft.quiz.language) return draft
  const t = translationsIn(draft, language)
  const text = (key: string, base: string | null) => t.get(key)?.text ?? base
  const image = (key: string, base: string | null) => t.get(key)?.image ?? base
  return {
    ...draft,
    quiz: { ...draft.quiz, title: text('quiz.title', draft.quiz.title) ?? draft.quiz.title,
      intro: text('quiz.intro', draft.quiz.intro) },
    materials: draft.materials.map((m) => {
      const own = variantOf(draft, m, language)
      return own ? { ...own, watchToAnswer: m.watchToAnswer } : m
    }),
    questions: draft.questions.map((q) => ({
      ...q,
      stem: text(stemKey(q.questionId), q.stem),
      image: image(questionImageKey(q.questionId), q.image),
      options: q.options.map((o) => ({ ...o, text: text(optionTextKey(o.optionId), o.text),
        image: image(optionImageKey(o.optionId), o.image) })),
    })),
  }
}
