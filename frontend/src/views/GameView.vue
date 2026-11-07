<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { drawMap, type GameMapData } from '@/game/mapLoader'
import { createBulletRenderer, createPlayerRenderer,type PlayerState, } from '@/game/playRenderer'
import { bindDirectionKeys } from '@/game/keyboardInput'
import { movePlayer } from '@/game/playerMovement'
import { gameMap, TILE_SIZE } from '@/game/map'
import {
  advanceBullets,
  createBullet,
  FIRE_COOLDOWN_MS,
  MAX_BULLETS_PER_PLAYER,
  removeOutOfBounds,
  type BulletData,
} from '@/game/bullet'

// 保存画布元素和页面加载状态
const canvasRef = ref<HTMLCanvasElement | null>(null)
const loading = ref(true)
const errorMessage = ref('')

// 画布上方显示的坐标，用来直观确认坦克确实在移动
const hud = ref('')

// 方向编号转成中文，只给坐标显示用
const DIRECTION_NAMES = ['上', '下', '左', '右'] as const

// 保存解除键盘绑定的方法
let releaseKeys: (() => void) | undefined

// 保存动画请求编号，离开页面时取消
let animationId: number | null = null

// 标记页面是否已经离开，避免异步加载完成后继续绑定事件
let disposed = false

//页面挂载后触发
onMounted(async () => {
  try {
    // 页面挂载后取得画布的二维绘图工具
    const context = canvasRef.value?.getContext('2d')
    if (!context) {
      throw new Error('无法初始化游戏画布')
    }

    // 请求地图；Vite 会将 /api 请求转发给后端
    // 这里的 1 是数据库中的地图 ID
    let map: GameMapData

    try {
      const response = await fetch('/api/game/maps/1')

      // HTTP 错误需要手动检查
      if (!response.ok) {
        throw new Error(`地图请求失败，状态码：${response.status}`)
      }

      // 将响应 JSON 转成地图数据
      map = await response.json()
    } catch (error) {
      // 后端或数据库还没准备好时，退回 map.ts 里的本地地图。
      // 这样移动逻辑可以脱离后端单独验证，接回接口时只需删掉这个 catch。
      console.warn('地图接口不可用，改用本地地图：', error)
      map = {
        id: 0,
        name: '本地测试地图',
        tileSize: TILE_SIZE,
        seed: gameMap,
      }
    }

   // 创建离屏画布，缓存地图背景
    const mapCanvas = document.createElement('canvas')
    mapCanvas.width = context.canvas.width
    mapCanvas.height = context.canvas.height

    const mapContext = mapCanvas.getContext('2d')
    if (!mapContext) {
      throw new Error('无法创建地图缓存')
    }

    // 地图只在初始化时逐格绘制到缓存中
    await drawMap(mapContext, map)

    // 初始化绘制器，图片只在初始化时加载
    const playerRenderer = await createPlayerRenderer(context)

    // 子弹绘制器：一次加载四个方向的图，之后复用
    const bulletRenderer = await createBulletRenderer(context)

    // 定义玩家初始位置和显示尺寸
    // 使用 PlayerState 检查玩家字段和方向编号
    const player: PlayerState = {
      x: map.tileSize,
      y: map.tileSize,
      size: map.tileSize,
      direction: 1, // 0上、1下、2左、3右
    }

    // 异步加载期间如果已经离开页面，就结束初始化
    if (disposed) return

    // 当前按住的方向；null 表示已经松手，坦克停下
    let currentDirection: 0 | 1 | 2 | 3 | null = null

    // 上一帧的时间戳，用来算两帧之间过了多久
    let lastTimestamp = 0

    // 坐标显示上次刷新的时间，避免每帧都触发 Vue 重渲染
    let lastHudUpdate = 0

    // 场上所有子弹，每帧推进后重建数组
    let bullets: BulletData[] = []

    // 开火键是否按住。按住就持续尝试开火，实际能不能发由冷却决定
    let fireHeld = false

    // 上一次成功开火的时间戳，用来判断冷却
    let lastFireTime = 0

    // 每帧执行：先推进位置，再重绘
    const renderScene = (timestamp: number): void => {
      // 页面离开后，停止继续绘制
      if (disposed) return

      // 换算成秒。第一帧没有上一帧，按 0 处理，坦克不会凭空移动。
      const dt = lastTimestamp === 0 ? 0 : (timestamp - lastTimestamp) / 1000
      lastTimestamp = timestamp

      // 按住方向键时才推进，松手后坐标保持不变
      if (currentDirection !== null) {
        const next = movePlayer(player, currentDirection, dt, {
          width: context.canvas.width,
          height: context.canvas.height,
        })
        player.x = next.x
        player.y = next.y
      }

      // 按住空格且冷却已过，就生成一颗子弹
      if (fireHeld && timestamp - lastFireTime >= FIRE_COOLDOWN_MS) {
        // 场上属于本地玩家的子弹还没到上限才允许开火
        const mine = bullets.filter((item) => item.ownerId === 'local').length
        if (mine < MAX_BULLETS_PER_PLAYER) {
          bullets.push(createBullet(player, player.direction))
          lastFireTime = timestamp
        }
      }

      // 推进所有子弹，再删掉飞出画布的
      bullets = advanceBullets(bullets, dt)
      bullets = removeOutOfBounds(
        bullets,
        context.canvas.width,
        context.canvas.height,
      )

      // 清除上一帧
      context.clearRect(0, 0, context.canvas.width, context.canvas.height)

      // 复制缓存地图，无需重新遍历地图数据
      context.drawImage(mapCanvas, 0, 0)

      // 根据玩家当前状态绘制坦克
      playerRenderer.draw(player)

      // 子弹画在坦克之后，保证子弹不会被坦克挡住
      for (const bullet of bullets) {
        bulletRenderer.draw(bullet)
      }

      // 每 100 毫秒更新一次坐标显示
      if (timestamp - lastHudUpdate >= 100) {
        lastHudUpdate = timestamp
        hud.value = `x ${Math.round(player.x)}  y ${Math.round(player.y)}  朝向 ${DIRECTION_NAMES[player.direction]}  子弹 ${bullets.length}`
      }

      // 下一次浏览器刷新前，再次执行绘制
      animationId = requestAnimationFrame(renderScene)
    }

    // 接收移动输入变化。
    // 这里只记录方向，不直接改坐标——位置统一交给每帧的 movePlayer 计算。
    // 将来接入联机时，这个回调换成"向服务器发送按键"，循环里换成"读服务器坐标"。
    releaseKeys = bindDirectionKeys(
      context.canvas,
      (direction) => {
        currentDirection = direction

        // 停止输入时保留坦克朝向，避免将 null 传给绘制器
        if (direction !== null) {
          player.direction = direction
        }
      },
      (held) => {
        // 只记录开火键状态，真正生成子弹在每帧的循环里判断
        fireHeld = held
      },
    )

    // 初始化完成后，只启动一次循环；给一个起始时间戳
    renderScene(performance.now())

  } catch (error) {
    // 显示接口请求或图片加载过程中的错误
    errorMessage.value =
      error instanceof Error ? error.message : '地图加载失败'
  } finally {
    // 无论成功还是失败，都结束加载状态
    loading.value = false
  }
})

