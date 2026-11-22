import assert from 'node:assert/strict'
import test from 'node:test'
import { loadGameModule } from './load-game-module.mjs'

const { shouldShowTouchControlsByDefault } = await loadGameModule(
  new URL('../src/game/touchDefaults.ts', import.meta.url),
)
const desktop = {
  userAgent:
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/142.0.0.0 Safari/537.36',
  platform: 'Win32',
  maxTouchPoints: 0,
  mobileHint: false,
  coarsePointer: false,
  hoverNone: false,
}

test('Windows和Linux桌面默认收起', () => {
  assert.equal(shouldShowTouchControlsByDefault(desktop), false)
  assert.equal(
    shouldShowTouchControlsByDefault({
      ...desktop,
      platform: 'Linux x86_64',
      userAgent:
        'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/142.0.0.0 Safari/537.36',
    }),
    false,
  )
})

test('带触摸屏的Windows笔记本仍默认收起', () => {
  assert.equal(
    shouldShowTouchControlsByDefault({
      ...desktop,
      maxTouchPoints: 10,
      coarsePointer: true,
      hoverNone: true,
    }),
    false,
  )
})

test('Android手机和平板默认展开，包括横屏和连接鼠标的设备', () => {
  const phone = {
    ...desktop,
    userAgent:
      'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 Chrome/142.0.0.0 Mobile Safari/537.36',
    platform: 'Linux armv8l',
    maxTouchPoints: 5,
    mobileHint: true,
    coarsePointer: true,
    hoverNone: true,
  }
  assert.equal(shouldShowTouchControlsByDefault(phone), true)
  assert.equal(
    shouldShowTouchControlsByDefault({
      ...phone,
      userAgent: phone.userAgent.replace(' Mobile', ''),
      mobileHint: false,
      coarsePointer: false,
      hoverNone: false,
    }),
    true,
  )
})

test('iPhone与iPad桌面网站模式默认展开，普通Mac默认收起', () => {
  assert.equal(
    shouldShowTouchControlsByDefault({
      ...desktop,
      userAgent:
        'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1',
      platform: 'iPhone',
      maxTouchPoints: 5,
    }),
    true,
  )
  const mac = {
    ...desktop,
    userAgent:
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15',
    platform: 'MacIntel',
  }
  assert.equal(shouldShowTouchControlsByDefault(mac), false)
  assert.equal(shouldShowTouchControlsByDefault({ ...mac, maxTouchPoints: 5 }), true)
})

test('未知平板需要主触控和无悬停能力，单独有触点不足以展开', () => {
  const unknown = { ...desktop, userAgent: '', platform: '', maxTouchPoints: 5 }
  assert.equal(shouldShowTouchControlsByDefault(unknown), false)
  assert.equal(
    shouldShowTouchControlsByDefault({ ...unknown, coarsePointer: true, hoverNone: true }),
    true,
  )
})
