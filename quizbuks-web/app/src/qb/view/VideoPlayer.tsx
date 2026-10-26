/**
 * A video's own player in the page (Q19): YouTube's no-cookie player or Vimeo's, loaded when it comes near the
 * screen and never playing by itself (the viewer presses its play button). The frame is sandboxed to what the players
 * need; it cannot navigate the App. Its address is one of the two players (`qb/video.ts`), never anything else.
 */
export function VideoPlayer({ player, title }: { player: string; title: string }) {
  return (
    <div className="bg-muted aspect-video w-full overflow-hidden rounded-md" data-testid="video-player">
      <iframe
        src={player}
        title={title}
        loading="lazy"
        className="size-full border-0"
        allow="encrypted-media; picture-in-picture; fullscreen"
        allowFullScreen
        // YouTube's player refuses to play without the page's origin as the referrer.
        referrerPolicy="strict-origin-when-cross-origin"
        sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox"
      />
    </div>
  )
}
