import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import '../qb/view/messages'
import { coverBox, openWithSound, soundOn, UNASKED_SOUND_MS, useStage, VERTICAL_ASPECT, VideoSlot, VideoStage } from './stage'

const FILE = 'https://cdn.example.com/first-frame.mp4'
const YOUTUBE = 'https://www.youtube-nocookie.com/embed/firstFrame01'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

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

describe('a vertical video fills the vertical mode\'s screen (F-S10)', () => {
  it('places a player of a shape over all of a place, centred, cut at the edges (object-fit: cover)', () => {
    // A phone's screen, narrower than 9:16: as high as the screen, cut at the sides.
    expect(coverBox(390, 844, VERTICAL_ASPECT)).toEqual({ width: 474.75, height: 844, left: -42.375, top: 0 })
    // The wide layout's column, of the video's own shape: nothing cut.
    expect(coverBox(450, 800, VERTICAL_ASPECT)).toEqual({ width: 450, height: 800, left: 0, top: 0 })
    // Wider than 9:16 (a tablet): as wide as the place, cut at the top and the bottom.
    expect(coverBox(900, 1000, VERTICAL_ASPECT)).toEqual({ width: 900, height: 1600, left: 0, top: -300 })
    // No place yet: nothing.
    expect(coverBox(0, 0, VERTICAL_ASPECT)).toEqual({ width: 0, height: 0, left: 0, top: 0 })
  })

  function Short({ player, vertical = true }: { player: string; vertical?: boolean }) {
    return (
      <>
        <VideoSlot id={`short:${player}`} player={player} title="Short" mode="short" fit={vertical ? 'cover' : undefined} />
        <VideoStage />
      </>
    )
  }
  const place = (width: number, height: number) => vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect')
    .mockReturnValue({ top: 0, left: 0, right: width, bottom: height, width, height, x: 0, y: 0,
      toJSON: () => ({}) } as DOMRect)

  it('gives a frame\'s player the vertical shape over all of a phone\'s screen, under the screens\' overlays', async () => {
    place(390, 844)
    render(<Short player="https://www.youtube-nocookie.com/embed/coverFrame1" />)
    const frame = await screen.findByTitle('Short') as HTMLIFrameElement
    await waitFor(() => expect(frame.style.height).toBe('844px'))
    expect(frame.style.width).toBe('474.75px')
    expect(frame.style.left).toBe('-42.375px')
    const stage = screen.getByTestId('video-stage')
    expect(stage).toHaveAttribute('data-fit', 'cover')
    expect(stage).toHaveStyle({ overflow: 'hidden', zIndex: '45' })
  })

  it('fills the screen with a video file (cover), a landscape one letterboxed as before', async () => {
    const { unmount } = render(<Short player="https://cdn.example.com/tall-cover.mp4" />)
    expect(await screen.findByTestId('video-stage-file')).toHaveClass('object-cover')
    unmount()
    render(<Short player="https://cdn.example.com/wide-contain.mp4" vertical={false} />)
    expect(await screen.findByTestId('video-stage-file')).toHaveClass('object-contain')
  })
})

describe('a short plays on when the browser refuses the sound asked for without a tap (F-S10)', () => {
  function Sound() {
    const stage = useStage()
    return <span data-testid="sound">{String(stage.soundOn || stage.soundAsked)}</span>
  }
  function Short({ player }: { player: string }) {
    return (
      <>
        <VideoSlot id={`short:${player}`} player={player} title="Short" mode="short" fit="cover" />
        <VideoStage />
        <Sound />
      </>
    )
  }

  it('starts a video file muted again when its play with the sound is refused', async () => {
    const player = 'https://cdn.example.com/refused-sound.mp4'
    let refuse = true
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (this: HTMLMediaElement) {
      return refuse && !this.muted ? Promise.reject(new DOMException('No tap', 'NotAllowedError')) : Promise.resolve()
    })
    // The sound asked for by the tap on the screen before (the new player takes it as it comes).
    openWithSound(player)
    render(<Short player={player} />)
    const video = await screen.findByTestId('video-stage-file') as HTMLVideoElement
    await waitFor(() => expect(video.muted).toBe(true))
    expect(play.mock.calls.length).toBeGreaterThanOrEqual(2)
    // The sound button shows again; the viewer's own tap then turns it on.
    expect(screen.getByTestId('sound')).toHaveTextContent('false')
    refuse = false
    soundOn()
    expect(video.muted).toBe(false)
  })

  it('plays a YouTube short on, muted, when it paused as the sound came without a tap; a later pause is the viewer\'s', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(9_000_000)
    const player = 'https://www.youtube-nocookie.com/embed/refusedSnd1'
    openWithSound(player)
    render(<Short player={player} />)
    const frame = await screen.findByTitle('Short') as HTMLIFrameElement
    const posted: string[] = []
    vi.spyOn(frame.contentWindow!, 'postMessage').mockImplementation((message: unknown) => {
      posted.push(JSON.parse(String(message)).func ?? '')
    })
    const hear = (data: unknown) => fireEvent(window, new MessageEvent('message', { source: frame.contentWindow,
      data: JSON.stringify(data) }))
    hear({ event: 'onReady' })
    expect(posted).toContain('unMute')
    // The browser refused: the player paused on its controls. It plays on, muted.
    posted.length = 0
    hear({ event: 'onStateChange', info: 2 })
    expect(posted).toEqual(['mute', 'playVideo'])
    expect(screen.getByTestId('sound')).toHaveTextContent('false')
    // The viewer's tap turns the sound on; a pause long after is the viewer's own and is left alone.
    soundOn()
    hear({ event: 'onStateChange', info: 1 })
    now.mockReturnValue(9_000_000 + UNASKED_SOUND_MS + 1)
    posted.length = 0
    hear({ event: 'onStateChange', info: 2 })
    expect(posted).toEqual([])
  })
})
