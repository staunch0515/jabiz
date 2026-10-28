/**
 * Whether the sign-in page offers the mailed link first (Q50; platform 17m): in Chinese, since Google, Facebook and LINE
 * do not open in mainland China, where the app is reached as a website and a home-screen app only. Their buttons stay,
 * after the address and the password.
 */
export function mailFirst(language: string): boolean {
  return language === 'zh' || language.startsWith('zh-')
}
