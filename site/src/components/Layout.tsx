import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { useSiteBlocks } from '../api/blocks'
import type { Locale } from '../api/public-queries'
import { useLocale, useT } from '../i18n/locale'
import { LOCALES } from '../lib/localized'
import { path, type Section } from '../lib/paths'
import { LocalizedText } from './LocalizedText'
import styles from './Layout.module.css'

const SECTIONS: Section[] = ['people', 'themes', 'stories', 'method', 'resources', 'about']
const LANGUAGE_NAMES: Record<Locale, string> = { en: 'English', zh: '中文', ja: '日本語' }
const LANGUAGE_SHORT: Record<Locale, string> = { en: 'EN', zh: '中文', ja: '日本語' }

/** The same page in another language: only the first segment of the address changes. */
function inLanguage(pathname: string, search: string, locale: Locale): string {
  const rest = pathname.replace(/^\/[^/]*/, '')
  return `/${locale}${rest || '/'}${search}`
}

function Wordmark() {
  const locale = useLocale()
  return (
    <Link className={styles.wordmark} to={path(locale)} lang="en">
      Culture,&nbsp;<i>Unfiltered</i>
    </Link>
  )
}

/**
 * Header, main navigation, language switch and footer of every page (design section 9.1). On narrow screens the
 * navigation is behind a menu button: opening it moves the focus into the menu, Esc closes it and returns the focus.
 */
export function Layout({ children }: { children?: ReactNode }) {
  const t = useT()
  const locale = useLocale()
  const location = useLocation()
  const [open, setOpen] = useState(false)
  const [menuPath, setMenuPath] = useState(location.pathname)
  if (menuPath !== location.pathname) {
    // Following a link in the menu closes it.
    setMenuPath(location.pathname)
    setOpen(false)
  }
  const burger = useRef<HTMLButtonElement>(null)
  const nav = useRef<HTMLDivElement>(null)
  const main = useRef<HTMLElement>(null)
  const first = useRef(true)
  const { blocks } = useSiteBlocks(['site.tagline'])

  useEffect(() => {
    document.documentElement.lang = locale
  }, [locale])

  // A new page starts at the top (or at the part asked for) with the focus on the content, so that keyboard and
  // screen reader users start reading where the page starts.
  useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    if (location.hash) return
    window.scrollTo(0, 0)
    main.current?.focus({ preventScroll: true })
  }, [location.pathname, location.hash])

  useEffect(() => {
    if (!open) return
    nav.current?.querySelector('a')?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
        burger.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  return (
    <>
      <a className={styles.skip} href="#main">
        {t('site.skip')}
      </a>
      <header className={styles.header}>
        <div className={`wrap ${styles.bar}`}>
          <Wordmark />
          <button
            ref={burger}
            type="button"
            className={styles.burger}
            aria-expanded={open}
            aria-controls="site-menu"
            aria-label={t('site.menu')}
            onClick={() => setOpen((value) => !value)}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              {open ? <path d="M5 5l14 14M19 5L5 19" /> : <path d="M3 7h18M3 12h18M3 17h18" />}
            </svg>
          </button>
          <div ref={nav} className={styles.nav} id="site-menu" data-open={open}>
            <nav aria-label={t('site.mainNav')}>
              <ul className={styles.list}>
                <li>
                  <NavLink to={path(locale)} end>
                    {t('nav.home')}
                  </NavLink>
                </li>
                {SECTIONS.map((section) => (
                  <li key={section}>
                    <NavLink to={path(locale, section)}>{t(`nav.${section}`)}</NavLink>
                  </li>
                ))}
              </ul>
            </nav>
            <NavLink className={styles.search} to={path(locale, 'search')}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <circle cx="10.5" cy="10.5" r="6.5" />
                <path d="M15.5 15.5L21 21" />
              </svg>
              <span className={styles.searchText}>{t('nav.search')}</span>
            </NavLink>
            <ul className={styles.languages} aria-label={t('site.language')}>
              {LOCALES.map((l) => (
                <li key={l}>
                  <Link
                    to={inLanguage(location.pathname, location.search, l)}
                    lang={l}
                    hrefLang={l}
                    aria-current={l === locale ? 'true' : undefined}
                    aria-label={LANGUAGE_NAMES[l]}
                  >
                    {LANGUAGE_SHORT[l]}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        </div>
      </header>
      <main id="main" ref={main} tabIndex={-1} className={styles.main}>
        {children ?? <Outlet />}
      </main>
      <footer className={styles.footer}>
        <div className={`wrap ${styles.footerInner}`}>
          <div className={styles.footerText}>
            <Wordmark />
            <LocalizedText as="p" value={blocks['site.tagline']} />
            <p>{t('site.privacyNote')}</p>
          </div>
          <nav aria-label={t('site.footerNav')}>
            <ul>
              {(['map', 'search', 'method', 'resources', 'about'] as const).map((section) => (
                <li key={section}>
                  <Link to={path(locale, section)}>{t(`nav.${section}`)}</Link>
                </li>
              ))}
            </ul>
          </nav>
        </div>
      </footer>
    </>
  )
}
