import { configureClient, registerServiceWorker } from '@jabiz/client'
import { applyAppearance } from '@jabiz/ui'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'
import './messages'

// The portal's sign-in entry (jabizApp passes it as VITE_JABIZ_ENTRY; named here as well for `pnpm dev`): its sessions,
// sign-ins and identity providers are the portal's, apart from the administration's in the same browser.
configureClient({ entry: 'portal' })
// The remembered appearance before the first paint.
applyAppearance()
// The PWA's Service Worker (VITE_JABIZ_PWA=on in the jar's build); a new version waits for PwaUpdatePrompt.
void registerServiceWorker()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
