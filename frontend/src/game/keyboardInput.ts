import type { PlayerState } from './playRenderer'

// 复用玩家方向类型：0上、1下、2左、3右
type Direction = PlayerState['direction']

// 绑定键盘按键与坦克方向
const keyDirections = new Map<string, Direction>([
  ['ArrowUp', 0],
  ['ArrowDown', 1],
  ['ArrowLeft', 2],
  ['ArrowRight', 3],
  ['KeyW', 0],
  ['KeyS', 1],
  ['KeyA', 2],
  ['KeyD', 3],
])

/**
 * 监听移动输入变化，并返回解除绑定的方法。
 * @param canvas 获得焦点后接收方向键和 WASD 的游戏画布。
 * @param onDirectionChange 输入改变时回调；0～3 表示方向，null 表示停止。
 * @param onFireChange 开火键按住状态变化时回调；true 表示按住，false 表示松开。
 */
export function bindDirectionKeys(
  canvas: HTMLCanvasElement,
  onDirectionChange: (direction: Direction | null) => void,
  onFireChange?: (held: boolean) => void,
): () => void {
  // 按下的按键按顺序保存，最后按下的优先
  let pressedKeys: string[] = []

  // null 表示当前没有移动输入
  let currentDirection: Direction | null = null

  // 开火键是否按住。报告里的 player:input 消息带的就是这个状态，而不是“按了一下”
  let fireHeld = false

  // 根据仍然按住的键，报告当前方向
  function reportDirection(): void {
    const lastKey = pressedKeys[pressedKeys.length - 1]
    let direction: Direction | null = null

    if (lastKey !== undefined) {
      direction = keyDirections.get(lastKey) ?? null
    }

    // 输入没有改变时，无需重复通知
    if (direction === currentDirection) return

    currentDirection = direction
    onDirectionChange(direction)
  }

  function handleKeyDown(event: KeyboardEvent): void {
    // 空格是开火键，不参与方向判断，单独处理
    if (event.code === 'Space') {
      event.preventDefault()
      // 长按会持续触发 keydown，只有状态真的变了才通知
      if (!fireHeld) {
        fireHeld = true
        onFireChange?.(true)
      }
      return
    }

    // 只处理方向键和 WASD
    if (!keyDirections.has(event.code)) return

    event.preventDefault()

    // 只记录首次按下，忽略长按或重新聚焦时的重复事件
    if (event.repeat || pressedKeys.includes(event.code)) return

    pressedKeys.push(event.code)
    reportDirection()
  }

  function handleKeyUp(event: KeyboardEvent): void {
    // 空格松开时清除开火状态
    if (event.code === 'Space') {
      if (fireHeld) {
        fireHeld = false
        onFireChange?.(false)
      }
      return
    }

    // 只处理游戏中已经记录过的按键
    if (!pressedKeys.includes(event.code)) return

    event.preventDefault()

    // 删除松开的键，重新确定方向
    pressedKeys = pressedKeys.filter((key) => key !== event.code)
    reportDirection()
  }

  function clearKeys(): void {
    // 失去焦点时清空输入，避免一直保持移动状态
    pressedKeys = []
    reportDirection()

    // 开火键也要一起放开，否则切走再切回来会一直开火
    if (fireHeld) {
      fireHeld = false
      onFireChange?.(false)
    }
  }

  canvas.addEventListener('keydown', handleKeyDown)
  window.addEventListener('keyup', handleKeyUp)
  canvas.addEventListener('blur', clearKeys)
  window.addEventListener('blur', clearKeys)

  return () => {
    canvas.removeEventListener('keydown', handleKeyDown)
    window.removeEventListener('keyup', handleKeyUp)
    canvas.removeEventListener('blur', clearKeys)
    window.removeEventListener('blur', clearKeys)

    // 页面离开时，也报告停止输入
    clearKeys()
  }
}
