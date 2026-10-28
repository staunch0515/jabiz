/**
 * The block's own texts in the languages of QuizBuks (en, zh, ja), chosen by the storefront's language and English
 * otherwise. The server's refusals are mapped to these by their codes ({@link refusalText}): the block never shows
 * the server's own text.
 */
const en = {
  open: 'Answer a quiz, get {p}% off',
  openClaimed: 'Your {p}% discount code',
  dialog: 'Quiz',
  close: 'Close',
  intro: '{n} questions · {s} seconds · {k} correct to pass',
  watch: 'Watch the video',
  video: 'Video',
  start: 'Start the quiz',
  question: 'Question {i} of {n}',
  left: '{t} left',
  soon: '{t} seconds left',
  back: 'Back',
  next: 'Next',
  submit: 'Submit',
  sending: 'Sending…',
  passed: 'You passed!',
  score: '{score} of {full} correct.',
  code: 'Your discount code: {p}% off',
  copy: 'Copy',
  copied: 'Copied',
  use: 'Use it now',
  until: 'Valid until {date}.',
  failed: 'Not this time.',
  dayFull: 'You passed, but today\'s discount codes have all been handed out. Sorry!',
  noneLeft: 'You passed, but no discount code is left right now. Sorry!',
  over: 'This quiz has ended.',
  already: 'You have already taken this quiz.',
  tooFast: 'That was quick: read the questions, then submit again.',
  expired: 'The time is up.',
  error: 'Something went wrong. Please try again.',
}

type Texts = typeof en

const zh: Texts = {
  open: '答题赢 {p}% 折扣',
  openClaimed: '你的 {p}% 折扣码',
  dialog: '答题',
  close: '关闭',
  intro: '{n} 道题 · {s} 秒 · 答对 {k} 题通过',
  watch: '观看视频',
  video: '视频',
  start: '开始答题',
  question: '第 {i} 题，共 {n} 题',
  left: '剩余 {t}',
  soon: '还剩 {t} 秒',
  back: '上一题',
  next: '下一题',
  submit: '提交',
  sending: '正在提交…',
  passed: '通过了！',
  score: '答对 {score} / {full} 题。',
  code: '你的折扣码：{p}% 折扣',
  copy: '复制',
  copied: '已复制',
  use: '立即使用',
  until: '有效期至 {date}。',
  failed: '这次没有通过。',
  dayFull: '你通过了，但今天的折扣码已经发完了，抱歉！',
  noneLeft: '你通过了，但现在没有可发的折扣码了，抱歉！',
  over: '这个答题已经结束。',
  already: '你已经答过这个题了。',
  tooFast: '太快了：请先读题，再提交一次。',
  expired: '时间到了。',
  error: '出错了，请再试一次。',
}

const ja: Texts = {
  open: 'クイズに答えて {p}% オフ',
  openClaimed: 'あなたの {p}% 割引コード',
  dialog: 'クイズ',
  close: '閉じる',
  intro: '{n} 問 · {s} 秒 · {k} 問正解で合格',
  watch: '動画を見る',
  video: '動画',
  start: 'クイズを始める',
  question: '問題 {i} / {n}',
  left: '残り {t}',
  soon: '残り {t} 秒',
  back: '戻る',
  next: '次へ',
  submit: '送信',
  sending: '送信中…',
  passed: '合格です！',
  score: '{full} 問中 {score} 問正解。',
  code: 'あなたの割引コード：{p}% オフ',
  copy: 'コピー',
  copied: 'コピーしました',
  use: '今すぐ使う',
  until: '{date} まで有効です。',
  failed: '今回は不合格でした。',
  dayFull: '合格ですが、本日の割引コードはすべて配布済みです。申し訳ありません。',
  noneLeft: '合格ですが、現在配布できる割引コードがありません。申し訳ありません。',
  over: 'このクイズは終了しました。',
  already: 'このクイズにはすでに回答しています。',
  tooFast: '早すぎます。問題を読んでからもう一度送信してください。',
  expired: '時間切れです。',
  error: 'エラーが発生しました。もう一度お試しください。',
}

export const MESSAGES: Record<string, Texts> = { en, zh, ja }

export type TextKey = keyof Texts

/** The texts of a storefront language ({@code zh-CN}, {@code ja}, …): its own when QuizBuks has them, else English. */
export function textsOf(locale: string | null | undefined): Texts {
  const language = (locale ?? 'en').toLowerCase().split('-')[0]
  return MESSAGES[language] ?? en
}

/** A text with its {placeholders} filled. */
export function format(text: string, values: Record<string, string | number> = {}): string {
  return text.replace(/\{(\w+)\}/g, (all, key: string) => (key in values ? String(values[key]) : all))
}

/** What the block says for a refusal of the server, by its rule code; a general error for any other. */
export function refusalText(texts: Texts, code: string | null): string {
  switch (code) {
    case 'QB_ATTEMPT_ALREADY_TAKEN':
    case 'QB_ATTEMPT_FINISHED':
      return texts.already
    case 'QB_ATTEMPT_TOO_FAST':
      return texts.tooFast
    case 'QB_ATTEMPT_EXPIRED':
      return texts.expired
    case 'QB_PUBLICATION_NOT_OPEN':
      return texts.over
    default:
      return texts.error
  }
}
