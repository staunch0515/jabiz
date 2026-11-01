import { configureClient, registerServiceWorker } from '@jabiz/client'
import { applyAppearance } from '@jabiz/ui'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'
import './messages'
import './qb/kudos'
import { rememberInstalled } from './install'
import { applyLinkLanguage } from './linkLanguage'
import { rememberShell } from './shell'
import { takeReferralFromAddress } from './referral/api'

// The app's sign-in entry (jabizApp passes it as VITE_JABIZ_ENTRY; named here as well for `pnpm dev`): its sessions,
// kept on the device for 30 days, and its identity providers are the app's, apart from the sponsor console's and the
// administration's in the same browser.
configureClient({ entry: 'app' })
// Opened by a store app (Q49b): noted for the tab before the router reads the address, which loses the shell's mark.
rememberShell()
// Q59b: a link's language (?lang=) for someone new to the app, before the first page; the parameter goes.
applyLinkLanguage()
// Q70: a friend's referral link: its key noted for the tab, the parameter gone, before the router reads the address.
takeReferralFromAddress()
// The remembered appearance before the first paint.
applyAppearance()
// The PWA's Service Worker (VITE_JABIZ_PWA=on in the jar's build); a new version waits for PwaUpdatePrompt.
void registerServiceWorker()
// Installed (now or while open): the business site's "start answering" then opens the app's window (Q41).
rememberInstalled()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
