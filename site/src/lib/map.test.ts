import { describe, expect, it } from 'vitest'
import { extent, HEIGHT, landPath, projectionFor, spread, WIDTH } from './map'

describe('map (design section 9.5)', () => {
  const places = [
    { slug: 'uk', longitude: -1.9, latitude: 52.5 },
    { slug: 'poland', longitude: 19.9, latitude: 50.1 },
    { slug: 'sweden', longitude: 18.1, latitude: 59.3 },
    { slug: 'singapore', longitude: 103.8, latitude: 1.35 },
    { slug: 'japan', longitude: 139.7, latitude: 35.7 },
    { slug: 'eswatini', longitude: 31.1, latitude: -26.3 },
  ]

  it('shows the part of the world with the places, never less than a region', () => {
    const box = extent(places)
    expect(box.west).toBeLessThan(-1.9)
    expect(box.east).toBeGreaterThan(139.7)
    expect(box.south).toBeLessThan(-26.3)
    expect(box.north).toBeGreaterThan(59.3)
    const one = extent([{ slug: 'x', longitude: 10, latitude: 50 }])
    expect(one.east - one.west).toBeGreaterThanOrEqual(70)
    expect(one.north - one.south).toBeGreaterThanOrEqual(36)
    expect(extent([])).toEqual({ west: -170, east: 180, south: -58, north: 80 })
  })

  it('projects every place into the drawing and draws the land', () => {
    const projection = projectionFor(extent(places))
    for (const p of places) {
      const [x, y] = projection([p.longitude, p.latitude])!
      expect(x).toBeGreaterThan(0)
      expect(x).toBeLessThan(WIDTH)
      expect(y).toBeGreaterThan(0)
      expect(y).toBeLessThan(HEIGHT)
    }
    expect(landPath(projection)).toMatch(/^M/)
  })

  it('moves markers apart until each can be pressed on its own, deterministically', () => {
    const points = [
      { x: 100, y: 100 },
      { x: 110, y: 104 },
      { x: 100, y: 100 },
      { x: 500, y: 300 },
    ]
    const moved = spread(points, 40)
    for (let i = 0; i < moved.length; i++) {
      for (let j = i + 1; j < moved.length; j++) {
        expect(Math.hypot(moved[i].x - moved[j].x, moved[i].y - moved[j].y)).toBeGreaterThanOrEqual(39.9)
      }
    }
    // A marker with room stays where its place is; the input is not changed.
    expect(moved[3]).toEqual({ x: 500, y: 300 })
    expect(points[1]).toEqual({ x: 110, y: 104 })
    expect(spread(points, 40)).toEqual(moved)
  })

  it('keeps markers inside the drawing', () => {
    const moved = spread([{ x: 0, y: 0 }, { x: 1, y: 1 }], 40)
    for (const p of moved) {
      expect(p.x).toBeGreaterThanOrEqual(20)
      expect(p.y).toBeGreaterThanOrEqual(20)
    }
  })
})
