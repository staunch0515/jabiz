import { Button, ConfirmDialog, notify } from '@jabiz/ui'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { actionShown, needsOnlyTheKey } from '../meta/actions'
import type { ProcessEntry } from '../meta/types'
import { paths } from '../pages/paths'

interface Props {
  /** The processes acting on the entity (from the catalog, so the caller may run them). */
  actions: ProcessEntry[]
  id: unknown
  attributes: Record<string, unknown>
  /** Called after a process ran from here, so the row and its details are read again. */
  onDone(): void
}

const LINK = 'h-auto px-1'

/**
 * The actions of one row (docs/design/16-content-authoring.md section 3). A process that needs only the key runs
 * after a confirmation, each time with a new idempotency key; any other opens its form with the key filled in and
 * read-only.
 */
export default function RowActions({ actions, id, attributes, onDone }: Props) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [running, setRunning] = useState<string | null>(null)

  const run = async (process: ProcessEntry) => {
    setRunning(process.name)
    try {
      await unwrap(
        api.POST('/api/processes/{name}/{version}', {
          params: {
            path: { name: process.name, version: String(process.version) },
            header: { 'Idempotency-Key': crypto.randomUUID() },
          },
          body: { [process.actsOn!.input!]: String(id) },
        }),
      )
      notify.success(t('process.succeeded'))
      onDone()
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setRunning(null)
    }
  }

  return (
    <>
      {actions
        .filter((process) => actionShown(process, attributes))
        .map((process) =>
          needsOnlyTheKey(process) ? (
            <ConfirmDialog
              key={process.name}
              title={t('list.actionConfirm', { action: process.label })}
              onConfirm={() => run(process)}
              trigger={
                <Button
                  variant="link"
                  size="sm"
                  className={LINK}
                  disabled={running === process.name}
                  data-testid={`row-action-${process.name}`}
                >
                  {process.label}
                </Button>
              }
            />
          ) : (
            <Button
              key={process.name}
              variant="link"
              size="sm"
              className={LINK}
              data-testid={`row-action-${process.name}`}
              onClick={() =>
                navigate(
                  `${paths.process(process.name, process.version)}?${new URLSearchParams({
                    [process.actsOn!.input!]: String(id),
                  })}`,
                )
              }
            >
              {process.label}
            </Button>
          ),
        )}
    </>
  )
}
