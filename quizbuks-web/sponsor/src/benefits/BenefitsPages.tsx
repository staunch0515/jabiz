import { fetchFileContent, formatDateTime, uploadFile } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Checkbox,
  ConfirmDialog,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  FileUpload,
  Input,
  Label,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Tabs,
  TabsList,
  TabsTrigger,
  Textarea,
  cn,
  notify,
  type ColumnDef,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Plus } from 'lucide-react'
import { useEffect, useId, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { neutralName } from '../content/api'
import { SPONSOR } from '../messages'
import { type PublicationStatus } from '../publishing/api'
import { DigitalPoolSection } from '../publishing/DigitalPool'
import { useStatusLabel } from '../publishing/shared'
import { FileImage } from '../qb/FileImage'
import { pickText } from '../qb/legal'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem, problemText } from '../qb/problems'
import { Link, navigate } from '../qb/router'
import {
  BENEFIT_CONVERSATIONS_PATH,
  BENEFITS_QUERY,
  cancelPurchase,
  confirmPurchase,
  conversationPath,
  declinePurchase,
  markRead,
  newPurchasePath,
  openConversation,
  PURCHASES_PATH,
  purchasePath,
  readAccess,
  readCatalog,
  readConversations,
  readMessages,
  readMyOffers,
  readProvidedPublications,
  readPurchases,
  saveOffer,
  savePurchase,
  sendMessage,
  submitPurchase,
  type Conversation,
  type Offer,
  type OfferInput,
  type Purchase,
  type PurchaseInput,
  type Side,
} from './api'
import { BENEFIT, type BenefitKind } from './ids'
import { PurchaseFollowUp } from './PurchaseFollowUp'
import './messages'
import { AreaPageHeader } from '../shell/AreaPageHeader'
import { mainAction } from '../shell/mainAction'

const IMAGE_TYPES = 'image/jpeg,image/png'

/** A proof's upload name: neutral, like the content's (the policy keeps the name private anyway). */
function proofName(fileName: string): string {
  const extension = /\.([A-Za-z0-9]{1,8})$/.exec(fileName)?.[1]
  return extension ? `proof.${extension.toLowerCase()}` : 'proof'
}
const PROOF_TYPES = 'application/pdf,image/jpeg,image/png'

/**
 * The two benefit roles (F-S2c): open, or which node of the platform's knowledge map to light in the app (none named:
 * not available yet). The server decides with the same rule; this card only says where the caller stands.
 */
