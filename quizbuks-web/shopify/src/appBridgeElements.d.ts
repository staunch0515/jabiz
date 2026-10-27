import 'react'

// App Bridge's own elements (loaded from Shopify's CDN with app-bridge.js): the app's menu in the admin's side bar
// and the page's title bar (S4b, docs/quizbuks/shopify/S4-launch.md P-1).
declare module 'react' {
  namespace JSX {
    interface IntrinsicElements {
      'ui-nav-menu': React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement>
      'ui-title-bar': React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement> & { title?: string }
    }
  }
}
