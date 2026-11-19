// Prerequisites: isolated GameBrowserTestServer on 18082 and headless Chrome CDP on 9226. No development database writes.
import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
let browser
for (let i = 0; i < 50; i++) {
  try {
    browser = await (await fetch('http://127.0.0.1:9226/json/version')).json()
    break
  } catch {
    await sleep(100)
  }
}
assert.ok(browser, 'Browser did not start')
const ws = new WebSocket(browser.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve, { once: true })
  ws.addEventListener('error', reject, { once: true })
})
let nextId = 0
const pending = new Map()
const runtimeErrors = []
ws.addEventListener('message', (event) => {
  const message = JSON.parse(event.data)
  if (message.method === 'Runtime.exceptionThrown')
    runtimeErrors.push(message.params.exceptionDetails.text)
  const request = pending.get(message.id)
  if (!request) return
  pending.delete(message.id)
  clearTimeout(request.timer)
  message.error ? request.reject(new Error(message.error.message)) : request.resolve(message.result)
})
function command(method, params = {}, sessionId) {
  return new Promise((resolve, reject) => {
    const id = ++nextId
    const timer = setTimeout(() => {
      pending.delete(id)
      reject(new Error('CDP timeout: ' + method))
    }, 5000)
    pending.set(id, { resolve, reject, timer })
    ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
  })
}
async function evaluate(page, expression, userGesture = false) {
  const response = await command(
    'Runtime.evaluate',
    { expression, returnByValue: true, awaitPromise: true, userGesture },
    page.sessionId,
  )
  if (response.exceptionDetails) throw new Error(response.exceptionDetails.text)
  return response.result.value
}
async function waitFor(page, expression, label, timeout = 8000) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    if (await evaluate(page, expression)) return
    await sleep(80)
  }
  const details = await evaluate(page, 'document.body.innerText.slice(-4500)')
  throw new Error('Browser condition failed: ' + label + '\n' + details)
}
const recordSockets = `window.__gameLast = {}; window.__gameMessages = []; window.__gameSockets = [];
const RealWebSocket = window.WebSocket;
window.WebSocket = class extends RealWebSocket {
 constructor(...args) { super(...args); window.__gameSockets.push(this); this.addEventListener('message', event => {
  if (typeof event.data !== 'string') return;
  for (const frame of event.data.replaceAll('\\r\\n', '\\n').split('\\0')) {
   const index = frame.indexOf('\\n\\n'); if (index < 0) continue;
   try { const message = JSON.parse(frame.slice(index + 2)); if (!message.type) continue;
    window.__gameLast[message.type] = message.payload; window.__gameMessages.push(message);
    if (window.__gameMessages.length > 1000) window.__gameMessages.shift();
   } catch {}
  }
 }); }
};`

const audioProbe = `
window.__audioStarts=[];window.__audioDecoded=0;window.__audioFiles=[];window.__testGameSpeed=1;
const nativeStart=AudioBufferSourceNode.prototype.start;
AudioBufferSourceNode.prototype.start=function(...args){window.__audioStarts.push({time:performance.now(),duration:this.buffer?.duration??0});return nativeStart.apply(this,args)};
const nativeDecode=AudioContext.prototype.decodeAudioData;
AudioContext.prototype.decodeAudioData=function(...args){return nativeDecode.apply(this,args).then(buffer=>{window.__audioDecoded++;return buffer})};
const nativeFetch=window.fetch;
window.fetch=function(...args){return nativeFetch.apply(this,args).then(response=>{if(String(args[0]).includes('.wav'))window.__audioFiles.push({path:String(args[0]),status:response.status});return response})};
const nativeRaf=window.requestAnimationFrame;let previousReal=performance.now();let simulated=previousReal;
window.requestAnimationFrame=function(callback){return nativeRaf.call(window,time=>{simulated+=Math.max(0,time-previousReal)*window.__testGameSpeed;previousReal=time;callback(simulated)})};
`

