import { Button, CopyButton, notify, Spinner, UI_NAMESPACE, UI_SCOPE } from '@jabiz/ui'
import { Eye } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError, unwrap } from '../api/client'

interface Props {
  datasetId: string
  id: unknown
  field: string
  /** The masked form every read shows. */
  masked: string
}

/**
 * A masked value with a button that shows it in plain text (docs/design/10-security.md section 13.1). Offered to
 * holders of the field's permission only; each click asks the server again, which checks the permission and records
 * the display. The plain value lives in this component's state only and is gone when the page is left.
 */
export default function MaskedValue({ datasetId, id, field, masked }: Props) {
  const { t } = useTranslation()
  const [plain, setPlain] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  if (plain !== null) {
    return (
      <span className={`${UI_SCOPE} inline-flex items-center gap-1`}>
        <span data-testid={`revealed-${field}`}>{plain}</span>
        <CopyButton value={plain} label={t('copy.copy', { ns: UI_NAMESPACE })} />
      </span>
    )
  }
  const reveal = async () => {
    setLoading(true)
    try {
      const answer = await unwrap(
        api.POST('/api/datasets/{resourceId}/reveal', {
          params: { path: { resourceId: datasetId } },
          body: { id, field },
        }),
      )
      setPlain(answer.value === null || answer.value === undefined ? '' : String(answer.value))
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setLoading(false)
    }
  }
  return (
    <span className={`${UI_SCOPE} inline-flex items-center gap-1`}>
      <span>{masked}</span>
      <Button
        variant="link"
        size="sm"
        className="h-auto px-1"
        disabled={loading}
        aria-busy={loading || undefined}
        onClick={(event) => {
          event.stopPropagation()
          void reveal()
        }}
        data-testid={`reveal-${field}`}
      >
        {loading ? <Spinner className="text-current" /> : <Eye aria-hidden />}
        {t('masked.reveal')}
      </Button>
    </span>
  )
}
