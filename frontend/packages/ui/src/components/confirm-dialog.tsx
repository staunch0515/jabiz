import { useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { buttonVariants } from './ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from './ui/alert-dialog'

export interface ConfirmDialogProps {
  title: ReactNode
  description?: ReactNode
  /** The confirming button's text; "OK" when absent. */
  confirmLabel?: ReactNode
  cancelLabel?: ReactNode
  /** A destructive action (delete, revoke): the confirming button is drawn in the destructive colour. */
  destructive?: boolean
  /**
   * Runs on confirmation. While a returned promise is pending both buttons are disabled; the dialog closes when it
   * resolves and stays open when it rejects (the caller shows why, for example with `notify.error`).
   */
  onConfirm: () => void | Promise<unknown>
  /** Opens the dialog when clicked (rendered as is, `asChild`); or control it with `open` / `onOpenChange`. */
  trigger?: ReactNode
  open?: boolean
  onOpenChange?: (open: boolean) => void
  /**
   * Where focus goes when the dialog closes and what opened it is gone (the button of a row the action deleted):
   * otherwise focus would fall to the page's body. Focus returns to the opener while it is there.
   */
  returnFocus?: () => HTMLElement | null | undefined
}

/**
 * Asks before an action that is hard to undo, as an alert dialog: focus starts on "Cancel", Escape cancels, and the
 * question is the dialog's name. Replaces antd's `Modal.confirm` / `Popconfirm`.
 */
export function ConfirmDialog({
  title,
  description,
  confirmLabel,
  cancelLabel,
  destructive = false,
  onConfirm,
  trigger,
  open: openProp,
  onOpenChange,
  returnFocus,
}: ConfirmDialogProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const [ownOpen, setOwnOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const open = openProp ?? ownOpen
  // What had focus when the dialog opened (its trigger): focus returns there, or elsewhere when it is gone.
  const opener = useRef<Element | null>(null)
  const setOpen = (next: boolean) => {
    if (next) opener.current = document.activeElement
    if (busy) return
    setOwnOpen(next)
    onOpenChange?.(next)
  }

  const confirm = async (event: React.MouseEvent) => {
    // Kept open until the action has finished; Radix would close it on click.
    event.preventDefault()
    setBusy(true)
    try {
      await onConfirm()
      setBusy(false)
      setOwnOpen(false)
      onOpenChange?.(false)
    } catch {
      setBusy(false)
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      {trigger && <AlertDialogTrigger asChild>{trigger}</AlertDialogTrigger>}
      {/* Without a description the question alone names the dialog; Radix wants that said explicitly. */}
      <AlertDialogContent
        {...(description ? {} : { 'aria-describedby': undefined })}
        onOpenAutoFocus={() => {
          opener.current ??= document.activeElement
        }}
        onCloseAutoFocus={(event) => {
          const from = opener.current
          opener.current = null
          if (from?.isConnected || !returnFocus) return
          const target = returnFocus()
          if (!target) return
          event.preventDefault()
          target.focus()
        }}
      >
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          {description && <AlertDialogDescription>{description}</AlertDialogDescription>}
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>{cancelLabel ?? t('confirm.cancel')}</AlertDialogCancel>
          <AlertDialogAction
            disabled={busy}
            aria-busy={busy || undefined}
            className={destructive ? buttonVariants({ variant: 'destructive' }) : undefined}
            onClick={(event) => void confirm(event)}
          >
            {confirmLabel ?? t('confirm.ok')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
