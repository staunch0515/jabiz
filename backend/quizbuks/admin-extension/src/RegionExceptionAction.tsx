import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  Textarea,
  useAuth,
} from '@jabiz/admin'
import { PATH, PERMISSION } from './ids'
import { addRegionException, readRegionExceptions } from './region-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
/** The exceptions in force that one read covers; more than this and the status of the rest is simply not shown. */
const IN_FORCE_READ = 200
const KEY = ['qb-ext', 'region-in-force'] as const

/**
 * F-F11b: sets a mainland China exception (F-S11) from where the Sponsor or the publication is seen, or says that one
 * is in force. Setting one is the platform's `QB_REGION_EXCEPTION_ADD`, with the target filled in and the reason left
 * for the person to write (the server requires it). Shown only to those who may write exceptions (the server decides
 * the same: an operator sets its own Sponsors' only). One read of the exceptions in force serves every instance on a page.
 */
export function RegionExceptionAction({ kind, id, name }: { kind: 'SPONSOR' | 'PUBLICATION'; id: string; name: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const write = can(PERMISSION.regionWrite)
  const [open, setOpen] = useState(false)
  const inForce = useQuery({
    queryKey: KEY,
    queryFn: () => readRegionExceptions(0, IN_FORCE_READ, undefined, true, new Date().toISOString()),
    enabled: write,
    staleTime: 30_000,
  })
  if (!write || !inForce.data) return null
  const found = inForce.data.items.find((e) => !e.revoked && (kind === 'SPONSOR'
    ? e.kind === 'SPONSOR' && e.sponsorId === id : e.kind === 'PUBLICATION' && e.publicationId === id))
  if (found) {
    return (
      <Link to={PATH.region} className={linkClass} data-testid="region-in-force">
        {t('region.exception.inForce')} ({found.expiresOn ? t('region.exception.until', { day: found.expiresOn })
          : t('region.exception.untilRevoked')})
      </Link>
    )
  }
  return (
    <>
      <Button size="sm" variant="outline" onClick={() => setOpen(true)}
        data-testid={kind === 'SPONSOR' ? 'region-add-for-sponsor' : 'region-add-for-publication'}>
        {t('region.exception.set')}<span className="sr-only">: {name}</span>
      </Button>
      {open && <ExceptionDialog kind={kind} id={id} name={name} onClose={() => setOpen(false)} />}
    </>
  )
}

function ExceptionDialog({ kind, id, name, onClose }: {
  kind: 'SPONSOR' | 'PUBLICATION'; id: string; name: string; onClose: () => void
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const form = useId()
  const queryClient = useQueryClient()
  const [reason, setReason] = useState('')
  const [expiresOn, setExpiresOn] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !reason.trim()) return
    setBusy(true)
    setProblem(null)
    try {
      await addRegionException({ kind, id }, reason.trim(), expiresOn || null)
      notify.success(t('region.exception.done'))
      void queryClient.invalidateQueries({ queryKey: ['qb-ext'] })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(next) => { if (!next) onClose() }}>
      <DialogContent data-testid="region-exception-dialog">
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('region.exception.title', { name })}</DialogTitle>
            <DialogDescription>
              {t(kind === 'SPONSOR' ? 'region.exception.hintSponsor' : 'region.exception.hintPublication')}
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${form}-reason`}>{t('region.exception.reason')}</Label>
            <Textarea id={`${form}-reason`} value={reason} maxLength={500} required data-testid="region-exception-reason"
              onChange={(event) => setReason(event.target.value)} />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${form}-until`}>{t('region.exception.expiresOn')}</Label>
            <Input id={`${form}-until`} type="date" value={expiresOn} className="w-56"
              data-testid="region-exception-until" onChange={(event) => setExpiresOn(event.target.value)} />
          </div>
          {problem && (
            <Alert variant="destructive" data-testid="region-exception-problem">
              <AlertDescription>{problem}</AlertDescription>
            </Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('region.exception.cancel')}</Button>
            <Button type="submit" disabled={busy || !reason.trim()} aria-busy={busy || undefined}
              data-testid="region-exception-save">{t('region.exception.save')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
