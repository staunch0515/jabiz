import { ListOrderedIcon, ShoppingCartIcon, UserRoundIcon } from 'lucide-react'

/**
 * The portal's areas (phase 17zj): each is an entry of the sidebar and of the bottom menu on a phone, and its icon
 * stands before the title of its page.
 */
export const AREAS = {
  orders: { path: '/orders', icon: ListOrderedIcon },
  order: { path: '/order', icon: ShoppingCartIcon },
  account: { path: '/account', icon: UserRoundIcon },
} as const
