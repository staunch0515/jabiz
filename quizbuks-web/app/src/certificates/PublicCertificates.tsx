import { formatDate } from '@jabiz/client'
import { Badge, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { Award, BadgeCheck, CircleX, Clock, LinkIcon } from 'lucide-react'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { QB } from '../qb/messages'
import { SOURCE_NODE } from '../qb/certificates'
import { Link } from '../qb/router'
import { nameOf, readTakerPage, readVerifiedCertificate, stateOf, verifyPath } from './api'

const VERIFY_PAGE = /^\/verify\/([^/]+)$/
const TAKER_PAGE = /^\/u\/([^/]+)$/

function decoded(match: RegExpExecArray | null): string | null {
  if (!match) return null
  try {
    return decodeURIComponent(match[1]).toLowerCase()
  } catch {
    return null
  }
}

/** The key of a verify page's path (`/verify/<key>`), lowercase, or null for any other path. */
export function verifyKeyOf(path: string): string | null {
  return decoded(VERIFY_PAGE.exec(path))
}

/** The name of a public certification page's path (`/u/<name>`), lowercase, or null for any other path. */
export function takerHandleOf(path: string): string | null {
  return decoded(TAKER_PAGE.exec(path))
}

/**
 * Asks search engines not to index the page while it is shown (D-Q77-5: a name made public is not to be found by
 * searching it).
 */
function useNoIndex() {
  useEffect(() => {
    const meta = document.createElement('meta')
    meta.name = 'robots'
    meta.content = 'noindex'
    document.head.appendChild(meta)
    return () => meta.remove()
  }, [])
}

function HomeLink() {
  const { t } = useTranslation(APP)
  return <Link to="/" className="text-primary text-sm underline" data-testid="certificates-home">{t('certificates.home')}</Link>
}

/**
 * A certificate's verify page (Q77, D-Q77-4; F-S5b), for anybody with its key: what it is, the name on it, its issuer,
 * the day it was lit (a node's certificate has no end; Q77's shows its holding) and whether it is valid, expired or
 * revoked. One its holder turned the link off says only that. Nothing else of the holder; an unknown key (or an
 * erased account's certificate) finds nothing. The page is cached for up to a minute, so a revocation or a switched-off
 * link can show that late.
 */
export function VerifyPage({ verifyKey }: { verifyKey: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  useNoIndex()
  const certificate = useQuery({ queryKey: ['qb', 'verify', verifyKey], queryFn: () => readVerifiedCertificate(verifyKey) })
  if (certificate.isPending) return <PageState kind="loading" />
  if (certificate.isError) return <PageState kind="error" description={tQb('failed')} />
  const c = certificate.data
  if (!c) {
    return <PageState kind="notFound" titleAs="h1" title={t('certificates.verify.notFound')}
      description={t('certificates.verify.notFoundHint')} action={<HomeLink />} />
  }
  const state = stateOf(c)
  const Icon = state === 'VALID' ? BadgeCheck : state === 'EXPIRED' ? Clock : state === 'DISABLED' ? LinkIcon : CircleX
  const node = c.source === SOURCE_NODE
  return (
    <article className="flex flex-col gap-4" aria-labelledby="verify-title" data-testid="verify-page"
      data-state={state}>
      <h1 id="verify-title" className="text-xl font-semibold">{t('certificates.verify.title')}</h1>
      <div className="flex flex-col gap-3 rounded-lg border p-4">
        <div className="flex items-center gap-2">
          <Icon aria-hidden className={state === 'VALID' ? 'text-primary size-6' : 'text-muted-foreground size-6'} />
          <Badge variant={state === 'VALID' ? 'default' : state === 'REVOKED' ? 'destructive' : 'secondary'}
            data-testid="verify-state">{t(`certificates.verify.states.${state}`)}</Badge>
        </div>
        {state === 'DISABLED' ? (
          <p className="text-sm" data-testid="verify-disabled">{t('certificates.verify.disabled')}</p>
        ) : (
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
            <dt className="text-muted-foreground">{t('certificates.verify.certification')}</dt>
            <dd data-testid="verify-certification">{nameOf(c, tQb)}</dd>
            {c.issuerName && (
              <>
                <dt className="text-muted-foreground">{t('certificates.verify.issuer')}</dt>
                <dd data-testid="verify-issuer">{c.issuerName}</dd>
              </>
            )}
            <dt className="text-muted-foreground">{t('certificates.verify.holder')}</dt>
            <dd data-testid="verify-holder">{c.holderName}</dd>
            {/* A node's certificate has no validity: the day it was lit, and nothing about an end. */}
            {node ? (
              <>
                <dt className="text-muted-foreground">{t('certificates.verify.lit')}</dt>
                <dd data-testid="verify-lit">{formatDate(c.heldFrom!)}</dd>
              </>
            ) : (
              <>
                <dt className="text-muted-foreground">{t('certificates.verify.held')}</dt>
                <dd data-testid="verify-held">
                  {t('certificates.period', { from: formatDate(c.heldFrom!), until: formatDate(c.validUntil!) })}
                </dd>
              </>
            )}
            {c.revokedAt && (
              <>
                <dt className="text-muted-foreground">{t('certificates.verify.revokedAt')}</dt>
                <dd>{formatDate(c.revokedAt)}</dd>
              </>
            )}
            <dt className="text-muted-foreground">{t('certificates.verify.number')}</dt>
            <dd className="break-all font-mono text-xs">{verifyKey}</dd>
          </dl>
        )}
      </div>
      <p className="text-muted-foreground text-sm">{t('certificates.verify.hint')}</p>
    </article>
  )
}

/**
 * A taker's public certification page (Q77, D-Q77-5): the certificates they issued that are valid now, each leading
 * to its verify page. Off or unknown, the same "not available".
 */
export function TakerPage({ handle }: { handle: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  useNoIndex()
  const page = useQuery({ queryKey: ['qb', 'taker-page', handle], queryFn: () => readTakerPage(handle) })
  if (page.isPending) return <PageState kind="loading" />
  if (page.isError) return <PageState kind="error" description={tQb('failed')} />
  if (page.data.length === 0) {
    return <PageState kind="notFound" titleAs="h1" title={t('certificates.takerPage.notFound')}
      description={t('certificates.takerPage.notFoundHint')} action={<HomeLink />} />
  }
  const name = page.data[0].holderName
  return (
    <article className="flex flex-col gap-4" aria-labelledby="taker-page-title" data-testid="taker-page">
      <div className="flex flex-col gap-1">
        <h1 id="taker-page-title" className="text-xl font-semibold" data-testid="taker-page-name">{name}</h1>
        <p className="text-muted-foreground text-sm">{t('certificates.takerPage.subtitle')}</p>
      </div>
      <ul className="flex flex-col gap-3">
        {page.data.map((c) => (
          <li key={c.verifyKey}>
            <Link to={verifyPath(c.verifyKey)} data-testid={`taker-page-${c.certification ?? c.verifyKey}`}
              className="hover:bg-accent flex items-start gap-2 rounded-lg border p-3">
              <Award aria-hidden className="text-primary mt-0.5 size-5 shrink-0" />
              <span className="flex min-w-0 flex-col">
                <span className="font-medium">{nameOf(c, tQb)}</span>
                {c.issuerName && (
                  <span className="text-muted-foreground text-sm">{tQb('certification.issuedBy', { sponsor: c.issuerName })}</span>
                )}
                <span className="text-muted-foreground text-sm">
                  {c.validUntil
                    ? t('certificates.period', { from: formatDate(c.heldFrom), until: formatDate(c.validUntil) })
                    : t('certificates.takerPage.litOn', { date: formatDate(c.heldFrom) })}
                </span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
      <HomeLink />
    </article>
  )
}
