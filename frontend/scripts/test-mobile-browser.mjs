// Run against the isolated GameBrowserTestServer, never the development database.
// Start headless Chrome with CDP 9229, then: node scripts/test-mobile-browser.mjs
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'

const origin = process.env.MOBILE_TEST_BASE_URL || 'http://127.0.0.1:18082'
const cdp = process.env.MOBILE_TEST_CDP_URL || 'http://127.0.0.1:9229'
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))
const errors = []
const measurements = []
let browser
for (let attempt = 0; attempt < 50; attempt++) {
  try {
    browser = await (await fetch(cdp + '/json/version')).json()
    break
  } catch {
    await sleep(100)
  }
}
assert.ok(browser, 'Headless Chrome did not start on ' + cdp)
const ws = new WebSocket(browser.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve, { once: true })
  ws.addEventListener('error', reject, { once: true })
})
let nextId = 0
const pending = new Map()
ws.addEventListener('message', (event) => {
  const message = JSON.parse(event.data)
  if (message.method === 'Runtime.exceptionThrown') {
    const detail = message.params.exceptionDetails
    errors.push(detail.exception?.description || detail.text)
  }
  const request = pending.get(message.id)
  if (!request) return
  pending.delete(message.id)
  clearTimeout(request.timeout)
  if (message.error) request.reject(new Error(request.label + ': ' + message.error.message))
  else request.resolve(message.result)
})
function command(method, params = {}, sessionId) {
  return new Promise((resolve, reject) => {
    const id = ++nextId
    const timeout = setTimeout(() => {
      pending.delete(id)
      reject(new Error('CDP timeout: ' + method))
    }, 8000)
    const label =
      method === 'Input.dispatchTouchEvent' ? method + ' ' + JSON.stringify(params) : method
    pending.set(id, { resolve, reject, timeout, label })
    ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
  })
}
async function evaluate(page, expression, userGesture = false) {
  const result = await command(
    'Runtime.evaluate',
    {
      expression,
      returnByValue: true,
      awaitPromise: true,
      userGesture,
    },
    page.sessionId,
  )
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text)
  }
  return result.result.value
}
async function waitFor(page, expression, label, timeout = 10000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await evaluate(page, expression)) return
    await sleep(60)
  }
  throw new Error(label + '\n' + (await evaluate(page, 'document.body.innerText.slice(-4500)')))
}

// Observe public transport messages and actual Canvas drawing, without component hooks.
const probe = `
window.__mobileProbe={player:null,nearBulletDraws:0,pointers:[],lastInputs:[],last:{},states:{}};
const realDraw=CanvasRenderingContext2D.prototype.drawImage;
CanvasRenderingContext2D.prototype.drawImage=function(image,...args){
  if(this.canvas.isConnected && this.canvas.closest('.battle-frame') && image instanceof HTMLImageElement){
    const probe=window.__mobileProbe;
    if(image.src.includes('players/tank_T2_0.png'))probe.player={x:args[4],y:args[5],size:args[6]};
    if(image.src.includes('projectiles/bullet_') && probe.player && args.length===4){
      if(Math.abs(args[0]-probe.player.x)<44 && Math.abs(args[1]-probe.player.y)<44)probe.nearBulletDraws++;
    }
  }
  return realDraw.call(this,image,...args);
};
for(const eventType of ['pointerdown','pointermove','pointerup','pointercancel','gotpointercapture','lostpointercapture']){
  document.addEventListener(eventType,event=>{
    const list=window.__mobileProbe.pointers;
    list.push({event:eventType,id:event.pointerId,type:event.pointerType,x:event.clientX,y:event.clientY,
      target:event.target.className,fire:Boolean(event.target.closest?.('.touch-fire-button')),at:performance.now()});
    if(list.length>1000)list.shift();
  },true);
}
const RealWebSocket=window.WebSocket;
window.WebSocket=class extends RealWebSocket{
  constructor(...args){
    super(...args);
    this.addEventListener('message',event=>{
      if(typeof event.data!=='string')return;
      for(const frame of event.data.replaceAll('\\r\\n','\\n').split('\\0')){
        const separator=frame.indexOf('\\n\\n');if(separator<0)continue;
        try{
          const message=JSON.parse(frame.slice(separator+2));if(!message.type)continue;
          const probe=window.__mobileProbe;probe.last[message.type]=message.payload;
          const state=message.type==='game:end'?message.payload.state:message.payload;
          if(message.type.startsWith('game:')&&Number.isInteger(state?.tick)){
            probe.states[state.tick]=state;
            const ticks=Object.keys(probe.states);if(ticks.length>80)delete probe.states[ticks[0]];
          }
        }catch{}
      }
    });
  }
  send(data){
    if(typeof data==='string'&&data.includes('destination:/app/player/input')){
      const separator=data.indexOf('\\n\\n');
      try{
        const body=JSON.parse(data.slice(separator+2).replaceAll('\\0',''));
        window.__mobileProbe.lastInputs.push(body);
        if(window.__mobileProbe.lastInputs.length>100)window.__mobileProbe.lastInputs.shift();
      }catch{}
    }
    return super.send(data);
  }
};`

