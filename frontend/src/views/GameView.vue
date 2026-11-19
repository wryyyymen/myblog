<script setup lang="ts">
import { Client } from '@stomp/stompjs'
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import GameBattleCanvas from '@/components/GameBattleCanvas.vue'
import type { BattleHudState, BattleHudPlayer } from '@/game/battleHud'
import { useAuthStore } from '@/stores/auth'
import { loadGameResult, saveGameResult, type GameResult } from '@/game/results'
import { unlockGameAudio, observeOnlineGameAudio, stopGameAudio } from '@/game/audio/gameAudio'
import {
  isObject,
  readGameEnd,
  readGameInit,
  readGameState,
  readRoom,
  readRoomLeft,
  readAvailableRooms,
  type AvailableRoom,
  type Direction,
  type GameInitialization,
  type GameSnapshot,
  type RoomSnapshot,
} from '@/game/network/protocol'

type ConnectionStatus = 'idle' | 'connecting' | 'reconnecting' | 'connected' | 'closing' | 'closed'
type ActionKind = 'create' | 'join' | 'ready' | 'start' | 'leave' | 'sync' | 'rematch'
interface PendingAction {
  kind: ActionKind
  roomId: string | null
  version: number
  sentAt: number
}

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const isResultRoute = computed(() => route.name === 'game-online-result')
const settlement = shallowRef<GameResult | null>(null)
const displayedSettlement = computed(() =>
  settlement.value?.matchId === route.params.matchId ? settlement.value : null,
)
const availableRooms = shallowRef<AvailableRoom[]>([])
const roomsLoading = ref(false)
const roomsError = ref('')
let lastRoomListRequest = 0
let exitDestination: 'lobby' | 'menu' | null = null
const connection = ref<ConnectionStatus>('idle')
const room = shallowRef<RoomSnapshot | null>(null)
const initialization = shallowRef<GameInitialization | null>(null)
const gameState = shallowRef<GameSnapshot | null>(null)
const resourcesReady = ref(false)
const roomCode = ref(typeof route.query.roomId === 'string' ? route.query.roomId.slice(0, 36) : '')
const pending = ref<PendingAction | null>(null)
const errorMessage = ref('')
const notice = ref('创建房间，邀请另一位玩家加入。')
const receivedAt = ref(0)
const clock = ref(Date.now())
const recoveringRoom = ref(false)
const reconnectAttempts = ref(0)
const copyMessage = ref('')
const lastResultSummary = ref('')
const endpoint = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/tankgame`

let client: Client | null = null
let disposed = false
let inputTimer: ReturnType<typeof setInterval> | undefined
let reconnectTimer: ReturnType<typeof setTimeout> | undefined
let manualDisconnect = false
let recoveryStartedAt = 0
let desiredDirection: Direction | null = null
let desiredFire = false
let sequence = 0
const leftVersions = new Map<string, number>()
const retiredMatches = new Set<string>()
let initRoomVersion = 0

const connected = computed(
  () => connection.value === 'connected' && auth.user !== null && !auth.loggingOut,
)
const canConnect = computed(
  () =>
    auth.user !== null &&
    !auth.loggingOut &&
    (connection.value === 'idle' || connection.value === 'closed'),
)
const canChooseRoom = computed(
  () => connected.value && !recoveringRoom.value && room.value === null && pending.value === null,
)
const canChangeRoom = computed(
  () => connected.value && !recoveringRoom.value && room.value !== null && pending.value === null,
)
const isReady = computed(
  () => auth.user !== null && room.value?.readyUserIds.includes(auth.user.id) === true,
)
const isOwner = computed(() => auth.user !== null && room.value?.ownerUserId === auth.user.id)
const allReady = computed(
  () =>
    room.value?.memberUserIds.length === 2 &&
    room.value.readyUserIds.length === 2 &&
    room.value.connectedUserIds.length === 2,
)
const disconnectedMembers = computed(
  () =>
    room.value?.memberUserIds.filter((userId) => !room.value?.connectedUserIds.includes(userId)) ??
    [],
)
const canStart = computed(
  () => canChangeRoom.value && isOwner.value && allReady.value && room.value?.status === 'WAITING',
)
const canRematch = computed(
  () =>
    canChangeRoom.value &&
    isOwner.value &&
    room.value?.status === 'FINISHED' &&
    room.value.memberUserIds.length === 2,
)
const playing = computed(
  () =>
    connected.value &&
    resourcesReady.value &&
    initialization.value !== null &&
    gameState.value?.matchId === initialization.value.matchId &&
    gameState.value.status === 'PLAYING' &&
    room.value?.status === 'PLAYING' &&
    gameState.value.tanks.some((tank) => tank.userId === auth.user?.id && tank.hp > 0) &&
    room.value?.roomId === initialization.value.roomId,
)
const connectionText = computed(
  () =>
    ({
      idle: '未连接',
      connecting: '连接中',
      reconnecting: `正在重连（${reconnectAttempts.value}/5）`,
      connected: '已连接',
      closing: '断开中',
      closed: '已断开',
    })[connection.value],
)
const phaseText = computed(() => {
  if (room.value?.status === 'FINISHED' || gameState.value?.status === 'FINISHED')
    return '本局已结束'
  if (playing.value) return '游戏进行中'
  if (room.value?.status === 'LOADING') return '双方加载中'
  if (
    gameState.value?.status === 'PLAYING' &&
    gameState.value.tanks.some((tank) => tank.userId === auth.user?.id && tank.hp === 0)
  )
    return '坦克已被击毁，正在观战队友'
  if (room.value?.status === 'PLAYING') return '正在恢复战场'
  return '等待准备'
})
const finished = computed(() => gameState.value?.status === 'FINISHED')
const resultNotice = computed(() => {
  if (!connected.value) return connectionText.value + '，连接恢复后可继续操作。'
  if (pending.value) return pendingText.value
  if (room.value?.status === 'FINISHED' && !isOwner.value)
    return '等待房主再来一局，也可以返回大厅。'
  if (room.value?.status === 'WAITING') return '本局合作已结束，可以返回大厅重新组队。'
  return ''
})
const resultTitle = computed(() => {
  const state = gameState.value
  if (!state || state.status !== 'FINISHED') return ''
  if (state.mode === 'COOP') {
    if (state.teamResult === 'VICTORY') return '合作胜利！'
    if (state.teamResult === 'ABANDONED') return '合作已中止'
    return '防守失败'
  }
  if (state.winnerUserId === null) return '本局平局'
  return state.winnerUserId === auth.user?.id ? '你赢了！' : '本局失败'
})
const resultDescription = computed(() => {
  const reason = gameState.value?.endReason
  if (gameState.value?.mode === 'COOP') {
    if (reason === 'ENEMIES_CLEARED') return '你们共同消灭了全部 8 辆敌军，成功守住基地。'
    if (reason === 'BASE_DESTROYED') return '基地被敌军击毁。再来一局，一起守住防线。'
    if (reason === 'TEAM_DESTROYED') return '两名玩家的坦克均被击毁。'
    if (reason === 'TIME_LIMIT') return '时间已到，未能消灭全部敌军。'
    if (reason === 'TEAMMATE_LEFT') return '队友退出了房间，本局合作中止。'
    if (reason === 'TEAMMATE_DISCONNECTED') return '队友未在 15 秒内恢复连接，本局合作中止。'
    return '本局合作已结束。'
  }
  if (reason === 'TIME_LIMIT') return '对局时间已到，双方握手言和。'
  if (reason === 'DRAW') return '双方同时被击毁。'
  if (reason === 'PLAYER_LEFT') return '有玩家主动退出，本局结束。'
  if (reason === 'PLAYER_DISCONNECTED') return '玩家未在 15 秒内恢复连接，本局按断线结算。'
  if (reason === 'ABANDONED') return '双方均已断线，本局中止。'
  return gameState.value?.winnerUserId === auth.user?.id
    ? '你击毁了对方的坦克。'
    : '你的坦克已被击毁。'
})
const remainingTime = computed(() => {
  const state = gameState.value
  const init = initialization.value
  if (!state || !init) return ''
  const seconds = Math.max(
    0,
    Math.ceil((init.rules.matchDurationMillis - state.tick * init.rules.tickMillis) / 1000),
  )
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
})
const stateDelayed = computed(
  () => playing.value && receivedAt.value > 0 && clock.value - receivedAt.value > 1500,
)
const battleHud = computed<BattleHudState | null>(() => {
  const state = gameState.value
  if (!state || !auth.user) return null
  const players: BattleHudPlayer[] = []
  const ownTank = state.tanks.find((tank) => tank.userId === auth.user?.id)
  if (ownTank)
    players.push({
      role: 'self',
      hp: ownTank.hp,
      maxHp: initialization.value?.rules.initialHp ?? 3,
      protected:
        state.status === 'PLAYING' && ownTank.hp > 0 && state.tick < ownTank.shieldUntilTick,
    })
  for (const tank of state.tanks) {
    if (tank.userId === auth.user.id) continue
    players.push({
      role: state.mode === 'COOP' ? 'teammate' : 'opponent',
      hp: tank.hp,
      maxHp: initialization.value?.rules.initialHp ?? 3,
      protected: state.status === 'PLAYING' && tank.hp > 0 && state.tick < tank.shieldUntilTick,
    })
  }
  return {
    mode: state.mode === 'COOP' ? '双人合作 · 基地防线' : '双人对战',
    players,
    // 击毁数来自服务端权威快照，显示合作团队总分，不伪造个人击杀归属。
    score: state.mode === 'COOP' ? state.enemyDefeated * 100 : null,
    scoreLabel: '团队得分',
    defeated: state.enemyDefeated,
    enemyTotal: state.enemyTotal,
    activeEnemies: state.enemies.filter((enemy) => enemy.hp > 0).length,
    baseAlive: state.base?.alive ?? null,
    clock: finished.value ? '本局结束' : `剩余 ${remainingTime.value}`,
  }
})
const pendingText = computed(() => {
  if (pending.value === null) return ''
  const texts: Record<ActionKind, string> = {
    create: '正在创建或恢复房间……',
    join: '正在加入房间……',
    ready: '正在更新准备状态……',
    start: '正在开始游戏……',
    leave: '正在退出房间……',
    sync: '正在恢复战场……',
    rematch: '正在准备下一局……',
  }
  return texts[pending.value.kind]
})

function rememberLeft(roomId: string, version: number) {
  const previous = leftVersions.get(roomId)
  if (previous !== undefined && previous >= version) return
  leftVersions.delete(roomId)
  leftVersions.set(roomId, version)
  if (leftVersions.size > 50) {
    const oldest = leftVersions.keys().next().value
    if (oldest !== undefined) leftVersions.delete(oldest)
  }
}

function retireMatch(matchId: string | null | undefined) {
  if (!matchId) return
  retiredMatches.add(matchId)
  if (retiredMatches.size > 50) {
    const oldest = retiredMatches.values().next().value
    if (oldest !== undefined) retiredMatches.delete(oldest)
  }
}

function resultStorageKey(): string {
  return `tank:online:last-result:${auth.user?.id ?? 'anonymous'}`
}

function restoreSettlement() {
  if (!auth.user || !isResultRoute.value) return
  const saved = loadGameResult(resultStorageKey())
  if (saved?.matchId === route.params.matchId) settlement.value = saved
}

function presentSettlement(snapshot: GameSnapshot) {
  if (!auth.user || snapshot.status !== 'FINISHED') return
  const ownTank = snapshot.tanks.find((tank) => tank.userId === auth.user?.id)
  let outcome: GameResult['outcome']
  if (snapshot.mode === 'COOP') outcome = snapshot.teamResult ?? 'ABANDONED'
  else if (snapshot.winnerUserId === null) outcome = 'DRAW'
  else outcome = snapshot.winnerUserId === auth.user.id ? 'VICTORY' : 'DEFEAT'
  const result: GameResult = {
    mode: snapshot.mode === 'COOP' ? 'coop' : 'pvp',
    matchId: snapshot.matchId,
    outcome,
    reason: resultDescription.value,
    score: snapshot.mode === 'COOP' ? snapshot.enemyDefeated * 100 : null,
    defeated: snapshot.enemyDefeated,
    enemyTotal: snapshot.enemyTotal,
    elapsedSeconds: Math.floor(
      (snapshot.tick * (initialization.value?.rules.tickMillis ?? 50)) / 1000,
    ),
    remainingHp: ownTank?.hp ?? 0,
    maxHp: initialization.value?.rules.initialHp ?? 3,
    baseAlive: snapshot.base?.alive ?? null,
  }
  settlement.value = result
  saveGameResult(resultStorageKey(), result)
  if (pending.value?.kind === 'leave' || exitDestination !== null) return
  if (isResultRoute.value && route.params.matchId === snapshot.matchId) return
  void router.replace({ name: 'game-online-result', params: { matchId: snapshot.matchId } })
}

function returnFromSettlement(destination: 'lobby' | 'menu') {
  if (pending.value !== null) return
  if (room.value !== null) {
    if (!canChangeRoom.value) return
    exitDestination = destination
    leaveRoom()
  } else void router.replace({ name: destination === 'menu' ? 'game' : 'game-online' })
}

function requestRoomList(force = false) {
  if (!canChooseRoom.value || isResultRoute.value || (roomsLoading.value && !force)) return
  const now = Date.now()
  if (!force && now - lastRoomListRequest < 3000) return
  roomsLoading.value = true
  roomsError.value = ''
  lastRoomListRequest = now
  if (!publish('/app/room/list', {})) roomsLoading.value = false
}

function joinListedRoom(item: AvailableRoom) {
  if (!canChooseRoom.value) return
  roomCode.value = item.roomId
  joinRoom()
}

function publish(destination: string, payload: object): boolean {
  const activeClient = client
  if (!connected.value || !activeClient?.connected) {
    errorMessage.value = '请先连接游戏服务'
    return false
  }
  try {
    activeClient.publish({
      destination,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    })
    return true
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '消息发送失败，请重新连接'
    return false
  }
}

/** 输入只提交方向、开火状态和序号；位置、伤害与胜负以服务端为准。 */
function sendMovement(direction: Direction | null) {
  const current = initialization.value
  const state = gameState.value
  if (
    !connected.value ||
    !resourcesReady.value ||
    !current ||
    !state ||
    state.status !== 'PLAYING' ||
    room.value?.status !== 'PLAYING' ||
    !state.tanks.some((tank) => tank.userId === auth.user?.id && tank.hp > 0) ||
    current.matchId !== state.matchId
  )
    return
  ++sequence
  publish('/app/player/input', {
    roomId: current.roomId,
    matchId: current.matchId,
    seq: sequence,
    direction,
    fireHeld: desiredFire,
  })
}

function changeDirection(direction: Direction | null) {
  if (direction !== null) void unlockGameAudio()
  const nextDirection = playing.value && !document.hidden ? direction : null
  if (desiredDirection === nextDirection) return
  desiredDirection = nextDirection
  sendMovement(desiredDirection)
}

function stopMovement() {
  stopGameAudio()
  desiredDirection = null
  desiredFire = false
  sendMovement(null)
}

function changeFire(held: boolean) {
  if (held) void unlockGameAudio()
  const nextFire = playing.value && !document.hidden && held
  if (desiredFire === nextFire) return
  desiredFire = nextFire
  sendMovement(desiredDirection)
}

function clearGame(retire = true) {
  // 收到退房或结算通知时，本局已失效；这里只清输入，不再向旧对局发送停止指令。
  desiredDirection = null
  desiredFire = false
  if (retire) retireMatch(initialization.value?.matchId)
  initialization.value = null
  gameState.value = null
  resourcesReady.value = false
  sequence = 0
  receivedAt.value = 0
  initRoomVersion = 0
}

function clearConnectionState() {
  clearGame(false)
  room.value = null
  pending.value = null
  leftVersions.clear()
  retiredMatches.clear()
  recoveringRoom.value = false
  copyMessage.value = ''
  availableRooms.value = []
  roomsLoading.value = false
  roomsError.value = ''
  lastRoomListRequest = 0
  exitDestination = null
}

async function copyRoom(invitation = false) {
  const currentRoom = room.value
  if (!currentRoom) return
  const link = new URL(location.href)
  link.search = ''
  link.searchParams.set('roomId', currentRoom.roomId)
  try {
    await navigator.clipboard.writeText(invitation ? link.toString() : currentRoom.roomId)
    copyMessage.value = invitation ? '邀请链接已复制，发给另一位玩家即可。' : '房间号已复制。'
  } catch {
    copyMessage.value = '浏览器未允许复制，请选择上方房间号手动复制。'
  }
}

function beginAction(
  kind: ActionKind,
  destination: string,
  payload: object,
  roomId: string | null,
) {
  if (pending.value !== null) return
  errorMessage.value = ''
  pending.value = { kind, roomId, version: room.value?.version ?? 0, sentAt: Date.now() }
  if (kind === 'create' || kind === 'join' || kind === 'start' || kind === 'rematch')
    lastResultSummary.value = ''
  if (!publish(destination, payload)) pending.value = null
}

function createRoom() {
  void unlockGameAudio()
  if (canChooseRoom.value) beginAction('create', '/app/room/create-coop', {}, null)
}

function joinRoom() {
  void unlockGameAudio()
  if (!canChooseRoom.value) return
  const roomId = roomCode.value.trim()
  if (roomId.length === 0 || roomId.length > 36) {
    errorMessage.value = '请填写正确的房间号，最多 36 个字符'
    return
  }
  beginAction('join', '/app/room/join', { roomId }, roomId)
}

function toggleReady() {
  void unlockGameAudio()
  if (!canChangeRoom.value || !room.value || room.value.status !== 'WAITING') return
  beginAction(
    'ready',
    '/app/player/ready',
    { roomId: room.value.roomId, ready: !isReady.value },
    room.value.roomId,
  )
}

function startGame() {
  void unlockGameAudio()
  if (!canStart.value || !room.value) return
  beginAction('start', '/app/room/start', { roomId: room.value.roomId }, room.value.roomId)
}

function rematch() {
  if (!canRematch.value || !room.value) return
  beginAction('rematch', '/app/room/rematch', { roomId: room.value.roomId }, room.value.roomId)
}

function leaveRoom() {
  if (!canChangeRoom.value || !room.value) return
  stopMovement()
  beginAction('leave', '/app/room/leave', { roomId: room.value.roomId }, room.value.roomId)
}

function syncGame() {
  if (!canChangeRoom.value || !room.value || room.value.status === 'WAITING') return
  beginAction('sync', '/app/game/sync', { roomId: room.value.roomId }, room.value.roomId)
}

function acknowledgeRoom(type: string, snapshot: RoomSnapshot) {
  const action = pending.value
  if (action === null) return
  if (type === 'room:created' && action.kind === 'create') {
    pending.value = null
    return
  }
  if (action.roomId !== snapshot.roomId || snapshot.version < action.version) return
  // 恢复时对局可能刚好被取消；等待房间没有 game:init，不继续锁住按钮等地图。
  if (action.kind === 'sync' && snapshot.status === 'WAITING') {
    pending.value = null
    return
  }
  if (
    (type === 'room:joined' && action.kind === 'join') ||
    (type === 'room:ready' && action.kind === 'ready') ||
    (type === 'room:started' && action.kind === 'start') ||
    (type === 'room:rematched' && action.kind === 'rematch')
  )
    pending.value = null
}

function applyRoom(type: string, payload: unknown) {
  const snapshot = readRoom(payload)
  if (!snapshot || !auth.user || !snapshot.memberUserIds.includes(auth.user.id)) {
    errorMessage.value = '收到的房间信息不完整，请检查后端是否已更新并重新连接'
    pending.value = null
    return
  }
  acknowledgeRoom(type, snapshot)
  const leftVersion = leftVersions.get(snapshot.roomId)
  if (leftVersion !== undefined && snapshot.version <= leftVersion) return
  const previous = room.value
  if (previous?.roomId === snapshot.roomId && snapshot.version < previous.version) return
  const currentInit = initialization.value
  if (currentInit?.roomId === snapshot.roomId && snapshot.matchId !== currentInit.matchId) {
    if (snapshot.status === 'WAITING' && snapshot.version <= initRoomVersion) return
    clearGame()
  }
  if (previous?.matchId && previous.matchId !== snapshot.matchId) retireMatch(previous.matchId)
  if (previous && previous.roomId !== snapshot.roomId) clearGame()
  room.value = snapshot
  const returnedForRematch =
    snapshot.status === 'WAITING' &&
    previous?.status === 'FINISHED' &&
    snapshot.memberUserIds.length === 2
  if (
    isResultRoute.value &&
    (returnedForRematch || (snapshot.matchId !== null && snapshot.matchId !== route.params.matchId))
  ) {
    void router.replace({ name: 'game-online' })
  }
  if (snapshot.status === 'WAITING') {
    if (previous?.status === 'LOADING') notice.value = '开局已取消，请双方重新准备。'
    else if (previous?.status === 'PLAYING' || previous?.status === 'FINISHED')
      notice.value = lastResultSummary.value || '对局已结束，可以等待玩家加入后重新准备。'
    else if (!notice.value.includes('退出')) notice.value = '两名玩家准备后，由房主开始游戏。'
  }
  // 刷新后取回已开始的房间时，主动请求本局地图与快照。
  if (
    (type === 'room:created' || type === 'room:current') &&
    snapshot.status !== 'WAITING' &&
    initialization.value?.matchId !== snapshot.matchId
  )
    syncGame()
}

function applyGameState(snapshot: GameSnapshot) {
  const currentRoom = room.value
  if (
    !currentRoom ||
    snapshot.roomId !== currentRoom.roomId ||
    retiredMatches.has(snapshot.matchId)
  )
    return
  const expectedMatchId = initialization.value?.matchId ?? currentRoom.matchId
  if (expectedMatchId !== snapshot.matchId) return
  const previous = gameState.value
  if (currentRoom.status === 'FINISHED' && snapshot.status !== 'FINISHED') return
  if (previous?.matchId === snapshot.matchId) {
    if (snapshot.tick < previous.tick) return
    if (previous.status === 'PLAYING' && snapshot.status === 'LOADING') return
    if (previous.status === 'FINISHED' && snapshot.status !== 'FINISHED') return
  }
  if (!auth.user || !snapshot.tanks.some((tank) => tank.userId === auth.user?.id)) return
  const map = initialization.value?.map
  if (map) {
    const width = (map.seed[0]?.length ?? 0) * map.tileSize
    const height = map.seed.length * map.tileSize
    for (const tank of snapshot.tanks) {
      if (tank.x + tank.size > width + 0.001 || tank.y + tank.size > height + 0.001) return
    }
  }
  for (const tank of snapshot.tanks) {
    if (tank.userId === auth.user.id) sequence = Math.max(sequence, tank.lastInputSeq)
  }
  gameState.value = snapshot
  observeOnlineGameAudio(snapshot, auth.user.id)
  receivedAt.value = Date.now()
  if (snapshot.status === 'PLAYING') notice.value = '点击战场，WASD / 方向键移动，按住空格射击。'
  else if (snapshot.status === 'FINISHED') {
    desiredDirection = null
    desiredFire = false
    notice.value = isOwner.value ? '本局结束，可以选择再来一局。' : '本局结束，等待房主重新开局。'
    presentSettlement(snapshot)
  }
}

function applyInitialization(payload: unknown) {
  const next = readGameInit(payload)
  const currentRoom = room.value
  if (!next) {
    errorMessage.value = '战场初始化信息不完整，请检查后端版本或重新同步战场'
    if (pending.value?.kind === 'sync') pending.value = null
    return
  }
  if (!currentRoom || next.roomId !== currentRoom.roomId || retiredMatches.has(next.matchId)) return
  if (currentRoom.status === 'FINISHED' && next.state.status !== 'FINISHED') return
  // 同一局的地图不会改变。game:start 可能先到，不能仅因房间已进入 PLAYING 拒绝地图。
  if (next.roomVersion < currentRoom.version && currentRoom.matchId !== next.matchId) return
  if (currentRoom.matchId !== null && currentRoom.matchId !== next.matchId) return
  if (pending.value?.kind === 'sync' && pending.value.roomId === next.roomId) pending.value = null
  // 同局重复初始化只重发加载确认，避免持续推送反复加载素材。
  if (initialization.value?.matchId === next.matchId) {
    applyGameState(next.state)
    if (resourcesReady.value) resourceLoaded(next.roomId, next.matchId)
    else initialization.value = next
    return
  }
  const bufferedState = gameState.value?.matchId === next.matchId ? gameState.value : null
  clearGame(false)
  initRoomVersion = next.roomVersion
  initialization.value = next
  applyGameState(next.state)
  if (bufferedState !== null) applyGameState(bufferedState)
  console.log('[GameView.applyInitialization] 收到地图，matchId =', next.matchId)
}

function resourceLoaded(roomId: string, matchId: string) {
  if (initialization.value?.roomId !== roomId || initialization.value.matchId !== matchId) return
  resourcesReady.value = true
  if (gameState.value?.status !== 'FINISHED') publish('/app/game/loaded', { roomId, matchId })
}

function handleServerMessage(body: string) {
  let response: unknown
  try {
    response = JSON.parse(body)
  } catch {
    return
  }
  if (!isObject(response) || typeof response.type !== 'string') return
  const type = response.type
  if (type === 'room:list') {
    const list = readAvailableRooms(response.payload)
    roomsLoading.value = false
    if (list === null) roomsError.value = '房间列表格式不正确，请更新后端后重新连接。'
    else {
      availableRooms.value = list
      roomsError.value = ''
    }
    return
  }
  if (type === 'error') {
    if (roomsLoading.value && pending.value === null) {
      roomsLoading.value = false
      roomsError.value =
        typeof response.payload === 'string' ? response.payload : '房间列表加载失败'
      return
    }
    pending.value = null
    exitDestination = null
    recoveringRoom.value = false
    errorMessage.value =
      typeof response.payload === 'string' ? response.payload : '操作失败，请重试'
    requestRoomList(true)
    return
  }
  if (type === 'room:current') {
    recoveringRoom.value = false
    if (response.payload === null) {
      clearGame()
      room.value = null
      notice.value = '连接成功，可以创建房间，或输入朋友的房间号加入。'
      requestRoomList(true)
    } else applyRoom(type, response.payload)
    return
  }
  if (
    type === 'room:created' ||
    type === 'room:joined' ||
    type === 'room:ready' ||
    type === 'room:updated' ||
    type === 'room:started' ||
    type === 'room:rematched'
  ) {
    applyRoom(type, response.payload)
  } else if (type === 'room:left') {
    const left = readRoomLeft(response.payload)
    if (!left) return
    rememberLeft(left.roomId, left.version)
    if (room.value?.roomId === left.roomId && room.value.version <= left.version) {
      retireMatch(room.value.matchId)
      clearGame()
      room.value = null
      pending.value = null
      notice.value = '已退出房间，可以重新创建或加入。'
      const destination = exitDestination
      exitDestination = null
      if (destination !== null)
        void router.replace({ name: destination === 'menu' ? 'game' : 'game-online' })
      requestRoomList(true)
    } else if (
      pending.value?.kind === 'leave' &&
      pending.value.roomId === left.roomId &&
      left.version > pending.value.version
    )
      pending.value = null
  } else if (type === 'game:init') {
    applyInitialization(response.payload)
  } else if (type === 'game:start' || type === 'game:state') {
    const state = readGameState(response.payload)
    if (state) applyGameState(state)
  } else if (type === 'game:end') {
    const ending = readGameEnd(response.payload)
    if (!ending || ending.roomId !== room.value?.roomId) return
    const currentMatchId = initialization.value?.matchId ?? room.value.matchId
    if (currentMatchId !== ending.matchId) return
    applyGameState(ending.state)
    lastResultSummary.value = `${resultTitle.value} ${resultDescription.value}`
    desiredDirection = null
    desiredFire = false
    // 保留最终画面和结算；真正重开或退出时才淘汰旧 matchId。
    notice.value =
      ending.reason === 'PLAYER_LEFT' ||
      ending.reason === 'PLAYER_DISCONNECTED' ||
      ending.reason === 'ABANDONED'
        ? lastResultSummary.value
        : isOwner.value
          ? '本局结束，可以选择再来一局。'
          : '本局结束，等待房主重新开局。'
  }
}

function cancelReconnect() {
  if (reconnectTimer !== undefined) clearTimeout(reconnectTimer)
  reconnectTimer = undefined
}

function scheduleReconnect() {
  cancelReconnect()
  if (disposed || manualDisconnect || !auth.user || auth.loggingOut) return
  if (reconnectAttempts.value >= 5) {
    connection.value = 'closed'
    errorMessage.value = '暂时无法恢复连接，请检查网络和登录状态，再点击连接游戏。'
    return
  }
  reconnectAttempts.value++
  connection.value = 'reconnecting'
  notice.value = '连接中断，正在恢复原房间；断线席位最多保留 15 秒。'
  const delay = Math.min(4000, reconnectAttempts.value * 800)
  reconnectTimer = setTimeout(() => {
    reconnectTimer = undefined
    openConnection(true)
  }, delay)
}

function requestCurrentRoom() {
  recoveringRoom.value = true
  recoveryStartedAt = Date.now()
  if (!publish('/app/room/current', {})) recoveringRoom.value = false
}

function connect() {
  if (!canConnect.value || disposed) return
  manualDisconnect = false
  reconnectAttempts.value = 0
  cancelReconnect()
  openConnection(false)
}

function openConnection(retrying: boolean) {
  if (disposed || client !== null || !auth.user || auth.loggingOut || manualDisconnect) return
  const expectedUserId = auth.user.id
  clearConnectionState()
  errorMessage.value = ''
  connection.value = retrying ? 'reconnecting' : 'connecting'
  const nextClient = new Client({
    brokerURL: endpoint,
    reconnectDelay: 0,
    connectionTimeout: 5000,
    heartbeatIncoming: 5000,
    heartbeatOutgoing: 5000,
    onConnect: () => {
      if (
        disposed ||
        client !== nextClient ||
        auth.user?.id !== expectedUserId ||
        connection.value === 'closing'
      )
        return
      nextClient.subscribe('/user/queue/replies', (frame) => {
        if (disposed || client !== nextClient || !connected.value) return
        handleServerMessage(frame.body)
      })
      connection.value = 'connected'
      reconnectAttempts.value = 0
      notice.value = '连接成功，正在检查原房间……'
      // 只查询，不自动创建；刷新或短暂断线后可以找回原房间和本局状态。
      requestCurrentRoom()
      console.log('[GameView.connect] 已连接并订阅游戏回复')
    },
    onStompError: () => {
      if (client !== nextClient) return
      errorMessage.value = '游戏连接被拒绝，请检查登录状态后重新连接'
      void disconnect()
    },
    onWebSocketError: () => {
      if (client === nextClient) errorMessage.value = '连接失败，请检查登录状态和后端服务'
    },
    onWebSocketClose: () => {
      if (client !== nextClient) return
      client = null
      connection.value = 'closed'
      clearConnectionState()
      void nextClient.deactivate().catch(() => {})
      scheduleReconnect()
    },
  })
  client = nextClient
  try {
    nextClient.activate()
  } catch (error) {
    client = null
    connection.value = 'closed'
    errorMessage.value = error instanceof Error ? error.message : '连接启动失败'
    scheduleReconnect()
  }
}

async function disconnect() {
  manualDisconnect = true
  cancelReconnect()
  reconnectAttempts.value = 0
  stopMovement()
  const previousClient = client
  client = null
  connection.value = 'closing'
  clearConnectionState()
  try {
    await previousClient?.deactivate()
  } finally {
    if (client === null) connection.value = 'closed'
  }
}

function visibilityChanged() {
  if (document.hidden) stopMovement()
}

function pageLeaving() {
  stopMovement()
  void disconnect()
}

watch(
  () => route.params.matchId,
  () => restoreSettlement(),
)
watch(
  () => auth.user?.id,
  async (userId, previousId) => {
    if (userId !== previousId) {
      settlement.value = null
      await disconnect()
      if (userId !== undefined && !disposed && !auth.loggingOut) connect()
      restoreSettlement()
    }
  },
)
watch(
  () => auth.loggingOut,
  (loggingOut) => {
    if (loggingOut) void disconnect()
  },
)
onMounted(() => {
  restoreSettlement()
  window.addEventListener('blur', stopMovement)
  document.addEventListener('visibilitychange', visibilityChanged)
  window.addEventListener('pagehide', pageLeaving)
  inputTimer = setInterval(() => {
    clock.value = Date.now()
    if (pending.value && clock.value - pending.value.sentAt > 12_000) {
      pending.value = null
      exitDestination = null
      errorMessage.value = '操作响应超时，请检查连接后重试。'
    }
    if (recoveringRoom.value && clock.value - recoveryStartedAt > 10_000) {
      recoveringRoom.value = false
      errorMessage.value = '房间恢复超时，请重新连接后再试。'
    }
    if (playing.value && !document.hidden) sendMovement(desiredDirection)
    if (roomsLoading.value && clock.value - lastRoomListRequest > 8000) {
      roomsLoading.value = false
      roomsError.value = '房间列表加载超时，请点击刷新重试。'
    }
    if (!document.hidden) requestRoomList()
  }, 100)
  if (auth.user && !auth.loggingOut) connect()
})
onUnmounted(() => {
  stopMovement()
  disposed = true
  if (inputTimer !== undefined) clearInterval(inputTimer)
  window.removeEventListener('blur', stopMovement)
  document.removeEventListener('visibilitychange', visibilityChanged)
  window.removeEventListener('pagehide', pageLeaving)
  void disconnect()
})
</script>

<template>
  <RouterView v-if="isResultRoute" v-slot="{ Component }">
    <component
      :is="Component"
      :result="displayedSettlement"
      :can-retry="canRematch"
      retry-label="再来一局"
      :busy="pending !== null || recoveringRoom"
      :notice="resultNotice"
      :error="errorMessage"
      @retry="rematch"
      @lobby="returnFromSettlement('lobby')"
      @menu="returnFromSettlement('menu')"
    />
  </RouterView>
  <main v-show="!isResultRoute" class="page-container game-page">
    <RouterLink class="text-link back-menu" to="/game">← 返回游戏菜单</RouterLink>
    <div class="game-heading">
      <div>
        <span class="eyebrow">双人联机</span>
        <h1>{{ room?.mode === 'PVP' ? '双人坦克对战' : '双人合作 · 保卫基地' }}</h1>
        <p class="muted">
          {{
            room?.mode === 'PVP'
              ? '和朋友切磋，双方准备后开始。'
              : '邀请朋友一起迎战 AI，消灭敌军，守住基地。'
          }}
        </p>
      </div>
      <span class="connection-badge" :class="{ online: connected }" role="status">{{
        connectionText
      }}</span>
    </div>
    <p v-if="!auth.user" class="notice">
      联机需要登录账号。<RouterLink
        class="text-link"
        :to="{ path: '/login', query: { redirect: route.fullPath } }"
        >登录后返回联机大厅</RouterLink
      >
    </p>
    <p v-if="errorMessage" class="notice notice-error" role="alert">{{ errorMessage }}</p>
    <p v-if="stateDelayed" class="notice" role="alert">
      战场更新暂时变慢，请检查网络；可以重新同步战场。
    </p>
    <section class="surface room-panel" aria-label="游戏房间">
      <div class="room-heading">
        <h2>游戏房间</h2>
        <div class="action-row">
          <button
            class="button button-primary"
            type="button"
            :disabled="!canConnect"
            @click="connect"
          >
            {{
              connection === 'connecting' || connection === 'reconnecting' ? '连接中……' : '连接游戏'
            }}
          </button>
          <button
            class="button"
            type="button"
            :disabled="
              connection !== 'connected' &&
              connection !== 'connecting' &&
              connection !== 'reconnecting'
            "
            @click="disconnect"
          >
            断开连接
          </button>
        </div>
      </div>
      <p class="field-caption">
        同一账号请只打开一个游戏页；两个玩家需使用不同账号和独立浏览器登录。
      </p>
      <template v-if="!room">
        <div class="lobby-controls">
          <button
            class="button button-primary"
            type="button"
            :disabled="!canChooseRoom"
            @click="createRoom"
          >
            创建房间
          </button>
          <form class="join-form" @submit.prevent="joinRoom">
            <label class="visually-hidden" for="online-room-id">房间号</label>
            <input
              id="online-room-id"
              v-model="roomCode"
              maxlength="36"
              placeholder="粘贴朋友的房间号"
              :disabled="!canChooseRoom"
            />
            <button class="button" type="submit" :disabled="!canChooseRoom">加入房间</button>
          </form>
        </div>
        <section v-if="auth.user" class="available-rooms" aria-labelledby="available-rooms-title">
          <div class="available-rooms-heading">
            <div>
              <h3 id="available-rooms-title">房间列表</h3>
              <p class="field-caption">等待中且未满员的房间，每 3 秒自动刷新。</p>
            </div>
            <button
              class="button button-small"
              type="button"
              :disabled="!canChooseRoom || roomsLoading"
              @click="requestRoomList(true)"
            >
              {{ roomsLoading ? '刷新中……' : '刷新列表' }}
            </button>
          </div>
          <p v-if="roomsError" class="notice notice-error" role="alert">{{ roomsError }}</p>
          <p v-if="!connected || recoveringRoom" class="room-list-empty">
            连接游戏后显示可加入的房间。
          </p>
          <p
            v-else-if="roomsLoading && availableRooms.length === 0"
            class="room-list-empty"
            role="status"
          >
            正在查找等待中的房间……
          </p>
          <p v-else-if="availableRooms.length === 0 && !roomsError" class="room-list-empty">
            暂无可加入的房间，可以创建房间邀请朋友。
          </p>
          <ul
            v-else-if="availableRooms.length > 0"
            class="available-room-cards"
            aria-label="可加入房间"
          >
            <li v-for="item in availableRooms" :key="item.roomId" class="available-room-card">
              <div>
                <strong>{{ item.mode === 'COOP' ? '双人合作 PVE' : '双人对战' }}</strong
                ><span class="available-room-meta"
                  >房主 {{ item.ownerUserId }} · {{ item.memberCount }}/{{ item.maxMembers }} 人 ·
                  等待中</span
                ><code>{{ item.roomId }}</code>
              </div>
              <button
                class="button button-primary"
                type="button"
                :disabled="!canChooseRoom"
                @click="joinListedRoom(item)"
              >
                加入
              </button>
            </li>
          </ul>
        </section>
      </template>
      <template v-else>
        <div class="room-meta">
          <div>
            <span class="meta-label">房间号</span><code>{{ room.roomId }}</code>
          </div>
          <span class="phase-badge">{{ phaseText }}</span>
        </div>
        <div class="invite-actions">
          <button class="button button-small" type="button" @click="copyRoom()">复制房间号</button>
          <button class="button button-small" type="button" @click="copyRoom(true)">
            复制邀请链接
          </button>
          <span v-if="copyMessage" class="field-caption" role="status">{{ copyMessage }}</span>
        </div>
        <p v-if="disconnectedMembers.length > 0" class="notice disconnect-notice" role="status">
          队友暂时断线，席位保留 15 秒，战斗仍在继续。恢复连接后继续；超时则中止本局合作。
        </p>
        <ul class="players" aria-label="房间成员">
          <li v-for="memberId in room.memberUserIds" :key="memberId">
            <span
              class="player-color"
              :class="{ self: memberId === auth.user?.id }"
              aria-hidden="true"
            ></span>
            <strong>{{
              memberId === auth.user?.id ? `${auth.user.username}（你）` : `队友 ${memberId}`
            }}</strong>
            <span v-if="memberId === room.ownerUserId" class="small-badge">房主</span>
            <span
              class="member-connection"
              :class="{ offline: !room.connectedUserIds.includes(memberId) }"
            >
              {{ room.connectedUserIds.includes(memberId) ? '在线' : '断线中' }}
            </span>
            <span v-if="gameState" class="ready-label">{{
              gameState.tanks.find((tank) => tank.userId === memberId)?.hp === 0
                ? '已阵亡'
                : '战斗中'
            }}</span>
            <span
              v-else
              class="ready-label"
              :class="{ ready: room.readyUserIds.includes(memberId) }"
              >{{ room.readyUserIds.includes(memberId) ? '已准备' : '未准备' }}</span
            >
          </li>
          <li v-if="room.memberUserIds.length < 2" class="empty-seat">
            等待另一位玩家加入（{{ room.memberUserIds.length }} / 2）
          </li>
        </ul>
        <div class="action-row">
          <button
            v-if="room.status === 'WAITING'"
            class="button"
            type="button"
            :disabled="!canChangeRoom"
            @click="toggleReady"
          >
            {{ isReady ? '取消准备' : '准备' }}
          </button>
          <button
            v-if="isOwner && room.status === 'WAITING'"
            class="button button-primary"
            type="button"
            :disabled="!canStart"
            @click="startGame"
          >
            开始游戏
          </button>
          <button
            v-if="room.status !== 'WAITING'"
            class="button"
            type="button"
            :disabled="!canChangeRoom"
            @click="syncGame"
          >
            重新同步战场
          </button>
          <button class="button" type="button" :disabled="!canChangeRoom" @click="leaveRoom">
            退出房间
          </button>
        </div>
      </template>
      <p class="room-notice" role="status">
        {{ recoveringRoom ? '正在恢复原房间……' : pendingText || notice }}
      </p>
      <p class="field-caption">
        断线后会自动尝试重连，席位最多保留 15 秒。要立即释放席位，请点击“退出房间”。
      </p>
    </section>
    <section v-if="room && auth.user" class="arena-panel" aria-label="联机坦克战场">
      <GameBattleCanvas
        v-if="!isResultRoute"
        :hud="battleHud"
        :initialization="initialization"
        :state="gameState"
        :playing="playing"
        :current-user-id="auth.user.id"
        @loaded="resourceLoaded"
        @direction="changeDirection"
        @fire="changeFire"
        @load-error="errorMessage = $event"
      />
    </section>
    <section v-else class="surface how-to">
      <h2>如何开始</h2>
      <ol>
        <li>两人分别登录，页面自动连接。一人创建房间，分享房间号或邀请链接。</li>
        <li>双方准备后，由房主点击开始游戏。</li>
        <li>点击战场，用 WASD 或方向键移动，按住空格射击。松键或切换窗口后停止。</li>
        <li>每人 3 血，队友互不伤害。共同消灭 8 辆敌军获胜；基地被毁或全员阵亡则失败。</li>
        <li>一人阵亡后可以观战队友。结束后由房主选择再来一局。</li>
      </ol>
    </section>
  </main>
</template>

<style scoped>
.game-page {
  max-width: 1120px;
}
.available-rooms {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--color-border);
}
.available-rooms-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.available-rooms-heading h3 {
  margin: 0 0 6px;
  font-size: 17px;
}
.room-list-empty {
  padding: 24px 0 8px;
  color: var(--color-muted);
  font-size: 13px;
}
.available-room-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  list-style: none;
  padding: 0;
  margin: 16px 0 0;
}
.available-room-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-width: 0;
  padding: 16px;
  background: var(--color-background-soft);
  border-radius: 10px;
}
.available-room-card > div {
  min-width: 0;
}
.available-room-card strong {
  display: block;
  font-size: 14px;
}
.available-room-meta {
  display: block;
  margin: 7px 0;
  color: var(--color-muted);
  font-size: 12px;
}
.available-room-card code {
  display: block;
  overflow-wrap: anywhere;
  color: var(--color-muted);
  font-size: 11px;
}
.available-room-card button {
  flex-shrink: 0;
}
@media (max-width: 720px) {
  .available-room-cards {
    grid-template-columns: 1fr;
  }
}
.back-menu {
  display: inline-block;
  margin-bottom: 18px;
  font-size: 13px;
}
.invite-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 12px 0 18px;
}
.member-connection {
  color: var(--color-brand);
  font-size: 11px;
  white-space: nowrap;
}
.member-connection.offline {
  color: var(--color-danger);
}
.disconnect-notice {
  margin-bottom: 16px;
  font-size: 12px;
}
.arena-panel {
  margin-top: 20px;
  padding: 0;
}
.battle-result {
  max-width: 656px;
  margin-inline: auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  border: 1px solid #e0c6b3;
  background: #fff5ed;
  border-radius: 12px;
  padding: 18px;
  margin-bottom: 18px;
}
.battle-result.victory {
  border-color: #bad7c1;
  background: #eff8f0;
}
.battle-result.draw {
  border-color: var(--color-border);
  background: var(--color-background-soft);
}
.battle-result h3 {
  font-size: 24px;
  margin: 0 0 6px;
}
.battle-result p {
  margin: 0;
  font-size: 13px;
}
.game-heading,
.room-heading,
.room-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.game-heading {
  margin-bottom: 24px;
}
.game-heading h1 {
  margin: 6px 0 10px;
  font-size: clamp(27px, 4vw, 36px);
}
.game-heading p {
  font-size: 14px;
}
.connection-badge,
.phase-badge {
  flex-shrink: 0;
  padding: 7px 12px;
  border-radius: 20px;
  background: var(--color-background-soft);
  color: var(--color-muted);
  font-size: 13px;
}
.connection-badge.online {
  color: var(--color-brand);
  background: var(--color-brand-soft);
}
.room-panel,
.how-to {
  padding: 24px;
  margin-top: 20px;
}
.room-heading {
  margin-bottom: 16px;
  flex-wrap: wrap;
}
h2 {
  margin: 0;
  font-size: 19px;
}
.action-row,
.lobby-controls,
.join-form {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.lobby-controls,
.room-meta {
  margin: 22px 0;
}
.join-form {
  flex: 1;
}
.join-form input {
  width: min(100%, 360px);
  min-width: 200px;
  flex: 1;
}
.room-meta code {
  display: block;
  margin-top: 5px;
  overflow-wrap: anywhere;
  font-size: 13px;
}
.meta-label,
.map-name {
  color: var(--color-muted);
  font-size: 13px;
}
.players {
  list-style: none;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 18px 0;
}
.players li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 9px;
  padding: 15px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  font-size: 13px;
}
.player-color {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  background: #d49a2f;
}
.player-color.self {
  background: #258851;
}
.small-badge {
  padding: 2px 7px;
  border-radius: 4px;
  background: var(--color-background-soft);
  color: var(--color-muted);
  font-size: 11px;
}
.ready-label {
  margin-left: auto;
  color: var(--color-muted);
}
.ready-label.ready {
  color: var(--color-brand);
  font-weight: 600;
}
.players .empty-seat {
  color: var(--color-muted);
  border-style: dashed;
}
.room-notice {
  margin: 18px 0 8px;
  color: var(--color-brand);
  font-size: 13px;
}
.field-caption {
  display: block;
  font-size: 12px;
  line-height: 1.7;
  color: var(--color-muted);
}
.how-to ol {
  margin: 16px 0 0;
  padding-left: 22px;
  font-size: 14px;
  line-height: 2;
  color: var(--color-muted);
}
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
@media (max-width: 640px) {
  .players {
    grid-template-columns: 1fr;
  }
  .game-heading,
  .room-meta {
    align-items: flex-start;
  }
  .room-meta {
    flex-direction: column;
  }
  .room-panel,
  .how-to {
    padding: 18px;
  }
  .action-row {
    gap: 8px;
  }
}
</style>
