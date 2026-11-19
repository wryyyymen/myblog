<script setup lang="ts">
import { Client } from '@stomp/stompjs'
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 页面展示的连接状态：
 * idle：尚未连接
 * connecting：正在连接
 * open：STOMP 已连接，可以发送消息
 * closing：正在断开
 * closed：连接已经关闭
 */
type ConnectionState = 'idle' | 'connecting' | 'open' | 'closing' | 'closed'

// 日志类别，用于显示不同标签和颜色
type LogKind = 'system' | 'sent' | 'received' | 'error'

// 页面中一条日志的数据结构
interface LogEntry {
  id: number // 唯一编号，供 Vue 的 v-for :key 使用
  time: string // 日志产生的本地时间
  kind: LogKind // 日志类别
  content: string // 日志正文
}

// 与后端 RoomResponse 对应，只保存服务器返回的房间信息
interface RoomResponse {
  roomId: string
  ownerUserId: number
  memberUserIds: number[]
  readyUserIds: number[]
  version: number
}

// 等待哪一次房间操作的确认；房间更新推送本身不代表该请求已处理完成
type RoomActionKind = 'create' | 'join' | 'ready' | 'leave'

interface PendingRoomAction {
  kind: RoomActionKind
  roomId: string | null
  baseVersion: number
}

// 复用博客的登录状态
const auth = useAuthStore()

// HTTPS 页面使用 wss，HTTP 页面使用 ws
const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'

// 使用当前页面的域名和端口。
// 本地由 Vite 代理，生产环境由 Nginx 转发到后端。
const endpoint = `${protocol}//${location.host}/ws/tankgame`

// ref 创建响应式数据：值变化后，Vue 会更新页面
const state = ref<ConnectionState>('idle')
const message = ref('hello')
const logs = ref<LogEntry[]>([])

// 收到创建、加入回复或成员更新通知后赋值；断开或切换账号时清空页面显示
const currentRoom = ref<RoomResponse | null>(null)

const pendingRoomAction = ref<PendingRoomAction | null>(null)

// 记住本次连接中已退出房间的版本，避免迟到的旧快照把已退出房间重新显示出来
const leftRoomVersions = new Map<string, number>()
const maxRememberedLeftRooms = 50

// 输入框中的目标房间号
const joinRoomId = ref('')

// 对应模板中 ref="logPanel" 的日志容器
const logPanel = ref<HTMLElement | null>(null)

// 保存真实的 STOMP 客户端实例
let client: Client | null = null

// 每新增一条日志，编号加一
let nextLogId = 0

// 将内部状态转换成页面展示的中文
const statusText = computed(() => {
  const labels: Record<ConnectionState, string> = {
    idle: '未连接',
    connecting: '连接中',
    open: '已连接',
    closing: '断开中',
    closed: '已断开',
  }

  return labels[state.value]
})

// 已登录、未退出，且没有正在使用的连接时，允许点击“连接”
const canConnect = computed(
  () =>
    auth.user !== null && !auth.loggingOut && (state.value === 'idle' || state.value === 'closed'),
)

// 正在连接时允许取消；连接成功后允许断开
const canDisconnect = computed(() => state.value === 'connecting' || state.value === 'open')

// 测试页允许发送空文本，用来验证后端的 @NotBlank。
// 实际能否发送，还会在 sendMessage() 中检查 client.connected。
const canSend = computed(() => auth.user !== null && !auth.loggingOut && state.value === 'open')

const canChooseRoom = computed(
  () => canSend.value && currentRoom.value === null && pendingRoomAction.value === null,
)

const canChangeRoom = computed(
  () => canSend.value && currentRoom.value !== null && pendingRoomAction.value === null,
)

const isCurrentPlayerReady = computed(() => {
  const room = currentRoom.value
  const user = auth.user
  if (room === null || user === null) return false
  return room.readyUserIds.includes(user.id)
})

const areAllPlayersReady = computed(() => {
  const room = currentRoom.value
  if (room === null) return false
  return room.memberUserIds.length === 2 && room.readyUserIds.length === 2
})

const pendingActionText = computed(() => {
  const action = pendingRoomAction.value
  if (action === null) return ''

  const descriptions: Record<RoomActionKind, string> = {
    create: '正在创建或取回房间……',
    join: '正在加入房间……',
    ready: '正在更新准备状态……',
    leave: '正在退出房间……',
  }
  return descriptions[action.kind]
})

// 日志类别对应的中文标签
const logLabels: Record<LogKind, string> = {
  system: '系统',
  sent: '发送',
  received: '收到',
  error: '错误',
}