async function viewport(page, width, height, mobile = true) {
  await command(
    'Emulation.setDeviceMetricsOverride',
    {
      width,
      height,
      deviceScaleFactor: 1,
      mobile,
    },
    page.sessionId,
  )
  await command(
    'Emulation.setTouchEmulationEnabled',
    {
      enabled: mobile,
      ...(mobile ? { maxTouchPoints: 5 } : {}),
    },
    page.sessionId,
  )
  await sleep(120)
}
async function page(mobile = true) {
  const context = await command('Target.createBrowserContext')
  const target = await command('Target.createTarget', {
    url: 'about:blank',
    browserContextId: context.browserContextId,
  })
  const attached = await command('Target.attachToTarget', {
    targetId: target.targetId,
    flatten: true,
  })
  const result = { sessionId: attached.sessionId, targetId: target.targetId, contacts: new Map() }
  await command('Runtime.enable', {}, result.sessionId)
  await command('Page.enable', {}, result.sessionId)
  await command('Page.addScriptToEvaluateOnNewDocument', { source: probe }, result.sessionId)
  await viewport(result, mobile ? 390 : 1366, mobile ? 844 : 900, mobile)
  return result
}
async function navigate(page, path) {
  page.contacts.clear()
  await command('Page.navigate', { url: origin + path }, page.sessionId)
}
async function routeTo(page, path) {
  await evaluate(
    page,
    `history.pushState(history.state,'',${JSON.stringify(path)});dispatchEvent(new PopStateEvent('popstate',{state:history.state}))`,
  )
}
function button(text) {
  return `[...document.querySelectorAll('button')].find(element=>element.textContent.trim()===${JSON.stringify(text)})`
}
async function clickExpression(page, expression, label) {
  await command('Target.activateTarget', { targetId: page.targetId })
  await waitFor(page, `Boolean(${expression}) && !(${expression}).disabled`, label)
  await evaluate(page, `(${expression}).scrollIntoView({block:'center',behavior:'instant'})`)
  // User activation is necessary for native fullscreen and AudioContext.
  await evaluate(page, `(${expression}).click()`, true)
}
async function click(page, selector) {
  await clickExpression(page, `document.querySelector(${JSON.stringify(selector)})`, selector)
}
async function clickText(page, text) {
  await clickExpression(page, button(text), text)
}
async function center(page, selector) {
  return evaluate(
    page,
    `(()=>{const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`,
  )
}
async function contactDown(page, id, selector) {
  const point = await center(page, selector)
  page.contacts.set(id, { ...point, id, radiusX: 3, radiusY: 3, force: 1 })
  await command(
    'Input.dispatchTouchEvent',
    {
      type: 'touchStart',
      touchPoints: [page.contacts.get(id)],
    },
    page.sessionId,
  )
}
async function contactMove(page, id, point) {
  const existing = page.contacts.get(id)
  assert.ok(existing, 'Contact must be down before moving')
  page.contacts.set(id, { ...existing, ...point })
  await command(
    'Input.dispatchTouchEvent',
    {
      type: 'touchMove',
      touchPoints: [page.contacts.get(id)],
    },
    page.sessionId,
  )
}
async function contactUp(page, id) {
  const point = page.contacts.get(id)
  assert.ok(point, 'Contact must be down before releasing')
  page.contacts.delete(id)
  // Match Puppeteer's CdpTouchHandle: submit only the point being released.
  await command(
    'Input.dispatchTouchEvent',
    {
      type: 'touchEnd',
      touchPoints: [point],
    },
    page.sessionId,
  )
}
async function cancelContacts(page) {
  if (page.contacts.size === 0) return
  page.contacts.clear()
  await command(
    'Input.dispatchTouchEvent',
    { type: 'touchCancel', touchPoints: [] },
    page.sessionId,
  )
}
async function key(page, type, code, key, virtual) {
  await command(
    'Input.dispatchKeyEvent',
    {
      type,
      code,
      key,
      windowsVirtualKeyCode: virtual,
    },
    page.sessionId,
  )
}
async function playerPosition(page) {
  await waitFor(page, 'Boolean(window.__mobileProbe.player)', 'player sprite drawn')
  return evaluate(page, 'window.__mobileProbe.player')
}
async function assertStopped(page, label) {
  await sleep(130)
  const before = await playerPosition(page)
  await sleep(220)
  const after = await playerPosition(page)
  assert.ok(Math.abs(after.x - before.x) <= 1 && Math.abs(after.y - before.y) <= 1, label)
}
async function controlsReleased(page, label) {
  await waitFor(
    page,
    `!document.querySelector('.touch-direction-button.pressed') && document.querySelector('.touch-fire-button')?.getAttribute('aria-pressed')==='false'`,
    label,
    1500,
  )
}
async function screenshot(page, name) {
  const shot = await command(
    'Page.captureScreenshot',
    { format: 'png', captureBeyondViewport: false },
    page.sessionId,
  )
  await writeFile(
    new URL('../../backend/target/' + name, import.meta.url),
    Buffer.from(shot.data, 'base64'),
  )
}
async function noOverflow(page, label) {
  await sleep(80)
  assert.equal(
    await evaluate(page, 'document.documentElement.scrollWidth > innerWidth + 1'),
    false,
    label + ': page horizontal overflow',
  )
}
async function checkBattleLayout(page, label, touchExpected = true) {
  await waitFor(
    page,
    'document.querySelector(".battle-stage")?.getBoundingClientRect().width > 0',
    label + ': stage visible',
  )
  await sleep(100)
  const result = await evaluate(
    page,
    `(()=>{
    const frame=document.querySelector('.battle-frame');
    const rect=element=>{if(!element)return null;const r=element.getBoundingClientRect();return {left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height}};
    const canvas=frame.querySelector('canvas');
    return {width:innerWidth,height:innerHeight,canvas:rect(canvas),logical:[canvas.width,canvas.height],
      frame:rect(frame),hud:rect(frame.querySelector('.battle-hud')),footer:rect(frame.querySelector('.battle-footer')),
      pad:rect(frame.querySelector('.touch-direction-pad')),fire:rect(frame.querySelector('.touch-fire-button')),
      overflow:[frame.scrollWidth-frame.clientWidth,frame.scrollHeight-frame.clientHeight],
      controlsInsideFullscreen:!document.fullscreenElement||[...frame.querySelectorAll('.touch-direction-pad,.touch-fire-button')].every(element=>document.fullscreenElement.contains(element))};
  })()`,
  )
  assert.deepEqual(result.logical, [624, 624], label + ': keep logical coordinates')
  assert.equal(
    Boolean(result.pad && result.fire),
    touchExpected,
    label + ': expected touch control visibility',
  )
  assert.ok(
    Math.abs(result.canvas.width - result.canvas.height) <= 1,
    label + ': keep map proportions',
  )
  assert.ok(result.canvas.width > 60, label + ': battlefield remains visible')
  for (const [name, box] of Object.entries({
    frame: result.frame,
    hud: result.hud,
    footer: result.footer,
    canvas: result.canvas,
    pad: result.pad,
    fire: result.fire,
  })) {
    if (!box) continue
    assert.ok(
      box.left >= -1 &&
        box.top >= -1 &&
        box.right <= result.width + 1 &&
        box.bottom <= result.height + 1,
      label + ': ' + name + ' within screen: ' + JSON.stringify(result),
    )
  }
  for (const box of [result.pad, result.fire].filter(Boolean)) {
    const overlapX =
      Math.min(box.right, result.canvas.right) - Math.max(box.left, result.canvas.left)
    const overlapY =
      Math.min(box.bottom, result.canvas.bottom) - Math.max(box.top, result.canvas.top)
    assert.ok(overlapX <= 1 || overlapY <= 1, label + ': controls do not cover battlefield')
  }
  assert.ok(
    result.overflow.every((value) => value <= 1),
    label + ': fullscreen overflow ' + JSON.stringify(result),
  )
  assert.ok(result.controlsInsideFullscreen, label + ': controls are inside native fullscreen root')
  measurements.push({
    label,
    viewport: `${result.width}x${result.height}`,
    canvas: result.canvas.width,
  })
}
async function fallbackFullscreen(page) {
  await evaluate(
    page,
    `Element.prototype.requestFullscreen=function(){return Promise.reject(new DOMException('Test browser restriction','NotAllowedError'))}`,
  )
  await click(page, '.fullscreen-button')
  await waitFor(
    page,
    `document.querySelector('.battle-frame.is-fullscreen') && !document.fullscreenElement`,
    'window fullscreen fallback',
  )
}
async function restartSolo(page) {
  await cancelContacts(page)
  await clickExpression(
    page,
    `[...document.querySelectorAll('.battle-actions button')].find(element=>element.textContent.includes('重开'))`,
    'restart solo',
  )
  await waitFor(
    page,
    `!document.querySelector('.game-overlay') && !document.querySelector('.touch-fire-button').disabled`,
    'solo restarted',
  )
  await controlsReleased(page, 'restart releases controls')
  await sleep(80)
}

