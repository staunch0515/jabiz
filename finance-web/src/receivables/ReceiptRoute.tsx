import { useLocation } from 'react-router'
import ReceiptPage from './ReceiptPage'

/** The receipt page under its routes: every navigation starts it afresh. */
export default function ReceiptRoute() {
  const location = useLocation()
  return <ReceiptPage key={location.key} />
}
