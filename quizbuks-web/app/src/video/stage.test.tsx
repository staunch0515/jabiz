import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import '../qb/view/messages'
import { VideoSlot, VideoStage } from './stage'

const FILE = 'https://cdn.example.com/first-frame.mp4'
const YOUTUBE = 'https://www.youtube-nocookie.com/embed/firstFrame01'

afterEach(cleanup)

/** A card's video slot, with the one stage laid over it (as the app has them). */
function Card({ player }: { player: string }) {
  return (
    <>
      <VideoSlot id={`card:${player}`} player={player} title="Clip" mode="card" />
      <VideoStage />
    </>
  )
}

describe('the stage shows a player only once it has its first frame', () => {
  it('keeps what is under a video file until its first frame is ready', async () => {
    render(<Card player={FILE} />)
    const stage = await screen.findByTestId('video-stage')
    const video = screen.getByTestId('video-stage-file')
    // Until the frame is there the stage is transparent, so the cover under the slot stays visible (no black frame).
    expect(stage).toHaveStyle({ opacity: '0' })
    fireEvent(video, new Event('loadeddata'))
    expect(stage).toHaveStyle({ opacity: '1' })
  })

  it('keeps what is under a YouTube player until it plays, then shows the player', async () => {
    render(<Card player={YOUTUBE} />)
    const frame = await screen.findByTitle('Clip') as HTMLIFrameElement
    const stage = screen.getByTestId('video-stage')
    expect(stage).toHaveStyle({ opacity: '0' })
    fireEvent(window, new MessageEvent('message', { source: frame.contentWindow,
      data: JSON.stringify({ event: 'infoDelivery', info: { playerState: 1 } }) }))
    expect(stage).toHaveStyle({ opacity: '1' })
  })
})
