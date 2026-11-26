import { addMessages, entryInfo } from '@jabiz/client'
import { useEffect, useState } from 'react'
import { AUTH_NAMESPACE, type SignInMethod } from '@jabiz/ui'

/*
 * The button that opens the mailed link's form, in the words of the timeline's sign-in ("Continue with …", Q113 §9.4):
 * the platform's longer text ("… (no password)") does not fit a pill one line high on a 360 px phone in English and
 * Japanese. Changed for this app only, as the platform's texts allow (addMessages on its namespace).
 */
addMessages(AUTH_NAMESPACE, {
  en: { entry: { emailLink: 'Continue with a link by mail' } },
  zh: { entry: { emailLink: '通过邮件链接继续' } },
  ja: { entry: { emailLink: 'メールのリンクで続ける' } },
})

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
export function signInOrder(language: string, unified = false): readonly SignInMethod[] {
  // Q113f-2: with one "continue" (platform D84) the address and its button stand where the password was, and the mailed
  // link is that button: in Chinese first, in Japanese and English after the providers and the passkey.
  if (unified) return mailFirst(language) ? ['password', 'providers', 'passkey'] : ['providers', 'passkey', 'password']
  return mailFirst(language)
    ? ['emailLink', 'providers', 'passkey', 'password']
    : ['providers', 'emailLinkButton', 'passkey', 'password']
}

/**
 * Whether the app's entry signs in and signs up with one "continue" (Q113f-2; `QB_APP_UNIFIED_SIGN_IN`, platform D84):
 * null until known. Without it the page has its two tabs and password registration again.
 */
export function useUnifiedSignIn(): boolean | null {
  const [unified, setUnified] = useState<boolean | null>(null)
  useEffect(() => {
    let live = true
    entryInfo()
      .then((info) => {
        if (live) setUnified(!!info.unifiedSignIn)
      })
      // Not read: the page as it was, which works without the entry's settings.
      .catch(() => {
        if (live) setUnified(false)
      })
    return () => {
      live = false
    }
  }, [])
  return unified
}
