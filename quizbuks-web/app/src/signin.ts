import type { SignInMethod } from '@jabiz/ui'

/**
 * Whether the sign-in page offers the mailed link first (Q50; platform 17m): in Chinese, since Google, Facebook and LINE
 * do not open in mainland China, where the app is reached as a website and a home-screen app only. Their buttons stay,
 * after the address.
 */
export function mailFirst(language: string): boolean {
  return language === 'zh' || language.startsWith('zh-')
}

/**
 * The order of the ways of signing in by the page's language (platform 17zk, suggestion ③ and gap 1): in Chinese the
 * mailed link's form first; in Japanese and English the providers first and the mailed link behind a button among the
 * other ways. The password is always there, last; a way the entry does not offer is not shown.
 */
export function signInOrder(language: string): readonly SignInMethod[] {
  return mailFirst(language)
    ? ['emailLink', 'providers', 'passkey', 'password']
    : ['providers', 'emailLinkButton', 'passkey', 'password']
}
