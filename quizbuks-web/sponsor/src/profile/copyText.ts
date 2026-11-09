/** Puts a text on the clipboard; false when the browser refuses (not a secure page, or no permission). */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}
