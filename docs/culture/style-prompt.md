# Style prompt: the "Culture, Unfiltered" look for an Astro site

Hand everything below the line to the AI working on the other site. It describes the visual system of the public site
of Culture, Unfiltered (`site/` on the `culture` branch) precisely enough to rebuild it without seeing that code.

---

You are restyling an existing **Astro** website so that it looks exactly like a reference site called *Culture,
Unfiltered*. The client loves that site's look. Your job is the **visual layer only**: colours, fonts, spacing, layout,
components, motion and accessibility. Keep this site's own content, name, logo text, pages, routes, data and features.
Do not copy the reference site's text or its wordmark.

Work in this order:
1. Add the fonts and the design tokens (sections 2–3).
2. Add the global base styles (section 4) and import them once in the base layout.
3. Restyle the header, footer and page shell (section 6).
4. Restyle each component and page type (sections 7–8), reusing the shared classes.
5. Check everything against section 10 and fix what fails.

If something on this site has no match in this brief, style it with the same ingredients: paper background, ink text,
serif display headings, small monospace uppercase labels, square corners, hairline rules, the highlighter used sparingly.

## 1. The idea

An editorial exhibition made by young people: a printed field notebook, not a travel or marketing site.

- Calm paper background, near-black ink, **one** accent: a fluorescent yellow-green *highlighter*.
- Huge serif headings with an italic second line; highly legible body text; small monospace uppercase "field note" labels.
- Generous white space, a 12-column grid, hairline rules between sections, **square corners everywhere** (only avatars
  are round).
- People's photos are the main visuals, always in fixed-ratio frames.
- No gradients (except the highlighter technique below), no shadows on cards, no rounded buttons, no colour fills
  behind sections except the one inverted (ink) block per page at most.

## 2. Fonts

