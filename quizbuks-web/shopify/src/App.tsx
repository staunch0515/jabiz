import { ApiError, AuthProvider, useAuth } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@jabiz/ui'
import { QueryClient, QueryClientProvider, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Component, lazy, Suspense, useCallback, useEffect, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { check, connect, join, transferAdmin, type Shop } from './api'
import { NotEmbeddedError, sessionToken } from './appBridge'
import { pathOf, usePage, type Page } from './routes'
import { planKey, UsageNotice } from './usage'
import { reasonOf } from './errors'
import { SHOPIFY } from './messages'

// The campaigns and the plan are chunks of their own, loaded when first shown (S4b, F-2): the overview opens first.
const CampaignsPage = lazy(() => import('./CampaignsPage').then((module) => ({ default: module.CampaignsPage })))
const PlanPage = lazy(() => import('./PlanPage'))

/**
 * QuizBuks for Shopify's embedded pages (docs/quizbuks/shopify/S1-install.md section 6): sign in with Shopify's
 * session token (the platform's external token sign-in, decision D59), join the store (QBS_SHOP_JOIN, whose answer is
 * what the page shows), and the store's overview: its connection to Shopify and its members, the admin handing the
 * role on; (S2b, docs/quizbuks/shopify/S2b-ui.md) the store's quiz campaigns; and (S3b, docs/quizbuks/shopify/S3b-ui.md)
 * its plan and use, with a notice on the overview as the period's attempts run out. (S4b) Each page has its own
 * address under /shopify/, which App Bridge's menu in Shopify's side bar opens; its title is App Bridge's title bar. Every rule is the server's; the
 * page only offers what it may.
 */
export default function App() {
  const [queryClient] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }))
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <main className="mx-auto max-w-3xl p-4">
          <ShopPage />
        </main>
      </AuthProvider>
    </QueryClientProvider>
  )
}


