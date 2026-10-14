/**
 * A short name for the device of a sign-in, from its User-Agent (docs/quizbuks/plans/Q2-accounts.md, D-Q2-5: the
 * platform records the header, the page names the device). Only the common browsers and systems; null otherwise.
 */
export function deviceOf(userAgent: string | null | undefined): string | null {
  if (!userAgent) return null
  const ua = userAgent
  const browser = /Edg\//.test(ua) ? 'Edge'
    : /OPR\//.test(ua) ? 'Opera'
      : /Firefox\/|FxiOS\//.test(ua) ? 'Firefox'
        : /Chrome\/|CriOS\//.test(ua) ? 'Chrome'
          : /Safari\//.test(ua) ? 'Safari'
            : null
  const os = /iPhone|iPad|iPod/.test(ua) ? 'iOS'
    : /Android/.test(ua) ? 'Android'
      : /Windows/.test(ua) ? 'Windows'
        : /Mac OS X|Macintosh/.test(ua) ? 'macOS'
          : /CrOS/.test(ua) ? 'ChromeOS'
            : /Linux/.test(ua) ? 'Linux'
              : null
  if (browser && os) return `${browser} · ${os}`
  return browser ?? os
}