try {
  assert.equal((await fetch(origin + '/game')).ok, true, 'Isolated server must be running')
  const solo = await page()
  await navigate(solo, '/game')
  await waitFor(solo, 'Boolean(document.querySelector(".menu-screen"))', 'mobile menu')
  await noOverflow(solo, 'menu')
  await click(solo, '.solo-option')
  await waitFor(
    solo,
    `document.querySelector('.play-button') && !document.querySelector('.play-button').disabled && document.querySelector('.touch-fire-button')`,
    'solo assets and automatic touch controls',
  )
  assert.equal(
    await evaluate(solo, 'document.querySelector(".touch-fire-button").disabled'),
    true,
    'ready game disables combat input',
  )
  await click(solo, '.fullscreen-button')
  await waitFor(
    solo,
    'document.fullscreenElement === document.querySelector(".battle-frame")',
    'native fullscreen',
  )
  await checkBattleLayout(solo, 'native portrait')
  await click(solo, '.fullscreen-button')
  await waitFor(
    solo,
    '!document.fullscreenElement && !document.querySelector(".battle-frame.is-fullscreen")',
    'native exit',
  )
  await fallbackFullscreen(solo)
  await click(solo, '.play-button')
  await waitFor(solo, '!document.querySelector(".game-overlay")', 'solo playing')

  const initial = await playerPosition(solo)
  await contactDown(solo, 1, '.touch-direction-pad [data-direction="0"]')
  await sleep(180)
  await contactDown(solo, 2, '.touch-fire-button')
  await waitFor(
    solo,
    `document.querySelector('.touch-fire-button').getAttribute('aria-pressed')==='true' && window.__mobileProbe.nearBulletDraws>0`,
    'two fingers actually fire near the player',
  )
  const moving = await playerPosition(solo)
  assert.ok(moving.y < initial.y - 4, 'touch direction moves the player')
  await contactUp(solo, 2)
  await waitFor(
    solo,
    `document.querySelector('.touch-fire-button').getAttribute('aria-pressed')==='false'`,
    'release fire only',
  )
  const stillMoving = await playerPosition(solo)
  await sleep(180)
  const afterFireRelease = await playerPosition(solo)
  const releaseDetails = await evaluate(
    solo,
    `(()=>{
    const rect=selector=>{const r=document.querySelector(selector).getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height}};
    return {direction:document.querySelector('.touch-direction-button.pressed')?.dataset.direction,
      fire:document.querySelector('.touch-fire-button').getAttribute('aria-pressed'),
      pad:rect('.touch-direction-pad'),fireRect:rect('.touch-fire-button'),pointers:window.__mobileProbe.pointers,
      viewport:{width:innerWidth,height:innerHeight,visualWidth:visualViewport.width,visualHeight:visualViewport.height},
      hidden:document.hidden,overlay:document.querySelector('.game-overlay')?.innerText};
  })()`,
  )
  assert.ok(
    afterFireRelease.y < stillMoving.y - 3,
    'releasing fire preserves direction ' +
      JSON.stringify({ initial, moving, stillMoving, afterFireRelease, releaseDetails }),
  )
  await contactUp(solo, 1)
  await controlsReleased(solo, 'all fingers released')
  await assertStopped(solo, 'lifting direction stops movement')

  await restartSolo(solo)
  await contactDown(solo, 1, '.touch-direction-pad [data-direction="0"]')
  await contactDown(solo, 2, '.touch-fire-button')
  await sleep(120)
  await contactUp(solo, 1)
  assert.equal(
    await evaluate(
      solo,
      'document.querySelector(".touch-fire-button").getAttribute("aria-pressed")',
    ),
    'true',
    'lifting direction preserves fire',
  )
  await assertStopped(solo, 'lifting direction while firing stops movement')
  await cancelContacts(solo)
  await controlsReleased(solo, 'touchcancel releases all controls')

  await restartSolo(solo)
  await contactDown(solo, 1, '.touch-direction-pad [data-direction="0"]')
  await contactDown(solo, 2, '.touch-fire-button')
  const fireContact = solo.contacts.get(2)
  await contactMove(solo, 2, { x: fireContact.x + 12, y: fireContact.y })
  await evaluate(
    solo,
    `(()=>{const id=window.__mobileProbe.pointers.filter(event=>event.fire).at(-1).id;document.querySelector('.touch-fire-button').releasePointerCapture(id)})()`,
  )
  // Pending pointer-capture changes are processed before the next pointer event.
  await contactMove(solo, 2, { x: fireContact.x + 24, y: fireContact.y })
  const captureDetails = await evaluate(
    solo,
    `({fire:document.querySelector('.touch-fire-button').getAttribute('aria-pressed'),direction:document.querySelector('.touch-direction-button.pressed')?.dataset.direction,events:window.__mobileProbe.pointers.slice(-20)})`,
  )
  await waitFor(
    solo,
    `document.querySelector('.touch-fire-button').getAttribute('aria-pressed')==='false' && document.querySelector('.touch-direction-button.up').classList.contains('pressed')`,
    'losing fire capture preserves movement ' + JSON.stringify(captureDetails),
    1500,
  )
  await cancelContacts(solo)

  await restartSolo(solo)
  await contactDown(solo, 1, '.touch-direction-pad [data-direction="0"]')
  const beforeTurn = await playerPosition(solo)
  await contactMove(solo, 1, await center(solo, '.touch-direction-pad [data-direction="2"]'))
  await sleep(180)
  assert.ok(
    (await playerPosition(solo)).x < beforeTurn.x - 3,
    'dragging within the pad turns the tank',
  )
  const outsidePad = await evaluate(
    solo,
    `(()=>{const r=document.querySelector('.touch-direction-pad').getBoundingClientRect();return {x:r.left-5,y:r.y+r.height/2}})()`,
  )
  await contactMove(solo, 1, outsidePad)
  await assertStopped(solo, 'dragging out of the captured pad stops movement')
  await contactMove(solo, 1, await center(solo, '.touch-direction-pad [data-direction="0"]'))
  const beforeReturn = await playerPosition(solo)
  await sleep(160)
  assert.ok(
    (await playerPosition(solo)).y < beforeReturn.y - 3,
    'returning into the pad resumes the chosen direction',
  )
  await cancelContacts(solo)

  await restartSolo(solo)
  await evaluate(solo, 'document.querySelector("canvas").focus({preventScroll:true})')
  await key(solo, 'keyDown', 'ArrowDown', 'ArrowDown', 40)
  await contactDown(solo, 1, '.touch-direction-pad [data-direction="0"]')
  const mixedStart = await playerPosition(solo)
  await sleep(180)
  assert.ok(
    (await playerPosition(solo)).y < mixedStart.y - 3,
    'touch direction takes precedence while keyboard held',
  )
  await key(solo, 'keyUp', 'ArrowDown', 'ArrowDown', 40)
  const afterKeyUp = await playerPosition(solo)
  await sleep(160)
  assert.ok(
    (await playerPosition(solo)).y < afterKeyUp.y - 3,
    'keyboard release does not cancel touch direction',
  )
  await cancelContacts(solo)

  await restartSolo(solo)
  await evaluate(solo, 'document.querySelector("canvas").focus({preventScroll:true})')
  await key(solo, 'keyDown', 'Space', ' ', 32)
  await contactDown(solo, 2, '.touch-fire-button')
  await key(solo, 'keyUp', 'Space', ' ', 32)
  await sleep(350)
  const afterKeyboardFireRelease = await evaluate(solo, 'window.__mobileProbe.nearBulletDraws')
  await sleep(450)
  assert.ok(
    (await evaluate(solo, 'window.__mobileProbe.nearBulletDraws')) > afterKeyboardFireRelease,
    'keyboard fire release preserves actual touch firing',
  )
  await cancelContacts(solo)

  await restartSolo(solo)
  await contactDown(solo, 1, '.touch-direction-pad [data-direction="0"]')
  await contactDown(solo, 2, '.touch-fire-button')
  await clickExpression(
    solo,
    `[...document.querySelectorAll('.battle-actions button')].find(element=>element.textContent.includes('暂停'))`,
    'pause solo',
  )
  await waitFor(solo, 'Boolean(document.querySelector(".game-overlay"))', 'pause overlay')
  await controlsReleased(solo, 'pause releases both controls')
  await cancelContacts(solo)
  await click(solo, '.play-button')
  await assertStopped(solo, 'resume requires fresh input')

  await contactDown(solo, 1, '.touch-direction-pad [data-direction="0"]')
  await contactDown(solo, 2, '.touch-fire-button')
  await clickExpression(
    solo,
    `[...document.querySelectorAll('.battle-actions button')].find(element=>element.textContent.includes('重开'))`,
    'restart while pressed',
  )
  await controlsReleased(solo, 'playing-to-playing restart releases pointers')
  await cancelContacts(solo)
  await assertStopped(solo, 'restart has no stale movement')

  await contactDown(solo, 1, '.touch-direction-pad [data-direction="0"]')
  await contactDown(solo, 2, '.touch-fire-button')
  // Simulate lifecycle notification; real lock-screen behavior remains a device acceptance check.
  await evaluate(
    solo,
    `Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});document.dispatchEvent(new Event('visibilitychange'));delete document.hidden`,
  )
  await waitFor(solo, 'Boolean(document.querySelector(".game-overlay"))', 'background pauses solo')
  await controlsReleased(solo, 'background releases pointers')
  await cancelContacts(solo)
  await click(solo, '.play-button')
  await assertStopped(solo, 'background return requires fresh input')

  await contactDown(solo, 1, '.touch-direction-pad [data-direction="0"]')
  await contactDown(solo, 2, '.touch-fire-button')
  await viewport(solo, 844, 390)
  await controlsReleased(solo, 'rotation releases pointers')
  await cancelContacts(solo)
  await assertStopped(solo, 'rotation has no stale input')
  for (const [width, height] of [
    [844, 390],
    [390, 844],
    [320, 568],
  ]) {
    await viewport(solo, width, height)
    await checkBattleLayout(solo, 'solo ' + width + 'x' + height)
    await screenshot(solo, `mobile-solo-${width}x${height}.png`)
  }
  await routeTo(solo, '/game/single/result')
  await waitFor(
    solo,
    `document.querySelector('.result-page') && !document.querySelector('.battle-frame.is-fullscreen') && document.body.style.overflow!=='hidden'`,
    'nested result exits fullscreen and restores scroll',
  )
  await noOverflow(solo, 'result')
  await navigate(solo, '/login')
  await waitFor(solo, 'Boolean(document.querySelector("form"))', 'mobile login')
  await noOverflow(solo, 'login')

  const desktop = await page(false)
  await navigate(desktop, '/game/single')
  await waitFor(
    desktop,
    `document.querySelector('.play-button') && !document.querySelector('.play-button').disabled`,
    'desktop solo ready',
  )
  // Disabling emulation restores physical host capability, which can still be touch-enabled.
  // Use the product's manual toggle to exercise the no-control fullscreen layout in either case.
  if (await evaluate(desktop, 'Boolean(document.querySelector(".touch-direction-pad"))')) {
    await click(desktop, '.touch-toggle')
  }
  await waitFor(
    desktop,
    '!document.querySelector(".touch-direction-pad")',
    'desktop keyboard-only layout',
  )
  await click(desktop, '.fullscreen-button')
  await waitFor(
    desktop,
    'document.fullscreenElement === document.querySelector(".battle-frame")',
    'desktop native fullscreen',
  )
  await checkBattleLayout(desktop, 'desktop without touch controls', false)
  await click(desktop, '.touch-toggle')
  await waitFor(
    desktop,
    'Boolean(document.querySelector(".touch-direction-pad"))',
    'desktop manual touch controls on',
  )
  await checkBattleLayout(desktop, 'desktop manual touch controls')
  await click(desktop, '.touch-toggle')
  await waitFor(
    desktop,
    '!document.querySelector(".touch-direction-pad")',
    'desktop manual touch controls off',
  )
  await checkBattleLayout(desktop, 'desktop controls hidden again', false)
  await click(desktop, '.fullscreen-button')
  await waitFor(desktop, '!document.fullscreenElement', 'desktop fullscreen exit')

  const owner = await page()
  const teammate = await page(false)
  for (const [peer, id] of [
    [owner, 101],
    [teammate, 102],
  ]) {
    await navigate(peer, '/__test/login/' + id)
    await waitFor(peer, 'document.body.innerText.includes("player")', 'fixture login ' + id)
    await navigate(peer, '/game/online')
    await waitFor(
      peer,
      `Boolean(${button('创建房间')}) && !(${button('创建房间')}).disabled`,
      'connected lobby ' + id,
    )
  }
  await noOverflow(owner, 'mobile lobby')
  await clickText(owner, '创建房间')
  await waitFor(owner, 'Boolean(document.querySelector(".room-meta code"))', 'room created')
  const roomId = await evaluate(
    owner,
    'document.querySelector(".room-meta code").textContent.trim()',
  )
  await evaluate(
    teammate,
    `(()=>{const field=document.querySelector('input[maxlength="36"]');field.value=${JSON.stringify(roomId)};field.dispatchEvent(new Event('input',{bubbles:true}))})()`,
  )
  await clickText(teammate, '加入房间')
  await waitFor(
    owner,
    'document.querySelectorAll(".players li:not(.empty-seat)").length===2',
    'both players joined',
  )
  await noOverflow(owner, 'mobile waiting room')
  await clickText(owner, '准备')
  await clickText(teammate, '准备')
  await clickText(owner, '开始游戏')
  for (const peer of [owner, teammate]) {
    await waitFor(
      peer,
      'Boolean(window.__mobileProbe.last["game:start"]) && document.querySelector(".phase-badge")?.textContent.includes("游戏进行中")',
      'cooperative game started',
    )
  }
  await command('Target.activateTarget', { targetId: owner.targetId })
  await fallbackFullscreen(owner)
  await checkBattleLayout(owner, 'cooperative portrait')
  const originalY = await evaluate(
    owner,
    'window.__mobileProbe.last["game:start"].tanks.find(tank=>tank.userId===101).y',
  )
  await contactDown(owner, 1, '.touch-direction-pad [data-direction="0"]')
  await contactDown(owner, 2, '.touch-fire-button')
  await waitFor(
    owner,
    `window.__mobileProbe.lastInputs.at(-1)?.direction===0 && window.__mobileProbe.lastInputs.at(-1)?.fireHeld===true`,
    'touch input uses real STOMP protocol',
  )
  await waitFor(
    owner,
    `window.__mobileProbe.last['game:state']?.tanks.some(tank=>tank.userId===101 && tank.y<${originalY}-4)`,
    'server moves touch-controlled player',
  )
  await contactUp(owner, 2)
  await waitFor(
    owner,
    `window.__mobileProbe.lastInputs.at(-1)?.direction===0 && window.__mobileProbe.lastInputs.at(-1)?.fireHeld===false`,
    'network release fire preserves movement',
  )
  await contactUp(owner, 1)
  await waitFor(
    owner,
    `window.__mobileProbe.lastInputs.at(-1)?.direction===null && window.__mobileProbe.lastInputs.at(-1)?.fireHeld===false`,
    'network release stops both actions',
  )
  const ownerStates = await evaluate(owner, 'window.__mobileProbe.states')
  const teammateStates = await evaluate(teammate, 'window.__mobileProbe.states')
  const commonTicks = Object.keys(ownerStates)
    .filter((tick) => teammateStates[tick])
    .map(Number)
  assert.ok(commonTicks.length > 0, 'both clients received common authoritative ticks')
  const tick = Math.max(...commonTicks)
  assert.deepEqual(
    ownerStates[tick],
    teammateStates[tick],
    'both clients share the same authoritative state',
  )
  await screenshot(owner, 'mobile-coop-preview.png')
  await clickText(teammate, '退出房间')
  await waitFor(
    owner,
    `location.pathname.includes('/game/online/result/') && document.querySelector('.result-screen.abandoned') && !document.querySelector('.battle-frame.is-fullscreen')`,
    'cooperative end exits fullscreen',
  )
  await noOverflow(owner, 'cooperative result')
  assert.equal(errors.length, 0, errors.join('\n'))
  console.log(
    'Mobile browser checks passed: real multi-touch move/fire and independent release; cancel/capture cleanup; keyboard coexistence; pause/restart/background/rotation cleanup; native and fallback fullscreen with controls; proportional mobile layouts; menu/login/lobby/results; authoritative two-player movement and matching snapshots; no runtime exceptions.',
  )
  console.log(JSON.stringify(measurements, null, 2))
} finally {
  await command('Browser.close').catch(() => {})
  ws.close()
}
