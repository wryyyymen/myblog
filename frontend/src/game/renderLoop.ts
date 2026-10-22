export function startRenderLoop(render:()=>void): () => void {
  // 目标绘制频率；每帧间隔约为 16.67 毫秒
  const frameInterval = 1000 / 60

  // 允许少量时间误差，避免边界附近误跳过一帧
  const tolerance = 0.5

  // 保存动画请求编号，用于取消下一次回调
  let frameId: number | null = null

  // 保存绘制时间基准
  let lastTime: number | null = null

  // 标记循环是否已经停止
  let stopped = false

  function frame(timestamp: number): void {
    if (stopped) return

    if (lastTime === null) {
      // 第一次回调直接绘制
      lastTime = timestamp
      render()
    } else {
      // timestamp 是浏览器提供的时间，单位为毫秒
      const elapsed = timestamp - lastTime

      if (elapsed + tolerance >= frameInterval) {
        // 按完整帧间隔推进时间，避免误差持续累计
        // 卡顿时跳过过期画面，每次回调只绘制一次
        const passedFrames = Math.floor(
          (elapsed + tolerance) / frameInterval,
        )
        lastTime += passedFrames * frameInterval

        render()
      }
    }

    // requestAnimationFrame 只调用一次，需要继续申请下一次
    if (!stopped) {
      frameId = requestAnimationFrame(frame)
    }
  }

  // 启动第一次回调
  frameId = requestAnimationFrame(frame)

  return () => {
    // 停止后，即使回调被执行，也不会继续绘制
    stopped = true

    if (frameId !== null) {
      cancelAnimationFrame(frameId)
      frameId = null
    }
  }
}