/**
 * 添加一条页面日志，并滚动到最新位置。
 *
 * @param kind 日志类别
 * @param content 日志正文
 */
function addLog(kind: LogKind, content: string) {
  logs.value.push({
    id: ++nextLogId,
    time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    kind,
    // 限制单条日志的展示长度
    content: content.slice(0, 10_000),
  })

  // 只保留最近 200 条，移除最早的记录
  if (logs.value.length > 200) {
    logs.value.splice(0, logs.value.length - 200)
  }

  // 等 Vue 把新增日志渲染到 DOM 后，再滚动到底部
  void nextTick(() => {
    if (logPanel.value) {
      logPanel.value.scrollTop = logPanel.value.scrollHeight
    }
  })
}

/** 清理当前连接的页面状态；后端席位只有收到退出指令才会移除。 */
function resetRoomState() {
  currentRoom.value = null
  pendingRoomAction.value = null
  leftRoomVersions.clear()
}

/**
 * 操作确认和房间推送可能交错到达。只用对应的确认解除按钮等待状态。
 * baseVersion 用来防止上一轮迟到的确认干扰重新加入后的操作。
 */
function acknowledgeRoomAction(type: string, roomId: string, version: number) {
  const action = pendingRoomAction.value
  if (action === null) return

  if (type === 'room:created' && action.kind === 'create') {
    pendingRoomAction.value = null
    return
  }

  if (action.roomId !== roomId || version < action.baseVersion) return

  if (type === 'room:joined' && action.kind === 'join') {
    pendingRoomAction.value = null
  } else if (type === 'room:ready' && action.kind === 'ready') {
    pendingRoomAction.value = null
  }
}

/** 回复本身不完整时记录原因，同时允许用户修正环境后重新尝试该操作。 */
function rejectRoomSnapshot(type: string, reason: string) {
  addLog('error', reason)

  const action = pendingRoomAction.value
  if (action === null) return

  if (
    (type === 'room:created' && action.kind === 'create') ||
    (type === 'room:joined' && action.kind === 'join') ||
    (type === 'room:ready' && action.kind === 'ready')
  ) {
    pendingRoomAction.value = null
  }
}

/**
 * 退出确认可能来自本页，也可能来自同账号的另一标签页。
 * 清理前始终核对房间和版本，迟到的退出不能清掉后来加入的新房间。
 */
function handleRoomLeft(payload: unknown) {
  if (
    typeof payload !== 'object' ||
    payload === null ||
    !('roomId' in payload) ||
    typeof payload.roomId !== 'string' ||
    payload.roomId.trim() === '' ||
    payload.roomId.length > 36 ||
    !('version' in payload) ||
    typeof payload.version !== 'number' ||
    !Number.isSafeInteger(payload.version) ||
    payload.version < 1
  ) {
    addLog('error', '退出回复的房间或版本不正确，未清除当前房间')
    if (pendingRoomAction.value?.kind === 'leave') pendingRoomAction.value = null
    return
  }

  const roomId = payload.roomId
  const version = payload.version
  const rememberedVersion = leftRoomVersions.get(roomId)

  if (rememberedVersion === undefined || version > rememberedVersion) {
    // 更新插入顺序，仅保留最近 50 个退出房间的记录
    leftRoomVersions.delete(roomId)
    leftRoomVersions.set(roomId, version)
    if (leftRoomVersions.size > maxRememberedLeftRooms) {
      const oldestRoomId = leftRoomVersions.keys().next().value
      if (oldestRoomId !== undefined) leftRoomVersions.delete(oldestRoomId)
    }
  }

  const room = currentRoom.value
  if (room !== null && room.roomId === roomId && room.version > version) {
    addLog('system', '已忽略较早的退出通知，保留重新加入后的房间信息')
    return
  }

  if (room !== null && room.roomId === roomId) {
    currentRoom.value = null
    if (pendingRoomAction.value?.roomId === roomId) pendingRoomAction.value = null
    addLog('system', '已退出房间，可以创建或加入其他房间')
  }

  const action = pendingRoomAction.value
  if (
    action !== null &&
    action.kind === 'leave' &&
    action.roomId === roomId &&
    version > action.baseVersion
  ) {
    pendingRoomAction.value = null
  }
}

/**
 * 由 /user/queue/replies 的订阅回调调用。
 *
 * body 是后端发来的 JSON 正文：
 * pong：记录测试消息；error：显示错误；房间快照更新成员和准备状态；room:left：确认退出。
 * 只有收到格式正确的房间回复，才更新页面，点击按钮本身不代表创建成功。
 */
