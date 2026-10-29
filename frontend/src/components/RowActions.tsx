import { api, unwrap, ApiError } from '@jabiz/client'
import {
  Button,
  ConfirmDialog,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  notify,
} from '@jabiz/ui'
import { ChevronDown, Ellipsis } from 'lucide-react'
import { useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import { actionShown, needsOnlyTheKey } from '../meta/actions'
import { pendingApproval } from '../meta/approval'
import type { ProcessEntry } from '../meta/types'
import { paths } from '../pages/paths'

/** A page about the row the menu links to (its history, its audit trail). */
export interface RowLink {
  label: string
  to: string
  icon?: ReactNode
  testId: string
}

interface Props {
  /** The processes acting on the entity (from the catalog, so the caller may run them). */
  actions: ProcessEntry[]
  id: unknown
  attributes: Record<string, unknown>
  /**
   * Called after a process ran from here, so the row and its details are read again; the confirmation stays until a
   * returned promise settles, so focus can move on when the row (with this menu) is gone.
   */
  onDone(): void | Promise<unknown>
  /** Where focus goes after a confirmed action whose menu went away with the change (the table). */
  returnFocus?: () => HTMLElement | null | undefined
  /** Pages about the row, after the processes. */
  links?: RowLink[]
  /** Deletes the row after a confirmation; last in the menu, drawn as destructive. Absent: no deleting here. */
  onDelete?: () => Promise<unknown>
  /** The menu's button shows this text (where there is room, as in an entry's details); else only an icon. */
  label?: string
}

interface Confirming {
  title: string
  destructive: boolean
  run: () => Promise<unknown>
}

/**
 * The actions of one row in a single menu (docs/design/16-content-authoring.md section 3; 12 section 3): the processes
 * acting on it, the pages about it and deleting it, so a row with many processes stays one line. A process that needs
 * only the key runs after a confirmation, each time with a new idempotency key; any other opens its form with the key
 * filled in and read-only. Nothing to offer: no menu.
 */
export default function RowActions({ actions, id, attributes, onDone, returnFocus, links = [], onDelete, label }: Props) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  // The last confirmation asked for stays while its dialog closes, so the dialog does not go blank as it fades out.
  const [confirming, setConfirming] = useState<Confirming | null>(null)
  const [open, setOpen] = useState(false)
  // The item that asked is gone with the closed menu: focus goes back to the menu's button while the row is there.
  const trigger = useRef<HTMLButtonElement>(null)
  const ask = (next: Confirming) => {
    setConfirming(next)
    setOpen(true)
  }

  const run = async (process: ProcessEntry) => {
    try {
      const response = await unwrap(
        api.POST('/api/processes/{name}/{version}', {
          params: {
            path: { name: process.name, version: String(process.version) },
            header: { 'Idempotency-Key': crypto.randomUUID() },
          },
          body: { [process.actsOn!.input!]: String(id) },
        }),
      )
      if (pendingApproval(response.output)) notify.info(t('process.pendingApproval'))
      else notify.success(t('process.succeeded'))
      await onDone()
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    }
  }

  const shown = actions.filter((process) => actionShown(process, attributes))
  if (shown.length === 0 && links.length === 0 && !onDelete) return null
  return (
    <>
      {/* Not modal: a confirmation opened from an item is a dialog of its own. */}
      <DropdownMenu modal={false}>
        <DropdownMenuTrigger asChild>
          {label ? (
            <Button ref={trigger} variant="outline" size="sm" data-testid="row-actions">
              {label}
              <ChevronDown aria-hidden />
            </Button>
          ) : (
            <Button ref={trigger} variant="ghost" size="icon-sm" aria-label={t('list.moreActions')} data-testid="row-actions">
              <Ellipsis aria-hidden />
            </Button>
          )}
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          {shown.map((process) => (
            <DropdownMenuItem
              key={process.name}
              data-testid={`row-action-${process.name}`}
              onSelect={() =>
                needsOnlyTheKey(process)
                  ? ask({
                      title: t('list.actionConfirm', { action: process.label }),
                      destructive: false,
                      run: () => run(process),
                    })
                  : navigate(
                      `${paths.process(process.name, process.version)}?${new URLSearchParams({
                        [process.actsOn!.input!]: String(id),
                      })}`,
                    )
              }
            >
              {process.label}
            </DropdownMenuItem>
          ))}
          {shown.length > 0 && links.length > 0 && <DropdownMenuSeparator />}
          {links.map((link) => (
            <DropdownMenuItem key={link.testId} asChild>
              <Link to={link.to} data-testid={link.testId}>
                {link.icon}
                {link.label}
              </Link>
            </DropdownMenuItem>
          ))}
          {onDelete && (shown.length > 0 || links.length > 0) && <DropdownMenuSeparator />}
          {onDelete && (
            <DropdownMenuItem
              variant="destructive"
              data-testid="row-delete"
              onSelect={() => ask({ title: t('list.deleteConfirm'), destructive: true, run: onDelete })}
            >
              {t('list.delete')}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      <ConfirmDialog
        open={open}
        onOpenChange={setOpen}
        title={confirming?.title ?? ''}
        destructive={confirming?.destructive}
        onConfirm={() => confirming?.run()}
        returnFocus={() => (trigger.current?.isConnected ? trigger.current : returnFocus?.())}
      />
    </>
  )
}
