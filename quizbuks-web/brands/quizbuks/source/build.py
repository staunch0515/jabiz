# The mark and the app icons (Q113 §9.5): a large white tick on the accent colour. Drawn on the 24 grid of the
# prototype the client approved (tick M6 12.5 l4 4 8-9, stroke 2.8; the page mark's square has corners of 7) and
# scaled to each size, so every file shows the same tick.
ACCENT, WHITE = "#0F6B5C", "#FFFFFF"
TICK, STROKE = "M6 12.5l4 4 8-9", 2.8


def tick(size, colour):
    s = size / 24
    return (f'<path d="{TICK}" transform="scale({s:.4f})" fill="none" stroke="{colour}" stroke-width="{STROKE}" '
            f'stroke-linecap="round" stroke-linejoin="round"/>')


def svg(size, body):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}">{body}</svg>\n'


# App icon (PWA 192/512, Android launcher and splash, iOS splash): the launcher's rounded square as before (rx 224).
open("icon-master.svg", "w").write(svg(1024, f'<rect width="1024" height="1024" rx="224" fill="{ACCENT}"/>' + tick(1024, WHITE)))
# Maskable and store: full bleed. The tick's farthest point with its stroke is 380 of 1024 from the centre, inside the
# maskable circle of 80 % (radius 410).
open("icon-maskable.svg", "w").write(svg(1024, f'<rect width="1024" height="1024" fill="{ACCENT}"/>' + tick(1024, WHITE)))
open("icon-store.svg", "w").write(svg(1024, f'<rect width="1024" height="1024" fill="{ACCENT}"/>' + tick(1024, WHITE)))
# The page's mark: 48 grid, the brand's tokens (brand.css and site.css give both).
open("../mark.svg", "w").write(
    '<svg viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg" aria-hidden="true" focusable="false">\n'
    '  <rect width="48" height="48" rx="14" fill="var(--qb-mark)"/>'
    f'<path d="{TICK}" transform="scale(2)" fill="none" stroke="var(--qb-mark-foreground)" stroke-width="{STROKE}" '
    'stroke-linecap="round" stroke-linejoin="round"/>\n</svg>\n')