function handleServerMessage(body: string) {
  let response: unknown

  try {
    response = JSON.parse(body)
  } catch {
    // 非 JSON 消息也保留，方便排查后端返回的内容
    addLog('received', body)
    return
  }

  if (typeof response !== 'object' || response === null || !('type' in response)) {
    addLog('received', body)
    return
  }

  if (response.type === 'error') {
    pendingRoomAction.value = null
    addLog('error', body)
    return
  }

  addLog('received', body)

  if (response.type === 'room:left') {
    const payload = 'payload' in response ? response.payload : undefined
    handleRoomLeft(payload)
    return
  }

  // 操作回复和主动推送使用相同的房间结构，都从当前用户的订阅中接收
  if (
    response.type !== 'room:created' &&
    response.type !== 'room:joined' &&
    response.type !== 'room:ready' &&
    response.type !== 'room:updated'
  ) {
    return
  }

  if (
    !('payload' in response) ||
    typeof response.payload !== 'object' ||
    response.payload === null
  ) {
    rejectRoomSnapshot(response.type, '房间回复缺少 payload，未更新房间信息')
    return
  }

  const payload = response.payload

  if (
    !('roomId' in payload) ||
    typeof payload.roomId !== 'string' ||
    payload.roomId.trim() === '' ||
    payload.roomId.length > 36
  ) {
    rejectRoomSnapshot(response.type, '房间回复中的 roomId 不正确，未更新房间信息')
    return
  }

  if (
    !('ownerUserId' in payload) ||
    typeof payload.ownerUserId !== 'number' ||
    !Number.isSafeInteger(payload.ownerUserId) ||
    payload.ownerUserId <= 0
  ) {
    rejectRoomSnapshot(response.type, '房间回复中的 ownerUserId 不正确，未更新房间信息')
    return
  }

  if (
    !('memberUserIds' in payload) ||
    !Array.isArray(payload.memberUserIds) ||
    payload.memberUserIds.length < 1 ||
    payload.memberUserIds.length > 2
  ) {
    rejectRoomSnapshot(response.type, '房间回复中的成员名单不正确，未更新房间信息')
    return
  }

  // 逐个确认成员 ID 合法且没有重复，再复制到本次页面状态中
  const memberUserIds: number[] = []

  for (const memberUserId of payload.memberUserIds) {
    if (
      typeof memberUserId !== 'number' ||
      !Number.isSafeInteger(memberUserId) ||
      memberUserId <= 0 ||
      memberUserIds.includes(memberUserId)
    ) {
      rejectRoomSnapshot(response.type, '房间回复中的成员 ID 不正确或重复，未更新房间信息')
      return
    }

    memberUserIds.push(memberUserId)
  }

  if (!memberUserIds.includes(payload.ownerUserId)) {
    rejectRoomSnapshot(response.type, '房主不在成员名单中，未更新房间信息')
    return
  }

  if (!auth.user || !memberUserIds.includes(auth.user.id)) {
    rejectRoomSnapshot(response.type, '当前用户不在成员名单中，未更新房间信息')
    return
  }

  if (
    !('readyUserIds' in payload) ||
    !Array.isArray(payload.readyUserIds) ||
    payload.readyUserIds.length > memberUserIds.length
  ) {
    rejectRoomSnapshot(response.type, '房间回复中的准备名单不正确，未更新房间信息')
    return
  }

  const readyUserIds: number[] = []
  for (const readyUserId of payload.readyUserIds) {
    if (
      typeof readyUserId !== 'number' ||
      !Number.isSafeInteger(readyUserId) ||
      !memberUserIds.includes(readyUserId) ||
      readyUserIds.includes(readyUserId)
    ) {
      rejectRoomSnapshot(response.type, '准备名单包含无效成员或重复成员，未更新房间信息')
      return
    }
    readyUserIds.push(readyUserId)
  }

  if (
    !('version' in payload) ||
    typeof payload.version !== 'number' ||
    !Number.isSafeInteger(payload.version) ||
    payload.version < 1
  ) {
    rejectRoomSnapshot(response.type, '房间回复中的 version 不正确，未更新房间信息')
    return
  }

  // 确认请求已处理后再判断快照是否过期，避免等待按钮被迟到回复一直锁住
  acknowledgeRoomAction(response.type, payload.roomId, payload.version)

  const leftVersion = leftRoomVersions.get(payload.roomId)
  if (leftVersion !== undefined && payload.version <= leftVersion) {
    addLog('system', '已忽略退出前的房间信息，保留当前房间状态')
    return
  }

  // 加入推送可能先于较早的创建回复到达，旧版本不能覆盖已经收到的新成员名单
  if (
    currentRoom.value !== null &&
    currentRoom.value.roomId === payload.roomId &&
    payload.version < currentRoom.value.version
  ) {
    addLog('system', '已忽略较早的房间信息，保留最新成员名单')
    return
  }

  currentRoom.value = {
    roomId: payload.roomId,
    ownerUserId: payload.ownerUserId,
    memberUserIds: memberUserIds,
    readyUserIds: readyUserIds,
    version: payload.version,
  }

  console.log('[handleServerMessage] 收到消息类型 =', response.type)
  console.log('[handleServerMessage] 房间 roomId =', payload.roomId)
  console.log('[handleServerMessage] 房主 ownerUserId =', payload.ownerUserId)
  console.log('[handleServerMessage] 成员 memberUserIds =', memberUserIds)
  console.log('[handleServerMessage] 已准备成员 readyUserIds =', readyUserIds)
  console.log('[handleServerMessage] 房间版本 version =', payload.version)
}

