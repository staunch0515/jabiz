/**
 * The texts the block's opener shows before the quiz itself is loaded (S4c: quiz.js, which every product page loads,
 * stays under Shopify's 10 000 bytes for a block's script; quiz-player.js holds the rest). {@link MESSAGES} builds
 * every language's texts on these, so each text has one place.
 */
export const OPENER_TEXTS = {
  en: {
    open: 'Answer a quiz, get {p}% off',
    openClaimed: 'Your {p}% discount code',
    loadFailed: 'The quiz could not be loaded. Please try again.',
  },
  zh: {
    open: '答题赢 {p}% 折扣',
    openClaimed: '你的 {p}% 折扣码',
    loadFailed: '答题加载失败，请重试。',
  },
  ja: {
    open: 'クイズに答えて {p}% オフ',
    openClaimed: 'あなたの {p}% 割引コード',
    loadFailed: 'クイズを読み込めませんでした。もう一度お試しください。',
  },
}

export type Language = keyof typeof OPENER_TEXTS

/** The QuizBuks language of a storefront language ({@code zh-CN}, {@code ja}, …): English when it has none. */
export function languageOf(locale: string | null | undefined): Language {
  const language = (locale ?? 'en').toLowerCase().split('-')[0]
  return language in OPENER_TEXTS ? language as Language : 'en'
}

/** A text with its {placeholders} filled. */
export function format(text: string, values: Record<string, string | number> = {}): string {
  return text.replace(/\{(\w+)\}/g, (all, key: string) => (key in values ? String(values[key]) : all))
}