//页面离开时触发
onUnmounted(() => {
  // 阻止异步初始化和绘制继续执行
  disposed = true

  // 取消下一次动画回调
  if (animationId !== null) {
    cancelAnimationFrame(animationId)
    animationId = null
  }

  // 解除键盘监听
  releaseKeys?.()
})



</script>

<template>
  <main class="page-container">
  <!-- 显示加载进度和失败原因 -->
  <p v-if="loading" role="status">正在加载地图……</p>
  <p v-if="errorMessage" class="notice notice-error" role="alert">
    {{ errorMessage }}
  </p>
  <!-- 坐标显示：确认坦克真的在动，也方便对照代码里的计算 -->
  <p v-if="!loading && !errorMessage" class="hud" role="status">{{ hud }}</p>
  <canvas
    ref="canvasRef"
    width="960"
    height="624"
    tabindex="0"
    aria-label="坦克大战，使用方向键或 WASD 移动"
    @pointerdown="canvasRef?.focus()"
  >
    当前浏览器不支持 Canvas。
  </canvas>
  </main>
</template>

<style scoped>
.hud {
  max-width: 960px;
  margin: 16px auto 0;
  font-family: ui-monospace, monospace;
  font-size: 13px;
  color: #526176;
}

canvas {
  display: block;
  width: 100%;

  height: auto;
  max-width: 960px;
  margin: 20px auto 0;
  background: #000;
  image-rendering: pixelated;
}

.game-canvas {
  max-width: 100%;
  height: auto;
}

</style>
