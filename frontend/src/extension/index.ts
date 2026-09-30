import loaded from 'virtual:jabiz-extension'
import { checkedExtension } from './registry'

/** The extension of the application being built, checked once at startup. */
export const extension = checkedExtension(loaded)
