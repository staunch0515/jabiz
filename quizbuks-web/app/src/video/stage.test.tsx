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

/** The player's and the cover's opacity (they cross-fade). */
function faded(): [string, string] {
  return [screen.getByTestId('video-stage-content').style.opacity, screen.getByTestId('video-stage-cover').style.opacity]
}
const HIDDEN: [string, string] = ['0', '1']
const SHOWN: [string, string] = ['1', '0']

describe('the stage shows a player only once it has its first frame', () => {
  it('fades a video file in over its cover, and the cover out, once its first frame is ready', async () => {
    render(<Card player={FILE} cover />)
    const stage = await screen.findByTestId('video-stage')
    const video = screen.getByTestId('video-stage-file')
    const content = screen.getByTestId('video-stage-content')
    // Until the frame is there the cover fills the place; the player lies over it, transparent.
    expect(stage).toHaveStyle({ overflow: 'hidden' })
    expect(screen.getByTestId('video-stage-cover')).toContainElement(screen.getByTestId('stage-cover'))
    expect(content).toContainElement(video)
    expect(faded()).toEqual(HIDDEN)
    expect(video).not.toHaveClass('bg-black')
    fireEvent(video, new Event('loadeddata'))
    expect(content.style.transition).not.toContain('250ms')
    // They cross-fade at once (a file's frame is painted when it says so); nothing black lies behind the picture.
    expect(faded()).toEqual(SHOWN)
    expect(screen.getByTestId('video-stage-cover')).toBeInTheDocument()
  })

  it('fades a YouTube player in over its cover once it plays, a beat later (its frame is painted after)', async () => {
    render(<Card player={YOUTUBE} cover />)
    const frame = await screen.findByTitle('Clip') as HTMLIFrameElement
    const content = screen.getByTestId('video-stage-content')
    expect(faded()).toEqual(HIDDEN)
    fireEvent(window, new MessageEvent('message', { source: frame.contentWindow,
      data: JSON.stringify({ event: 'infoDelivery', info: { playerState: 1 } }) }))
    expect(faded()).toEqual(SHOWN)
    expect(content.style.transition).toContain('250ms')
  })

  it('fades the player back to its cover when its card stops playing, and releases the player only after', async () => {
    const { rerender } = render(<Card player={FILE} cover />)
    fireEvent(await screen.findByTestId('video-stage-file'), new Event('loadeddata'))
    expect(faded()).toEqual(SHOWN)
    rerender(<Card player={FILE} cover playing={false} />)
    // The cover stays in place (its copy in the stage, already painted) while the player fades out.
    expect(screen.getByTestId('video-stage-cover')).toContainElement(screen.getByTestId('stage-cover'))
    expect(screen.getByTestId('video-stage')).toBeInTheDocument()
    expect(faded()).toEqual(HIDDEN)
    await waitFor(() => expect(screen.queryByTestId('video-stage')).toBeNull())
  })

  it('shows a player on a place without a cover at once (the start page), with nothing cut off it', async () => {
    render(<Card player={FILE} />)
    await screen.findByTestId('video-stage-file')
    expect(screen.getByTestId('video-stage-content').style.opacity).toBe('')
    expect(screen.queryByTestId('video-stage-cover')).toBeNull()
  })
})
