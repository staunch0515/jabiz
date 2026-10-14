import { addMessages } from '@jabiz/client'

/** The portal's own texts (namespace `portal`); the sign-in pages speak the platform's namespace `auth`. */
export const PORTAL = 'portal'

const en = {
  brand: 'Customer portal',
  nav: { label: 'Portal', orders: 'My orders', order: 'Place an order', account: 'Account', signOut: 'Sign out' },
  orders: {
    title: 'My orders',
    empty: 'You have no orders yet.',
    orderNo: 'Order',
    status: 'Status',
    orderedTime: 'Placed',
    totalAmount: 'Total',
    lineCount: 'Lines',
  },
  order: {
    title: 'Place an order',
    product: 'Product',
    choose: 'Choose a product',
    quantity: 'Quantity',
    submit: 'Place the order',
    placed: 'Order {{orderNo}} placed.',
    none: 'Nothing can be ordered at the moment.',
    verifyFirst: 'Verify your e-mail address before you order.',
    noCustomer: 'Your account is not linked to a customer yet. Please contact us.',
    howTo: [
      '## How ordering works',
      '',
      '1. Choose a product and a quantity: only what is **in stock** is offered.',
      '2. Place the order. Your e-mail address must be verified first.',
      '3. Follow it under *My orders*.',
    ].join('\n'),
  },
  account: { title: 'Account', mfa: 'Two-step verification' },
  notFound: 'This page does not exist.',
  failed: 'Something went wrong. Please try again.',
}

const zh: typeof en = {
  brand: '客户门户',
  nav: { label: '门户', orders: '我的订单', order: '下单', account: '账户', signOut: '退出登录' },
  orders: {
    title: '我的订单',
    empty: '您还没有订单。',
    orderNo: '订单',
    status: '状态',
    orderedTime: '下单时间',
    totalAmount: '金额',
    lineCount: '行数',
  },
  order: {
    title: '下单',
    product: '商品',
    choose: '请选择商品',
    quantity: '数量',
    submit: '提交订单',
    placed: '订单 {{orderNo}} 已提交。',
    none: '目前没有可订购的商品。',
    verifyFirst: '请先验证邮箱再下单。',
    noCustomer: '您的账号尚未关联客户，请与我们联系。',
    howTo: [
      '## 如何下单',
      '',
      '1. 选择商品与数量：只列出**有库存**的商品。',
      '2. 提交订单。须先验证邮箱。',
      '3. 在*我的订单*中查看进度。',
    ].join('\n'),
  },
  account: { title: '账户', mfa: '两步验证' },
  notFound: '页面不存在。',
  failed: '出错了，请重试。',
}

const ja: typeof en = {
  brand: '顧客ポータル',
  nav: { label: 'ポータル', orders: '注文履歴', order: '注文する', account: 'アカウント', signOut: 'ログアウト' },
  orders: {
    title: '注文履歴',
    empty: 'まだ注文がありません。',
    orderNo: '注文',
    status: '状態',
    orderedTime: '注文日時',
    totalAmount: '合計',
    lineCount: '明細数',
  },
  order: {
    title: '注文する',
    product: '商品',
    choose: '商品を選んでください',
    quantity: '数量',
    submit: '注文する',
    placed: '注文 {{orderNo}} を受け付けました。',
    none: '現在注文できる商品はありません。',
    verifyFirst: '注文の前にメールアドレスを確認してください。',
    noCustomer: 'アカウントがまだ顧客に関連付けられていません。お問い合わせください。',
    howTo: [
      '## ご注文の流れ',
      '',
      '1. 商品と数量を選びます。**在庫のある**商品だけが表示されます。',
      '2. 注文します。先にメールアドレスの確認が必要です。',
      '3. *注文履歴*で状況を確認できます。',
    ].join('\n'),
  },
  account: { title: 'アカウント', mfa: '2 段階認証' },
  notFound: 'このページは存在しません。',
  failed: 'エラーが発生しました。もう一度お試しください。',
}

addMessages(PORTAL, { en, zh, ja })
