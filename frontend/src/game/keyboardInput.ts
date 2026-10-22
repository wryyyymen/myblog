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
 */
export function bindDirectionKeys(
  canvas: HTMLCanvasElement,
  onDirectionChange: (direction: Direction | null) => void,
): () => void {
  // 按下的按键按顺序保存，最后按下的优先
  let pressedKeys: string[] = []

  // null 表示当前没有移动输入
  let currentDirection: Direction | null = null

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
    // 只处理方向键和 WASD
    if (!keyDirections.has(event.code)) return

    event.preventDefault()

    // 只记录首次按下，忽略长按或重新聚焦时的重复事件
    if (event.repeat || pressedKeys.includes(event.code)) return

    pressedKeys.push(event.code)
    reportDirection()
  }

  function handleKeyUp(event: KeyboardEvent): void {
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