/**
 * 点击“连接”时执行。
 *
 * 创建 Client → activate() → WebSocket 连接
 * → STOMP 协商成功 → onConnect() → 订阅回复。
 */
function connect() {
  // 前端先限制未登录操作；真实 Session 仍由后端检查
  if (!auth.user) {
    addLog('error', '请先登录博客')
    return
  }

  // 防止重复点击产生多条连接
  if (!canConnect.value) return

  resetRoomState()
  state.value = 'connecting'
  addLog('system', `开始连接 ${endpoint}`)

  try {
    const currentClient: Client = new Client({
      // 建立 WebSocket 连接的地址
      brokerURL: endpoint,

      // 设为 0：断线后不自动重连，当前由用户手动点击连接
      reconnectDelay: 0,

      // STOMP 连接超时时间，单位毫秒
      connectionTimeout: 5000,

      /**
       * 收到服务端的 STOMP CONNECTED 帧后执行。
       * 到这里，WebSocket 连接和 STOMP 协商都已成功。
       */
      onConnect: () => {
        // 旧连接的回调不能改变新连接的页面状态
        if (client !== currentClient || state.value === 'closing') return

        /**
         * 订阅当前用户的回复和房间更新。
         *
         * /user/queue/replies 对应后端的：
         * @SendToUser(value = "/queue/replies", broadcast = false)
         * 也接收 GameRoomNotifier 发给当前用户的 room:updated 通知。
         *
         * frame 是收到的 STOMP 消息；
         * frame.body 是其中的正文，目前是 JSON 字符串。
         */
        currentClient.subscribe('/user/queue/replies', (frame) => {
          if (client !== currentClient || state.value !== 'open' || !auth.user || auth.loggingOut) {
            return
          }

          handleServerMessage(frame.body)
        })

        // 先发送订阅请求，再开放页面的发送按钮
        state.value = 'open'
        addLog('system', 'STOMP 连接成功，已发送回复订阅请求')
      },

      /**
       * 收到 STOMP ERROR 帧时执行，属于协议层错误。
       *
       * 后端返回 {"type":"error", ...} 属于普通业务消息，
       * 会进入上面的订阅回调。
       */
      onStompError: (frame) => {
        if (client !== currentClient) return

        addLog('error', frame.headers['message'] || 'STOMP 协议错误')
        void disconnect('服务端拒绝了 STOMP 通信')
      },

      /**
       * 底层 WebSocket 发生错误时执行。
       * 浏览器的这个事件不直接提供握手 HTTP 状态码。
       */
      onWebSocketError: () => {
        if (client !== currentClient) return

        addLog('error', '连接或通信发生错误，请检查登录状态、后端及 Network 握手请求')
      },

      /**
       * 底层 WebSocket 关闭时执行。
       * 手动断开、网络异常、服务端关闭，都可能进入这里。
       *
       * event.code：关闭码
       * event.reason：关闭原因，可能为空
       */
      onWebSocketClose: (event) => {
        if (client !== currentClient) return

        client = null
        state.value = 'closed'
        resetRoomState()

        addLog('system', `连接关闭，关闭码：${event.code}，原因：${event.reason || '未提供'}`)

        // 同时结束这个 STOMP 客户端的活动状态
        void currentClient.deactivate()
      },
    })

    // 先保存实例，后面的回调才能判断自己是不是当前连接
    client = currentClient

    // 开始异步连接；成功和失败由上面的回调通知
    currentClient.activate()
  } catch (error) {
    // 这里处理创建、启动客户端时的同步异常
    client = null
    state.value = 'closed'
    resetRoomState()

    addLog('error', error instanceof Error ? error.message : '创建连接失败')
  }
}

