import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/**
 * The frame's texts (F-F3), part of the Sponsor app's namespace `sponsor`: the areas' names (in the menu and before the
 * pages' titles), their short names in the phone's bottom menu, the tabs, "+ New", the avatar menu and the pages the
 * areas added. The areas' names are in docs/quizbuks/glossary.md (the requester may change them).
 */
const en = {
  shell: {
    menu: 'Areas',
    bottomMenu: 'Main areas',
    areas: {
      home: 'Home', quizzes: 'Quizzes', publications: 'Publications', prizes: 'Prizes',
      messages: 'Customers & messages', benefits: 'Sponsored benefits', money: 'Money', assets: 'Asset library',
      hub: 'Sponsor hub',
    },
    bottom: {
      home: 'Home', quizzes: 'Quizzes', publications: 'Publications', prizes: 'Redeem', messages: 'Messages',
      benefits: 'Benefits', money: 'Money', assets: 'Library', hub: 'Hub',
    },
    tabs: {
      home: { home: 'Home' },
      quizzes: { quizzes: 'All quizzes' },
      publications: { publications: 'All publications', map: 'Knowledge maps' },
      prizes: { redeem: 'In-store prizes', cash: 'Cash rewards', digital: 'Digital prizes' },
      messages: { conversations: 'Conversations', notices: 'Notices & announcements' },
      benefits: { catalog: 'Benefit catalog', offers: 'My benefits', conversations: 'Inquiries', purchases: 'Purchases' },
      money: { transactions: 'Transactions', wallet: 'Kudos wallet', account: 'Platform fee account', ai: 'AI credit',
        bills: 'Invoices' },
      assets: { all: 'All', media: 'Media', text: 'Texts', prize: 'Prizes', products: 'Products' },
      hub: { overview: 'Overview', basics: 'Basics', page: 'Public page', staff: 'Staff', 'mail-server': 'Mail server' },
    },
    create: {
      label: 'New',
      quiz: 'New quiz',
      publication: 'New publication',
      notice: 'Send a notice',
      importSettings: 'Import publication settings',
      asset: 'New asset in the library',
    },
    avatar: {
      label: 'Your account: {{name}}',
      fallback: 'Your account',
      account: 'Account & security',
      appearance: 'Appearance',
      appearances: { light: 'Light', dark: 'Dark', system: 'As the device' },
      language: 'Language',
      help: 'Help',
      signOut: 'Sign out',
    },
    page: { edit: 'Your page', stats: 'Statistics', label: 'Public page' },
    account: {
      title: 'Platform fee account',
      intro: 'What pays the platform\'s fees: the cash account (top-ups to cash and the entry fees your quizzes collect).',
      topUp: 'Top up',
    },
    bills: { title: 'Invoices', intro: 'The platform\'s invoices to you and how to pay them.' },
    ai: { title: 'AI credit', intro: 'How much of the AI you used this month.' },
    benefitsEntry: { title: 'Sponsored benefits', hint: 'Offer your benefits to other Sponsors\' Takers, or buy theirs for yours. The area opens once you hold a benefit role; see how to get one.', link: 'How to open them' },
    conversations: { title: 'Conversations' },
    notices: { title: 'Notices & announcements' },
  },
}

