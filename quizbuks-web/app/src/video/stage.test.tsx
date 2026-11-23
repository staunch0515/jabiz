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

const HIDDEN = 'polygon(125% 0%, 125% 0%, 125% 100%, 100% 100%)'
const SHOWN = 'polygon(0% 0%, 125% 0%, 125% 100%, -25% 100%)'

describe('the stage shows a player only once it has its first frame', () => {
  it('sweeps a video file in over its cover along a slanted edge once its first frame is ready', async () => {
    render(<Card player={FILE} cover />)
    const stage = await screen.findByTestId('video-stage')
    const video = screen.getByTestId('video-stage-file')
    const content = screen.getByTestId('video-stage-content')
    // Until the frame is there the cover fills the place; the player lies over it, cut off past its right side.
    expect(stage).toHaveStyle({ overflow: 'hidden' })
    expect(screen.getByTestId('video-stage-cover')).toContainElement(screen.getByTestId('stage-cover'))
    expect(content).toContainElement(video)
    expect(content.style.clipPath).toBe(HIDDEN)
    fireEvent(video, new Event('loadeddata'))
    // The slanted edge sweeps across: the player over the whole place, the cover still under it.
    expect(content.style.clipPath).toBe(SHOWN)
    expect(screen.getByTestId('video-stage-cover')).toBeInTheDocument()
  })

  it('sweeps a YouTube player in over its cover once it plays', async () => {
    render(<Card player={YOUTUBE} cover />)
    const frame = await screen.findByTitle('Clip') as HTMLIFrameElement
    const content = screen.getByTestId('video-stage-content')
    expect(content.style.clipPath).toBe(HIDDEN)
    fireEvent(window, new MessageEvent('message', { source: frame.contentWindow,
      data: JSON.stringify({ event: 'infoDelivery', info: { playerState: 1 } }) }))
    expect(content.style.clipPath).toBe(SHOWN)
  })

  it('sweeps the player back off its cover when its card stops playing, and releases the player only after', async () => {
    const { rerender } = render(<Card player={FILE} cover />)
    fireEvent(await screen.findByTestId('video-stage-file'), new Event('loadeddata'))
    const content = screen.getByTestId('video-stage-content')
    expect(content.style.clipPath).toBe(SHOWN)
    rerender(<Card player={FILE} cover playing={false} />)
    // The cover stays in place (its copy in the stage, already painted) while the player sweeps off it.
    expect(screen.getByTestId('video-stage-cover')).toContainElement(screen.getByTestId('stage-cover'))
    expect(screen.getByTestId('video-stage')).toBeInTheDocument()
    expect(content.style.clipPath).toBe(HIDDEN)
    await waitFor(() => expect(screen.queryByTestId('video-stage')).toBeNull())
  })

  it('shows a player on a place without a cover at once (the start page), with nothing cut off it', async () => {
    render(<Card player={FILE} />)
    await screen.findByTestId('video-stage-file')
    expect(screen.getByTestId('video-stage-content').style.clipPath).toBe('')
    expect(screen.queryByTestId('video-stage-cover')).toBeNull()
  })
})