/**
 * 点击“发送消息”时执行。
 *
 * 文本框内容 → JSON 字符串 → publish()
 * → 后端 @MessageMapping("/ping")。
 */
function sendMessage() {
  const currentClient = client

  // 页面状态与客户端的真实连接状态都要满足要求
  if (!canSend.value || !currentClient || !currentClient.connected) {
    addLog('error', '请等待 STOMP 连接成功')
    return
  }

  const destination = '/app/ping'
  // 例如文本框是 hello，这里得到：{"text":"hello"}
  const body = JSON.stringify({ text: message.value })

  console.log('[sendMessage] 发送目标 =', destination)
  console.log('[sendMessage] 消息正文 =', body)

  try {
    currentClient.publish({
      // /app 是配置前缀，/ping 对应后端 @MessageMapping("/ping")
      destination: destination,

      // 声明正文是 JSON，供后端选择相应的消息转换器
      headers: { 'content-type': 'application/json' },

      // STOMP 消息正文
      body: body,
    })

    addLog('sent', destination + ' → ' + body)
  } catch (error) {
    console.error('[sendMessage] 发送失败', error)
    addLog('error', '测试消息发送失败')
  }
}

/**
 * 点击“创建房间”时执行。
 *
 * publish() → /app/room/create → GameMessageController.createRoom(principal)。
 * 当前接口没有业务参数，所以正文是 {}；用户身份由后端从连接中取得。
 * 成功回复从已有的 /user/queue/replies 订阅进入 handleServerMessage()。
 */
function createRoom() {
  const currentClient = client

  if (!canChooseRoom.value || !currentClient || !currentClient.connected) {
    addLog('error', '请先连接；已在房间时需要先退出，正在处理的操作需要等待确认')
    return
  }

  const destination = '/app/room/create'
  const body = '{}'

  console.log('[createRoom] 发送目标 =', destination)
  console.log('[createRoom] 消息正文 =', body)

  pendingRoomAction.value = { kind: 'create', roomId: null, baseVersion: 0 }

  try {
    currentClient.publish({
      destination: destination,
      headers: { 'content-type': 'application/json' },
      body: body,
    })

    // 这里只表示请求已发送；房间是否创建成功，要看后端回复
    addLog('sent', destination + ' → ' + body)
  } catch (error) {
    pendingRoomAction.value = null
    console.error('[createRoom] 发送失败', error)
    addLog('error', '创建房间消息发送失败')
  }
}

/**
 * 点击“加入房间”时执行。
 * 输入框 → JSON 正文 → /app/room/join → 后端 joinRoom()。
 */
function joinRoom() {
  const currentClient = client

  // 页面状态和真实连接状态都必须允许发送
  if (!canChooseRoom.value || !currentClient || !currentClient.connected) {
    addLog('error', '请先连接；已在房间时需要先退出，正在处理的操作需要等待确认')
    return
  }

  // 去掉复制房间号时可能带上的首尾空白
  const roomId = joinRoomId.value.trim()

  if (roomId === '') {
    addLog('error', '请输入房间号')
    return
  }

  if (roomId.length > 36) {
    addLog('error', '房间号最多允许 36 个字符')
    return
  }

  const destination = '/app/room/join'

  // 只发送房间号，用户 ID 由后端从连接身份中读取
  const body = JSON.stringify({
    roomId: roomId,
  })

  console.log('[joinRoom] 发送目标 =', destination)
  console.log('[joinRoom] 消息正文 =', body)

  pendingRoomAction.value = { kind: 'join', roomId: roomId, baseVersion: 0 }

  try {
    currentClient.publish({
      destination: destination,
      headers: { 'content-type': 'application/json' },
      body: body,
    })

    // 请求发出后，等待后端回复才能确认加入成功
    addLog('sent', destination + ' → ' + body)
  } catch (error) {
    pendingRoomAction.value = null
    console.error('[joinRoom] 发送失败', error)
    addLog('error', '加入房间消息发送失败')
  }
}

