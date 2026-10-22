<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { drawMap, type GameMapData } from '@/game/mapLoader'
import { createPlayerRenderer,type PlayerState, } from '@/game/playRenderer'
import { bindDirectionKeys } from '@/game/keyboardInput'

// 保存画布元素和页面加载状态
const canvasRef = ref<HTMLCanvasElement | null>(null)
const loading = ref(true)
const errorMessage = ref('')

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
    const response = await fetch('/api/game/maps/1')

    // HTTP 错误需要手动检查
    if (!response.ok) {
      throw new Error(`地图请求失败，状态码：${response.status}`)
    }

    // 将响应 JSON 转成地图数据
    const map: GameMapData = await response.json()

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

    // 定义玩家初始位置和显示尺寸
    // 使用 PlayerState 检查玩家字段和方向编号
    const player: PlayerState = {
      x: map.tileSize,
      y: map.tileSize,
      size: map.tileSize,
      direction: 1, // 0上、1下、2左、3右
    }

    // 根据玩家状态绘制坦克
    // 异步加载期间如果已经离开页面，就结束初始化
    if (disposed) return

    // 绘制当前状态，并预约下一帧
    const renderScene = (): void => {
      // 页面离开后，停止继续绘制
      if (disposed) return

      // 清除上一帧
      context.clearRect(0, 0, context.canvas.width, context.canvas.height)

      // 复制缓存地图，无需重新遍历地图数据
      context.drawImage(mapCanvas, 0, 0)

      // 根据玩家当前状态绘制坦克
      playerRenderer.draw(player)

      // 下一次浏览器刷新前，再次执行绘制
      animationId = requestAnimationFrame(renderScene)
    }

    // 接收移动输入变化；后续在这里向服务器发送操作指令
    releaseKeys = bindDirectionKeys(context.canvas, (direction) => {
      // 临时验证输入：0～3 表示移动方向，null 表示停止
      console.log('输入方向：', direction)

      // 停止输入时保留坦克朝向，避免将 null 传给绘制器
      if (direction !== null) {
        player.direction = direction
      }
    })

    // 初始化完成后，只启动一次循环
    renderScene()

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
  <canvas
    ref="canvasRef"
    width="960"
    height="624"
    tabindex="0"
    aria-label="坦克大战，使用方向键或 WASD 转向"
    @pointerdown="canvasRef?.focus()"
  >
    当前浏览器不支持 Canvas。
  </canvas>
  </main>
</template>

<style scoped>
canvas {
  display: block;
  width: 100%;

  height: auto;
  max-width: 960px;
  margin: 20px auto 0;
  background: #000;
  image-rendering: pixelated;
}

</style>
