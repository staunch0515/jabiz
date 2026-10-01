import { useLocation } from 'react-router'
import JournalEntryPage from './JournalEntryPage'

/**
 * The entry page under its routes. Every navigation starts the page afresh (a new entry after an existing one),
 * except the one the page makes itself when a new entry is first saved: the page goes on with what it was doing
 * (submitting, showing the server's refusal) at the entry's own address.
 */
export default function JournalEntryRoute() {
  const location = useLocation()
  const kept = (location.state as { entryInstance?: string } | null)?.entryInstance
  const instance = kept ?? location.key
  return <JournalEntryPage key={instance} instanceKey={instance} />
}