/** 从本次服务器快照读取自己的准备状态，只提交相反的目标状态。 */
function toggleReady() {
  const currentClient = client
  const room = currentRoom.value

  if (!canChangeRoom.value || !currentClient || !currentClient.connected || room === null) {
    addLog('error', '请先进入房间，并等待上一次操作确认')
    return
  }

  const ready = !isCurrentPlayerReady.value
  const destination = '/app/player/ready'
  const body = JSON.stringify({ roomId: room.roomId, ready: ready })

  console.log('[toggleReady] 发送目标 =', destination)
  console.log('[toggleReady] 消息正文 =', body)

  pendingRoomAction.value = { kind: 'ready', roomId: room.roomId, baseVersion: room.version }

  try {
    currentClient.publish({
      destination: destination,
      headers: { 'content-type': 'application/json' },
      body: body,
    })
    addLog('sent', destination + ' → ' + body)
  } catch (error) {
    pendingRoomAction.value = null
    console.error('[toggleReady] 发送失败', error)
    addLog('error', '准备状态发送失败')
  }
}

/** 主动释放当前席位，收到 room:left 后才清除页面中的房间。 */
function leaveRoom() {
  const currentClient = client
  const room = currentRoom.value

  if (!canChangeRoom.value || !currentClient || !currentClient.connected || room === null) {
    addLog('error', '请先进入房间，并等待上一次操作确认')
    return
  }

  const destination = '/app/room/leave'
  const body = JSON.stringify({ roomId: room.roomId })

  console.log('[leaveRoom] 发送目标 =', destination)
  console.log('[leaveRoom] 消息正文 =', body)

  pendingRoomAction.value = { kind: 'leave', roomId: room.roomId, baseVersion: room.version }

  try {
    currentClient.publish({
      destination: destination,
      headers: { 'content-type': 'application/json' },
      body: body,
    })
    addLog('sent', destination + ' → ' + body)
  } catch (error) {
    pendingRoomAction.value = null
    console.error('[leaveRoom] 发送失败', error)
    addLog('error', '退出房间消息发送失败')
  }
}

/**
 * 主动断开当前连接。
 *
 * @param reason 展示在页面日志中的断开原因
 */
async function disconnect(reason = '手动断开') {
  const currentClient = client
  if (!currentClient || !canDisconnect.value) return

  state.value = 'closing'
  // 清空的是页面中的结果；断开连接与主动退出房间是两个不同操作
  resetRoomState()
  addLog('system', reason)

  try {
    // deactivate() 返回 Promise；await 等待关闭流程完成
    await currentClient.deactivate()
  } catch (error) {
    if (client === currentClient) {
      addLog('error', error instanceof Error ? error.message : '断开失败')
    }
  } finally {
    // 防止旧连接的异步关闭任务误清理后来建立的新连接
    if (client === currentClient) {
      client = null
      state.value = 'closed'
    }
  }
}

// 已登录用户退出或切换账号时，关闭本页建立的连接
watch(
  () => auth.user?.id,
  (userId, previousUserId) => {
    if (previousUserId !== undefined && userId !== previousUserId) {
      resetRoomState()
      // void 表示这里不等待 Promise，断开操作仍会执行
      void disconnect('登录状态已改变')
    }
  },
)

// 离开测试页时，关闭连接，避免留下后台通信
onUnmounted(() => {
  const currentClient = client

  resetRoomState()

  // 先清空引用，让这个旧实例之后触发的回调失效
  client = null

  if (currentClient) {
    void currentClient.deactivate().catch((error) => {
      // 页面已经卸载，清理失败的信息写入浏览器控制台
      console.error('清理 STOMP 连接失败', error)
    })
  }
})
</script>

