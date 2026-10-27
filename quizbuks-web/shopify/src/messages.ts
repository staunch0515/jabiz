import { addMessages } from '@jabiz/client'

/** The embedded pages' texts (namespace `shopify`), in en, zh and ja as the rest of QuizBuks. */
export const SHOPIFY = 'shopify'

const en = {
  title: 'QuizBuks for Shopify',
  starting: 'Opening QuizBuks…',
  notEmbedded: 'Open QuizBuks from your store\'s Shopify admin (Apps → QuizBuks).',
  failed: 'QuizBuks could not open: {{reason}}',
  retry: 'Try again',
  shop: 'Store',
  status: { ACTIVE: 'Installed', UNINSTALLED: 'Uninstalled' },
  you: { ADMIN: 'You are the admin of this store in QuizBuks.', MEMBER: 'You are a member of this store in QuizBuks.' },
  reinstalled: 'Welcome back: your store and its members are as you left them.',
  connection: {
    title: 'Connection to Shopify',
    NONE: 'Not connected',
    CONNECTED: 'Connected',
    RECONNECT: 'Connect again',
    explain: 'QuizBuks asks Shopify for access to your store when it first needs it.',
    connect: 'Connect',
    check: 'Check the connection',
    checked: 'Checked: the connection works.',
    lost: 'Shopify no longer accepts the connection: connect again.',
  },
  members: {
    title: 'Members',
    staff: 'Staff ID',
    role: 'Role',
    joined: 'Joined',
    actions: 'Actions',
    ADMIN: 'Admin',
    MEMBER: 'Member',
    me: '(you)',
    makeAdmin: 'Make admin',
    confirm: 'Hand the admin role to staff member {{staff}}? You become a member.',
    transferred: 'The admin role was handed on.',
  },
}

const zh: typeof en = {
  title: 'QuizBuks for Shopify',
  starting: '正在打开 QuizBuks…',
  notEmbedded: '请从店铺的 Shopify 后台打开 QuizBuks（应用 → QuizBuks）。',
  failed: 'QuizBuks 无法打开：{{reason}}',
  retry: '重试',
  shop: '店铺',
  status: { ACTIVE: '已安装', UNINSTALLED: '已卸载' },
  you: { ADMIN: '你是本店在 QuizBuks 中的主管理员。', MEMBER: '你是本店在 QuizBuks 中的成员。' },
  reinstalled: '欢迎回来：店铺与成员都保持原样。',
  connection: {
    title: '与 Shopify 的连接',
    NONE: '未连接',
    CONNECTED: '已连接',
    RECONNECT: '需要重新连接',
    explain: 'QuizBuks 第一次需要时才向 Shopify 申请访问你的店铺。',
    connect: '连接',
    check: '检查连接',
    checked: '已检查：连接正常。',
    lost: 'Shopify 不再接受这个连接，请重新连接。',
  },
  members: {
    title: '成员',
    staff: '店员编号',
    role: '角色',
    joined: '加入时间',
    actions: '操作',
    ADMIN: '主管理员',
    MEMBER: '成员',
    me: '（你）',
    makeAdmin: '设为主管理员',
    confirm: '把主管理员转交给店员 {{staff}}？你将成为成员。',
    transferred: '主管理员已转交。',
  },
}

const ja: typeof en = {
  title: 'QuizBuks for Shopify',
  starting: 'QuizBuks を開いています…',
  notEmbedded: 'ストアの Shopify 管理画面から QuizBuks を開いてください（アプリ → QuizBuks）。',
  failed: 'QuizBuks を開けませんでした：{{reason}}',
  retry: 'もう一度',
  shop: 'ストア',
  status: { ACTIVE: 'インストール済み', UNINSTALLED: 'アンインストール済み' },
  you: { ADMIN: 'あなたは QuizBuks でこのストアの管理者です。', MEMBER: 'あなたは QuizBuks でこのストアのメンバーです。' },
  reinstalled: 'おかえりなさい。ストアとメンバーは以前のままです。',
  connection: {
    title: 'Shopify との接続',
    NONE: '未接続',
    CONNECTED: '接続済み',
    RECONNECT: '再接続が必要',
    explain: 'QuizBuks は初めて必要になったときに Shopify へストアへのアクセスを求めます。',
    connect: '接続する',
    check: '接続を確認',
    checked: '確認しました：接続は正常です。',
    lost: 'Shopify はこの接続を受け付けなくなりました。もう一度接続してください。',
  },
  members: {
    title: 'メンバー',
    staff: 'スタッフ ID',
    role: '役割',
    joined: '参加日時',
    actions: '操作',
    ADMIN: '管理者',
    MEMBER: 'メンバー',
    me: '（あなた）',
    makeAdmin: '管理者にする',
    confirm: 'スタッフ {{staff}} に管理者を引き継ぎますか？あなたはメンバーになります。',
    transferred: '管理者を引き継ぎました。',
  },
}

export const messages = { en, zh, ja }

addMessages(SHOPIFY, messages)
