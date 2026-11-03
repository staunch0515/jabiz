import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/** Q87b: the platform fees' texts on the wallet page (namespace `sponsor`, key `fees`). */
const en = {
  fees: {
    title: 'Platform fees and invoices',
    intro: 'Platform fees are paid from your cash account. Each month brings an invoice of the fees of the month before.',
    mode: 'Billing',
    modes: { PREPAID: 'Prepaid: paid from your cash account when charged', POSTPAID: 'Postpaid: paid by invoice' },
    credit: 'Credit',
    unpaid: 'Unpaid',
    overdue: 'An invoice is overdue. Buying Kudos and submitting new publications are stopped until it is paid.',
    owed: 'Fees of {{amount}} are not yet on an invoice. You can pay them now from your cash account.',
    payOwed: 'Pay now',
    paid: 'Paid.',
    invoices: 'Invoices',
    invoicesTable: 'Your invoices',
    noInvoices: 'No invoices yet.',
    number: 'Number',
    period: 'Month',
    total: 'Total',
    due: 'Due',
    dueOn: 'Due date',
    status: 'Status',
    statuses: { PAID: 'Paid', ISSUED: 'Unpaid', OVERDUE: 'Overdue', VOID: 'Void' },
    pay: 'Pay',
    download: 'Invoice',
  },
}

const zh: typeof en = {
  fees: {
    title: '平台费与账单',
    intro: '平台费从现金账户支付。每月开具上个月平台费的账单。',
    mode: '计费方式',
    modes: { PREPAID: '预付：记费时从现金账户支付', POSTPAID: '后付：按账单支付' },
    credit: '信用额',
    unpaid: '未付',
    overdue: '有账单已逾期。付清之前不能换学分，也不能提交新的发布。',
    owed: '有 {{amount}} 的平台费尚未出账单，可以现在从现金账户支付。',
    payOwed: '现在支付',
    paid: '已支付。',
    invoices: '账单',
    invoicesTable: '您的账单',
    noInvoices: '还没有账单。',
    number: '账单号',
    period: '月份',
    total: '合计',
    due: '应付',
    dueOn: '到期日',
    status: '状态',
    statuses: { PAID: '已付', ISSUED: '待付', OVERDUE: '逾期', VOID: '已作废' },
    pay: '支付',
    download: '账单',
  },
}

const ja: typeof en = {
  fees: {
    title: '利用料と請求書',
    intro: '利用料は現金口座からお支払いいただきます。毎月、前月分の請求書を発行します。',
    mode: '支払方法',
    modes: { PREPAID: '前払い：計上時に現金口座から支払い', POSTPAID: '後払い：請求書で支払い' },
    credit: '与信額',
    unpaid: '未払い',
    overdue: '支払期限を過ぎた請求書があります。お支払いまでクドスの購入と新しい公開の申請はできません。',
    owed: 'まだ請求書になっていない利用料 {{amount}} があります。今すぐ現金口座からお支払いいただけます。',
    payOwed: '今すぐ支払う',
    paid: 'お支払いが完了しました。',
    invoices: '請求書',
    invoicesTable: '請求書の一覧',
    noInvoices: '請求書はまだありません。',
    number: '番号',
    period: '対象月',
    total: '合計',
    due: '支払額',
    dueOn: '支払期限',
    status: '状態',
    statuses: { PAID: '支払済み', ISSUED: '未払い', OVERDUE: '期限超過', VOID: '無効' },
    pay: '支払う',
    download: '請求書',
  },
}

export const feeMessages = { en, zh, ja }

addMessages(SPONSOR, feeMessages)