<template>
  <main class="page-container ws-test-page">
    <div class="test-heading">
      <div>
        <span class="eyebrow">连接 · 消息 · 房间</span>
        <h1>WebSocket 测试</h1>
        <p class="muted">登录并连接后，测试双人房间同步、准备与主动退出。</p>
      </div>
      <span class="connection-status" :class="`status-${state}`" role="status">
        <span class="status-dot" aria-hidden="true"></span>
        {{ statusText }}
      </span>
    </div>

    <p v-if="auth.user" class="notice notice-success">
      当前登录：{{ auth.user.username }}。连接时会使用博客已有的登录状态。
    </p>
    <p v-else class="notice">
      当前未登录，请先登录博客，再建立游戏连接。
      <RouterLink class="text-link" to="/login">前往登录</RouterLink>
    </p>

    <section class="surface test-card" aria-labelledby="connection-title">
      <h2 id="connection-title">1. 建立连接</h2>
      <div class="field">
        <label for="ws-endpoint">连接地址</label>
        <input id="ws-endpoint" :value="endpoint" readonly />
        <span class="field-caption"> 等待连接成功后，再使用下方的消息和房间功能。 </span>
      </div>
      <div class="action-row">
        <button
          class="button button-primary"
          type="button"
          :disabled="!canConnect"
          @click="connect"
        >
          {{ !auth.user ? '请先登录' : state === 'connecting' ? '正在连接……' : '连接' }}
        </button>
        <button class="button" type="button" :disabled="!canDisconnect" @click="disconnect()">
          {{ state === 'connecting' ? '取消连接' : '断开连接' }}
        </button>
      </div>
    </section>

    <section class="surface test-card" aria-labelledby="send-title">
      <h2 id="send-title">2. 发送消息</h2>
      <form @submit.prevent="sendMessage">
        <div class="field">
          <label for="ws-message">文本内容</label>
          <textarea
            id="ws-message"
            v-model="message"
            rows="3"
            maxlength="2000"
            placeholder="输入 hello，试试后端的回复"
          ></textarea>
          <span class="field-caption">
            发送 hello 后应收到 pong；清空内容可测试后端参数校验，最多输入 2000 个字符。
          </span>
        </div>
        <button class="button button-primary" type="submit" :disabled="!canSend">发送消息</button>
      </form>
    </section>

    <section class="surface test-card" aria-labelledby="room-title">
      <h2 id="room-title">3. 房间与准备</h2>

      <p class="muted room-description">
        一位玩家创建房间，另一位玩家输入房间号加入。成员变化后，双方需要重新准备。
      </p>

      <button
        class="button button-primary"
        type="button"
        :disabled="!canChooseRoom"
        @click="createRoom"
      >
        创建房间
      </button>

      <form class="join-room-form" @submit.prevent="joinRoom">
        <div class="field">
          <label for="join-room-id">要加入的房间号</label>

          <input
            id="join-room-id"
            v-model="joinRoomId"
            type="text"
            maxlength="36"
            :disabled="!canChooseRoom"
            placeholder="粘贴另一位玩家的房间号"
          />

          <span class="field-caption">
            加入他人房间时直接填写房间号；已在房间中时，需要先主动退出。
          </span>
        </div>

        <button class="button" type="submit" :disabled="!canChooseRoom">加入房间</button>
      </form>

      <p v-if="pendingRoomAction" class="room-pending" role="status">{{ pendingActionText }}</p>

      <div class="room-result" role="status" aria-live="polite">
        <dl v-if="currentRoom" class="room-details">
          <div>
            <dt>房间号</dt>
            <dd>{{ currentRoom.roomId }}</dd>
          </div>

          <div>
            <dt>房主用户 ID</dt>
            <dd>{{ currentRoom.ownerUserId }}</dd>
          </div>

          <div>
            <dt>房间人数</dt>
            <dd>{{ currentRoom.memberUserIds.length }} / 2 人</dd>
          </div>

          <div>
            <dt>成员名单</dt>
            <dd>
              <ul class="room-members">
                <li v-for="memberUserId in currentRoom.memberUserIds" :key="memberUserId">
                  <span>用户 {{ memberUserId }}</span>
                  <span v-if="memberUserId === currentRoom.ownerUserId" class="member-badge"
                    >房主</span
                  >
                  <span v-if="memberUserId === auth.user?.id" class="member-badge">你</span>
                  <span
                    class="member-readiness"
                    :class="{ 'is-ready': currentRoom.readyUserIds.includes(memberUserId) }"
                  >
                    {{ currentRoom.readyUserIds.includes(memberUserId) ? '已准备' : '未准备' }}
                  </span>
                </li>
              </ul>
            </dd>
          </div>
        </dl>

        <p v-else class="muted">创建或加入成功后，房间信息会显示在这里。</p>
      </div>

      <div v-if="currentRoom" class="room-actions">
        <div class="action-row">
          <button
            class="button button-primary"
            type="button"
            :disabled="!canChangeRoom"
            @click="toggleReady"
          >
            {{ isCurrentPlayerReady ? '取消准备' : '准备' }}
          </button>
          <button class="button" type="button" :disabled="!canChangeRoom" @click="leaveRoom">
            退出房间
          </button>
        </div>
        <p v-if="areAllPlayersReady" class="notice notice-success room-ready-notice">
          双方已准备。本阶段先确认房间流程，开始游戏将在下一阶段实现。
        </p>
        <p v-else class="field-caption room-ready-notice">
          两名玩家都准备后会显示提示；房主退出时，剩余玩家会成为新房主。
        </p>
      </div>

      <p class="field-caption">
        断开连接不会释放房间席位，请使用“退出房间”。断开后重新连接，可点击创建取回已有房间。
        后端重启后，原房间号会失效。
      </p>
    </section>

    <section class="surface test-card" aria-labelledby="log-title">
      <div class="log-heading">
        <div>
          <h2 id="log-title">4. 观察消息</h2>
          <p class="muted">保留最近 200 条记录，关闭连接后仍可查看。</p>
        </div>
        <button
          class="button button-small"
          type="button"
          :disabled="logs.length === 0"
          @click="logs = []"
        >
          清空日志
        </button>
      </div>
      <div
        ref="logPanel"
        class="log-panel"
        role="log"
        aria-label="WebSocket 通信日志"
        aria-live="polite"
        tabindex="0"
      >
        <p v-if="logs.length === 0" class="log-empty">还没有记录。点击“连接”开始测试。</p>
        <div v-for="entry in logs" :key="entry.id" class="log-entry" :class="`log-${entry.kind}`">
          <time class="log-time">{{ entry.time }}</time>
          <span class="log-label">{{ logLabels[entry.kind] }}</span>
          <!-- 用文本插值展示消息，不把后端消息当成 HTML 执行。 -->
          <pre class="log-content">{{ entry.content }}</pre>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.ws-test-page {
  max-width: 1000px;
}
.join-room-form {
  margin-top: 24px;
}

