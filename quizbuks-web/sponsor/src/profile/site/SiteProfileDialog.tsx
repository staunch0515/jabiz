import { ApiError } from '@jabiz/client'
import { Alert, AlertDescription, Button, Checkbox, Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader,
  DialogTitle, Input, Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Spinner } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { AI_NOTICE_QUERY, AiNoticeSection, useAiNotice } from '../../ai/AiQuestionsPanel'
import { AI_CODE } from '../../ai/ids'
import { reasonsOf } from '../../content/QuizTransfer'
import { SPONSOR } from '../../messages'
import { CONTENT_LANGUAGES, contentLanguageOf, type ContentLanguage } from '../../qb/api'
import { QB } from '../../qb/messages'
import { cancelSiteDraft, discardSiteDraft, isReady, readSiteDrafts, requestSiteProfile, SITE_QUERY, stillRunning,
  type SiteDraft } from './api'
import { SITE_FAILURES, SITE_POLL } from './ids'
import './messages'

/**
 * "Draft from your website" (F-C7b, docs/quizbuks/plans/impl/F-C7.md): the sponsor gives the address, the languages and
 * what to draft, declares the website is theirs, accepts the AI notice on first use, and the server reads the website
 * (one to three minutes; leaving the page does not stop it). A finished draft is reviewed in the usual profile import's
 * confirmation ({@link ProfileTransfer}); nothing is written before the sponsor confirms it there.
 */
