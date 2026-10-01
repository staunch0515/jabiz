import { useLocation } from 'react-router'
import InvoicePage from './InvoicePage'

/**
 * The invoice page under its routes. Every navigation starts the page afresh, except the one the page makes itself
 * when a new document is first saved: it goes on (posting, showing a refusal) at the document's own address.
 */
export default function InvoiceRoute() {
  const location = useLocation()
  const kept = (location.state as { pageInstance?: string } | null)?.pageInstance
  const instance = kept ?? location.key
  return <InvoicePage key={instance} instanceKey={instance} />
}