Three open-licence families, **self-hosted** (never load Google Fonts: visitors' IPs must not leak):

```sh
npm i @fontsource/instrument-serif @fontsource/atkinson-hyperlegible @fontsource/ibm-plex-mono
```

| Role | Family | Weights | Use |
|---|---|---|---|
| Display | Instrument Serif | 400, 400 italic | h1–h3, big quotes, place and person names, subtitles |
| Body | Atkinson Hyperlegible | 400, 400 italic, 700 | all running text, buttons in CJK |
| Labels | IBM Plex Mono | 500 | small uppercase labels, buttons, nav links, meta lines |

Chinese and Japanese use system fonts (no CJK web fonts are bundled), switched with `:lang(zh)` / `:lang(ja)`, and CJK
headings fall back to the bold body face (CJK has no italic or display serif).

## 3. Design tokens

Create `src/styles/tokens.css` exactly like this. Every colour on the site comes from these variables; do not
introduce new colours.

```css
/*
 * Every text/background pair used on the site has contrast >= 4.5:1 (large text and the focus ring >= 3:1).
 * The highlighter (--mark) only ever sits behind ink text (--on-mark); it is never a text colour.
 */
:root {
  --paper: #f2f1ec;      /* page background */
  --paper-2: #e8e7e0;    /* image placeholders, map background */
  --ink: #17181a;        /* text, rules that matter, primary buttons */
  --muted: #53554e;      /* secondary text, labels */
  --rule: #d5d4cb;       /* hairlines, quiet borders */
  --mark: #d9f24a;       /* the highlighter: behind ink text, short bars, selected chips */
  --on-mark: #17181a;    /* text on the highlighter, in both schemes */
  --focus: #1b4dd8;      /* focus ring */
  --inv-bg: #17181a;     /* the inverted block */
  --inv-ink: #f2f1ec;
  --inv-muted: #b9bab2;
  --poster: #17181a;     /* behind a video before it plays */
  --on-poster: #f2f1ec;

  --display: 'Instrument Serif', 'Iowan Old Style', Georgia, serif;
  --body: 'Atkinson Hyperlegible', 'Segoe UI', system-ui, sans-serif;
  --mono: 'IBM Plex Mono', ui-monospace, 'SFMono-Regular', Menlo, monospace;
  --body-zh: 'PingFang SC', 'Hiragino Sans GB', 'Noto Sans CJK SC', 'Microsoft YaHei', system-ui, sans-serif;
  --body-ja: 'Hiragino Sans', 'Hiragino Kaku Gothic ProN', 'Noto Sans CJK JP', 'Yu Gothic', Meiryo, system-ui, sans-serif;

  --gutter: clamp(16px, 4vw, 48px);  /* side padding */
  --max: 1240px;                      /* content width */
  --measure: 66ch;                    /* line length of running text */
  --tap: 44px;                        /* minimum target size */
  --motion: 200ms;                    /* longest animation */

  color-scheme: light;
}

/* Dark scheme follows the system: paper and ink swap, the highlighter stays. */
@media (prefers-color-scheme: dark) {
  :root {
    --paper: #131412;
    --paper-2: #1d1e1b;
    --ink: #ecece5;
    --muted: #a9aaa2;
    --rule: #34352f;
    --focus: #8fb0ff;
    --inv-bg: #ecece5;
    --inv-ink: #131412;
    --inv-muted: #4a4b45;
    color-scheme: dark;
  }
}
```

## 4. Global base styles

Create `src/styles/global.css` and import it once in the base layout's frontmatter
(`import '../styles/global.css'`). These are the shared classes the rest of the brief uses.

```css
@import '@fontsource/instrument-serif/400.css';
@import '@fontsource/instrument-serif/400-italic.css';
@import '@fontsource/atkinson-hyperlegible/400.css';
@import '@fontsource/atkinson-hyperlegible/400-italic.css';
@import '@fontsource/atkinson-hyperlegible/700.css';
@import '@fontsource/ibm-plex-mono/500.css';
@import './tokens.css';

*, *::before, *::after { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; text-size-adjust: 100%; }

body {
  margin: 0;
  background: var(--paper);
  color: var(--ink);
  font: 18px/1.6 var(--body);
  overflow-wrap: break-word; /* long names never widen the page on a phone */
}

:lang(zh) { --body: var(--body-zh); }
:lang(ja) { --body: var(--body-ja); }

/* CJK has no italic or display serif: headings use the bold body face. */
:lang(zh) :is(h1, h2, h3), :lang(ja) :is(h1, h2, h3),
h1:lang(zh), h1:lang(ja), h2:lang(zh), h2:lang(ja), h3:lang(zh), h3:lang(ja) {
  font-family: var(--body);
  font-weight: 700;
  letter-spacing: 0;
}

a { color: inherit; text-underline-offset: 0.2em; text-decoration-thickness: 1px; }
a:hover { text-decoration-thickness: 2px; }
:focus-visible { outline: 3px solid var(--focus); outline-offset: 3px; }

h1, h2, h3 {
  font-family: var(--display);
  font-weight: 400;
  line-height: 1.05;
  text-wrap: balance;
  margin: 0;
}
p { margin: 0; }
img { max-width: 100%; }
button { font: inherit; }

/* Page width */
.wrap { max-width: var(--max); margin-inline: auto; padding-inline: var(--gutter); }

/* "Field note" label: places, dates, media types, counts, eyebrows */
.label {
  font: 500 12px/1.4 var(--mono);
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--muted);
}
.label:lang(zh), .label:lang(ja), :lang(zh) .label, :lang(ja) .label {
  font-family: var(--body); font-size: 13px; letter-spacing: 0.02em;
}

/* The highlighter: a band behind ink text, like a marker pen. Use it for at most one phrase per screen. */
.mark {
  background: linear-gradient(transparent 12%, var(--mark) 12%, var(--mark) 88%, transparent 88%);
  color: var(--on-mark);
  padding-inline: 0.12em;
  -webkit-box-decoration-break: clone;
  box-decoration-break: clone;
}

.visually-hidden {
  position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0;
  overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}

/* Buttons: square, outlined in ink, monospace uppercase. The primary one is solid ink. */
.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 10px;
  min-height: 48px; padding: 0 22px;
  font: 500 14px/1.2 var(--mono); letter-spacing: 0.08em; text-transform: uppercase;
  text-decoration: none;
  border: 1.5px solid var(--ink); border-radius: 0;
  background: transparent; color: var(--ink); cursor: pointer;
}
.btn:lang(zh), .btn:lang(ja), :lang(zh) .btn, :lang(ja) .btn { font-family: var(--body); letter-spacing: 0.02em; }
.btn:hover { text-decoration: underline; }
.btn[disabled] { opacity: 0.5; cursor: default; text-decoration: none; }
.btn-primary { background: var(--ink); color: var(--paper); }

/* Vertical rhythm between page sections, separated by a hairline */
.section { padding-block: clamp(48px, 8vw, 104px); }
.section + .section { border-top: 1px solid var(--rule); }
.section-head {
  display: flex; justify-content: space-between; align-items: end; gap: 16px; flex-wrap: wrap;
  margin-bottom: 32px;
}
.section-head h2 { font-size: clamp(34px, 5vw, 56px); }

/* The head of every list or text page: huge title, short intro */
.page-head {
  padding-block: clamp(40px, 6vw, 80px) 32px;
  display: grid; grid-template-columns: minmax(0, 1fr); gap: 18px;
}
.page-head h1 { font-size: clamp(46px, 7.6vw, 108px); max-width: 16ch; }
.page-head .intro { max-width: 62ch; font-size: 20px; }

/* Breadcrumbs */
.crumbs {
  font: 500 13px/1.4 var(--mono); letter-spacing: 0.06em; text-transform: uppercase;
  display: flex; flex-wrap: wrap; gap: 4px 8px;
}
.crumbs a { color: var(--muted); display: inline-flex; min-height: 24px; align-items: center; }

/* Running text written by editors (Markdown) */
.prose { display: grid; gap: 1em; max-width: var(--measure); }
.prose h2 { font-size: clamp(30px, 4vw, 44px); margin-top: 0.4em; }
.prose :is(p, ul, ol, blockquote) { margin: 0; }
.prose blockquote { border-left: 3px solid var(--mark); padding-left: 1em; font-style: italic; }

/* The only motion: a short rise on entering, never when the visitor asked for less motion */
@media (prefers-reduced-motion: no-preference) {
  .enter { animation: rise var(--motion) ease-out both; }
  @keyframes rise {
    from { opacity: 0.001; transform: translateY(6px); }
    to { opacity: 1; transform: none; }
  }
}
```

## 5. Typography scale

| Element | Style |
|---|---|
| Home hero h1 | Display, `clamp(60px, 9.5vw, 140px)`, line-height 0.9, letter-spacing −0.01em. Text before the first comma on line 1, the rest on its own line in *italic* (`<span>` with `display:block; font-style:italic`) |
| Page h1 (lists, about…) | `clamp(46px, 7.6vw, 108px)`, max 16ch |
| Article / detail h1 | `clamp(44px, 7vw, 100px)`, max 16ch |
| Person h1 | `clamp(56px, 9vw, 128px)` |
| Section h2 | `clamp(34px, 5vw, 56px)` (section heads), `clamp(30px, 4vw, 44px)` inside text |
| Card heading | 28–30px display, no underline until hover |
| Hero subtitle / big quote | Display *italic*, `clamp(22px, 2.6vw, 30px)` / `clamp(26px, 3vw, 38px)`, line-height 1.3 |
| Intro paragraph | Body 20px, max 62ch |
| Body | Body 18px / 1.6; secondary text 16–17px in `--muted` |
| Labels, nav, buttons, meta | Mono 500, 12–14px, uppercase, letter-spacing 0.06–0.08em |

The headline device: one sentence of an important paragraph is wrapped in `.mark` (e.g. the first sentence of an
answer, set in large display italic). Never highlight more than one phrase per screen.

## 6. Page shell (base layout)

**Skip link.** The first focusable element: "Skip to content", ink background, paper text, hidden above the viewport
(`top: -100px`) until focused (`top: 8px`), links to `#main`.

**Header.** Sticky (`position: sticky; top: 0; z-index: 20`), paper background, `1px solid var(--rule)` bottom border,
min-height 64px, content in `.wrap`, flex row with 16px gap:
- Wordmark on the left: display font 28px, no underline, `white-space: nowrap`, part of the name in *italic*
  (this site's own name; same typographic treatment).
- Main navigation on the right: a list of links in mono 500 13px uppercase, letter-spacing 0.08em, min-height 44px,
  10px side padding, no underline (underline on hover). The current page's link has `aria-current="page"` and an inset
  3px highlighter underline: `box-shadow: inset 0 -3px 0 var(--mark)`. In CJK: body font 500 15px, no letter-spacing.
- A search link as a 22px magnifier icon (stroke `currentColor`, 2px) with visually hidden text, 44×44px.
- Language switch (if the site has several languages): a bordered group (`1px solid var(--rule)`) of mono 12px
  items, each ≥44px wide; the current language is solid ink with paper text.
- **Below 1060px** the navigation goes behind a menu button (24px three-line icon, 44×44px, `aria-expanded`,
  `aria-controls`). Open: a full-screen panel under the header (`position: fixed; inset: 65px 0 0`), paper background,
  links stacked as large display text (32px, no uppercase, 60px tall rows with a `--rule` bottom border). Opening moves
  the focus to the first link; `Esc` closes it and returns the focus to the button; following a link closes it.

**Main.** `<main id="main" tabindex="-1">` with `min-height: 100vh` (so the footer never jumps up while content loads)
and no focus outline on the element itself. After client-side navigation (if any), move the focus to `main`.

**Footer.** `border-top: 1.5px solid var(--ink)`, `padding-block: 40px 56px`, `margin-top: clamp(48px, 8vw, 104px)`.
Flex row, wraps, `gap: 24px 48px`, space-between: on the left the wordmark, a one-line tagline and a privacy line in
`--muted` 16px (max 44ch); on the right a plain vertical list of secondary links, each min-height 44px.

## 7. Components

**Fixed-ratio image frame.** Every photo sits in a frame that reserves its space before loading (no layout shift):
`display:block; position:relative; width:100%; overflow:hidden; background:var(--paper-2)` with `aspect-ratio`
4/5 for portraits, 16/9 for thumbnails, 1 for squares, 1 + `border-radius:50%` for avatars; the `<img>` fills it with
`object-fit: cover`. Without an image, show the person's initial centred in the frame in display font
(`clamp(28px, 8vw, 96px)`, `--muted`). Use `srcset`/`sizes`, `loading="lazy"` below the fold, explicit `width`/`height`.
Use Astro's `<Image>`/`<Picture>` if the site already does.

**Card** (story / article / item in a grid). A grid column with 10px gap: image frame, a `.label` meta line
(type · date), heading (h2 on list pages, h3 inside sections) at 28–30px display with the link un-underlined, a
summary in `--muted` 17px. **The whole card is one link**: the heading link gets
`a::after { content:''; position:absolute; inset:0 }` on a `position:relative` card; hovering the card underlines the
heading (1px, offset 0.15em); `:focus-within` draws the focus ring 6px outside the card and the link's own outline
is removed. Person cards: portrait 4:5, the place as a `.label` with a text-size flag emoji, the name as the heading
(28px), one line of bio, then "View profile →" as a mono label.

**Grids.** `list-style:none; margin:0; padding:0; display:grid`:
- 3 columns, `gap: 40px 24px`, one column below 860px;
- 4 columns, `gap: 32px 20px`, 3 below 980px, 2 below 640px.

Always `minmax(0, 1fr)` columns.

**Topic list (themes, categories).** A ruled list rather than cards: `border-top: 1.5px solid var(--ink)`, each row
`border-bottom: 1px solid var(--rule)`, row link `display:grid; grid-template-columns: 48px minmax(0,1fr)
minmax(0,1.6fr) auto; gap:16px; align-items:baseline; padding:22px 0`: emoji icon 26px (decorative, `aria-hidden`),
title in display 36px, the question/description in `--muted`, a count `.label`. Hover: title turns *italic* and
underlined. Below 760px: two columns (icon, then everything stacked).

**Accent bar.** Before an h3 of a key list (principles, reflection questions):
`h3::before { content:''; display:block; width:40px; height:6px; background:var(--mark); margin-bottom:14px }`.

**Inverted block.** At most one per page, for the most reflective content (e.g. "What we learned"):
full-width `background: var(--inv-bg); color: var(--inv-ink); padding-block: clamp(48px, 7vw, 96px)`; its labels in
`--inv-muted`; heading `clamp(40px, 6vw, 76px)`; inside, three columns (one below 860px) of h3 in display *italic* 32px
with the accent bar above each.

**Perspective / quote card.** No box: `border-top: 1.5px solid var(--ink); padding-top: 16px`, then a row with a
52px round avatar and the person's name in display 26px plus their place as a `.label`, a heading in display 28px,
text in 17px clamped to 6 lines, and a mono "Read more" link. Grid of 3 (2 below 980px, 1 below 640px),
`gap: 48px 28px`.

**Filter bar.** `border-block: 1px solid var(--rule); padding-block: 16px; margin-bottom: 32px`. Each group is a
`fieldset` (no border) with a `.label` legend (min-width 96px) and **chips**: real checkboxes visually hidden on top
of a span that is `min-height:44px; padding:0 14px; border:1.5px solid var(--rule); font-size:16px`, ink border on
hover, **checked = highlighter background + ink border**, focus ring around the span. Below: the result count as a
`.label` with `role="status"`, and a plain underlined "Clear all filters" text button. Keep the filter state in the
URL query string.

**Segmented control** (view switch): `border:1.5px solid var(--ink)`, buttons mono 500 13px uppercase, min-height
44px, 16px padding, transparent; the pressed one (`aria-pressed="true"`) solid ink with paper text.

**Tags.** Inline-flex items, min-height 44px, padding 0 14px, `border:1.5px solid var(--rule)`, ink border on hover.

**Toolbar row.** `border-block: 1px solid var(--rule); padding-block: 12px`, flex, space-between.

**Video before playing.** A 16:9 box in `--poster` with our own thumbnail, a centred 88px round **highlighter disc**
with an ink play triangle (64px below 520px), `box-shadow: 0 0 0 6px rgb(23 24 26 / 0.35)`, scaling to 1.06 on
hover (only without reduced motion), and a bottom notice strip in mono 12px on `rgb(0 0 0 / 0.72)` saying the player
loads only on click. Load the YouTube/Vimeo iframe only after the click.

**Disclosure** (transcript, "more"): `<details>` with a mono uppercase 13px summary, min-height 44px,
`border-bottom: 1px solid var(--rule)`.

**Facts list.** A `dl` as a two-column grid (`max-content 1fr`, `gap: 8px 24px`), `dt` as `.label`.

**Search form.** Label as `.label`; a row with the input (min-height 48px, 14px padding, `1.5px solid var(--ink)`,
**no radius**, paper background) and a `.btn.btn-primary` "Search" button; results as a ruled list
(`border-top: 1px solid var(--rule)` per row, 16px padding) with an optional 120px image (72px on phones), a 28px
display heading link and a `--muted` 17px summary; group headings h2 with a count label.

## 8. Page patterns

**Home.**
1. Hero in a 12-column grid (`gap: 24px; align-items: start; padding-block: clamp(40px, 6vw, 88px)`): text in
   columns 1–6 (eyebrow `.label`, the huge two-line h1, italic subtitle, a wrapped list of mono links, two buttons:
   primary "Explore …" with →, secondary outlined); a **collage** in columns 7–12 of 3 portrait-frame columns
   (`gap: 14px`) where **every middle column item drops 28px** (`li:nth-child(3n+2) { transform: translateY(28px) }`),
   each with the name in display 22px and the place as a label. Below 860px both take the full width and the drop is
   removed.
2. A `.section` with a big question as h2 (`clamp(44px, 7vw, 96px)`, columns 1–9), the answer in display italic
   `clamp(28px, 3.4vw, 44px)` whose first sentence is `.mark`ed (columns 1–8), and body text in columns 6–11.
3. `.section`s for featured items (3-column cards), topics (ruled list), people (4-column cards), each with a
   `.section-head` (h2 + outlined button "All …").

Paint the hero title at once (it is the largest element of the first screen, so it decides LCP); show the rest of the
hero in one go when its data is ready instead of piece by piece.

**List page.** `.page-head` (h1 + intro) → filter bar → grid of cards with h2 headings.

**Detail page (article/story).** Head: breadcrumbs, h1 (max 16ch), summary 22px (max 52ch), a meta row of labels
and contributor links (underlined with a `--rule` bottom border that turns ink on hover). Body in a 12-column grid,
content in columns 3–10 (full width below 860px), `gap: 56px 24px`; media and wide blocks span all 12. End with the
inverted block if there is a reflective section.

**Person page.** Head grid `4fr / 8fr` (portrait / name + text), aligned to the bottom, `gap: clamp(24px, 4vw, 56px)`;
one column below 760px with the portrait max 320px. Sections in a `4fr / 8fr` two-column grid (heading left, content
right). A personal quote in display italic.

**Text pages (about, method).** `.page-head`, then `.prose` sections separated by `clamp(40px, 6vw, 72px)`; a list of
principles as an auto-fill grid (`minmax(min(100%, 220px), 1fr)`, `gap: 32px 24px`) of h3 with the accent bar.

**Resource/download list.** Ruled list (`border-top: 1.5px solid var(--ink)`, rows `1px --rule`, 24px padding) with
h2 headings `clamp(28px, 3.4vw, 40px)`, label meta and plain links.

## 9. Motion and behaviour

- The only animation is `.enter` (6px rise, ≤200ms) and the video disc's scale; all of it inside
  `@media (prefers-reduced-motion: no-preference)`. No parallax, no scroll-jacking, no carousels that move by themselves.
- No layout shift: fixed-ratio frames for every image, `min-height: 100vh` on `main`, groups of content appear together.
- Square corners everywhere except avatars and the video disc. No box-shadows on cards or buttons.

## 10. Accessibility (WCAG 2.2 AA) and the final checklist

- `<html lang>` set to the page language; content in another language carries its own `lang` attribute.
- Exactly **one h1 per page** and no skipped heading levels (list-page cards are h2, cards inside a section are h3).
- Every interactive element is keyboard reachable, has a visible focus ring (`3px solid var(--focus)`, offset 3px)
  and a target of **at least 44×44px**.
- All text/background pairs have contrast **≥ 4.5:1** (large text and the focus ring ≥ 3:1) in both schemes; only use
  the token pairs above. Never put text in `--mark` colour; never put `--muted` on `--mark`.
- Images have meaningful `alt`; decorative ones (`alt=""`), flags and emoji icons are `aria-hidden`; names are given in text.
- Result counts and live changes are announced with `role="status"`.
- At **375px** width and at 200% zoom nothing scrolls sideways (single-column grids use `minmax(0, 1fr)`, long words wrap).
- No third-party requests before the visitor asks (self-hosted fonts, no tracking pixels, video iframes only after click).

Before you finish, check every page in a desktop width and at 375px, in light and dark scheme, and with the keyboard
only. Run axe (e.g. `@axe-core/playwright`) or Lighthouse: zero critical/serious accessibility findings, accessibility
score 100. Report what you changed per page and anything that could not be matched.
