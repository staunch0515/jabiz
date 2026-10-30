import { addMessages } from '@jabiz/client'
import { APP } from '../messages'
import { BRAND } from '../qb/brand'

/**
 * The inbox's texts, part of the app's namespace `app` (added here, next to the pages that use them). The messages
 * themselves come from the server in every language (Q7-15); the page shows the interface's.
 */
const en = {
  inbox: {
    nav: 'Inbox',
    navUnread: 'Inbox, {{count}} unread',
    title: 'Inbox',
    search: 'Search messages',
    searchButton: 'Search',
    show: 'Show',
    categories: { all: 'All', SYSTEM: 'System', PROMO: 'Promotions', REWARD: 'Rewards', REMINDER: 'Reminders' },
    unreadOnly: 'Unread only',
    dates: 'Dates',
    from: 'From',
    to: 'To',
    readAll: 'Mark all as read',
    readAllDone: 'Everything is marked as read.',
    settings: 'Settings',
    promotions: 'Show promotions',
    promotionsHint: `Notices from sponsors and ${BRAND.name} offers. System messages, rewards and reminders always come.`,
    unread: 'Unread',
    fromSponsor: 'From {{name}}',
    fromSponsorUnknown: 'From a sponsor',
    fromQuizbuks: `From ${BRAND.name}`,
    emptyTitle: 'Nothing here yet',
    empty: 'Rewards, transfers and news appear here.',
    noMatch: 'No message matches.',
    more: 'Show more',
    open: 'Open',
    back: 'All messages',
    gone: 'This message is no longer in your inbox.',
    viewQuiz: 'View quiz',
    action: { RESULT: 'See the result', WALLET: 'Open the wallet', PAYOUT: 'Open the payout account' },
    until: 'Until {{time}}',
  },
}

type Texts = typeof en

const zh: Texts = {
  inbox: {
    nav: '收件箱',
    navUnread: '收件箱，{{count}} 条未读',
    title: '收件箱',
    search: '搜索消息',
    searchButton: '搜索',
    show: '显示',
    categories: { all: '全部', SYSTEM: '系统', PROMO: '推广', REWARD: '奖励', REMINDER: '提醒' },
    unreadOnly: '只看未读',
    dates: '日期',
    from: '从',
    to: '到',
    readAll: '全部标为已读',
    readAllDone: '已全部标为已读。',
    settings: '设置',
    promotions: '接收推广',
    promotionsHint: `商家的通知与 ${BRAND.name} 的推广。系统消息、奖励与提醒总会收到。`,
    unread: '未读',
    fromSponsor: '来自 {{name}}',
    fromSponsorUnknown: '来自商家',
    fromQuizbuks: `来自 ${BRAND.name}`,
    emptyTitle: '还没有消息',
    empty: '奖励、转账与新闻会显示在这里。',
    noMatch: '没有符合条件的消息。',
    more: '显示更多',
    open: '打开',
    back: '全部消息',
    gone: '这条消息已不在你的收件箱中。',
    viewQuiz: '查看 Quiz',
    action: { RESULT: '查看结果', WALLET: '打开钱包', PAYOUT: '打开收款账户' },
    until: '至 {{time}}',
  },
}

const ja: Texts = {
  inbox: {
    nav: '受信箱',
    navUnread: '受信箱（未読 {{count}} 件）',
    title: '受信箱',
    search: 'メッセージを検索',
    searchButton: '検索',
    show: '表示',
    categories: { all: 'すべて', SYSTEM: 'システム', PROMO: 'プロモーション', REWARD: '報酬', REMINDER: 'リマインダー' },
    unreadOnly: '未読のみ',
    dates: '期間',
    from: '開始日',
    to: '終了日',
    readAll: 'すべて既読にする',
    readAllDone: 'すべて既読にしました。',
    settings: '設定',
    promotions: 'プロモーションを受け取る',
    promotionsHint: `スポンサーからの通知と ${BRAND.name} のおすすめです。システムのお知らせ、報酬、リマインダーは常に届きます。`,
    unread: '未読',
    fromSponsor: '{{name}} から',
    fromSponsorUnknown: 'スポンサーから',
    fromQuizbuks: `${BRAND.name} から`,
    emptyTitle: 'まだメッセージはありません',
    empty: '報酬、送金、お知らせがここに表示されます。',
    noMatch: '該当するメッセージがありません。',
    more: 'さらに表示',
    open: '開く',
    back: 'メッセージ一覧',
    gone: 'このメッセージは受信箱にありません。',
    viewQuiz: 'クイズを見る',
    action: { RESULT: '結果を見る', WALLET: 'ウォレットを開く', PAYOUT: '受取口座を開く' },
    until: '{{time}} まで',
  },
}

export const inboxMessages = { en, zh, ja }

addMessages(APP, inboxMessages)
