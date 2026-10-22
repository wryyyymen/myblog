import { gameAssets } from './assets'

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
