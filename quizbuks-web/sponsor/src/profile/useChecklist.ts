import { useQuery } from '@tanstack/react-query'
import { readMySponsorPage } from '../page/api'
import { SPONSOR_PAGE_QUERY } from '../page/SponsorPageEditor'
import { readMyProducts } from '../products/api'
import { PRODUCTS_QUERY } from '../products/ProductsPage'
import { readStaff } from '../publishing/api'
import { useMailServer } from '../publishing/MailServerPage'
import { STAFF_QUERY } from '../publishing/StaffPage'
import { useMySponsor } from './api'
import { checklist, type ChecklistItem } from './checklist'

/**
 * The completion list of the sponsor hub (Q79, D-Q79-4) read from what the other pages read: the hub's overview shows
 * it, the home counts what is missing (F-F3b). `ready` is true once every part is read, so a part not read yet is not
 * taken for one that is missing.
 */
export function useChecklist(): { items: ChecklistItem[]; ready: boolean } {
  const sponsor = useMySponsor()
  const page = useQuery({ queryKey: SPONSOR_PAGE_QUERY, queryFn: readMySponsorPage })
  const products = useQuery({ queryKey: PRODUCTS_QUERY, queryFn: readMyProducts })
  const staff = useQuery({ queryKey: STAFF_QUERY, queryFn: readStaff })
  const mailServer = useMailServer()
  const items = checklist({
    sponsor: sponsor.data ?? null,
    page: page.data ?? null,
    listedProducts: (products.data ?? []).filter((p) => p.status === 'LISTED').length,
    staff: (staff.data ?? []).length,
    mailServerVerified: !!mailServer.data?.verifiedAt,
  })
  return { items, ready: sponsor.isSuccess && page.isSuccess && products.isSuccess && staff.isSuccess
    && mailServer.isSuccess }
}
