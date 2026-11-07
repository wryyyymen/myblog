// 结算数据与展示转换。
//
// 对应类图里的两个类：
//   GameOver  —— 服务端发来的原始结果，只描述事实（谁赢、打了多久、为什么结束）
//   ResultView —— 界面能直接渲染的数据（标题、几行统计、能不能重开）
//
// 两者分开的理由：判定胜负是服务端的职责，前端只做格式转换，
// 不做二次判定。这样两边屏幕上的结果永远一致。

// 本地玩家的标识。联机后由服务端下发真实 userId，这里先占位。
export const LOCAL_PLAYER_ID = 'local'

// 一局的个人统计
export interface PlayerStat {
  // 玩家标识，和 winnerId 用同一套
  playerId: string
  // 命中对方的次数
  hits: number
  // 累计造成的伤害值
  damage: number
}

// 对局结束的原因
export type EndReason = 'destroyed' | 'surrender' | 'disconnect' | 'draw'

// 服务端 game_over 消息的内容（类图 GameOver）
export interface GameOverData {
  // 获胜者标识；null 表示平局
  winnerId: string | null
  // 双方统计
  stats: PlayerStat[]
  // 本局用时，单位秒
  durationSec: number
  // 结束原因
  reason: EndReason
}

// 界面渲染用的视图数据（类图 ResultView）
export interface ResultViewModel {
  // win / lose / draw，用于决定标题颜色
  outcome: 'win' | 'lose' | 'draw'
  // 主标题，如「胜利」
  title: string
  // 副标题，说明怎么赢的或为什么结束
  detail: string
  // 统计行，直接渲染成「标签 — 数值」
  rows: Array<{ label: string; value: string }>
}

/**
 * 把服务端结果转成界面数据。
 *
 * 只做格式转换，不重新判定胜负——胜负以服务端为准。
 * 这一条对应验收标准 AC-9：胜负与服务端 winnerId 一致。
 *
 * @param data 服务端发来的结果
 * @param localPlayerId 本地玩家标识
 */
export function toResultView(
  data: GameOverData,
  localPlayerId = LOCAL_PLAYER_ID,
): ResultViewModel {
  const outcome: ResultViewModel['outcome'] =
    data.winnerId === null
      ? 'draw'
      : data.winnerId === localPlayerId
        ? 'win'
        : 'lose'

  const title = outcome === 'win' ? '胜利' : outcome === 'lose' ? '失败' : '平局'

  // 结束原因说明。用中文短句，不出现内部代码
  const detailMap: Record<EndReason, string> = {
    destroyed: outcome === 'win' ? '对方坦克被击毁' : '你的坦克被击毁',
    surrender: outcome === 'win' ? '对方主动认输' : '你主动认输',
    disconnect: '对方已离开，本局结束',
    draw: '双方同时被击毁',
  }

  // 自己的数据，找不到时用零值兜底
  const mine = data.stats.find((item) => item.playerId === localPlayerId)

  const rows = [
    { label: '用时', value: formatDuration(data.durationSec) },
    { label: '命中', value: `${mine?.hits ?? 0} 次` },
    { label: '伤害', value: `${mine?.damage ?? 0}` },
  ]

  // 断线结束的局不计胜负，额外提示一句
  if (data.reason === 'disconnect') {
    rows.push({ label: '说明', value: '本局不计胜负' })
  }

  return { outcome, title, detail: detailMap[data.reason], rows }
}

/** 把秒数转成「1 分 23 秒」这种可读形式。 */
function formatDuration(totalSeconds: number): string {
  const safe = Math.max(0, Math.round(totalSeconds))
  const minutes = Math.floor(safe / 60)
  const seconds = safe % 60
  return minutes > 0 ? `${minutes} 分 ${seconds} 秒` : `${seconds} 秒`
}

// 错误码到中文提示的映射。
// 对应验收标准 AC-11：界面不得出现 code、异常堆栈等技术字样。
export const ERROR_MESSAGES: Record<string, string> = {
  ROOM_FULL: '房间已满，请换一个房间',
  ROOM_NOT_FOUND: '房间不存在，请核对房间号',
  NOT_ROOM_OWNER: '只有房主可以开始对局',
  NOT_READY: '双方都准备后才能开始',
  SESSION_EXPIRED: '登录已失效，请重新登录',
  STALE_MATCH: '本局已结束，请重新开始',
  BUSY: '服务器繁忙，请稍后重试',
  NETWORK: '网络连接中断，请检查网络后重试',
}

/**
 * 把错误码转成能直接给用户看的中文。
 *
 * 认不出的错误码一律返回通用提示，不把原始 code 暴露到界面上。
 */
export function toErrorMessage(code?: string): string {
  if (!code) return '操作失败，请稍后重试'
  return ERROR_MESSAGES[code] ?? '操作失败，请稍后重试'
}
