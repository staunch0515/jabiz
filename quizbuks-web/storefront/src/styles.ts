/**
 * The block's styles, in its shadow root: the theme's font and text colour (inherited), neutral borders made of the
 * text colour, no colour of its own (docs/quizbuks/shopify/S2b-ui.md, Q-S2b-4). Focus is always visible.
 */
export const STYLES = `
:host { display: block; margin: 1rem 0; font: inherit; color: inherit; }
button, a.primary { font: inherit; color: inherit; background: transparent; border: 1px solid currentColor;
  border-radius: 0.375rem; padding: 0.5rem 1rem; cursor: pointer; text-decoration: none; display: inline-block; }
button:disabled { opacity: 0.5; cursor: default; }
button:focus-visible, a:focus-visible, input:focus-visible, h2:focus-visible { outline: 2px solid currentColor;
  outline-offset: 2px; }
.open, .primary { font-weight: 600; border-width: 2px; }
.open { width: 100%; }
dialog { font: inherit; color: CanvasText; background: Canvas; border: 1px solid currentColor;
  border-radius: 0.5rem; padding: 1.25rem; width: min(32rem, calc(100vw - 2rem)); max-height: calc(100vh - 2rem);
  overflow: auto; }
dialog::backdrop { background: rgb(0 0 0 / 0.5); }
h2 { margin: 0 2rem 0.75rem 0; font-size: 1.25rem; }
h2:focus { outline: none; }
.close { position: absolute; top: 0.75rem; right: 0.75rem; border: none; font-size: 1.5rem; line-height: 1;
  padding: 0.25rem 0.5rem; }
iframe { width: 100%; aspect-ratio: 16 / 9; border: 0; margin: 0.5rem 0; }
.video { display: block; width: 100%; aspect-ratio: 16 / 9; margin: 0.5rem 0; }
fieldset { border: none; padding: 0; margin: 0.75rem 0; }
legend { font-weight: 600; margin-bottom: 0.5rem; }
.option { display: flex; gap: 0.5rem; align-items: flex-start; padding: 0.5rem; border: 1px solid currentColor;
  border-radius: 0.375rem; margin: 0.25rem 0; cursor: pointer; }
.actions { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-top: 0.75rem; }
.clock { font-variant-numeric: tabular-nums; margin: 0; }
.code { font-size: 1.5rem; font-weight: 700; letter-spacing: 0.05em; user-select: all; margin: 0.5rem 0; }
.status { min-height: 1.25rem; }
.badge { font-size: 0.75rem; margin: 1rem 0 0; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0);
  white-space: nowrap; }
`
