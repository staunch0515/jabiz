import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import '../qb/view/messages'
import { VideoSlot, VideoStage } from './stage'

const FILE = 'https://cdn.example.com/first-frame.mp4'
const YOUTUBE = 'https://www.youtube-nocookie.com/embed/firstFrame01'

afterEach(cleanup)

/** A card's video slot, with the one stage laid over it (as the app has them); `cover` as a card's has one. */
function Card({ player, cover = false, playing = true }: { player: string; cover?: boolean; playing?: boolean }) {
  return (
    <>
      {playing && <VideoSlot id={`card:${player}`} player={player} title="Clip" mode="card"
        cover={cover ? <span data-testid="stage-cover">Cover</span> : undefined} />}
      <VideoStage />
    </>
  )
}

describe('the stage shows a player only once it has its first frame', () => {
  it('slides a video file in beside its cover once its first frame is ready, as one strip', async () => {
    render(<Card player={FILE} cover />)
    const stage = await screen.findByTestId('video-stage')
    const video = screen.getByTestId('video-stage-file')
    const strip = screen.getByTestId('video-stage-strip')
    // Until the frame is there the cover fills the place; the player waits to its right, clipped off the place.
    expect(stage).toHaveStyle({ overflow: 'hidden' })
    expect(screen.getByTestId('video-stage-cover')).toContainElement(screen.getByTestId('stage-cover'))
    // The two halves lie side by side in a row (not one above the other), so the player is beside the cover.
    expect(strip.style.display).toBe('flex')
    expect(strip).toContainElement(screen.getByTestId('video-stage-content'))
    expect(strip.style.transform).toBe('translateX(0)')
    fireEvent(video, new Event('loadeddata'))
    // Both move together: the cover slides out to the left as the player comes in from the right.
    expect(strip.style.transform).toBe('translateX(-50%)')
  })

  it('slides a YouTube player in beside its cover once it plays', async () => {
    render(<Card player={YOUTUBE} cover />)
    const frame = await screen.findByTitle('Clip') as HTMLIFrameElement
    const strip = screen.getByTestId('video-stage-strip')
    expect(strip.style.transform).toBe('translateX(0)')
    fireEvent(window, new MessageEvent('message', { source: frame.contentWindow,
      data: JSON.stringify({ event: 'infoDelivery', info: { playerState: 1 } }) }))
    expect(strip.style.transform).toBe('translateX(-50%)')
  })

  it('slides the cover back over the player when its card stops playing, and releases the player only after', async () => {
    const { rerender } = render(<Card player={FILE} cover />)
    fireEvent(await screen.findByTestId('video-stage-file'), new Event('loadeddata'))
    const strip = screen.getByTestId('video-stage-strip')
    expect(strip.style.transform).toBe('translateX(-50%)')
    rerender(<Card player={FILE} cover playing={false} />)
    // The cover comes back in place (its copy in the stage, already painted) while the player is still there to slide out.
    expect(screen.getByTestId('video-stage-cover')).toContainElement(screen.getByTestId('stage-cover'))
    expect(screen.getByTestId('video-stage')).toBeInTheDocument()
    expect(strip.style.transform).toBe('translateX(0)')
    await waitFor(() => expect(screen.queryByTestId('video-stage')).toBeNull())
  })

  it('shows a player on a place without a cover at once (the start page), with no cover to move', async () => {
    render(<Card player={FILE} />)
    await screen.findByTestId('video-stage-file')
    const content = screen.getByTestId('video-stage-content')
    expect(content.style.opacity).toBe('')
    expect(screen.getByTestId('video-stage-strip').style.transform).toBe('translateX(-50%)')
  })
})
