import type { MailLayouts, MailTexts } from '../api/mail'

/** The answers of the mail endpoints the mail pages' tests share. */
export const SHIPPED_BODY_ZH = '您的订单 {orderNo} 已发货。\n\n[退订]({unsubscribeUrl})'

export const MAIL_TEXTS: MailTexts = {
  maxSubjectLength: 300,
  maxBodyLength: 20000,
  allowedLinkHosts: [],
  templates: [
    {
      template: 'commerce.order-shipped',
      name: 'Order shipped',
      category: 'NOTIFICATION',
      needsApproval: false,
      placeholders: ['orderNo', 'baseUrl', 'unsubscribeUrl'],
      required: ['orderNo', 'unsubscribeUrl'],
      secrets: ['unsubscribeUrl'],
      languages: [
        { locale: 'zh', state: 'DEFAULT', defaultSubject: '订单 {orderNo} 已发货', defaultBody: SHIPPED_BODY_ZH, defaultHash: 'h', problems: [] },
        {
          locale: 'ja', state: 'CUSTOM', defaultSubject: 'ご注文 {orderNo} を発送しました', defaultBody: 'ご注文 {orderNo}\n\n[停止]({unsubscribeUrl})',
          defaultHash: 'h', versionNo: 3, subject: '発送のお知らせ {orderNo}', body: 'ご注文 {orderNo} を本日発送しました。\n\n[停止]({unsubscribeUrl})', problems: [],
        },
        {
          locale: 'en', state: 'INVALID', defaultSubject: 'Order {orderNo} shipped', defaultBody: 'Order {orderNo}\n\n[Unsubscribe]({unsubscribeUrl})',
          defaultHash: 'h', versionNo: 2, subject: 'Shipped', body: 'Shipped {gone}', problems: [{ field: 'body', message: '{gone} is not a placeholder of this mail', ruleCode: 'MAIL_TEXT_UNKNOWN_PLACEHOLDER' }],
        },
      ],
    },
    {
      template: 'jabiz.password-reset',
      name: 'Password reset',
      category: 'TRANSACTIONAL',
      needsApproval: true,
      placeholders: ['resetToken', 'entryUrl', 'baseUrl'],
      required: ['resetToken'],
      secrets: ['resetToken'],
      languages: [
        { locale: 'zh', state: 'DEFAULT_UPDATED', defaultSubject: '重设密码', defaultBody: '[重设]({entryUrl}reset?token={resetToken})', defaultHash: 'h2', versionNo: 1, subject: '重设你的密码', body: '[点此重设]({entryUrl}reset?token={resetToken})', problems: [] },
        { locale: 'ja', state: 'UNAPPROVED', defaultSubject: 'パスワード再設定', defaultBody: '[再設定]({entryUrl}reset?token={resetToken})', defaultHash: 'h2', versionNo: 4, subject: '再設定', body: '[こちら]({entryUrl}reset?token={resetToken})', problems: [] },
        { locale: 'en', state: 'DEFAULT', defaultSubject: 'Reset your password', defaultBody: '[Reset]({entryUrl}reset?token={resetToken})', defaultHash: 'h2', problems: [] },
      ],
    },
  ],
}

export const MAIL_LAYOUTS: MailLayouts = {
  maxFooterLength: 2000,
  maxLineLength: 200,
  allowedLinkHosts: [],
  languages: [
    { locale: 'zh', state: 'DEFAULT', defaultLayout: { displayName: '示范商店', footer: '示范商店 · 上海' }, defaultHash: 'l', problems: [] },
    { locale: 'ja', state: 'DEFAULT', defaultLayout: { displayName: 'デモストア', footer: 'デモストア' }, defaultHash: 'l', problems: [] },
    {
      locale: 'en', state: 'CUSTOM', defaultLayout: { displayName: 'Demo store', footer: 'Demo store · Shanghai' }, defaultHash: 'l',
      versionNo: 2, layout: { displayName: 'Demo Store Ltd', logoUrl: '/api/public/files/logo', logoAlt: 'Demo Store', footer: 'Demo Store Ltd\nShanghai' }, problems: [],
    },
  ],
}

/** Shown names of the users the tests' records name. */
export const USER_NAMES: Record<string, string> = { 'u-ann': 'Ann Lee', 'u-bob': 'Bob Wu' }