function ShopPage() {
  const { t } = useTranslation(SHOPIFY)
  const auth = useAuth()
  // The page the address shows. The campaigns are read when first shown, then kept mounted (hidden when not shown) so
  // an open editor keeps what was typed while another page is shown.
  const [page, show] = usePage()
  const [campaignsShown, setCampaignsShown] = useState(page === 'campaigns')
  if (page === 'campaigns' && !campaignsShown) setCampaignsShown(true)

  // Signs in with a session token (each is used once) whenever the tab has no session.
  const signIn = useMutation({
    mutationFn: async () => {
      const step = await auth.signInWithExternalToken(await sessionToken())
      if (step.status !== 'SIGNED_IN') throw new Error(step.status)
    },
  })
  useEffect(() => {
    if (auth.ready && !auth.signedIn && signIn.isIdle) signIn.mutate()
  }, [auth.ready, auth.signedIn, signIn])

  // Joins the store with a fresh session token: its answer is the overview. Opening runs a process: only when the
  // page opens (or the user changes) and when asked, never on focus.
  const opened = useQuery({
    queryKey: ['qbs.shop', auth.userId],
    enabled: auth.signedIn && auth.userId !== null,
    queryFn: join,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })
  const queryClient = useQueryClient()
  // A reload (after connecting or checking the store) reads the plan again too: its connection may have changed.
  const reload = useCallback(async () => {
    await opened.refetch()
    await queryClient.invalidateQueries({ queryKey: planKey(auth.userId) })
  }, [opened, queryClient, auth.userId])

  // The tab's session is another store's staff member (the admin switched stores): sign in as this one, once.
  const switchUser = useMutation({
    mutationFn: () => auth.signOut(),
    onSuccess: () => signIn.reset(),
  })
  const otherStore = opened.error instanceof ApiError && opened.error.status === 403 && switchUser.isIdle
  useEffect(() => {
    if (otherStore) switchUser.mutate()
  }, [otherStore, switchUser])

  const error = signIn.error ?? (otherStore ? null : opened.error)
  const waiting = !error && !opened.data
  return (
    <div className="flex flex-col gap-4">
      <ui-nav-menu>
        <a href={pathOf('overview')} rel="home">{t('tabs.overview')}</a>
        <a href={pathOf('campaigns')}>{t('tabs.campaigns')}</a>
        <a href={pathOf('plan')}>{t('tabs.plan')}</a>
      </ui-nav-menu>
      <ui-title-bar title={pageTitle(t, page)} />
      {/* Shopify's title bar names the page; the page's own heading is for readers and when not embedded. */}
      <h1 className="text-2xl font-semibold">{pageTitle(t, page)}</h1>
      {waiting && <p role="status">{t('starting')}</p>}
      {error instanceof NotEmbeddedError && (
        <Alert>
          <AlertDescription>{t('notEmbedded')}</AlertDescription>
        </Alert>
      )}
      {error && !(error instanceof NotEmbeddedError) && (
        <Alert variant="destructive">
          <AlertDescription className="flex flex-col gap-2">
            <span>{t('failed', { reason: reasonOf(error) })}</span>
            <Button variant="outline" className="self-start" onClick={() => {
              if (signIn.error) signIn.reset()
              else void reload()
            }}>
              {t('retry')}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {opened.data && (
        <ChunkBoundary>
        <Suspense fallback={<p role="status">{t('loading')}</p>}>
          {page === 'overview' && (
            <div className="flex flex-col gap-4">
              <UsageNotice userId={auth.userId} onShowPlan={() => show('plan')} />
              <Overview shop={opened.data} userId={auth.userId} reload={reload} />
            </div>
          )}
          {campaignsShown && (
            <div hidden={page !== 'campaigns'}>
              <CampaignsPage connection={opened.data.connection} />
            </div>
          )}
          {page === 'plan' && <PlanPage userId={auth.userId} onShowOverview={() => show('overview')} />}
        </Suspense>
        </ChunkBoundary>
      )}
      <Help />
    </div>
  )
}

function Overview({ shop, userId, reload }: { shop: Shop; userId: string | null; reload: () => Promise<void> }) {
  const { t } = useTranslation(SHOPIFY)
  const [notice, setNotice] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const admin = shop.role === 'ADMIN'

  async function act(work: () => Promise<string | null>) {
    setBusy(true)
    setProblem(null)
    setNotice(null)
    try {
      const done = await work()
      await reload()
      setNotice(done)
    } catch (error) {
      setProblem(reasonOf(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {shop.reinstalled && (
        <Alert>
          <AlertDescription>{t('reinstalled')}</AlertDescription>
        </Alert>
      )}
      <Card>
        <CardHeader>
          <CardTitle>{shop.shopName ?? shop.shopDomain}</CardTitle>
          <CardDescription>
            {t('shop')}: {shop.shopDomain}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <p>{t(`you.${shop.role}`)}</p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t('connection.title')}</CardTitle>
          <CardDescription>{t('connection.explain')}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-3">
          <Badge variant={shop.connection === 'CONNECTED' ? 'default' : 'secondary'}>
            {t(`connection.${shop.connection}`)}
          </Badge>
          {shop.connection === 'CONNECTED' ? (
            <Button variant="outline" disabled={busy} onClick={() => void act(async () => {
              const state = await check()
              return state.connection === 'CONNECTED' ? t('connection.checked') : t('connection.lost')
            })}>
              {t('connection.check')}
            </Button>
          ) : (
            <Button disabled={busy} onClick={() => void act(async () => {
              await connect()
              return null
            })}>
              {t('connection.connect')}
            </Button>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t('members.title')}</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('members.staff')}</TableHead>
                <TableHead>{t('members.role')}</TableHead>
                <TableHead>{t('members.joined')}</TableHead>
                {admin && <TableHead>{t('members.actions')}</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {shop.members.map((member) => (
                <TableRow key={member.userId}>
                  <TableCell>
                    {member.staffId} {member.userId === userId && t('members.me')}
                  </TableCell>
                  <TableCell>{t(`members.${member.role}`)}</TableCell>
                  <TableCell>{new Date(member.joinedAt).toLocaleString()}</TableCell>
                  {admin && (
                    <TableCell>
                      {member.role !== 'ADMIN' && (
                        <Button size="sm" variant="outline" disabled={busy} onClick={() => {
                          if (!window.confirm(t('members.confirm', { staff: member.staffId }))) return
                          void act(async () => {
                            await transferAdmin(member.userId)
                            return t('members.transferred')
                          })
                        }}>
                          {t('members.makeAdmin')}
                        </Button>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {notice && <p role="status">{notice}</p>}
      {problem && (
        <Alert variant="destructive">
          <AlertDescription>{problem}</AlertDescription>
        </Alert>
      )}
    </>
  )
}

/**
 * A page's chunk that cannot be loaded (a new version was deployed while the app was open: the old files are gone):
 * says so and offers a reload, instead of leaving Shopify's admin with an empty frame.
 */
class ChunkBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  render() {
    return this.state.failed ? <ChunkFailed /> : this.props.children
  }
}

function ChunkFailed() {
  const { t } = useTranslation(SHOPIFY)
  return (
    <Alert variant="destructive">
      <AlertDescription className="flex flex-col gap-2">
        <span>{t('outdated')}</span>
        <Button variant="outline" className="self-start" onClick={() => window.location.reload()}>
          {t('reload')}
        </Button>
      </AlertDescription>
    </Alert>
  )
}

/** A page's title: the app's name on the overview, the page's name on the others. */
function pageTitle(t: (key: string) => string, page: Page): string {
  return page === 'overview' ? t('title') : t(`tabs.${page}`)
}

/**
 * The support address the server wrote into the page (S4a: index-values, platform 17g); none when the page was not
 * served by it (the dev server leaves the placeholder).
 */
export function supportEmail(): string | null {
  const value = document.querySelector<HTMLMetaElement>('meta[name="quizbuks-support-email"]')?.content ?? ''
  return /^[^\s@{}]+@[^\s@{}]+$/.test(value) ? value : null
}

/** Where to get help (S4a): the support page of the public site, in the page's language, and the support address. */
function Help() {
  const { t, i18n } = useTranslation(SHOPIFY)
  const email = supportEmail()
  const language = i18n.language.startsWith('zh') ? 'zh/' : i18n.language.startsWith('ja') ? 'ja/' : ''
  return (
    <footer className="flex flex-wrap gap-x-2 border-t pt-3 text-sm text-muted-foreground">
      <a className="underline" href={`/shopify-app/${language}support/`} target="_blank" rel="noopener noreferrer">
        {t('help.page')}
      </a>
      {email && (
        <span>
          {t('help.write')} <a className="underline" href={`mailto:${email}`}>{email}</a>
        </span>
      )}
    </footer>
  )
}