export function SiteProfileDialog({ open, onClose, onReview, onPrompt }: {
  open: boolean
  onClose: () => void
  /** The sponsor reviews a ready draft: the import's confirmation takes it. */
  onReview: (draft: SiteDraft) => void
  /** The failure's way out: the prompt for an AI tool (F-C5). */
  onPrompt: () => void
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const id = useId()
  const drafts = useQuery({
    queryKey: SITE_QUERY,
    queryFn: readSiteDrafts,
    enabled: open,
    refetchInterval: (query) => (stillRunning(query.state.data?.[0] ?? null) ? SITE_POLL.everyMs : false),
  })
  const latest = drafts.data?.[0] ?? null
  const running = stillRunning(latest)
  const ready = isReady(latest) ? latest : null
  const failed = latest?.status === 'FAILED' ? latest : null
  const notice = useAiNotice(open)

  const [address, setAddress] = useState('')
  const [base, setBase] = useState<ContentLanguage>(() => contentLanguageOf(i18n.language))
  const [others, setOthers] = useState<ContentLanguage[]>([])
  const [profile, setProfile] = useState(true)
  const [products, setProducts] = useState(true)
  const [quiz, setQuiz] = useState(true)
  const [rights, setRights] = useState(false)
  const [agreed, setAgreed] = useState(false)
  const [problems, setProblems] = useState<string[]>([])

  const refresh = () => queryClient.invalidateQueries({ queryKey: SITE_QUERY })
  const request = useMutation({
    mutationFn: () => requestSiteProfile({ siteAddress: address.trim(), language: base,
      otherLanguages: others.filter((language) => language !== base), profile, products, quiz,
      rightsDeclared: rights, aiNoticeVersionNo: notice.data?.mustAccept ? notice.data.versionNo : null }),
    onSuccess: async () => {
      setProblems([])
      await refresh()
    },
    onError: async (error) => {
      setProblems(reasonsOf(error, t))
      // A newer notice took effect meanwhile: read it again, so that it is shown.
      if (error instanceof ApiError && error.violations.some((v) => v.ruleCode === AI_CODE.noticeRequired)) {
        setAgreed(false)
        await queryClient.invalidateQueries({ queryKey: AI_NOTICE_QUERY })
      }
    },
  })
  const cancel = useMutation({ mutationFn: () => cancelSiteDraft(latest!.aiJobId), onSettled: refresh })
  const discard = useMutation({ mutationFn: () => discardSiteDraft(latest!.aiJobId), onSettled: refresh })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const missing: string[] = []
    if (address.trim() === '') missing.push(t('site.form.addressMissing'))
    if (!profile && !products && !quiz) missing.push(t('site.form.nothing'))
    if (!rights) missing.push(t('site.form.rightsMissing'))
    if (notice.data?.mustAccept && !agreed) missing.push(t('ai.notice.required'))
    setProblems(missing)
    if (missing.length === 0) request.mutate()
  }

  const showForm = !running && !ready
  const reason = failed?.failureCode && (SITE_FAILURES as readonly string[]).includes(failed.failureCode)
    ? failed.failureCode : 'general'
  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next && !request.isPending) onClose() }}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl" data-testid="site-dialog">
        <DialogHeader>
          <DialogTitle>{t('site.title')}</DialogTitle>
          <DialogDescription>{t('site.description')}</DialogDescription>
        </DialogHeader>
        {running && latest && (
          <div className="flex flex-col gap-3" data-testid="site-running">
            <p role="status" className="flex items-center gap-2 text-sm">
              <Spinner />
              <span>{t('site.running', { address: latest.address })}</span>
            </p>
            <p className="text-muted-foreground text-sm">{t('site.runningLeave')}</p>
            <div>
              <Button variant="outline" disabled={cancel.isPending} data-testid="site-cancel"
                onClick={() => cancel.mutate()}>{t('site.cancel')}</Button>
            </div>
          </div>
        )}
        {ready && (
          <div className="flex flex-col gap-3" data-testid="site-ready">
            <p role="status" className="text-sm">{t('site.ready', { address: ready.address })}</p>
            {ready.expiresAt && <p className="text-muted-foreground text-sm">
              {t('site.expires', { at: new Date(ready.expiresAt).toLocaleDateString(i18n.language) })}</p>}
            <div className="flex flex-wrap gap-2">
              <Button data-testid="site-review" onClick={() => onReview(ready)}>{t('site.reviewDraft')}</Button>
              <Button variant="outline" disabled={discard.isPending} data-testid="site-discard"
                onClick={() => discard.mutate()}>{t('site.discard')}</Button>
            </div>
          </div>
        )}
        {latest?.status === 'QUEUED' && !running && (
          <Alert data-testid="site-slow"><AlertDescription>{t('site.slow')}</AlertDescription></Alert>
        )}
        {failed && showForm && (
          <Alert variant="destructive" data-testid="site-failed" data-reason={reason}>
            <AlertDescription className="flex flex-col gap-2">
              <span role="alert">{t(`site.failure.${reason}`)}</span>
              <span>
                <Button type="button" variant="outline" size="sm" data-testid="site-failed-prompt" onClick={onPrompt}>
                  {t('site.failure.prompt')}
                </Button>
              </span>
            </AlertDescription>
          </Alert>
        )}
        {showForm && (
          <form noValidate onSubmit={submit} className="flex flex-col gap-4" data-testid="site-form">
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-address`}>{t('site.form.address')}</Label>
              <Input id={`${id}-address`} type="url" inputMode="url" placeholder="https://" value={address}
                aria-describedby={`${id}-address-hint`} data-testid="site-address"
                onChange={(event) => setAddress(event.target.value)} />
              <p id={`${id}-address-hint`} className="text-muted-foreground text-sm">{t('site.form.addressHint')}</p>
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-base`}>{t('site.form.base')}</Label>
              <Select value={base} onValueChange={(next) => setBase(next as ContentLanguage)}>
                <SelectTrigger id={`${id}-base`} className="w-48" data-testid="site-base"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {CONTENT_LANGUAGES.map((language) => (
                    <SelectItem key={language} value={language}>{tQb(`languages.${language}`)}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <fieldset className="flex flex-col gap-1">
              <legend className="text-sm font-medium">{t('site.form.others')}</legend>
              <p className="text-muted-foreground text-sm">{t('site.form.othersHint')}</p>
              <div className="flex flex-wrap gap-4">
                {CONTENT_LANGUAGES.filter((language) => language !== base).map((language) => (
                  <span key={language} className="flex items-center gap-2">
                    <Checkbox id={`${id}-other-${language}`} checked={others.includes(language)}
                      data-testid={`site-other-${language}`}
                      onCheckedChange={(checked) => setOthers(checked === true
                        ? [...others, language] : others.filter((other) => other !== language))} />
                    <Label htmlFor={`${id}-other-${language}`}>{tQb(`languages.${language}`)}</Label>
                  </span>
                ))}
              </div>
            </fieldset>
            <fieldset className="flex flex-col gap-2">
              <legend className="text-sm font-medium">{t('site.form.include')}</legend>
              {([['profile', profile, setProfile], ['products', products, setProducts], ['quiz', quiz, setQuiz]] as const)
                .map(([key, value, set]) => (
                  <span key={key} className="flex items-center gap-2">
                    <Checkbox id={`${id}-${key}`} checked={value} data-testid={`site-include-${key}`}
                      onCheckedChange={(checked) => set(checked === true)} />
                    <Label htmlFor={`${id}-${key}`}>{t(`site.form.${key}`)}</Label>
                  </span>
                ))}
            </fieldset>
            <section className="flex flex-col gap-1 rounded-md border p-3 text-sm" data-testid="site-sent">
              <p>{t('site.form.sent')}</p>
              <p className="text-muted-foreground">{t('site.form.counted')}</p>
            </section>
            <span className="flex items-start gap-2">
              <Checkbox id={`${id}-rights`} checked={rights} data-testid="site-rights"
                onCheckedChange={(checked) => setRights(checked === true)} />
              <Label htmlFor={`${id}-rights`} className="leading-snug">{t('site.form.rights')}</Label>
            </span>
            <AiNoticeSection notice={notice} agreed={agreed} onAgreed={setAgreed} />
            {problems.length > 0 && (
              <Alert variant="destructive" data-testid="site-problems">
                <AlertDescription>
                  <ul className="list-disc pl-5">{problems.map((problem, i) => <li key={i}>{problem}</li>)}</ul>
                </AlertDescription>
              </Alert>
            )}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={onClose}>{t('site.close')}</Button>
              <Button type="submit" disabled={request.isPending} data-testid="site-submit">{t('site.submit')}</Button>
            </DialogFooter>
          </form>
        )}
        {!showForm && (
          <DialogFooter>
            <Button variant="outline" onClick={onClose}>{t('site.close')}</Button>
          </DialogFooter>
        )}
      </DialogContent>
    </Dialog>
  )
}
