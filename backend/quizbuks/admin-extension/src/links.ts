/** Only an https link is made a link (the server accepts no other for sponsors' pages and products). */
export const httpsUrl = (url: string | null) => (url && /^https:\/\/[^\s"'<>]+$/.test(url) ? url : null)
