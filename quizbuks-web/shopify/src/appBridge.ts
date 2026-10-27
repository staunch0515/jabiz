/**
 * App Bridge, Shopify's script for embedded apps (loaded from Shopify's CDN by index.html, as Shopify requires). Only
 * the session token is used: a JWT of about a minute that names the shop and the staff member, signed with the app's
 * secret (docs/quizbuks/shopify/S1-install.md section 2). A fresh one for every sign-in and every process that checks
 * one: each is used once.
 */
interface ShopifyGlobal {
  idToken(): Promise<string>
}

declare global {
  interface Window {
    shopify?: ShopifyGlobal
  }
}

/** The page is not inside the Shopify admin (opened directly): App Bridge has no shop to ask. */
export class NotEmbeddedError extends Error {
  constructor() {
    super('Not embedded in the Shopify admin')
  }
}

export async function sessionToken(): Promise<string> {
  const shopify = window.shopify
  if (!shopify || typeof shopify.idToken !== 'function') throw new NotEmbeddedError()
  return shopify.idToken()
}
