import { addMessages } from '@jabiz/client'
import { APP } from '../messages'
import { BRAND } from '../qb/brand'

/** The scanner's texts (Q52), part of the app's namespace `app`. */
const en = {
  scan: {
    open: 'Scan',
    title: 'Scan a code',
    hint: 'Point the camera at the QR code on the sponsor\'s screen or on a poster in the shop.',
    starting: 'Starting the camera…',
    scanning: 'Looking for a code…',
    foreign: `This is not a ${BRAND.name} code. Point the camera at the code on the sponsor's screen or poster.`,
    denied: `${BRAND.name} may not use the camera. Allow it in your phone's settings, or scan the code with your phone's camera app.`,
    noCamera: 'No camera was found on this device. Scan the code with your phone\'s camera app instead.',
    unavailable: 'Scanning is not available here. Scan the code with your phone\'s camera app instead.',
    failed: 'The camera could not be started. Try again, or scan the code with your phone\'s camera app.',
    again: 'Try again',
    cancel: 'Close',
    scanAgain: 'Scan again',
    privacy: 'The picture stays on your phone: only the code\'s link is used.',
  },
}

const zh: typeof en = {
  scan: {
    open: '扫一扫',
    title: '扫码',
    hint: '把相机对准商家屏幕上或店内海报上的二维码。',
    starting: '正在打开相机…',
    scanning: '正在寻找二维码…',
    foreign: `这不是 ${BRAND.name} 的码。请对准商家屏幕或海报上的码。`,
    denied: `${BRAND.name} 没有使用相机的权限。请在手机设置中允许，或用手机自带的相机扫码。`,
    noCamera: '这台设备上没有找到相机。请改用手机自带的相机扫码。',
    unavailable: '这里不能扫码。请改用手机自带的相机扫码。',
    failed: '相机没能打开。请重试，或用手机自带的相机扫码。',
    again: '重试',
    cancel: '关闭',
    scanAgain: '再扫一次',
    privacy: '画面只留在你的手机上，只使用码中的链接。',
  },
}

const ja: typeof en = {
  scan: {
    open: 'スキャン',
    title: 'コードを読み取る',
    hint: 'スポンサーの画面か店内ポスターの QR コードにカメラを向けてください。',
    starting: 'カメラを起動しています…',
    scanning: 'コードを探しています…',
    foreign: `${BRAND.name} のコードではありません。スポンサーの画面かポスターのコードにカメラを向けてください。`,
    denied: `${BRAND.name} はカメラを使えません。端末の設定で許可するか、端末のカメラアプリで読み取ってください。`,
    noCamera: 'この端末にカメラが見つかりません。端末のカメラアプリで読み取ってください。',
    unavailable: 'ここでは読み取れません。端末のカメラアプリで読み取ってください。',
    failed: 'カメラを起動できませんでした。もう一度試すか、端末のカメラアプリで読み取ってください。',
    again: 'もう一度',
    cancel: '閉じる',
    scanAgain: 'もう一度読み取る',
    privacy: '映像は端末の中だけで使われ、コードのリンクだけを使います。',
  },
}

export const scanMessages = { en, zh, ja }

addMessages(APP, scanMessages)
