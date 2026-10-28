import { useAuth } from '@jabiz/client'
import { Button, ConfirmDialog, notify } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, Store } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { AccountSection } from '../qb/AccountSections'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { leaveStaff, readStaffOf, STAFF_PATH, STAFF_QUERY } from './api'
import './messages'

/**
 * The account page's staff sections (Q39): the taker's ID, which a sponsor names them to their staff by, and, once
 * named, whose staff they are on, the way to the counter and leaving.
 */
export function StaffAccount() {
  const { t } = useTranslation(APP)
  const { userId } = useAuth()
  const queryClient = useQueryClient()
  const place = useQuery({ queryKey: STAFF_QUERY, queryFn: readStaffOf })
  const leave = useMutation({
    mutationFn: leaveStaff,
    onSuccess: async () => {
      notify.success(t('staff.left'))
      await queryClient.invalidateQueries({ queryKey: STAFF_QUERY })
    },
    onError: (error) => notify.error(problemText(error)),
  })
  // The clipboard is denied in some browsers and insecure contexts: the ID stays on screen to copy by hand.
  const copy = async () => {
    if (!userId) return
    try {
      await navigator.clipboard.writeText(userId)
      notify.success(t('staff.copied'))
    } catch {
      notify.error(t('staff.copyFailed'))
    }
  }
  const sponsor = place.data?.sponsorCompany ?? t('staff.theSponsor')
  return (
    <>
      <AccountSection title={t('staff.myIdTitle')} testId="account-my-id">
        <p className="text-muted-foreground text-sm">{t('staff.myIdText')}</p>
        <div className="flex items-center gap-2">
          <code className="bg-muted rounded px-2 py-1 font-mono text-sm break-all select-all" data-testid="my-id">
            {userId}
          </code>
          <Button type="button" variant="outline" size="sm" data-testid="my-id-copy" onClick={() => void copy()}>
            <Copy aria-hidden />
            {t('staff.copy')}
          </Button>
        </div>
      </AccountSection>
      {place.data && (
        <AccountSection title={t('staff.placeTitle')} testId="account-staff">
          <p className="text-sm">{t('staff.placeText', { sponsor })}</p>
          <div className="flex flex-wrap gap-2">
            <Button asChild size="sm">
              <Link to={STAFF_PATH} data-testid="staff-desk-link"><Store aria-hidden />{t('staff.openDesk')}</Link>
            </Button>
            <ConfirmDialog title={t('staff.leaveTitle', { sponsor })} description={t('staff.leaveText')}
              confirmLabel={t('staff.leaveConfirm')} cancelLabel={t('staff.cancel')} destructive
              onConfirm={() => leave.mutateAsync().then(() => undefined)}
              trigger={<Button type="button" variant="ghost" size="sm" disabled={leave.isPending}
                data-testid="staff-leave">{t('staff.leave')}</Button>} />
          </div>
        </AccountSection>
      )}
    </>
  )
}
