// The maintenance page's texts (Q55), one set per language; {name} is the brand's name, {s} the seconds until the
// next check. The page shows one language only (page.mjs chooses it in the browser).

export const LANGUAGES = ['ja', 'zh', 'en']

export const MESSAGES = {
  ja: {
    lang: 'ja',
    title: '{name} — メンテナンス中',
    badge: 'メンテナンス中',
    heading: 'ただいまメンテナンス中です',
    lead: '{name} をより快適にご利用いただけるよう、システムを更新しています。ご不便をおかけして申し訳ありません。',
    auto: '準備ができしだい、このページは自動で元の画面に戻ります。閉じずにそのままお待ちください。',
    next: '{s} 秒後にもう一度確認します',
    checking: '確認しています…',
    back: '再開しました。元の画面に戻ります…',
    retry: '今すぐ確認',
    language: '言語',
  },
  zh: {
    lang: 'zh-CN',
    title: '{name} — 系统维护中',
    badge: '维护中',
    heading: '系统维护中',
    lead: '我们正在升级 {name}，让您用得更顺畅。给您带来不便，敬请谅解。',
    auto: '维护完成后，本页会自动回到您刚才打开的页面，请勿关闭。',
    next: '{s} 秒后再次检查',
    checking: '正在检查…',
    back: '已恢复，正在返回原页面…',
    retry: '立即检查',
    language: '语言',
  },
  en: {
    lang: 'en',
    title: '{name} — Maintenance',
    badge: 'Maintenance',
    heading: 'We’ll be right back',
    lead: '{name} is being updated to serve you better. Sorry for the interruption.',
    auto: 'This page returns to where you were by itself as soon as we are back. Keep it open.',
    next: 'Checking again in {s} s',
    checking: 'Checking…',
    back: 'We’re back. Taking you there…',
    retry: 'Check now',
    language: 'Language',
  },
}

export const LANGUAGE_NAMES = { ja: '日本語', zh: '中文', en: 'English' }
