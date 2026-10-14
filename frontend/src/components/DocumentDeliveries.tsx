import { api, unwrap, formatDateTime } from '@jabiz/client'
import {
  Badge,
  Spinner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { components } from '@jabiz/client'

type Delivery = components['schemas']['DocumentDeliveryEntry']

const VARIANTS = { SENT: 'success', FAILED: 'destructive', PENDING: 'secondary' } as const

/** A delivery's outcome; the last error of a failed one on hover or focus. */
function Outcome({ delivery }: { delivery: Delivery }) {
  const { t } = useTranslation()
  const outcome = (delivery.outcome ?? 'PENDING') as keyof typeof VARIANTS
  const badge = (
    <Badge variant={VARIANTS[outcome] ?? 'secondary'} data-testid={`delivery-${delivery.deliveryId}`}>
      {t(`documents.outcome.${outcome}`)}
    </Badge>
  )
  if (!delivery.lastError) return badge
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        {/* A button, so the error is reached with the keyboard too (focus opens the tooltip). */}
        <button type="button" className="focus-visible:ring-ring/50 rounded-md outline-none focus-visible:ring-[3px]">
          {badge}
        </button>
      </TooltipTrigger>
      <TooltipContent>{delivery.lastError}</TooltipContent>
    </Tooltip>
  )
}

/**
 * Every address an issued document was sent to and how it went (docs/design/22-documents.md section 5): sent, waiting
 * for the first attempt, or failed so far (the platform retries; the last error is shown on hover or focus).
 */
export default function DocumentDeliveries({ runId }: { runId: string }) {
  const { t } = useTranslation()
  const detail = useQuery({
    queryKey: ['document', runId],
    queryFn: () => unwrap(api.GET('/api/documents/runs/{runId}', { params: { path: { runId } } })),
  })
  const deliveries = detail.data?.deliveries ?? []
  if (detail.isLoading) return <Spinner />
  return (
    <Table data-testid={`document-deliveries-${runId}`} aria-label={t('documents.recipients')}>
      <TableHeader>
        <TableRow>
          <TableHead>{t('documents.delivery.address')}</TableHead>
          <TableHead>{t('documents.delivery.outcome')}</TableHead>
          <TableHead className="text-right">{t('documents.delivery.attempts')}</TableHead>
          <TableHead>{t('documents.delivery.requested')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {deliveries.length === 0 ? (
          <TableRow>
            <TableCell colSpan={4} className="text-muted-foreground text-center">
              {t('documents.noDeliveries')}
            </TableCell>
          </TableRow>
        ) : (
          deliveries.map((delivery) => (
            <TableRow key={delivery.deliveryId}>
              <TableCell>{delivery.address}</TableCell>
              <TableCell>
                <Outcome delivery={delivery} />
              </TableCell>
              <TableCell className="text-right tabular-nums">{delivery.attempts}</TableCell>
              <TableCell>
                {`${delivery.createdTime ? formatDateTime(delivery.createdTime) : ''} · ${delivery.requestedBy ?? ''}`}
              </TableCell>
            </TableRow>
          ))
        )}
      </TableBody>
    </Table>
  )
}
