import { addMessages } from '@jabiz/client'
import { APP } from '../messages'

/**
 * The taker app's frame texts (F-F4, docs/quizbuks/plans/impl/F-F4.md section 5.5), part of the app's namespace
 * `app`: the five areas, their tabs, the prizes tab, the pass and "Me". The names follow docs/quizbuks/glossary.md
 * ("学者 App 的区域与条目"); the Sponsor's operator is called 運営パートナー in Japanese wherever a text names it.
 */
const en = {
  frame: {
    areas: { home: 'Home', history: 'Answered', scan: 'Scan', messages: 'Messages', me: 'Me' },
    sideNav: 'Main menu',
    messagesUnread: 'Messages, {{count}} unread',
    historyToCollect: 'Answered, {{count}} to collect',
    tabsLabel: { history: 'Answered', scan: 'Scan', messages: 'Messages' },
    tabs: { answered: 'Answered', prizes: 'To collect', scan: 'Scan', pass: 'Show my pass', inbox: 'Inbox', conversations: 'Conversations' },
  },
  prizes: {
    intro: 'Prizes you won that still need something: to collect at a Sponsor\'s place, to confirm or to settle.',
    show: 'Show',
    waiting: 'Waiting',
    all: 'All',
    kinds: { REDEEM: 'Collect at the Sponsor\'s place', CASH: 'Cash from the Sponsor', DIGITAL: 'Digital prize' },
    cash: 'Cash prize worth {{amount}}',
    digitalDue: 'Digital prize worth {{amount}}: the Sponsor sends it',
    collectBy: 'Collect by {{time}}',
    open: 'Open',
    emptyTitle: 'Nothing to collect',
    empty: 'Prizes you win appear here until you have collected or settled them.',
    emptyAll: 'You have won no prize yet.',
    findQuiz: 'Find a quiz',
  },
  pass: {
    mine: {
      intro: 'Prizes to collect at a Sponsor\'s place. Show the pass at the counter; the staff scan it or type the six digits.',
      button: 'Show my pass ({{count}})',
      emptyTitle: 'No pass to show',
      empty: 'When you win a prize to collect at a Sponsor\'s place, its pass is here.',
      toCollect: 'See what is waiting',
    },
  },
  me: {
    numbersLabel: 'Your numbers',
    numbers: { kudos: 'Kudos', prizes: 'Prizes to collect', certificates: 'Certificates' },
    soon: 'Coming soon',
    owned: 'What I have',
    settings: 'Settings',
    rows: {
      prizes: 'Prizes',
      kudos: 'Kudos details',
      prizesHint: 'What you won, and what is left to collect',
      kudosHint: 'Balance, Kudos by method, transactions, payout',
    },
  },
}

const zh = {
  frame: {
    areas: { home: '首页', history: '已答', scan: '扫一扫', messages: '消息', me: '我的' },
    sideNav: '主菜单',
    messagesUnread: '消息，{{count}} 条未读',
    historyToCollect: '已答，{{count}} 个待领奖',
    tabsLabel: { history: '已答', scan: '扫一扫', messages: '消息' },
    tabs: { answered: '已答', prizes: '待领奖', scan: '扫码', pass: '出示领奖码', inbox: '收件箱', conversations: '对话' },
  },
  prizes: {
    intro: '你赢得、还需要处理的奖品：到发起方处领取、确认收到或等待结算。',
    show: '显示',
    waiting: '待领',
    all: '全部',
    kinds: { REDEEM: '到发起方处领取', CASH: '发起方现金', DIGITAL: '电子奖品' },
    cash: '价值 {{amount}} 的现金奖品',
    digitalDue: '价值 {{amount}} 的电子奖品：由发起方发送',
    collectBy: '领取期限 {{time}}',
    open: '查看',
    emptyTitle: '没有待领奖品',
    empty: '你赢得的奖品会出现在这里，直到领取或处理完毕。',
    emptyAll: '你还没有赢得奖品。',
    findQuiz: '找一个 Quiz',
  },
  pass: {
    mine: {
      intro: '到发起方处领取的奖品。在柜台出示领奖码，店员扫码或输入六位数字。',
      button: '出示领奖码（{{count}}）',
      emptyTitle: '没有可出示的领奖码',
      empty: '赢得到发起方处领取的奖品后，它的领奖码会在这里。',
      toCollect: '查看待领奖品',
    },
  },
  me: {
    numbersLabel: '你的数字',
    numbers: { kudos: '学分', prizes: '待领奖品', certificates: '证书' },
    soon: '即将开放',
    owned: '我拥有的',
    settings: '设置',
    rows: {
      prizes: '奖品',
      kudos: '学分明细',
      prizesHint: '赢得的奖品，以及还要领取的',
      kudosHint: '余额、按方式的学分、交易、收款',
    },
  },
}

const ja = {
  frame: {
    areas: { home: 'ホーム', history: '回答済み', scan: 'スキャン', messages: 'メッセージ', me: 'マイページ' },
    sideNav: 'メインメニュー',
    messagesUnread: 'メッセージ、未読{{count}}件',
    historyToCollect: '回答済み、受け取り待ち{{count}}件',
    tabsLabel: { history: '回答済み', scan: 'スキャン', messages: 'メッセージ' },
    tabs: { answered: '回答済み', prizes: '受け取り待ち', scan: 'スキャン', pass: '引き換えパスを表示', inbox: '受信箱', conversations: '会話' },
  },
  prizes: {
    intro: '獲得した景品のうち、対応が必要なもの：主催者の店頭での受け取り、受領の確認、精算待ち。',
    show: '表示',
    waiting: '受け取り待ち',
    all: 'すべて',
    kinds: { REDEEM: '主催者の店頭で受け取り', CASH: '主催者からの現金', DIGITAL: 'デジタル景品' },
    cash: '{{amount}}相当の現金景品',
    digitalDue: '{{amount}}相当のデジタル景品：主催者が送ります',
    collectBy: '受け取り期限 {{time}}',
    open: '開く',
    emptyTitle: '受け取り待ちの景品はありません',
    empty: '獲得した景品は、受け取りや対応が終わるまでここに表示されます。',
    emptyAll: 'まだ景品を獲得していません。',
    findQuiz: 'クイズを探す',
  },
  pass: {
    mine: {
      intro: '主催者の店頭で受け取る景品です。カウンターで引き換えパスを見せてください。スタッフがコードを読み取るか、6桁の数字を入力します。',
      button: '引き換えパスを表示（{{count}}）',
      emptyTitle: '表示できる引き換えパスはありません',
      empty: '主催者の店頭で受け取る景品を獲得すると、その引き換えパスがここに表示されます。',
      toCollect: '受け取り待ちを見る',
    },
  },
  me: {
    numbersLabel: 'あなたの数字',
    numbers: { kudos: 'クドス', prizes: '受け取り待ちの景品', certificates: '証明書' },
    soon: '近日公開',
    owned: '持っているもの',
    settings: '設定',
    rows: {
      prizes: '景品',
      kudos: 'クドスの詳細',
      prizesHint: '獲得した景品と、受け取りが残っているもの',
      kudosHint: '残高、方法別のクドス、取引、受け取り口座',
    },
  },
}

addMessages(APP, { en, zh, ja })
