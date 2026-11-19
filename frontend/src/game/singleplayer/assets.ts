import type { Direction } from './engine'

/** 复用 public/resources/png 素材套装，统一在开局前加载。 */
export interface SinglePlayerAssets {
  player: HTMLImageElement
  teammate: HTMLImageElement
  enemy: HTMLImageElement
  tiles: Record<number, HTMLImageElement>
  waterFrames: HTMLImageElement[]
  base: HTMLImageElement
  destroyedBase: HTMLImageElement
  bullets: Record<Direction, HTMLImageElement>
  explosion: HTMLImageElement
  shield: HTMLImageElement
}

const baseUrl = `${import.meta.env.BASE_URL}resources/png/`
let pendingAssets: Promise<SinglePlayerAssets> | null = null

async function loadImage(path: string): Promise<HTMLImageElement> {
  const image = new Image()
  image.src = baseUrl + path
  try {
    await image.decode()
  } catch {
    throw new Error('游戏素材加载失败，请刷新重试')
  }
  return image
}

async function loadAssets(): Promise<SinglePlayerAssets> {
  const [
    player,
    teammate,
    enemy,
    brick,
    steel,
    water1,
    water2,
    tree,
    ice,
    base,
    destroyedBase,
    up,
    down,
    left,
    right,
    explosion,
    shield,
  ] = await Promise.all([
    loadImage('players/tank_T2_0.png'),
    loadImage('players/tank_T1_0.png'),
    loadImage('enemies/enemy_1_3.png'),
    loadImage('map/tiles/brick.png'),
    loadImage('map/tiles/iron.png'),
    loadImage('map/tiles/river1.png'),
    loadImage('map/tiles/river2.png'),
    loadImage('map/tiles/tree.png'),
    loadImage('map/tiles/ice.png'),
    loadImage('map/base/home.png'),
    loadImage('map/base/home_destroyed.png'),
    loadImage('projectiles/bullet_up.png'),
    loadImage('projectiles/bullet_down.png'),
    loadImage('projectiles/bullet_left.png'),
    loadImage('projectiles/bullet_right.png'),
    loadImage('effects/boom_dynamic.png'),
    loadImage('effects/protect.png'),
  ])
  return {
    player,
    teammate,
    enemy,
    tiles: { 1: brick, 2: steel, 3: water1, 4: tree, 5: ice },
    waterFrames: [water1, water2],
    base,
    destroyedBase,
    bullets: { 0: up, 1: down, 2: left, 3: right },
    explosion,
    shield,
  }
}

export function loadSinglePlayerAssets(): Promise<SinglePlayerAssets> {
  if (pendingAssets !== null) return pendingAssets
  pendingAssets = loadAssets().catch((error: unknown) => {
    pendingAssets = null
    throw error
  })
  return pendingAssets
}
