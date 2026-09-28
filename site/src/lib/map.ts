import { geoNaturalEarth1, geoPath, type GeoProjection } from 'd3-geo'
import { feature } from 'topojson-client'
import type { GeometryCollection, Topology } from 'topojson-specification'
import land from 'world-atlas/land-110m.json'

/** The map's drawing area; the SVG scales it to the width of the page. */
export const WIDTH = 960
export const HEIGHT = 540
const MARGIN = 36

export interface MapPlace {
  slug: string
  longitude: number
  latitude: number
}

export interface Box {
  west: number
  east: number
  south: number
  north: number
}

/**
 * The part of the world the map shows: the places with room around them, never narrower than a region, so that
 * places close together (Europe) stay apart. No places: the whole world.
 */
export function extent(places: readonly MapPlace[]): Box {
  if (places.length === 0) return { west: -170, east: 180, south: -58, north: 80 }
  const lons = places.map((p) => p.longitude)
  const lats = places.map((p) => p.latitude)
  let west = Math.min(...lons) - 14
  let east = Math.max(...lons) + 14
  let south = Math.min(...lats) - 10
  let north = Math.max(...lats) + 10
  const widen = (low: number, high: number, least: number): [number, number] => {
    const missing = least - (high - low)
    return missing > 0 ? [low - missing / 2, high + missing / 2] : [low, high]
  }
  ;[west, east] = widen(west, east, 70)
  ;[south, north] = widen(south, north, 36)
  return {
    west: Math.max(-180, west),
    east: Math.min(180, east),
    south: Math.max(-60, south),
    north: Math.min(84, north),
  }
}

/** A projection that fits the box into the drawing area. */
export function projectionFor(box: Box): GeoProjection {
  const corners: [number, number][] = []
  for (const lon of [box.west, (box.west + box.east) / 2, box.east]) {
    for (const lat of [box.south, (box.south + box.north) / 2, box.north]) corners.push([lon, lat])
  }
  return geoNaturalEarth1().fitExtent(
    [
      [MARGIN, MARGIN],
      [WIDTH - MARGIN, HEIGHT - MARGIN],
    ],
    { type: 'MultiPoint', coordinates: corners },
  )
}

/**
 * The outline of the land as one SVG path. Only land, no borders: the map locates people, it does not draw
 * countries (design section 0), and it takes no side on disputed borders.
 */
export function landPath(projection: GeoProjection): string {
  const topology = land as unknown as Topology<{ land: GeometryCollection }>
  return geoPath(projection)(feature(topology, topology.objects.land)) ?? ''
}

export interface Point {
  x: number
  y: number
}

/**
 * Moves markers apart until no two are closer than `distance`, each as little as possible, so that every marker can be
 * pressed on its own; a line joins a moved marker to its place. Deterministic: the same places give the same map.
 */
export function spread(points: readonly Point[], distance: number, bounds = { width: WIDTH, height: HEIGHT }): Point[] {
  const result = points.map((p) => ({ ...p }))
  const edge = distance / 2
  for (let round = 0; round < 200; round++) {
    let moved = false
    for (let i = 0; i < result.length; i++) {
      for (let j = i + 1; j < result.length; j++) {
        const a = result[i]
        const b = result[j]
        let dx = b.x - a.x
        let dy = b.y - a.y
        let d = Math.hypot(dx, dy)
        if (d >= distance - 1e-6) continue
        if (d < 1e-6) {
          // The same spot: part them in a direction that depends only on their order.
          const angle = (j * 2.399963) % (2 * Math.PI)
          dx = Math.cos(angle)
          dy = Math.sin(angle)
          d = 1
        }
        const push = (distance - Math.hypot(b.x - a.x, b.y - a.y)) / 2 + 0.01
        const ux = dx / d
        const uy = dy / d
        a.x -= ux * push
        a.y -= uy * push
        b.x += ux * push
        b.y += uy * push
        moved = true
      }
    }
    for (const p of result) {
      p.x = Math.min(bounds.width - edge, Math.max(edge, p.x))
      p.y = Math.min(bounds.height - edge, Math.max(edge, p.y))
    }
    if (!moved) break
  }
  return result
}
