import { bulletAssets, gameAssets } from './assets'
import { BULLET_SIZE, type BulletData } from './bullet'

// 玩家状态，由页面或后续游戏逻辑维护
export interface PlayerState {
  x: number
  y: number
  size: number

  // 方向对应精灵表的行：0上、1下、2左、3右
  direction: 0 | 1 | 2 | 3
}

// 初始化玩家绘制器，加载坦克图片
export async function createPlayerRenderer(
  context: CanvasRenderingContext2D,
) {
  const image = new Image()
  image.src = gameAssets.playerTank

  // 等待图片加载、解码完成
  await image.decode()

  // 素材元素尺寸
  const frameSize = 48

  // 返回绘制方法，后续调用时复用同一张图片
  return {
    draw(player: PlayerState): void {
      context.imageSmoothingEnabled = false

      //
      context.drawImage(
      image,
      0,                            // 使用玩家坦克图表第一列
      player.direction * frameSize, // 根据方向选择对应行
      frameSize, frameSize,         // 截取一帧
      player.x, player.y,           // 画布中的位置
      player.size, player.size,     // 显示尺寸
      )
    },
  }
}

// 初始化子弹绘制器，一次加载四张方向图
export async function createBulletRenderer(
  context: CanvasRenderingContext2D,
) {
  // 四个方向各一张图，并发加载，全部完成后才返回
  const images = await Promise.all(
    [0, 1, 2, 3].map(async (direction) => {
      const image = new Image()
      image.src = bulletAssets[direction]!
      await image.decode()
      return image
    }),
  )

  return {
    // 绘制单颗子弹：按方向选图，按坐标贴图
    draw(bullet: BulletData): void {
      context.imageSmoothingEnabled = false
      context.drawImage(
        images[bullet.direction]!,
        bullet.x,
        bullet.y,
        BULLET_SIZE,
        BULLET_SIZE,
      )
    },
  }
}
