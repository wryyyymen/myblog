import { tileAssets } from './assets'

// 对应后端 GameMapResponse 返回的数据结构
export interface GameMapData {
  id:number
  name:string
  tileSize:number
  seed:number[][]
}

// 缓存图片加载任务，同一地址只加载一次
const imageCache = new Map<string,Promise<HTMLImageElement>>()

// 加载一张图片，图片加载完成后返回图片对象
function loadImage(url:string): Promise<HTMLImageElement>{
  //获取缓存中是否有该图片的加载任务
  const cached = imageCache.get(url)

  //有缓存时返回
  if(cached) return cached

  // 没有缓存时创建新的加载任务
  //resolve(image)	宣布任务成功，并交出图片对象
  //reject(error)	宣布任务失败，并交出错误
  const task = new Promise<HTMLImageElement>((resolve,reject) =>{
    const image = new Image()

    //加载成功后resolve图片对象
    image.onload = () => resolve(image)

    image.onerror = () => {
      // 加载失败后移除缓存，允许下次重新加载
      imageCache.delete(url)
      reject(new Error(`素材加载失败：${url}`))
    }

    image.src = url
  })

  imageCache.set(url,task)
  return task
}

// 将数据库返回的地图绘制到 Canvas
export async function drawMap(
  context: CanvasRenderingContext2D,
  map: GameMapData,
): Promise<void> {
  // 保存“材质编号 → 图片对象”的对应关系
  const images = new Map<number, HTMLImageElement>()
  const tasks: Promise<void>[] = []

  // 同时加载素材配置表中的所有图片
  //Object.entries(tileAssets) 把tileAssets的键值对转换成数组
  // 例如：{1: 'brick.png', 2: 'iron.png'} → [['1', 'brick.png'], ['2', 'iron.png']]
  for (const [code, url] of Object.entries(tileAssets)) {
    tasks.push(
      loadImage(url).then((image) => {
        // 对象的键会变成字符串，因此转回数字
        images.set(Number(code), image)
      }),
    )
  }
// 等待全部图片加载完成，再开始绘制
  await Promise.all(tasks)

  // 清除上一次绘制的内容，保持像素图片清晰
  context.clearRect(0, 0, context.canvas.width, context.canvas.height)
  context.imageSmoothingEnabled = false

  // 外层遍历行，内层遍历这一行中的格子
  for (const [rowIndex, row] of map.seed.entries()) {
    for (const [columnIndex, code] of row.entries()) {
      // 编号 0 表示空地
      if (code === 0) continue

      const image = images.get(code)

      // 出现未配置的材质时，给出明确错误
      if (!image) {
        throw new Error(`地图包含未知材质编号：${code}`)
      }

      context.drawImage(
        image,
        columnIndex * map.tileSize, // 列号转换成横坐标
        rowIndex * map.tileSize,    // 行号转换成纵坐标
        map.tileSize,              // 使用数据库中的格子宽度
        map.tileSize,              // 使用数据库中的格子高度
      )
    }
  }

}
