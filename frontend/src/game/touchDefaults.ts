export interface TouchDeviceEnvironment {
  userAgent: string
  platform: string
  maxTouchPoints: number
  mobileHint?: boolean
  coarsePointer: boolean
  hoverNone: boolean
}

/** 识别移动设备，而不把支持触屏的电脑当作手机；不依赖窗口宽度。 */
export function shouldShowTouchControlsByDefault(device: TouchDeviceEnvironment): boolean {
  if (device.mobileHint === true) return true
  if (/Android|iPhone|iPad|iPod|Windows Phone|Tablet|Silk|Kindle/i.test(device.userAgent))
    return true

  // iPad 的“桌面网站”模式会报告 Macintosh / MacIntel，但仍有多触点。
  if (device.platform === 'MacIntel' && device.maxTouchPoints > 1) return true

  const desktopPlatform = /^(Win|Mac|Linux.*(?:x86|i[3-6]86))/i.test(device.platform)
  const desktopUserAgent = /Windows NT|Macintosh|X11/i.test(device.userAgent)
  if (desktopPlatform || desktopUserAgent) return false

  // 为未报告系统类型的触屏平板保留兜底，外接触屏本身不足以展开。
  return device.maxTouchPoints > 0 && device.coarsePointer && device.hoverNone
}
