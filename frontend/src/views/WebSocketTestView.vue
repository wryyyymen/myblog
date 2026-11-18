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
  id: number       // 唯一编号，供 Vue 的 v-for :key 使用
  time: string     // 日志产生的本地时间
  kind: LogKind    // 日志类别
  content: string  // 日志正文
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
    auth.user !== null &&
    !auth.loggingOut &&
    (state.value === 'idle' || state.value === 'closed'),
)

// 正在连接时允许取消；连接成功后允许断开
const canDisconnect = computed(
  () => state.value === 'connecting' || state.value === 'open',
)

// 测试页允许发送空文本，用来验证后端的 @NotBlank。
// 实际能否发送，还会在 sendMessage() 中检查 client.connected。
const canSend = computed(
  () => auth.user !== null && !auth.loggingOut && state.value === 'open',
)

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
         * 订阅当前连接的回复。
         *
         * /user/queue/replies 对应后端的：
         * @SendToUser(value = "/queue/replies", broadcast = false)
         *
         * frame 是收到的 STOMP 消息；
         * frame.body 是其中的正文，目前是 JSON 字符串。
         */
        currentClient.subscribe('/user/queue/replies', (frame) => {
          if (client !== currentClient) return

          try {
            // 读取 type，仅用于决定日志显示为“收到”还是“错误”
            const result = JSON.parse(frame.body) as { type?: string } | null

            addLog(
              result?.type === 'error' ? 'error' : 'received',
              frame.body,
            )
          } catch {
            // 无法解析为 JSON 时，仍按普通文本展示
            addLog('received', frame.body)
          }
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

        addLog(
          'error',
          '连接或通信发生错误，请检查登录状态、后端及 Network 握手请求',
        )
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

        addLog(
          'system',
          `连接关闭，关闭码：${event.code}，原因：${event.reason || '未提供'}`,
        )

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

  // 例如文本框是 hello，这里得到：{"text":"hello"}
  const body = JSON.stringify({ text: message.value })

  try {
    currentClient.publish({
      // /app 是配置前缀，/ping 对应后端 @MessageMapping("/ping")
      destination: '/app/ping',

      // 声明正文是 JSON，供后端选择相应的消息转换器
      headers: { 'content-type': 'application/json' },

      // STOMP 消息正文
      body,
    })

    addLog('sent', body)
  } catch (error) {
    addLog('error', error instanceof Error ? error.message : '发送失败')
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
      // void 表示这里不等待 Promise，断开操作仍会执行
      void disconnect('登录状态已改变')
    }
  },
)

// 离开测试页时，关闭连接，避免留下后台通信
onUnmounted(() => {
  const currentClient = client

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
        <span class="eyebrow">连接 · 发送 · 接收</span>
        <h1>WebSocket 测试</h1>
        <p class="muted">先连接，再发送 hello，观察后端的 pong 回复。</p>
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
        <span class="field-caption">
          输入 hello 测试收发；清空内容可以测试后端参数校验。
        </span>
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
          <span class="field-caption">连接成功后即可发送，最多 2000 个字符。</span>
        </div>
        <button class="button button-primary" type="submit" :disabled="!canSend">发送消息</button>
      </form>
    </section>

    <section class="surface test-card" aria-labelledby="log-title">
      <div class="log-heading">
        <div>
          <h2 id="log-title">3. 观察消息</h2>
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