async function page() {
  const context = await command('Target.createBrowserContext')
  const target = await command('Target.createTarget', {
    url: 'about:blank',
    browserContextId: context.browserContextId,
  })
  const attached = await command('Target.attachToTarget', {
    targetId: target.targetId,
    flatten: true,
  })
  const result = {
    sessionId: attached.sessionId,
    targetId: target.targetId,
    contextId: context.browserContextId,
  }
  await command('Runtime.enable', {}, result.sessionId)
  await command('Page.enable', {}, result.sessionId)
  await command(
    'Emulation.setDeviceMetricsOverride',
    { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false },
    result.sessionId,
  )
  await command(
    'Page.addScriptToEvaluateOnNewDocument',
    { source: recordSockets + audioProbe },
    result.sessionId,
  )
  return result
}
async function navigate(page, path) {
  await command('Page.navigate', { url: 'http://127.0.0.1:18082' + path }, page.sessionId)
}
function buttonExpression(text) {
  return `[...document.querySelectorAll('button')].find(button => button.textContent.trim() === ${JSON.stringify(text)})`
}
async function click(page, text) {
  await waitFor(
    page,
    `Boolean(${buttonExpression(text)}) && !(${buttonExpression(text)}).disabled`,
    'enabled button: ' + text,
  )
  await command('Target.activateTarget', { targetId: page.targetId })
  await evaluate(page, `(${buttonExpression(text)}).scrollIntoView({block:'center'})`)
  const point = await evaluate(
    page,
    `(()=>{const rect=(${buttonExpression(text)}).getBoundingClientRect();return {x:rect.x+rect.width/2,y:rect.y+rect.height/2}})()`,
  )
  await command(
    'Input.dispatchMouseEvent',
    { type: 'mousePressed', button: 'left', clickCount: 1, ...point },
    page.sessionId,
  )
  await command(
    'Input.dispatchMouseEvent',
    { type: 'mouseReleased', button: 'left', clickCount: 1, ...point },
    page.sessionId,
  )
}
async function key(page, code, key, virtual, duration) {
  await command('Target.activateTarget', { targetId: page.targetId })
  await evaluate(page, 'document.querySelector("canvas").focus()')
  await command(
    'Input.dispatchKeyEvent',
    { type: 'keyDown', code, key, windowsVirtualKeyCode: virtual },
    page.sessionId,
  )
  await sleep(duration)
  await command(
    'Input.dispatchKeyEvent',
    { type: 'keyUp', code, key, windowsVirtualKeyCode: virtual },
    page.sessionId,
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
try {
  let serverReady = false
  for (let attempt = 0; attempt < 100; attempt++) {
    try {
      if ((await fetch('http://127.0.0.1:18082/game')).ok) {
        serverReady = true
        break
      }
    } catch {}
    await sleep(100)
  }
  assert.ok(serverReady, 'Isolated GameBrowserTestServer did not become ready')
  const solo = await page()
  await navigate(solo, '/game')
  await waitFor(
    solo,
    'document.querySelector(".menu-screen") && document.querySelector(".audio-toggle")',
    'menu with sound controls',
  )
  assert.equal(await evaluate(solo, 'window.__audioStarts.length'), 0)
  await evaluate(solo, 'document.querySelector(".solo-option").click()')
  await click(solo, '开始游戏')
  await waitFor(
    solo,
    'window.__audioDecoded === 7 && window.__audioStarts.length > 0',
    'actual audio decoding and start',
  )
  const initialSounds = await evaluate(solo, 'window.__audioStarts.length')
  await key(solo, 'Space', ' ', 32, 850)
  assert.ok((await evaluate(solo, 'window.__audioStarts.length')) > initialSounds)
  await click(solo, '声音：开')
  const mutedSounds = await evaluate(solo, 'window.__audioStarts.length')
  await key(solo, 'Space', ' ', 32, 700)
  assert.equal(await evaluate(solo, 'window.__audioStarts.length'), mutedSounds)
  await click(solo, '声音：关')
  await evaluate(
    solo,
    'const volume=document.querySelector(".audio-volume input");volume.value="25";volume.dispatchEvent(new Event("input",{bubbles:true}))',
    true,
  )
  assert.equal(
    await evaluate(solo, 'JSON.parse(localStorage.getItem("tank:audio-settings")).volume'),
    0.25,
  )
  assert.ok(
    await evaluate(
      solo,
      'window.__audioFiles.length === 7 && window.__audioFiles.every(file=>file.status===200)',
    ),
  )
  await evaluate(solo, 'window.__testGameSpeed=6')
  await waitFor(
    solo,
    'location.pathname === "/game/single/result" && document.querySelector(".result-screen.defeat")',
    'real single-player defeat navigation',
    20000,
  )
  const soloEnding = await evaluate(
    solo,
    'JSON.parse(sessionStorage.getItem("tank:single:last-result"))',
  )
  assert.equal(soloEnding.outcome, 'DEFEAT')
  await screenshot(solo, 'single-result-preview.png')
  await navigate(solo, '/game/single/result')
  await waitFor(
    solo,
    'document.querySelector(".result-screen.defeat")',
    'single result refresh recovery',
  )
  assert.equal(await evaluate(solo, 'window.__audioStarts.length'), 0)
  assert.equal(
    await evaluate(solo, 'JSON.parse(localStorage.getItem("tank:audio-settings")).volume'),
    0.25,
  )
  await click(solo, '再来一局')
  await waitFor(
    solo,
    'location.pathname === "/game/single" && document.querySelector(".game-overlay") === null',
    'single result retry starts new game',
  )
  assert.ok(
    await evaluate(
      solo,
      'document.querySelector(".battle-health-row.self").textContent.includes("3/3")',
    ),
  )
  await evaluate(solo, 'document.querySelector(".solo-badge").click()')

  const owner = await page()
  const player = await page()
  const observer = await page()
  for (const [peer, id] of [
    [owner, 101],
    [player, 102],
    [observer, 103],
  ]) {
    await navigate(peer, '/__test/login/' + id)
    await waitFor(peer, 'document.body.innerText.includes("player")', 'isolated login ' + id)
    await navigate(peer, '/game/online')
    await waitFor(peer, 'window.__gameLast["room:list"] !== undefined', 'automatic room list ' + id)
  }
  await command('Target.activateTarget', { targetId: owner.targetId })
  await click(owner, '创建房间')
  await waitFor(owner, 'Boolean(document.querySelector(".room-meta code"))', 'owner creates room')
  const roomId = await evaluate(
    owner,
    'document.querySelector(".room-meta code").textContent.trim()',
  )
  for (const peer of [player, observer])
    await waitFor(
      peer,
      `Array.from(document.querySelectorAll('.available-room-card code')).some(code=>code.textContent===${JSON.stringify(roomId)})`,
      'waiting room appears in list',
    )
  await screenshot(player, 'room-list-preview.png')
  await command('Target.activateTarget', { targetId: player.targetId })
  await evaluate(
    player,
    `Array.from(document.querySelectorAll('.available-room-card')).find(card=>card.querySelector('code').textContent===${JSON.stringify(roomId)}).querySelector('button').click()`,
    true,
  )
  for (const peer of [owner, player])
    await waitFor(
      peer,
      'document.querySelectorAll(".players li:not(.empty-seat)").length === 2',
      'one-click list join',
    )
  await waitFor(
    observer,
    `!window.__gameLast['room:list'].some(room=>room.roomId===${JSON.stringify(roomId)})`,
    'full room removed from list',
  )
  await click(owner, '准备')
  await click(player, '准备')
  await command('Target.activateTarget', { targetId: owner.targetId })
  await click(owner, '开始游戏')
  for (const peer of [owner, player])
    await waitFor(
      peer,
      'document.querySelector(".phase-badge")?.textContent.includes("游戏进行中")',
      'cooperative battle starts',
    )
  const firstMatch = await evaluate(owner, 'window.__gameLast["game:start"].matchId')
  await key(owner, 'Space', ' ', 32, 900)
  await waitFor(owner, 'window.__audioStarts.length > 1', 'network start and fire actually play')
  for (const peer of [owner, player])
    await waitFor(
      peer,
      'location.pathname.includes("/game/online/result/") && document.querySelector(".result-screen.defeat")',
      'shared result page navigation',
      90000,
    )
  const ownerEnd = await evaluate(owner, 'window.__gameLast["game:end"]')
  const playerEnd = await evaluate(player, 'window.__gameLast["game:end"]')
  assert.deepEqual(ownerEnd, playerEnd)
  assert.equal(await evaluate(owner, 'window.__gameSockets.length'), 1)
  assert.equal(await evaluate(owner, 'window.__gameSockets[0].readyState'), 1)
  await screenshot(owner, 'online-result-preview.png')
  await navigate(owner, '/game/online/result/' + firstMatch)
  await waitFor(
    owner,
    `document.querySelector('.result-screen.defeat') && Boolean(${buttonExpression('再来一局')}) && !(${buttonExpression('再来一局')}).disabled`,
    'online result refresh and room recovery',
  )
  assert.equal(await evaluate(owner, 'window.__audioStarts.length'), 0)
  await click(owner, '再来一局')
  for (const peer of [owner, player])
    await waitFor(
      peer,
      'location.pathname === "/game/online" && document.querySelector(".phase-badge")?.textContent.includes("等待")',
      'both return to waiting room after retry',
    )
  await click(owner, '准备')
  await click(player, '准备')
  await click(owner, '开始游戏')
  for (const peer of [owner, player])
    await waitFor(
      peer,
      'document.querySelector(".phase-badge")?.textContent.includes("游戏进行中")',
      'fresh second battle',
    )
  const secondMatch = await evaluate(owner, 'window.__gameLast["game:start"].matchId')
  assert.notEqual(firstMatch, secondMatch)
  assert.ok(
    await evaluate(
      owner,
      'window.__gameLast["game:start"].tanks.every(tank=>tank.hp===3 && tank.shieldUntilTick===60)',
    ),
  )
  await evaluate(
    player,
    'window.__gameSockets.filter(socket=>socket.readyState===1).forEach(socket=>socket.close())',
  )
  await waitFor(
    player,
    'window.__gameSockets.length>1 && window.__gameSockets.at(-1).readyState===1 && document.querySelector(".phase-badge")?.textContent.includes("游戏进行中")',
    'reconnection retains current battle',
    10000,
  )
  assert.equal(await evaluate(player, 'window.__gameLast["game:start"].matchId'), secondMatch)
  await click(player, '退出房间')
  await waitFor(
    player,
    `Boolean(${buttonExpression('创建房间')}) && !(${buttonExpression('创建房间')}).disabled`,
    'leaver returns to lobby',
  )
  await waitFor(
    owner,
    'location.pathname.includes("/game/online/result/") && document.querySelector(".result-screen.abandoned")',
    'teammate departure keeps result page',
  )
  await waitFor(
    observer,
    `window.__gameLast['room:list'].some(room=>room.roomId===${JSON.stringify(roomId)})`,
    'waiting seat returns to list',
  )
  await click(owner, '返回大厅')
  await waitFor(
    owner,
    `location.pathname==='/game/online' && Boolean(${buttonExpression('创建房间')}) && !(${buttonExpression('创建房间')}).disabled`,
    'result exits room before returning lobby',
  )
  await waitFor(
    observer,
    `!window.__gameLast['room:list'].some(room=>room.roomId===${JSON.stringify(roomId)})`,
    'empty room removed after result exit',
  )
  assert.equal(runtimeErrors.length, 0, runtimeErrors.join('; '))
  console.log(
    'Browser E2E passed: real single defeat route + refresh + retry; seven WAVs fetched/decoded; actual start/fire calls; mute and volume persistence; available room discovery/one-click join/full-room removal; two-player result route without disconnect; result refresh + rematch reset; reconnect; abandonment page and lobby exit cleanup. No runtime exceptions.',
  )
  console.log(
    'Screenshots: backend/target/single-result-preview.png; room-list-preview.png; online-result-preview.png',
  )
} finally {
  await command('Browser.close').catch(() => {})
  ws.close()
}