export function BenefitAccess() {
  const { t, i18n } = useTranslation(SPONSOR)
  const access = useQuery({ queryKey: [...BENEFITS_QUERY, 'access'], queryFn: readAccess, staleTime: 60_000 })
  if (!access.data) return null
  return (
    <Card data-testid="benefit-access">
      <CardHeader><CardTitle className="text-base">{t('benefits.access.title')}</CardTitle></CardHeader>
      <CardContent className="flex flex-col gap-2 text-sm">
        <p className="text-muted-foreground">{t('benefits.access.why')}</p>
        <ul className="flex flex-col gap-1">
          {access.data.map((row) => {
            const node = pickText(row.nodeName, i18n.language)
            return (
              <li key={row.certification} className="flex flex-wrap items-center gap-2"
                data-testid="benefit-access-row" data-certification={row.certification}
                data-held={row.open ? 'true' : 'false'}>
                <span className="font-medium">{t(`benefits.access.${row.certification}`)}</span>
                {row.open ? (
                  <Badge variant="secondary">{t('benefits.access.held')}</Badge>
                ) : (
                  <>
                    <Badge variant="outline">{t('benefits.access.missing')}</Badge>
                    {node.text ? (
                      <span lang={node.lang}>{t('benefits.access.take', { node: node.text })}</span>
                    ) : <span className="text-muted-foreground">{t('benefits.access.noRound')}</span>}
                  </>
                )}
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
  )
}

/** A page's loading, error and retry, as the console's other pages show them. */
function Loading({ query, children }: {
  query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown }
  children: () => ReactNode
}) {
  const { t: tQb } = useTranslation(QB)
  if (query.isPending) return <PageState kind="loading" />
  if (query.isError) {
    return <PageState kind="error" description={problemText(query.error)}
      action={<Button variant="outline" onClick={() => void query.refetch()}>{tQb('retry')}</Button>} />
  }
  return <>{children()}</>
}

// ---- the catalog -----------------------------------------------------------------------------------------------------

/** The benefits other sponsors list (D-Q89-3): shown only while the caller holds a benefit certification. */
export function CatalogPage() {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [kind, setKind] = useState<BenefitKind | 'ALL'>('ALL')
  const [q, setQ] = useState('')
  const [asking, setAsking] = useState<Offer | null>(null)
  const mine = useQuery({ queryKey: [...BENEFITS_QUERY, 'offers'], queryFn: readMyOffers })
  const own = new Set((mine.data ?? []).map((offer) => offer.offerId))
  const catalog = useQuery({ queryKey: [...BENEFITS_QUERY, 'catalog', kind, q],
    queryFn: () => readCatalog({ kind: kind === 'ALL' ? null : kind, q }) })
  return (
    <section className="flex flex-col gap-4" data-testid="benefit-catalog">
      <AreaPageHeader title={t('benefits.catalog.title')} description={t('benefits.catalog.intro')} />
      <BenefitAccess />
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-q`}>{t('benefits.catalog.search')}</Label>
          <Input id={`${id}-q`} value={q} className="w-64" onChange={(event) => setQ(event.target.value)}
            data-testid="benefit-catalog-search" />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-kind`}>{t('benefits.catalog.kind')}</Label>
          <Select value={kind} onValueChange={(value) => setKind(value as BenefitKind | 'ALL')}>
            <SelectTrigger id={`${id}-kind`} className="w-44" data-testid="benefit-catalog-kind"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">{t('benefits.catalog.allKinds')}</SelectItem>
              {BENEFIT.kinds.map((k) => <SelectItem key={k} value={k}>{t(`benefits.kinds.${k}`)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      </div>
      <Loading query={catalog}>{() => (catalog.data ?? []).length === 0 ? (
        <PageState kind="empty" title={t('benefits.catalog.empty')} description={t('benefits.catalog.locked')}
          data-testid="benefit-catalog-empty" />
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {catalog.data!.map((offer) => (
            <li key={offer.offerId} data-testid="benefit-offer" data-id={offer.offerId}>
              <Card className="h-full">
                <CardHeader>
                  <CardTitle className="text-base">{offer.name}</CardTitle>
                  <p className="text-muted-foreground text-sm">
                    {t('benefits.catalog.by', { company: offer.providerCompany ?? '—' })}
                    {' · '}{t(`benefits.kinds.${offer.kind}`)}
                  </p>
                </CardHeader>
                <CardContent className="flex flex-col gap-2 text-sm">
                  {offer.images[0] && (
                    <FileImage fileId={offer.images[0]} variant="w640" alt={t('benefits.offers.imageAlt')}
                      className="aspect-video w-full rounded-md border" />
                  )}
                  <p className="font-medium">{t('benefits.catalog.value', { value: formatKudos(offer.unitValue) })}</p>
                  <p className="whitespace-pre-wrap break-words">{offer.description}</p>
                  {offer.pickupAddress && (
                    <p className="text-muted-foreground">{t('benefits.catalog.pickup', {
                      address: offer.pickupAddress, hours: offer.pickupHours ?? '' })}</p>
                  )}
                  {offer.terms && <p className="text-muted-foreground whitespace-pre-wrap">{offer.terms}</p>}
                  {own.has(offer.offerId) ? (
                    <Badge variant="outline" className="self-start">{t('benefits.catalog.own')}</Badge>
                  ) : (
                    <div className="mt-2 flex flex-wrap gap-2">
                      {offer.conversationId ? (
                        <Button asChild variant="outline" size="sm">
                          <Link to={conversationPath(offer.conversationId, 'SPONSOR')} data-testid="benefit-conversation-link">
                            {t('benefits.catalog.openConversation')}</Link>
                        </Button>
                      ) : (
                        <Button variant="outline" size="sm" onClick={() => setAsking(offer)} data-testid="benefit-ask">
                          {t('benefits.catalog.ask')}</Button>
                      )}
                      <Button asChild size="sm">
                        <Link to={newPurchasePath(offer.offerId)} data-testid="benefit-buy">{t('benefits.catalog.buy')}</Link>
                      </Button>
                    </div>
                  )}
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      )}</Loading>
      {asking && <AskDialog offer={asking} onClose={() => setAsking(null)} />}
    </section>
  )
}

/** The first message to a benefit's provider: it opens the conversation, which the page then shows. */
function AskDialog({ offer, onClose }: { offer: Offer; onClose: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [body, setBody] = useState('')
  const ask = useMutation({
    mutationFn: () => openConversation(offer.offerId, body),
    onSuccess: (output) => {
      void queryClient.invalidateQueries({ queryKey: BENEFITS_QUERY })
      navigate(conversationPath(output.conversationId, 'SPONSOR'))
    },
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent data-testid="benefit-ask-dialog">
        <DialogHeader>
          <DialogTitle>{t('benefits.catalog.askTitle', { company: offer.providerCompany ?? '—' })}</DialogTitle>
          <DialogDescription>{t('benefits.catalog.askHint')}</DialogDescription>
        </DialogHeader>
        <form id={`${id}-form`} className="flex flex-col gap-2" onSubmit={(event) => {
          event.preventDefault()
          if (body.trim()) ask.mutate()
        }}>
          <Label htmlFor={`${id}-body`}>{t('benefits.catalog.askBody')}</Label>
          <Textarea id={`${id}-body`} rows={5} value={body} maxLength={BENEFIT.messageLength}
            onChange={(event) => setBody(event.target.value)} data-testid="benefit-ask-body" />
          {ask.isError && <p className="text-destructive text-sm" role="alert">{problemText(ask.error)}</p>}
        </form>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>{t('benefits.offers.cancel')}</Button>
          <Button type="submit" form={`${id}-form`} disabled={!body.trim() || ask.isPending}
            data-testid="benefit-ask-send">{t('benefits.catalog.askSend')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ---- the provider's benefits -----------------------------------------------------------------------------------------

/** The caller's benefits as their provider: a list (cards on a phone) and the editor. */
export function OffersPage() {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const offers = useQuery({ queryKey: [...BENEFITS_QUERY, 'offers'], queryFn: readMyOffers })
  const [editing, setEditing] = useState<Offer | 'new' | null>(null)
  const columns = useMemo<ColumnDef<Offer, unknown>[]>(() => [
    { id: 'name', header: t('benefits.offers.columns.name'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => row.original.name },
    { id: 'kind', header: t('benefits.offers.columns.kind'), enableSorting: false, meta: { card: 'subtitle' },
      cell: ({ row }) => t(`benefits.kinds.${row.original.kind}`) },
    { id: 'value', header: t('benefits.offers.columns.value'), enableSorting: false,
      cell: ({ row }) => formatKudos(row.original.unitValue) },
    { id: 'status', header: t('benefits.offers.columns.status'), enableSorting: false, meta: { card: 'badge' },
      cell: ({ row }) => <Badge variant={row.original.status === 'LISTED' ? 'default' : 'outline'}>
        {t(`benefits.offers.status.${row.original.status ?? 'UNLISTED'}`)}</Badge> },
    { id: 'actions', header: '', enableSorting: false, meta: { card: 'actions' },
      cell: ({ row }) => <Button variant="outline" size="sm" onClick={() => setEditing(row.original)}
        data-testid="benefit-offer-edit">{t('benefits.offers.edit')}</Button> },
  ], [t])
  return (
    <section className="flex flex-col gap-4" data-testid="benefit-offers">
      <AreaPageHeader title={t('benefits.offers.title')} description={t('benefits.offers.intro')}
        primaryAction={mainAction({ label: t('benefits.offers.add'), icon: Plus, testId: 'benefit-offer-add',
          onClick: () => setEditing('new') })} />
      <BenefitAccess />
      <Loading query={offers}>{() => (offers.data ?? []).length === 0 ? (
        <PageState kind="empty" title={t('benefits.offers.empty')} />
      ) : (
        <DataTable label={t('benefits.offers.title')} columns={columns} data={offers.data!}
          storageKey="qb.sponsor.benefit-offers" getRowId={(row) => row.offerId}
          rowProps={(row) => ({ 'data-testid': 'benefit-offer-row', 'data-id': row.offerId })} />
      )}</Loading>
      {editing && <OfferDialog offer={editing === 'new' ? null : editing} onClose={() => setEditing(null)}
        onSaved={() => {
          notify.success(t('benefits.offers.saved'))
          setEditing(null)
          void queryClient.invalidateQueries({ queryKey: BENEFITS_QUERY })
        }} />}
    </section>
  )
}

const OFFER_FIELDS = ['kind', 'name', 'description', 'images', 'unitValue', 'pickupAddress', 'pickupHours', 'terms']

function OfferDialog({ offer, onClose, onSaved }: { offer: Offer | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [form, setForm] = useState<OfferInput>(() => ({
    offerId: offer?.offerId, kind: offer?.kind ?? 'GOODS', name: offer?.name ?? '', description: offer?.description ?? '',
    images: offer?.images ?? [], unitValue: offer ? String(offer.unitValue) : '', pickupAddress: offer?.pickupAddress ?? '',
    pickupHours: offer?.pickupHours ?? '', terms: offer?.terms ?? '', listed: offer ? offer.status === 'LISTED' : true,
    baseRevision: offer?.revision,
  }))
  const [uploading, setUploading] = useState(0)
  const set = <K extends keyof OfferInput>(key: K, value: OfferInput[K]) =>
    setForm((current) => ({ ...current, [key]: value }))
  const save = useMutation({ mutationFn: () => saveOffer(form), onSuccess: onSaved })
  const problem = (field: string) => fieldProblem(save.error, field)
  const error = (field: string) => problem(field) && <p className="text-destructive text-sm">{problem(field)}</p>
  const general = generalProblem(save.error, OFFER_FIELDS)
  const digital = form.kind === 'DIGITAL'
  const submit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate()
  }
  const field = (key: 'name' | 'pickupAddress' | 'pickupHours', max: number) => (
    <div className="flex flex-col gap-2">
      <Label htmlFor={`${id}-${key}`}>{t(`benefits.offers.${key}`)}</Label>
      <Input id={`${id}-${key}`} value={form[key]} maxLength={max} aria-invalid={problem(key) ? true : undefined}
        onChange={(event) => set(key, event.target.value)} data-testid={`benefit-offer-${key}`} />
      {error(key)}
    </div>
  )
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg" data-testid="benefit-offer-dialog">
        <DialogHeader>
          <DialogTitle>{offer ? t('benefits.offers.dialogEdit') : t('benefits.offers.dialogNew')}</DialogTitle>
          <DialogDescription>{t('benefits.offers.intro')}</DialogDescription>
        </DialogHeader>
        <form id={`${id}-form`} noValidate onSubmit={submit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-kind`}>{t('benefits.catalog.kind')}</Label>
            <Select value={form.kind} onValueChange={(value) => set('kind', value as BenefitKind)}>
              <SelectTrigger id={`${id}-kind`} data-testid="benefit-offer-kind"><SelectValue /></SelectTrigger>
              <SelectContent>
                {BENEFIT.kinds.map((k) => <SelectItem key={k} value={k}>{t(`benefits.kinds.${k}`)}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          {field('name', BENEFIT.nameLength)}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-description`}>{t('benefits.offers.description')}</Label>
            <Textarea id={`${id}-description`} rows={4} value={form.description} maxLength={BENEFIT.descriptionLength}
              aria-invalid={problem('description') ? true : undefined} data-testid="benefit-offer-description"
              onChange={(event) => set('description', event.target.value)} />
            {error('description')}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-images`}>{t('benefits.offers.images', { max: BENEFIT.maxImages })}</Label>
            {form.images.length > 0 && (
              <ul className="flex flex-wrap gap-2">
                {form.images.map((image) => (
                  <li key={image} className="flex flex-col items-center gap-1">
                    <FileImage fileId={image} variant="w256" alt={t('benefits.offers.imageAlt')}
                      className="size-16 rounded border" />
                    <Button type="button" variant="ghost" size="sm"
                      onClick={() => set('images', form.images.filter((other) => other !== image))}>
                      {t('benefits.offers.removeImage')}</Button>
                  </li>
                ))}
              </ul>
            )}
            {form.images.length < BENEFIT.maxImages && (
              <FileUpload id={`${id}-images`} accept={IMAGE_TYPES} maxSize={5 * 1024 * 1024} maxSizeText="5 MB"
                label={t('benefits.offers.upload')} hint={t('benefits.offers.imageHint')} data-testid="benefit-offer-image"
                onUpload={async (file) => {
                  setUploading((count) => count + 1)
                  try {
                    const uploaded = await uploadFile(BENEFIT.imagePolicy, file, neutralName('image', file.name))
                    if (uploaded.fileId) setForm((current) => ({ ...current, images: [...current.images, uploaded.fileId!] }))
                  } finally {
                    setUploading((count) => count - 1)
                  }
                }} />
            )}
            {error('images')}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-value`}>{t('benefits.offers.unitValue')}</Label>
            <Input id={`${id}-value`} value={form.unitValue} inputMode="numeric" className="w-40"
              aria-invalid={problem('unitValue') ? true : undefined} aria-describedby={`${id}-value-hint`}
              onChange={(event) => set('unitValue', event.target.value)} data-testid="benefit-offer-value" />
            <p id={`${id}-value-hint`} className="text-muted-foreground text-sm">{t('benefits.offers.unitValueHint')}</p>
            {error('unitValue')}
          </div>
          {!digital && field('pickupAddress', BENEFIT.addressLength)}
          {!digital && field('pickupHours', BENEFIT.hoursLength)}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-terms`}>{t('benefits.offers.terms')}</Label>
            <Textarea id={`${id}-terms`} rows={2} value={form.terms} maxLength={BENEFIT.termsLength}
              onChange={(event) => set('terms', event.target.value)} data-testid="benefit-offer-terms" />
          </div>
          <div className="flex items-center gap-2">
            <Checkbox id={`${id}-listed`} checked={form.listed} disabled={offer?.status === 'TAKEN_DOWN'}
              onCheckedChange={(value) => set('listed', value === true)} data-testid="benefit-offer-listed" />
            <Label htmlFor={`${id}-listed`} className="font-normal">{t('benefits.offers.listed')}</Label>
          </div>
          {general && <Alert variant="destructive" data-testid="benefit-offer-error"><AlertDescription>{general}</AlertDescription></Alert>}
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('benefits.offers.cancel')}</Button>
          <Button type="submit" form={`${id}-form`} disabled={save.isPending || uploading > 0}
            data-testid="benefit-offer-save">{t('benefits.offers.save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ---- conversations ---------------------------------------------------------------------------------------------------

/** The two sides as tabs whose value is the address (`?as=provider`), so that a link opens either one. */
function SideTabs({ side, base, mine, received }: { side: Side; base: string; mine: string; received: string }) {
  return (
    <Tabs value={side} onValueChange={(value) => navigate(value === 'PROVIDER' ? `${base}?as=provider` : base)}>
      <TabsList>
        <TabsTrigger value="SPONSOR" data-testid="benefit-side-sponsor">{mine}</TabsTrigger>
        <TabsTrigger value="PROVIDER" data-testid="benefit-side-provider">{received}</TabsTrigger>
      </TabsList>
    </Tabs>
  )
}

export function BenefitConversationsPage({ side }: { side: Side }) {
  const { t } = useTranslation(SPONSOR)
  const list = useQuery({ queryKey: [...BENEFITS_QUERY, 'conversations', side], queryFn: () => readConversations(side) })
  const columns = useMemo<ColumnDef<Conversation, unknown>[]>(() => [
    { id: 'offer', header: t('benefits.conversations.columns.offer'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => (
        <Link to={conversationPath(row.original.conversationId, side)} data-testid="benefit-conversation-open"
          className={cn('underline-offset-4 hover:underline', row.original.unread && 'font-semibold')}>
          {row.original.offerName}
        </Link>
      ) },
    { id: 'company', header: t('benefits.conversations.columns.company'), enableSorting: false,
      meta: { card: 'subtitle' }, cell: ({ row }) => row.original.otherCompany ?? '—' },
    { id: 'state', header: '', enableSorting: false, meta: { card: 'badge' },
      cell: ({ row }) => row.original.unread ? <Badge>{t('benefits.conversations.new')}</Badge> : null },
    { id: 'last', header: t('benefits.conversations.columns.last'), enableSorting: false,
      meta: { className: 'max-w-80 truncate' }, cell: ({ row }) => row.original.lastBody ?? '—' },
    { id: 'when', header: t('benefits.conversations.columns.when'), enableSorting: false,
      cell: ({ row }) => formatDateTime(row.original.lastMessageAt) },
  ], [t, side])
  return (
    <section className="flex flex-col gap-4" data-testid="benefit-conversations">
      <AreaPageHeader title={t('benefits.conversations.title')} description={t('benefits.conversations.intro')} />
      <SideTabs side={side} base={BENEFIT_CONVERSATIONS_PATH} mine={t('benefits.conversations.mine')}
        received={t('benefits.conversations.received')} />
      <Loading query={list}>{() => (list.data ?? []).length === 0 ? (
        <PageState kind="empty" title={t('benefits.conversations.empty')} />
      ) : (
        <DataTable label={t('benefits.conversations.title')} columns={columns} data={list.data!}
          storageKey={`qb.sponsor.benefit-conversations.${side}`} getRowId={(row) => row.conversationId}
          rowProps={(row) => ({ 'data-testid': 'benefit-conversation-row', 'data-id': row.conversationId,
            'data-unread': row.unread ? 'true' : 'false' })} />
      )}</Loading>
    </section>
  )
}

/** One conversation about a benefit: the messages and a box to write; opening it marks it read. */
export function BenefitConversationPage({ conversationId, side }: { conversationId: string; side: Side }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const key = [...BENEFITS_QUERY, 'conversation', side, conversationId]
  const conversation = useQuery({ queryKey: key,
    queryFn: async () => (await readConversations(side, conversationId))[0] ?? null })
  const messages = useQuery({ queryKey: [...key, 'messages'], queryFn: () => readMessages(side, conversationId) })
  const unread = conversation.data?.unread === true
  useEffect(() => {
    if (!unread) return
    void markRead(conversationId).then(() => queryClient.invalidateQueries({ queryKey: BENEFITS_QUERY }),
      () => undefined)
  }, [unread, conversationId, queryClient])
  if (conversation.isPending || messages.isPending) return <PageState kind="loading" />
  if (conversation.isError || messages.isError) {
    return <PageState kind="error" description={problemText(conversation.error ?? messages.error)} />
  }
  const data = conversation.data
  if (!data) return <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />
  const other = data.otherCompany ?? '—'
  return (
    <section className="flex flex-col gap-4" aria-labelledby="benefit-conversation-title" data-testid="benefit-conversation">
      <div className="flex flex-col gap-1">
        <Link to={side === 'PROVIDER' ? `${BENEFIT_CONVERSATIONS_PATH}?as=provider` : BENEFIT_CONVERSATIONS_PATH}
          className="text-muted-foreground inline-flex w-fit items-center gap-1 text-sm">
          <ArrowLeft aria-hidden className="size-4" />{t('benefits.conversations.back')}
        </Link>
        <h1 id="benefit-conversation-title" className="text-xl font-semibold">{data.offerName}</h1>
        <p className="text-muted-foreground text-sm" data-testid="benefit-conversation-company">{other}</p>
      </div>
      <ol className="flex flex-col gap-2" aria-label={t('benefits.conversations.messages')}>
        {messages.data.map((message) => (
          <li key={message.messageId} data-testid="benefit-message" data-mine={message.mine ? 'true' : 'false'}
            className={cn('flex max-w-[85%] flex-col gap-1 rounded-lg border p-3',
              message.mine ? 'bg-accent self-end' : 'self-start')}>
            <span className="text-muted-foreground text-xs">
              {message.mine ? t('benefits.conversations.you') : other}{' · '}{formatDateTime(message.createdAt)}
            </span>
            <span className="text-sm whitespace-pre-wrap break-words">
              {message.body ?? <em className="text-muted-foreground">{t('benefits.conversations.erased')}</em>}
            </span>
          </li>
        ))}
      </ol>
      <Reply conversationId={conversationId}
        onSent={() => void queryClient.invalidateQueries({ queryKey: BENEFITS_QUERY })} />
      {side === 'SPONSOR' && (
        <Button asChild variant="outline" size="sm" className="self-start">
          <Link to={newPurchasePath(data.offerId)}>{t('benefits.catalog.buy')}</Link>
        </Button>
      )}
    </section>
  )
}

function Reply({ conversationId, onSent }: { conversationId: string; onSent: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [body, setBody] = useState('')
  const send = useMutation({ mutationFn: () => sendMessage(conversationId, body), onSuccess: () => {
    setBody('')
    onSent()
  } })
  return (
    <form className="flex flex-col gap-2" data-testid="benefit-reply" onSubmit={(event) => {
      event.preventDefault()
      if (body.trim()) send.mutate()
    }}>
      <Label htmlFor={`${id}-body`}>{t('benefits.conversations.reply')}</Label>
      <Textarea id={`${id}-body`} value={body} rows={4} maxLength={BENEFIT.messageLength}
        aria-describedby={`${id}-hint`} onChange={(event) => setBody(event.target.value)} data-testid="benefit-reply-body" />
      <p id={`${id}-hint`} className="text-muted-foreground text-xs">{t('benefits.conversations.hint')}</p>
      {send.isError && <p className="text-destructive text-sm" role="alert">{problemText(send.error)}</p>}
      <Button type="submit" className="self-end" disabled={!body.trim() || send.isPending}
        data-testid="benefit-reply-send">{t('benefits.conversations.send')}</Button>
    </form>
  )
}

// ---- purchases -------------------------------------------------------------------------------------------------------

export function PurchasesPage({ side }: { side: Side }) {
  const { t } = useTranslation(SPONSOR)
  const list = useQuery({ queryKey: [...BENEFITS_QUERY, 'purchases', side], queryFn: () => readPurchases(side) })
  const columns = useMemo<ColumnDef<Purchase, unknown>[]>(() => [
    { id: 'title', header: t('benefits.purchases.columns.title'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => <Link to={purchasePath(row.original.purchaseId, side)} data-testid="benefit-purchase-open"
        className="underline-offset-4 hover:underline">{row.original.title}</Link> },
    { id: 'company', header: t('benefits.purchases.columns.company'), enableSorting: false, meta: { card: 'subtitle' },
      cell: ({ row }) => row.original.otherCompany ?? '—' },
    { id: 'quantity', header: t('benefits.purchases.columns.quantity'), enableSorting: false,
      cell: ({ row }) => row.original.quantity.toLocaleString() },
    { id: 'paid', header: t('benefits.purchases.columns.paid'), enableSorting: false,
      cell: ({ row }) => formatKudos(row.original.paidAmount) },
    { id: 'status', header: t('benefits.purchases.columns.status'), enableSorting: false, meta: { card: 'badge' },
      cell: ({ row }) => <Badge variant={row.original.status === 'CONFIRMED' ? 'default' : 'outline'}
        data-testid="benefit-purchase-status">{t(`benefits.purchases.status.${row.original.status}`)}</Badge> },
  ], [t, side])
  return (
    <section className="flex flex-col gap-4" data-testid="benefit-purchases">
      <AreaPageHeader title={t('benefits.purchases.title')} description={t('benefits.purchases.intro')} />
      <SideTabs side={side} base={PURCHASES_PATH} mine={t('benefits.purchases.mine')}
        received={t('benefits.purchases.received')} />
      <Loading query={list}>{() => (list.data ?? []).length === 0 ? (
        <PageState kind="empty" title={t('benefits.purchases.empty')} />
      ) : (
        <DataTable label={t('benefits.purchases.title')} columns={columns} data={list.data!}
          storageKey={`qb.sponsor.benefit-purchases.${side}`} getRowId={(row) => row.purchaseId}
          rowProps={(row) => ({ 'data-testid': 'benefit-purchase-row', 'data-id': row.purchaseId })} />
      )}</Loading>
    </section>
  )
}

/** Opens a proof (a PDF or an image) in a new tab: files need the session, so through an object URL. */
function ProofButton({ fileId, n }: { fileId: string; n: number }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <Button type="button" variant="link" size="sm" className="h-auto p-0" data-testid="benefit-proof"
      onClick={() => void fetchFileContent(fileId).then((blob) => {
        const url = URL.createObjectURL(blob)
        window.open(url, '_blank', 'noopener')
        setTimeout(() => URL.revokeObjectURL(url), 60_000)
      }, (error) => notify.error(problemText(error)))}>
      {t('benefits.purchases.proof', { n })}
    </Button>
  )
}

/**
 * One purchase. The sponsor writes it (a new one from a benefit of the catalog), sends it and withdraws it; the
 * provider confirms the revision it reads or sends it back. Every answer carries the revision the page read.
 */
export function PurchasePage({ purchaseId, side, offerId }: { purchaseId: string | null; side: Side; offerId?: string | null }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const purchase = useQuery({ queryKey: [...BENEFITS_QUERY, 'purchase', side, purchaseId],
    queryFn: async () => (await readPurchases(side, purchaseId!))[0] ?? null, enabled: !!purchaseId })
  const offer = useQuery({ queryKey: [...BENEFITS_QUERY, 'catalog', 'one', offerId],
    queryFn: async () => (await readCatalog({ offerId: offerId! }))[0] ?? null, enabled: !purchaseId && !!offerId })
  if (purchaseId ? purchase.isPending : offer.isPending) return <PageState kind="loading" />
  if (purchase.isError || offer.isError) return <PageState kind="error" description={problemText(purchase.error ?? offer.error)} />
  const data = purchaseId ? purchase.data : null
  if (purchaseId && !data) return <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />
  if (!purchaseId && !offer.data) return <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />
  const title = data ? data.title : t('benefits.purchases.newTitle', { name: offer.data!.name })
  const benefit = data ?? offer.data!
  return (
    <section className="flex flex-col gap-4" aria-labelledby="benefit-purchase-title" data-testid="benefit-purchase">
      <div className="flex flex-col gap-1">
        <Link to={side === 'PROVIDER' ? `${PURCHASES_PATH}?as=provider` : PURCHASES_PATH}
          className="text-muted-foreground inline-flex w-fit items-center gap-1 text-sm">
          <ArrowLeft aria-hidden className="size-4" />{t('benefits.purchases.back')}
        </Link>
        <h1 id="benefit-purchase-title" className="text-xl font-semibold">{title}</h1>
        {data && (
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <Badge variant={data.status === 'CONFIRMED' ? 'default' : 'outline'} data-testid="benefit-purchase-state">
              {t(`benefits.purchases.status.${data.status}`)}</Badge>
            <span className="text-muted-foreground">{data.otherCompany ?? '—'}</span>
          </div>
        )}
      </div>
      <Card>
        <CardHeader><CardTitle className="text-base">{t('benefits.purchases.benefit')}</CardTitle></CardHeader>
        <CardContent className="flex flex-col gap-1 text-sm">
          <p>{t(`benefits.kinds.${benefit.kind}`)}{' · '}
            {t('benefits.catalog.value', { value: formatKudos(benefit.unitValue) })}</p>
          {benefit.pickupAddress && <p className="text-muted-foreground">{t('benefits.catalog.pickup', {
            address: benefit.pickupAddress, hours: benefit.pickupHours ?? '' })}</p>}
          {benefit.terms && <p className="text-muted-foreground whitespace-pre-wrap">{benefit.terms}</p>}
        </CardContent>
      </Card>
      <p className="text-muted-foreground text-sm">{t('benefits.purchases.noPayment')}</p>
      {data?.declineReason && data.status === 'DRAFT' && (
        <Alert data-testid="benefit-purchase-sent-back"><AlertDescription>
          {t('benefits.purchases.sentBack', { reason: data.declineReason })}</AlertDescription></Alert>
      )}
      {side === 'SPONSOR'
        ? <PurchaseForm key={`${data?.purchaseId}:${data?.revision}`} purchase={data ?? null} offerId={offerId ?? null} />
        : <ProviderAnswer purchase={data!} />}
      {side === 'PROVIDER' && data && data.status !== 'DRAFT' && <ProvidedPublications purchase={data} />}
      {/* Q89d-1: what became of it, its suspension and incidents (both sides). */}
      {data && (data.status === 'CONFIRMED' || data.status === 'SUSPENDED' || data.status === 'CLOSED') && (
        <PurchaseFollowUp purchase={data} side={side} />
      )}
    </section>
  )
}

const PURCHASE_FIELDS = ['quantity', 'paidAmount', 'paidOn', 'proofs', 'note', 'baseRevision']

function PurchaseForm({ purchase, offerId }: { purchase: Purchase | null; offerId: string | null }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const editable = !purchase || ['DRAFT', 'PENDING_PROVIDER', 'CONFIRMED'].includes(purchase.status)
  const [form, setForm] = useState<PurchaseInput>(() => ({
    purchaseId: purchase?.purchaseId, offerId: purchase ? undefined : offerId ?? undefined,
    quantity: purchase ? String(purchase.quantity) : '', paidAmount: purchase ? String(purchase.paidAmount) : '',
    paidOn: purchase?.paidOn ?? '', proofs: purchase?.proofs ?? [], note: purchase?.note ?? '',
    baseRevision: purchase?.revision,
  }))
  const [uploading, setUploading] = useState(0)
  const set = <K extends keyof PurchaseInput>(key: K, value: PurchaseInput[K]) =>
    setForm((current) => ({ ...current, [key]: value }))
  const done = (message: string) => (output: { purchaseId: string }) => {
    notify.success(message)
    void queryClient.invalidateQueries({ queryKey: BENEFITS_QUERY })
    if (!purchase) navigate(purchasePath(output.purchaseId, 'SPONSOR'), { replace: true })
  }
  const save = useMutation({ mutationFn: () => savePurchase(form), onSuccess: done(t('benefits.purchases.saved')) })
  const submit = useMutation({ mutationFn: () => submitPurchase(purchase!.purchaseId, purchase!.revision),
    onSuccess: done(t('benefits.purchases.submitted')), onError: (error) => notify.error(problemText(error)) })
  const problem = (field: string) => fieldProblem(save.error, field)
  const error = (field: string) => problem(field) && <p className="text-destructive text-sm">{problem(field)}</p>
  const general = generalProblem(save.error, PURCHASE_FIELDS.filter((field) => field !== 'baseRevision'))
  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate()
  }
  if (!editable) return null
  return (
    <form noValidate onSubmit={onSubmit} className="flex max-w-xl flex-col gap-4" data-testid="benefit-purchase-form">
      {purchase?.status === 'CONFIRMED' && <p className="text-muted-foreground text-sm">{t('benefits.purchases.changedConfirmed')}</p>}
      {purchase?.status === 'PENDING_PROVIDER' && <p className="text-muted-foreground text-sm">{t('benefits.purchases.waiting')}</p>}
      <div className="flex flex-wrap gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-quantity`}>{t('benefits.purchases.quantity')}</Label>
          <Input id={`${id}-quantity`} inputMode="numeric" className="w-32" value={form.quantity}
            aria-invalid={problem('quantity') ? true : undefined} data-testid="benefit-purchase-quantity"
            onChange={(event) => set('quantity', event.target.value)} />
          {error('quantity')}
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-paid`}>{t('benefits.purchases.paidAmount')}</Label>
          <Input id={`${id}-paid`} inputMode="numeric" className="w-40" value={form.paidAmount}
            aria-invalid={problem('paidAmount') ? true : undefined} data-testid="benefit-purchase-paid"
            onChange={(event) => set('paidAmount', event.target.value)} />
          {error('paidAmount')}
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-paidOn`}>{t('benefits.purchases.paidOn')}</Label>
          <Input id={`${id}-paidOn`} type="date" className="w-44" value={form.paidOn}
            aria-invalid={problem('paidOn') ? true : undefined} data-testid="benefit-purchase-paid-on"
            onChange={(event) => set('paidOn', event.target.value)} />
          {error('paidOn')}
        </div>
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-proofs`}>{t('benefits.purchases.proofs', { max: BENEFIT.maxProofs })}</Label>
        <ul className="flex flex-col gap-1">
          {form.proofs.map((proof, i) => (
            <li key={proof} className="flex items-center gap-2" data-testid="benefit-purchase-proof">
              <ProofButton fileId={proof} n={i + 1} />
              <Button type="button" variant="ghost" size="sm"
                onClick={() => set('proofs', form.proofs.filter((other) => other !== proof))}>
                {t('benefits.purchases.remove')}</Button>
            </li>
          ))}
        </ul>
        {form.proofs.length < BENEFIT.maxProofs && (
          <FileUpload id={`${id}-proofs`} accept={PROOF_TYPES} maxSize={10 * 1024 * 1024} maxSizeText="10 MB"
            label={t('benefits.purchases.upload')} hint={t('benefits.purchases.proofHint')}
            data-testid="benefit-purchase-upload"
            onUpload={async (file) => {
              setUploading((count) => count + 1)
              try {
                const uploaded = await uploadFile(BENEFIT.proofPolicy, file, proofName(file.name))
                if (uploaded.fileId) setForm((current) => ({ ...current, proofs: [...current.proofs, uploaded.fileId!] }))
              } finally {
                setUploading((count) => count - 1)
              }
            }} />
        )}
        {error('proofs')}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-note`}>{t('benefits.purchases.note')}</Label>
        <Textarea id={`${id}-note`} rows={3} value={form.note} maxLength={BENEFIT.noteLength}
          onChange={(event) => set('note', event.target.value)} data-testid="benefit-purchase-note" />
      </div>
      {general && <Alert variant="destructive" data-testid="benefit-purchase-error"><AlertDescription>{general}</AlertDescription></Alert>}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={save.isPending || uploading > 0} data-testid="benefit-purchase-save">
          {t('benefits.purchases.save')}</Button>
        {purchase?.status === 'DRAFT' && (
          <Button type="button" variant="secondary" disabled={submit.isPending} onClick={() => submit.mutate()}
            data-testid="benefit-purchase-submit">{t('benefits.purchases.submit')}</Button>
        )}
        {purchase && (
          <ConfirmDialog destructive title={t('benefits.purchases.cancelTitle')} description={t('benefits.purchases.cancelBody')}
            confirmLabel={t('benefits.purchases.cancel')} cancelLabel={t('benefits.offers.cancel')}
            onConfirm={() => cancelPurchase(purchase.purchaseId, purchase.revision).then(() => {
              notify.success(t('benefits.purchases.cancelled'))
              void queryClient.invalidateQueries({ queryKey: BENEFITS_QUERY })
            }, (problem) => {
              notify.error(problemText(problem))
              throw problem
            })}
            trigger={<Button type="button" variant="outline" data-testid="benefit-purchase-cancel">
              {t('benefits.purchases.cancel')}</Button>} />
        )}
      </div>
    </form>
  )
}

/** The provider's view: what the sponsor says, the proofs, and the answer to a purchase waiting for it. */
function ProviderAnswer({ purchase }: { purchase: Purchase }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [declining, setDeclining] = useState(false)
  const [reason, setReason] = useState('')
  const refresh = () => void queryClient.invalidateQueries({ queryKey: BENEFITS_QUERY })
  const decline = useMutation({ mutationFn: () => declinePurchase(purchase.purchaseId, reason, purchase.revision),
    onSuccess: () => {
      notify.success(t('benefits.purchases.declined'))
      setDeclining(false)
      refresh()
    } })
  return (
    <div className="flex max-w-xl flex-col gap-4" data-testid="benefit-purchase-answer">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-foreground">{t('benefits.purchases.quantity')}</dt>
        <dd data-testid="benefit-purchase-quantity">{purchase.quantity.toLocaleString()}</dd>
        <dt className="text-muted-foreground">{t('benefits.purchases.paidAmount')}</dt>
        <dd>{formatKudos(purchase.paidAmount)}</dd>
        <dt className="text-muted-foreground">{t('benefits.purchases.paidOn')}</dt>
        <dd>{purchase.paidOn}</dd>
        <dt className="text-muted-foreground">{t('benefits.purchases.proofs', { max: BENEFIT.maxProofs })}</dt>
        <dd className="flex flex-wrap gap-3">{purchase.proofs.map((proof, i) =>
          <ProofButton key={proof} fileId={proof} n={i + 1} />)}</dd>
        {purchase.note && <>
          <dt className="text-muted-foreground">{t('benefits.purchases.note')}</dt>
          <dd className="whitespace-pre-wrap">{purchase.note}</dd>
        </>}
      </dl>
      {purchase.status === 'CONFIRMED' && purchase.confirmedAt && (
        <p className="text-sm">{t('benefits.purchases.confirmedAt', { when: formatDateTime(purchase.confirmedAt) })}</p>
      )}
      {purchase.status === 'PENDING_PROVIDER' && (
        <div className="flex flex-wrap gap-2">
          <ConfirmDialog title={t('benefits.purchases.confirmTitle')}
            description={t('benefits.purchases.confirmBody', { paid: formatKudos(purchase.paidAmount),
              quantity: purchase.quantity.toLocaleString() })}
            confirmLabel={t('benefits.purchases.confirm')} cancelLabel={t('benefits.offers.cancel')}
            onConfirm={() => confirmPurchase(purchase.purchaseId, purchase.revision).then(() => {
              notify.success(t('benefits.purchases.confirmed'))
              refresh()
            }, (problem) => {
              notify.error(problemText(problem))
              refresh()
              throw problem
            })}
            trigger={<Button data-testid="benefit-purchase-confirm">{t('benefits.purchases.confirm')}</Button>} />
          <Button variant="outline" onClick={() => setDeclining(true)} data-testid="benefit-purchase-decline">
            {t('benefits.purchases.decline')}</Button>
        </div>
      )}
      <Dialog open={declining} onOpenChange={setDeclining}>
        <DialogContent data-testid="benefit-decline-dialog">
          <DialogHeader>
            <DialogTitle>{t('benefits.purchases.declineTitle')}</DialogTitle>
            <DialogDescription>{purchase.title}</DialogDescription>
          </DialogHeader>
          <form id={`${id}-decline`} className="flex flex-col gap-2" onSubmit={(event) => {
            event.preventDefault()
            if (reason.trim()) decline.mutate()
          }}>
            <Label htmlFor={`${id}-reason`}>{t('benefits.purchases.reason')}</Label>
            <Textarea id={`${id}-reason`} rows={3} value={reason} maxLength={BENEFIT.reasonLength}
              onChange={(event) => setReason(event.target.value)} data-testid="benefit-decline-reason" />
            {decline.isError && <p className="text-destructive text-sm" role="alert">{problemText(decline.error)}</p>}
          </form>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeclining(false)}>{t('benefits.offers.cancel')}</Button>
            <Button type="submit" form={`${id}-decline`} disabled={!reason.trim() || decline.isPending}
              data-testid="benefit-decline-send">{t('benefits.purchases.decline')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/**
 * Q89b-2: the buyer's publications that give the purchase away. A digital product's pool is the provider's to load
 * (its codes are sealed under the provider's key and opened under it), so each pooled one opens its pool here.
 */
function ProvidedPublications({ purchase }: { purchase: Purchase }) {
  const { t } = useTranslation(SPONSOR)
  const statusLabel = useStatusLabel()
  const [open, setOpen] = useState<string | null>(null)
  const publications = useQuery({ queryKey: [...BENEFITS_QUERY, 'provided', purchase.purchaseId],
    queryFn: () => readProvidedPublications(purchase.purchaseId) })
  return (
    <section className="flex flex-col gap-3" aria-labelledby="benefit-provided-title" data-testid="benefit-provided">
      <h2 id="benefit-provided-title" className="text-lg font-semibold">{t('benefits.provided.title')}</h2>
      <p className="text-muted-foreground text-sm">{t('benefits.provided.intro')}</p>
      {publications.isError && <p className="text-destructive text-sm" role="alert">{problemText(publications.error)}</p>}
      {publications.data?.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="benefit-provided-empty">{t('benefits.provided.empty')}</p>
      )}
      <ul className="flex flex-col gap-3">
        {publications.data?.map((p) => {
          const pooled = p.rewardMethod === 'DIGITAL_PRIZE' && p.digitalDelivery === 'POOL'
          return (
            <li key={p.publicationId} className="flex flex-col gap-3 rounded-md border p-3" data-testid="benefit-provided-row">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{p.name}</span>
                <Badge variant="outline">{statusLabel(p.status)}</Badge>
                {p.sponsorCompany && <span className="text-muted-foreground text-sm">
                  {t('benefits.provided.by', { company: p.sponsorCompany })}</span>}
                {pooled && <span className="text-muted-foreground text-sm tabular-nums">
                  {t('benefits.provided.pool', { available: p.available, issued: p.issued })}</span>}
                {pooled && (
                  <Button variant="outline" size="sm" className="ml-auto" aria-expanded={open === p.publicationId}
                    onClick={() => setOpen(open === p.publicationId ? null : p.publicationId)}
                    data-testid="benefit-provided-codes">{t('benefits.provided.manage')}</Button>
                )}
              </div>
              {pooled && open === p.publicationId && (
                <DigitalPoolSection publicationId={p.publicationId} status={p.status as PublicationStatus} mode="provided" />
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
