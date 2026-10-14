import { addMessages } from '@jabiz/client'

/**
 * The texts both QuizBuks front ends share (namespace `qb`); each SPA has its own besides (`src/messages.ts`). The
 * sign-in pages speak the platform's namespace `auth`, and the server translates its own messages (`QB_*` problems,
 * dictionaries) by the request's language.
 */
export const QB = 'qb'

const en = {
  // The product's name, as the server's app.name (the pages before sign-in cannot ask the server for it).
  brand: 'QuizBuks',
  signOut: 'Sign out',
  notFound: 'This page does not exist.',
  failed: 'Something went wrong. Please try again.',
  retry: 'Try again',
  saved: 'Saved.',
  verifyFirst: 'Verify your e-mail address first: we sent you a link. Saving works once it is verified.',
  checkAgain: 'I have verified it',
  stillUnverified: 'Your address is not verified yet. Open the link in the mail, then try again.',
  account: {
    title: 'Account',
    email: 'E-mail address',
    emailHint: 'Your e-mail address cannot be changed here. Contact us if it needs to change.',
    mfa: 'Two-step verification',
  },
  signIns: {
    title: 'Recent sign-ins',
    time: 'Time',
    entry: 'Where',
    outcome: 'Result',
    address: 'IP address',
    device: 'Device',
    empty: 'No sign-ins yet.',
    entries: { app: 'App', sponsor: 'Sponsor console', admin: 'Administration' },
    unknownDevice: 'Unknown device',
  },
}

const zh: typeof en = {
  brand: 'QuizBuks',
  signOut: '退出登录',
  notFound: '页面不存在。',
  failed: '出错了，请重试。',
  retry: '重试',
  saved: '已保存。',
  verifyFirst: '请先验证邮箱：我们已向您发送了链接。验证之后才能保存。',
  checkAgain: '我已验证',
  stillUnverified: '邮箱尚未验证。请打开邮件中的链接后再试。',
  account: {
    title: '账户',
    email: '邮箱',
    emailHint: '邮箱不能在这里修改。如需修改，请与我们联系。',
    mfa: '两步验证',
  },
  signIns: {
    title: '最近的登录',
    time: '时间',
    entry: '登录到',
    outcome: '结果',
    address: 'IP 地址',
    device: '设备',
    empty: '还没有登录记录。',
    entries: { app: 'App', sponsor: '商家后台', admin: '管理后台' },
    unknownDevice: '未知设备',
  },
}

const ja: typeof en = {
  brand: 'QuizBuks',
  signOut: 'ログアウト',
  notFound: 'このページは存在しません。',
  failed: 'エラーが発生しました。もう一度お試しください。',
  retry: '再試行',
  saved: '保存しました。',
  verifyFirst: 'まずメールアドレスを確認してください（リンクをお送りしました）。確認後に保存できます。',
  checkAgain: '確認しました',
  stillUnverified: 'メールアドレスはまだ確認されていません。メールのリンクを開いてから、もう一度お試しください。',
  account: {
    title: 'アカウント',
    email: 'メールアドレス',
    emailHint: 'メールアドレスはここでは変更できません。変更が必要な場合はお問い合わせください。',
    mfa: '2 段階認証',
  },
  signIns: {
    title: '最近のログイン',
    time: '日時',
    entry: 'ログイン先',
    outcome: '結果',
    address: 'IP アドレス',
    device: 'デバイス',
    empty: 'ログイン記録はまだありません。',
    entries: { app: 'アプリ', sponsor: 'スポンサー管理画面', admin: '管理画面' },
    unknownDevice: '不明なデバイス',
  },
}

addMessages(QB, { en, zh, ja })
