import { addMessages } from '@jabiz/client'
import { contentEn, contentJa, contentZh } from './content/messages'
import './qb/messages'

/**
 * The sponsor console's own texts (namespace `sponsor`); shared ones are in `qb`, the sign-in pages speak the
 * platform's `auth`. The console ships in en, zh and ja (docs/quizbuks/plans/QP-pages.md, Q-4).
 */
export const SPONSOR = 'sponsor'

const en = {
  ...contentEn,
  brand: 'QuizBuks Sponsor',
  nav: { label: 'Sponsor console', dashboard: 'Dashboard', quizzes: 'Quizzes', onboarding: 'Sponsor profile', account: 'Account' },
  dashboard: {
    title: 'Dashboard',
    none: 'Before you publish quizzes, tell us about your company: we review every sponsor.',
    pending: 'Your sponsor application is in review. We will mail you when it is decided.',
    rejected: 'Your sponsor application was sent back. See why, change it and apply again.',
    approved: 'Your sponsor account is approved. Your quizzes and publications will appear here.',
    toOnboarding: 'Open the sponsor profile',
  },
  onboarding: {
    title: 'Sponsor profile',
    intro: 'Your name and company as takers will see them. Applications are reviewed before you can publish.',
    status: {
      NONE: 'Not applied yet',
      PENDING: 'In review',
      APPROVED: 'Approved',
      REJECTED: 'Sent back',
    },
    statusLabel: 'Status',
    submittedAt: 'Applied {{time}}',
    approvedAt: 'Approved {{time}}',
    rejectReason: 'Why it was sent back',
    displayName: 'Your name',
    company: 'Company or brand',
    intro2: 'Introduction',
    introHint: 'Up to 2,000 characters. Markdown is allowed (no HTML).',
    avatar: 'Logo or avatar',
    avatarHint: 'JPEG or PNG, at most 2 MB. Location data in photos is removed.',
    avatarAlt: 'The current logo',
    uploadAvatar: 'Upload an image',
    removeAvatar: 'Remove the image',
    terms: 'I accept the QuizBuks terms for sponsors.',
    apply: 'Apply',
    applyAgain: 'Apply again',
    save: 'Save',
    applied: 'Sent for review.',
    approvedNow: 'Approved: you can publish quizzes now.',
    saved: 'Saved.',
  },
}

const zh: typeof en = {
  ...contentZh,
  brand: 'QuizBuks 商家后台',
  nav: { label: '商家后台', dashboard: '概览', quizzes: 'Quiz', onboarding: '商家资料', account: '账户' },
  dashboard: {
    title: '概览',
    none: '发布 Quiz 之前，请先填写公司信息：每个商家都需经过审核。',
    pending: '你的商家申请正在审核中。审核完成后我们会发邮件通知你。',
    rejected: '你的商家申请已被驳回。请查看原因，修改后重新申请。',
    approved: '你的商家账户已通过审核。你的 Quiz 与发布会显示在这里。',
    toOnboarding: '打开商家资料',
  },
  onboarding: {
    title: '商家资料',
    intro: '答题人看到的你的名字与公司。申请经审核通过后才能发布。',
    status: {
      NONE: '尚未申请',
      PENDING: '审核中',
      APPROVED: '已通过',
      REJECTED: '已驳回',
    },
    statusLabel: '状态',
    submittedAt: '申请于 {{time}}',
    approvedAt: '通过于 {{time}}',
    rejectReason: '驳回原因',
    displayName: '你的名字',
    company: '公司或品牌',
    intro2: '简介',
    introHint: '最多 2,000 字，可使用 Markdown（不支持 HTML）。',
    avatar: '标志或头像',
    avatarHint: 'JPEG 或 PNG，不超过 2 MB。照片中的位置信息会被去除。',
    avatarAlt: '当前的标志',
    uploadAvatar: '上传图片',
    removeAvatar: '移除图片',
    terms: '我同意 QuizBuks 商家条款。',
    apply: '申请',
    applyAgain: '重新申请',
    save: '保存',
    applied: '已提交审核。',
    approvedNow: '已通过：现在可以发布 Quiz 了。',
    saved: '已保存。',
  },
}

const ja: typeof en = {
  ...contentJa,
  brand: 'QuizBuks スポンサー',
  nav: { label: 'スポンサー管理画面', dashboard: 'ダッシュボード', quizzes: 'クイズ', onboarding: 'スポンサー情報', account: 'アカウント' },
  dashboard: {
    title: 'ダッシュボード',
    none: 'クイズを公開する前に、会社について教えてください。すべてのスポンサーを審査します。',
    pending: 'スポンサー申請は審査中です。結果はメールでお知らせします。',
    rejected: 'スポンサー申請は差し戻されました。理由を確認し、修正して再申請してください。',
    approved: 'スポンサーアカウントは承認されました。クイズと公開がここに表示されます。',
    toOnboarding: 'スポンサー情報を開く',
  },
  onboarding: {
    title: 'スポンサー情報',
    intro: '回答者に表示されるお名前と会社名です。申請が承認されると公開できます。',
    status: {
      NONE: '未申請',
      PENDING: '審査中',
      APPROVED: '承認済み',
      REJECTED: '差し戻し',
    },
    statusLabel: '状態',
    submittedAt: '{{time}} に申請',
    approvedAt: '{{time}} に承認',
    rejectReason: '差し戻しの理由',
    displayName: 'お名前',
    company: '会社名またはブランド',
    intro2: '紹介文',
    introHint: '2,000 文字まで。Markdown を使えます（HTML は不可）。',
    avatar: 'ロゴまたはアバター',
    avatarHint: 'JPEG または PNG、2 MB まで。写真の位置情報は削除されます。',
    avatarAlt: '現在のロゴ',
    uploadAvatar: '画像をアップロード',
    removeAvatar: '画像を削除',
    terms: 'QuizBuks のスポンサー規約に同意します。',
    apply: '申請する',
    applyAgain: '再申請する',
    save: '保存',
    applied: '審査に送信しました。',
    approvedNow: '承認されました。クイズを公開できます。',
    saved: '保存しました。',
  },
}

addMessages(SPONSOR, { en, zh, ja })
