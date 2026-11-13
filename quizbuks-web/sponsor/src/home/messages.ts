import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/**
 * The home's "to do" texts (F-F3b), part of the Sponsor app's namespace `sponsor`. Each item's text reads the same for
 * one and for many: the count is shown beside it, so there are no plural forms.
 */
const en = {
  home: {
    todo: {
      title: 'To do',
      label: 'Things that need you',
      empty: 'All done. Nothing needs you right now.',
      go: 'Open',
      items: {
        claimsToConfirm: 'Prize claims waiting for you to confirm',
        digitalToSend: 'Digital prizes to send or in dispute',
        unreadConversations: 'Conversations with a new message from a Taker',
        fundsShort: 'Publications short of budget',
        rewardsWaiting: 'Rewards waiting for a top-up',
        invoicesOverdue: 'Overdue invoices',
        feeInvoicesUncovered: 'Open invoices the platform fee account cannot cover',
        creditsExpiring: 'Granted credit about to expire',
        exposureLow: 'Publications running low on exposures',
        onboardingInReview: 'Your Sponsor application is in review',
        publicationsInReview: 'Publications in review',
        pageInReview: 'Your public page is in review',
        sharedVideosInReview: 'Shared videos in review',
        videoUsesToConfirm: 'Uses of my shared videos waiting for your confirmation',
        profileMissing: 'Parts of your Sponsor hub still to fill in',
      },
    },
  },
}

const zh: typeof en = {
  home: {
    todo: {
      title: '待我处理',
      label: '需要你处理的事',
      empty: '都处理完了，现在没有要处理的事。',
      go: '去处理',
      items: {
        claimsToConfirm: '等你确认的领取',
        digitalToSend: '待发送或有争议的电子奖品',
        unreadConversations: '学者有新消息的对话',
        fundsShort: '预算不足的发布',
        rewardsWaiting: '等待充值的奖励',
        invoicesOverdue: '逾期未付的账单',
        feeInvoicesUncovered: '平台费账户不够支付的账单',
        creditsExpiring: '即将到期的赠送额度',
        exposureLow: '曝光即将用完的发布',
        onboardingInReview: '你的发起方申请正在审核',
        publicationsInReview: '审核中的发布',
        pageInReview: '审核中的公开主页',
        sharedVideosInReview: '审核中的共享视频',
        videoUsesToConfirm: '等你确认的我的视频使用台账',
        profileMissing: '发起方中心里还没填完的部分',
      },
    },
  },
}

const ja: typeof en = {
  home: {
    todo: {
      title: '対応が必要なこと',
      label: '対応が必要なこと',
      empty: 'すべて対応済みです。今、対応が必要なことはありません。',
      go: '開く',
      items: {
        claimsToConfirm: '確認待ちの受け取り',
        digitalToSend: '送付待ちまたは異議ありのデジタル賞品',
        unreadConversations: 'テイカーから新しいメッセージが届いたやり取り',
        fundsShort: '予算が足りない公開',
        rewardsWaiting: 'チャージ待ちの報酬',
        invoicesOverdue: '支払期限を過ぎた請求書',
        feeInvoicesUncovered: 'プラットフォーム利用料アカウントでは支払えない請求書',
        creditsExpiring: 'まもなく期限が切れる付与クレジット',
        exposureLow: '表示回数がまもなくなくなる公開',
        onboardingInReview: '主催者申請は審査中です',
        publicationsInReview: '審査中の公開',
        pageInReview: '公開ページは審査中です',
        sharedVideosInReview: '審査中の共有動画',
        videoUsesToConfirm: '確認待ちの自分の共有動画の利用台帳',
        profileMissing: '主催者センターで未入力の項目',
      },
    },
  },
}

export const homeMessages = { en, zh, ja }

addMessages(SPONSOR, homeMessages)
