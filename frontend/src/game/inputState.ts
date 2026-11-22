import type { PlayerState } from './playRenderer'

export type InputSource = 'keyboard' | 'touch'
export type InputDirection = PlayerState['direction'] | null

export interface InputSnapshot {
  direction: InputDirection
  fireHeld: boolean
}

/** 分开保存键盘和触屏，松开一种输入不会清掉另一种仍按住的输入。 */
export class GameInputState {
  private readonly keyboard: InputSnapshot = { direction: null, fireHeld: false }
  private readonly touch: InputSnapshot = { direction: null, fireHeld: false }

  setDirection(source: InputSource, direction: InputDirection): void {
    this.getSourceState(source).direction = direction
  }

  setFire(source: InputSource, held: boolean): void {
    this.getSourceState(source).fireHeld = held
  }

  clear(source: InputSource): void {
    const state = this.getSourceState(source)
    state.direction = null
    state.fireHeld = false
  }

  clearAll(): void {
    this.clear('keyboard')
    this.clear('touch')
  }

  read(): InputSnapshot {
    let direction = this.keyboard.direction
    if (this.touch.direction !== null) direction = this.touch.direction
    return { direction, fireHeld: this.keyboard.fireHeld || this.touch.fireHeld }
  }

  private getSourceState(source: InputSource): InputSnapshot {
    if (source === 'keyboard') return this.keyboard
    return this.touch
  }
}