const zh: typeof en = {
  shell: {
    menu: '区域',
    bottomMenu: '常用区域',
    areas: {
      home: '首页', quizzes: 'Quiz', publications: '发布', prizes: '奖品与兑换', messages: '客户与消息',
      benefits: '合作权益', money: '资金', assets: '资源库', hub: '发起方中心',
    },
    bottom: {
      home: '首页', quizzes: 'Quiz', publications: '发布', prizes: '兑换', messages: '消息', benefits: '权益',
      money: '资金', assets: '资源库', hub: '发起方中心',
    },
    tabs: {
      home: { home: '首页' },
      quizzes: { quizzes: '全部 Quiz' },
      publications: { publications: '全部发布', map: '知识结构图' },
      prizes: { redeem: '到店兑换', cash: '现金奖励', digital: '电子奖品' },
      messages: { conversations: '对话', notices: '通知与公告' },
      benefits: { catalog: '权益目录', offers: '我的权益', conversations: '洽谈', purchases: '采购单' },
      money: { transactions: '交易', wallet: '学分钱包', account: '平台费账户', ai: 'AI 额度', bills: '账单' },
      assets: { all: '全部', media: '素材', text: '文字', prize: '奖品', products: '商品' },
      hub: { overview: '概况', basics: '基本信息', page: '公开主页', staff: '店员', 'mail-server': '邮件服务器' },
    },
    create: {
      label: '新建',
      quiz: '新建 Quiz',
      publication: '新建发布',
      notice: '发通知',
      importSettings: '导入发布设置',
      asset: '在资源库中新建',
    },
    avatar: {
      label: '你的账户：{{name}}',
      fallback: '你的账户',
      account: '账户与安全',
      appearance: '外观',
      appearances: { light: '浅色', dark: '深色', system: '跟随设备' },
      language: '语言',
      help: '帮助',
      signOut: '退出',
    },
    page: { edit: '你的主页', stats: '统计', label: '公开主页' },
    account: {
      title: '平台费账户',
      intro: '用来支付平台费用的账户：现金账户（充值到现金的部分，以及你的 Quiz 收到的入场费）。',
      topUp: '充值',
    },
    bills: { title: '账单', intro: '平台给你的账单与付款方式。' },
    ai: { title: 'AI 额度', intro: '你本月使用 AI 的情况。' },
    benefitsEntry: { title: '合作权益', hint: '把你的权益提供给其他发起方的学者，或为你的学者购买他们的权益。取得权益角色后菜单中会出现这个区域；看看怎样取得。', link: '怎样开通' },
    conversations: { title: '对话' },
    notices: { title: '通知与公告' },
  },
}

const ja: typeof en = {
  shell: {
    menu: 'エリア',
    bottomMenu: 'よく使うエリア',
    areas: {
      home: 'ホーム', quizzes: 'クイズ', publications: '公開', prizes: '景品と引き換え', messages: 'お客様とメッセージ',
      benefits: '協賛特典', money: '資金', assets: 'リソースライブラリ', hub: '主催者センター',
    },
    bottom: {
      home: 'ホーム', quizzes: 'クイズ', publications: '公開', prizes: '引き換え', messages: 'メッセージ', benefits: '特典',
      money: '資金', assets: 'ライブラリ', hub: 'センター',
    },
    tabs: {
      home: { home: 'ホーム' },
      quizzes: { quizzes: 'すべてのクイズ' },
      publications: { publications: 'すべての公開', map: '知識マップ' },
      prizes: { redeem: '店頭での引き換え', cash: '現金報酬', digital: 'デジタル賞品' },
      messages: { conversations: 'やり取り', notices: 'お知らせ' },
      benefits: { catalog: '特典カタログ', offers: '自社の特典', conversations: '商談', purchases: '購入' },
      money: { transactions: '取引', wallet: 'クドスウォレット', account: 'プラットフォーム利用料アカウント', ai: 'AI クレジット',
        bills: '請求書' },
      assets: { all: 'すべて', media: '素材', text: 'テキスト', prize: '賞品', products: '商品' },
      hub: { overview: '概要', basics: '基本情報', page: '公開ページ', staff: 'スタッフ', 'mail-server': 'メールサーバー' },
    },
    create: {
      label: '新規作成',
      quiz: '新しいクイズ',
      publication: '新しい公開',
      notice: 'お知らせを送る',
      importSettings: '公開の設定を読み込む',
      asset: 'ライブラリに新規作成',
    },
    avatar: {
      label: 'あなたのアカウント：{{name}}',
      fallback: 'あなたのアカウント',
      account: 'アカウントとセキュリティ',
      appearance: '表示',
      appearances: { light: 'ライト', dark: 'ダーク', system: '端末の設定' },
      language: '言語',
      help: 'ヘルプ',
      signOut: 'ログアウト',
    },
    page: { edit: 'あなたのページ', stats: '統計', label: '公開ページ' },
    account: {
      title: 'プラットフォーム利用料アカウント',
      intro: 'プラットフォームの利用料を支払うアカウント：現金アカウント（現金へのチャージと、クイズの参加費）。',
      topUp: 'チャージ',
    },
    bills: { title: '請求書', intro: 'プラットフォームからの請求書と支払い方法。' },
    ai: { title: 'AI クレジット', intro: '今月の AI の利用状況。' },
    benefitsEntry: { title: '協賛特典', hint: '自社の特典をほかの主催者のテイカーに提供したり、ほかの主催者の特典を自社のテイカーのために購入したりできます。特典の権限を得るとメニューにこのエリアが表示されます。権限の得かたをご覧ください。', link: '開きかた' },
    conversations: { title: 'やり取り' },
    notices: { title: 'お知らせ' },
  },
}

export const shellMessages = { en, zh, ja }

addMessages(SPONSOR, shellMessages)
