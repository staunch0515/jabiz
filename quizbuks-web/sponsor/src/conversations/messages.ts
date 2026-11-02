import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/**
 * The conversations' texts (Q82), part of the console's namespace `sponsor` (added here, next to the pages that use
 * them). The server words the rules a message breaks (`QB_CONVERSATION_*`).
 */
const en = {
  conversations: {
    tab: 'Conversations',
    intro: 'Takers who completed one of your quizzes can write to you about it (their result, their prize). Answer here; they are told in the app. Messages are plain text, and the platform can read them to settle disputes.',
    onlyWaiting: 'Only those waiting for my reply',
    empty: 'No conversations yet.',
    taker: 'A taker',
    closed: 'Closed',
    new: 'New',
    waiting: 'Waiting for reply',
    answered: 'Answered',
    columns: { taker: 'Taker', publication: 'Quiz', state: 'State', last: 'Last message', when: 'When' },
    back: 'All conversations',
    messages: 'Messages',
    you: 'You',
    erased: 'This message was deleted with its writer\'s account.',
    reply: 'Your reply',
    plainText: 'Plain text only: no web or e-mail addresses, phone numbers or other accounts.',
    send: 'Send reply',
    close: 'Close conversation',
    closedDone: 'The conversation is closed.',
    closedBy: {
      TAKER: 'The taker closed this conversation.',
      SPONSOR: 'You closed this conversation. The taker can open it again by writing.',
      SYSTEM: 'This conversation closed after a long time without messages.',
    },
    rewards: {
      title: 'Their rewards on this quiz',
      none: 'Nothing you settle yourself (the platform paid any Kudos).',
      kinds: { CASH: 'Cash reward', REDEEM: 'Prize to collect', DIGITAL: 'Digital prize' },
    },
  },
}

type Texts = typeof en

const zh: Texts = {
  conversations: {
    tab: '对话',
    intro: '完成过你的测验的答题者可以就该测验（成绩、奖品）给你留言。在这里回复，对方会在 App 中收到通知。消息只能是纯文本，平台可以查看对话以处理纠纷。',
    onlyWaiting: '只看等待我回复的',
    empty: '还没有对话。',
    taker: '答题者',
    closed: '已关闭',
    new: '新消息',
    waiting: '待回复',
    answered: '已回复',
    columns: { taker: '答题者', publication: '测验', state: '状态', last: '最后一条消息', when: '时间' },
    back: '全部对话',
    messages: '消息',
    you: '我',
    erased: '该消息已随发送人的账户删除。',
    reply: '你的回复',
    plainText: '只限纯文本：不能包含网址、邮箱、电话号码或其他平台的账号。',
    send: '发送回复',
    close: '关闭对话',
    closedDone: '对话已关闭。',
    closedBy: {
      TAKER: '答题者已关闭这个对话。',
      SPONSOR: '你已关闭这个对话。答题者再次留言即可重新开启。',
      SYSTEM: '这个对话长时间没有消息，已自动关闭。',
    },
    rewards: {
      title: '对方在此测验获得的奖励',
      none: '没有需要你自己结算的奖励（Kudos 由平台支付）。',
      kinds: { CASH: '现金奖励', REDEEM: '到店领取的奖品', DIGITAL: '电子奖品' },
    },
  },
}

const ja: Texts = {
  conversations: {
    tab: 'やり取り',
    intro: 'あなたのクイズに回答した人は、そのクイズ（結果や賞品）についてメッセージを送れます。ここで返信すると、相手にアプリで通知されます。メッセージはテキストのみで、トラブルの解決のため運営が確認することがあります。',
    onlyWaiting: '返信待ちのみ',
    empty: 'やり取りはまだありません。',
    taker: '回答者',
    closed: '終了',
    new: '新着',
    waiting: '返信待ち',
    answered: '返信済み',
    columns: { taker: '回答者', publication: 'クイズ', state: '状態', last: '最新のメッセージ', when: '日時' },
    back: 'すべてのやり取り',
    messages: 'メッセージ',
    you: 'あなた',
    erased: 'このメッセージは送信者のアカウントとともに削除されました。',
    reply: '返信',
    plainText: 'テキストのみ：URL、メールアドレス、電話番号、他サービスのアカウントは書けません。',
    send: '返信を送る',
    close: 'やり取りを終了',
    closedDone: 'やり取りを終了しました。',
    closedBy: {
      TAKER: '回答者がこのやり取りを終了しました。',
      SPONSOR: 'あなたがこのやり取りを終了しました。回答者がメッセージを送ると再開されます。',
      SYSTEM: '長期間メッセージがなかったため、このやり取りは終了しました。',
    },
    rewards: {
      title: 'このクイズでの報酬',
      none: 'あなたが精算する報酬はありません（Kudos は運営が支払います）。',
      kinds: { CASH: '現金報酬', REDEEM: '店頭で受け取る賞品', DIGITAL: 'デジタル賞品' },
    },
  },
}

export const conversationsMessages = { en, zh, ja }

addMessages(SPONSOR, conversationsMessages)