.test-heading,
.log-heading,
.action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.test-heading {
  margin-bottom: 24px;
}

.test-heading h1 {
  margin-bottom: 10px;
  font-size: clamp(26px, 4vw, 34px);
}

.test-heading p,
.log-heading p {
  font-size: 13px;
}

.connection-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  padding: 7px 13px;
  border: 1px solid var(--color-border);
  border-radius: 20px;
  background: var(--color-surface);
  color: var(--color-muted);
  font-size: 13px;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.status-open {
  color: var(--color-brand);
  background: var(--color-brand-soft);
}

.status-connecting,
.status-closing {
  color: #8a692f;
  background: #faf5e9;
}

.test-card {
  padding: 26px;
  margin-top: 20px;
}

.test-card h2 {
  margin-bottom: 20px;
  font-size: 18px;
}

.test-card textarea {
  min-height: 100px;
}

#ws-endpoint {
  font-family: Consolas, monospace;
  font-size: 13px;
}

.action-row {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.room-description {
  margin-bottom: 16px;
  font-size: 13px;
}

.room-pending,
.room-ready-notice {
  margin-top: 16px;
}

.room-pending {
  font-size: 13px;
  color: var(--color-muted);
}

.room-actions {
  margin: 18px 0;
}

.room-result {
  margin: 18px 0 10px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-background-soft);
  font-size: 13px;
}

.room-details {
  margin: 0;
  display: grid;
  gap: 14px;
}

.room-details dt {
  margin-bottom: 4px;
  color: var(--color-muted);
}

.room-details dd {
  margin: 0;
  overflow-wrap: anywhere;
  font-family: Consolas, monospace;
  color: var(--color-heading);
}

.room-members {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.room-members li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.member-badge {
  padding: 2px 8px;
  border-radius: 12px;
  background: var(--color-brand-soft);
  color: var(--color-brand);
  font:
    12px/1.5 'Microsoft YaHei',
    sans-serif;
}

.member-readiness {
  margin-left: auto;
  color: var(--color-muted);
  font:
    12px/1.5 'Microsoft YaHei',
    sans-serif;
}

.member-readiness.is-ready {
  color: var(--color-brand);
  font-weight: 600;
}

.log-heading {
  margin-bottom: 18px;
}

.log-heading h2 {
  margin-bottom: 5px;
}

.log-heading button {
  flex-shrink: 0;
}

.log-panel {
  min-height: 220px;
  max-height: 440px;
  overflow: auto;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: #f8faf7;
}

.log-empty {
  padding: 38px 18px;
  text-align: center;
  color: var(--color-muted);
  font-size: 13px;
}

.log-entry {
  display: grid;
  grid-template-columns: 66px 34px minmax(0, 1fr);
  align-items: start;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--color-border);
  font-size: 12px;
}

.log-entry:last-child {
  border-bottom: 0;
}

.log-time,
.log-label {
  color: var(--color-muted);
}

.log-content {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font:
    12px/1.7 Consolas,
    'Microsoft YaHei',
    monospace;
}

.log-sent .log-label {
  color: #3a6b9b;
}

.log-received .log-label {
  color: var(--color-brand);
}

.log-error {
  color: var(--color-danger);
  background: var(--color-danger-soft);
}

.log-error .log-label {
  color: var(--color-danger);
}

@media (max-width: 580px) {
  .test-heading {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .test-card {
    padding: 20px;
  }

  .log-entry {
    grid-template-columns: 62px minmax(0, 1fr);
    gap: 4px 8px;
  }

  .log-content {
    grid-column: 1 / -1;
  }
}
</style>
