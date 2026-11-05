import math
BG1, BG2, LIME, WHITE = "#4361EE", "#3A22C4", "#84E01C", "#FFFFFF"
C = (512, 560); R = 240

def pt(deg, r=R):
    a = math.radians(deg)
    return (C[0] + r * math.cos(a), C[1] + r * math.sin(a))

def f(p):
    return f"{p[0]:.1f},{p[1]:.1f}"

# The bubble: a ring open where the tick leaves it (-54..-18 degrees), with a tail at the lower left.
g1, g2, t1, t2 = pt(-54), pt(-18), pt(150), pt(120)
TIP = (262, 858)
BUBBLE = (f"M{f(g1)} A{R} {R} 0 0 0 {f(t1)} L{f(TIP)} L{f(t2)} A{R} {R} 0 0 0 {f(g2)}")
RAYS = []
for deg in (-122, -90, -58):
    a, b = pt(deg, 312), pt(deg, 402)
    RAYS.append(f"M{f(a)} L{f(b)}")
TICK = "M400,560 L488,660 L775,345"
BBOX = (230, 126, 823, 890)  # the drawing's outer box, strokes included
CX, CY = (BBOX[0] + BBOX[2]) / 2, (BBOX[1] + BBOX[3]) / 2

def art(scale, size=1024, leaf=LIME, tick=WHITE, line=64, check=96):
    t = f"translate({size/2:.1f} {size/2:.1f}) scale({scale:.4f}) translate({-CX:.1f} {-CY:.1f})"
    rays = "".join(f'<path d="{d}"/>' for d in RAYS)
    return (f'<g transform="{t}" fill="none" stroke-linecap="round" stroke-linejoin="round">'
            f'<g stroke="{leaf}" stroke-width="{line}"><path d="{BUBBLE}"/>{rays}</g>'
            f'<path d="{TICK}" stroke="{tick}" stroke-width="{check}"/></g>')

GRAD = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0" stop-color="{BG1}"/><stop offset="1" stop-color="{BG2}"/></linearGradient></defs>')

def svg(size, body):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}">{body}</svg>\n'

H = BBOX[3] - BBOX[1]
open("icon-master.svg", "w").write(svg(1024, GRAD + '<rect width="1024" height="1024" rx="224" fill="url(#bg)"/>'
                                  + art(0.64 * 1024 / H)))
# Maskable and store: full bleed; the maskable drawing within the centre circle of 80 % (checked below).
open("icon-maskable.svg", "w").write(svg(1024, GRAD + '<rect width="1024" height="1024" fill="url(#bg)"/>' + art(0.70)))
open("icon-store.svg", "w").write(svg(1024, GRAD + '<rect width="1024" height="1024" fill="url(#bg)"/>'
                                 + art(0.64 * 1024 / H)))
# The page's mark: 48 grid, transparent, colours as the brand's tokens.
s48 = 44 / H
mark = art(s48, 48, "var(--qb-leaf)", "var(--qb-ink)")
open("mark.svg", "w").write(
    '<svg viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg" aria-hidden="true" focusable="false">\n  '
    + mark + '\n</svg>\n')
print("mark stroke at 48:", round(64 * s48, 2), "tick:", round(96 * s48, 2))
