<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import GameAudioControls from '@/components/GameAudioControls.vue'
import { unlockGameAudio } from '@/game/audio/gameAudio'
import { useAuthStore } from '@/stores/auth'
import { loadSinglePlayerAssets } from '@/game/singleplayer/assets'
import {
  createSinglePlayerGame,
  startSinglePlayerGame,
  stepSinglePlayerGame,
} from '@/game/singleplayer/engine'
import { drawSinglePlayer } from '@/game/singleplayer/renderer'

const auth = useAuthStore()
const showHelp = ref(false)
const previewRef = ref<HTMLCanvasElement | null>(null)
const previewReady = ref(false)
const previewFailed = ref(false)
const resourceBase = `${import.meta.env.BASE_URL}resources/png/`
let disposed = false

onMounted(async () => {
  try {
    const assets = await loadSinglePlayerAssets()
    if (disposed) return
    const context = previewRef.value?.getContext('2d')
    if (!context) return
    const scene = createSinglePlayerGame(42)
    startSinglePlayerGame(scene)
    for (let frame = 0; frame < 160; frame++)
      stepSinglePlayerGame(scene, { direction: null, fireHeld: false })
    drawSinglePlayer(context, scene, assets)
    previewReady.value = true
  } catch {
    if (!disposed) previewFailed.value = true
  }
})
onUnmounted(() => {
  disposed = true
})
</script>

<template>
  <main class="page-container game-menu-page">
    <div class="menu-breadcrumb">
      <RouterLink to="/">我的博客</RouterLink><span>/</span><span>游戏中心</span>
    </div>
    <section class="menu-machine" aria-labelledby="game-menu-title">
      <div class="machine-top">
        <span class="machine-led"></span><span>CLASSIC ARCADE</span
        ><span class="machine-edition">TANK BATTLE · 01</span>
      </div>
      <div class="menu-screen">
        <div class="menu-content">
          <span class="screen-eyebrow">经典坦克大战</span>
          <img
            class="game-logo"
            :src="`${resourceBase}ui/logo.png`"
            alt="Tank Stars"
            width="600"
            height="96"
          />
          <h1 id="game-menu-title">选择你的战场</h1>
          <p class="menu-intro">独自迎战敌军，或邀请朋友一起守住基地。</p>
          <nav class="mode-list" aria-label="游戏模式" @click="unlockGameAudio">
            <RouterLink class="mode-option solo-option" :to="{ name: 'game-single' }">
              <span
                class="mode-icon solo-icon"
                :style="{ backgroundImage: 'url(' + resourceBase + 'players/tank_T2_0.png)' }"
                aria-hidden="true"
              ></span>
              <span class="mode-copy"
                ><span class="mode-kicker">1 PLAYER · PVE</span><strong>单人闯关</strong
                ><span class="mode-caption">对抗 AI · 保卫基地 · 无需登录</span></span
              >
              <span class="mode-arrow" aria-hidden="true">→</span>
            </RouterLink>
            <RouterLink class="mode-option online-option" :to="{ name: 'game-online' }">
              <span
                class="mode-icon online-icon"
                :style="{ backgroundImage: 'url(' + resourceBase + 'players/tank_T1_0.png)' }"
                aria-hidden="true"
              ></span>
              <span class="mode-copy"
                ><span class="mode-kicker">2 PLAYERS · CO-OP</span><strong>双人合作</strong
                ><span class="mode-caption">邀请朋友 · 共同迎敌 · 保卫基地</span></span
              >
              <span class="mode-arrow" aria-hidden="true">→</span>
            </RouterLink>
          </nav>
          <p class="menu-account" role="status">
            <template v-if="auth.loading">正在检查登录状态……</template>
            <template v-else-if="auth.user"
              >已登录为 {{ auth.user.username }}，可以直接进入联机大厅。</template
            >
            <template v-else>单人模式即开即玩；联机模式使用博客账号登录。</template>
          </p>
          <GameAudioControls class="menu-audio" />
          <button
            class="help-toggle"
            type="button"
            :aria-expanded="showHelp"
            aria-controls="game-help"
            @click="showHelp = !showHelp"
          >
            {{ showHelp ? '收起操作说明' : '查看操作说明' }}
            <span aria-hidden="true">{{ showHelp ? '−' : '+' }}</span>
          </button>
          <div v-if="showHelp" id="game-help" class="game-help">
            <p><kbd>WASD</kbd> / <kbd>方向键</kbd> 移动 · <kbd>空格</kbd> 持续开火</p>
            <p>单人：击退 8 辆敌军并保护基地，<kbd>P</kbd> 暂停、<kbd>R</kbd> 重开。</p>
            <p>
              合作：双方登录不同账号，分享房间号并准备。共同消灭 8
              辆敌军、守住基地；队友之间不会互相伤害。
            </p>
          </div>
        </div>
        <div class="menu-preview" aria-label="游戏画面预览">
          <div class="preview-caption"><span>STAGE 01</span><span>READY TO PLAY</span></div>
          <div class="preview-frame">
            <canvas
              ref="previewRef"
              width="624"
              height="624"
              aria-label="使用游戏素材绘制的坦克战场预览"
            ></canvas>
            <div v-if="!previewReady" class="preview-loading">
              {{ previewFailed ? '选择模式开始游戏' : '正在装载战场……' }}
            </div>
          </div>
          <p class="preview-note">砖墙可以击碎，钢墙无法摧毁。记得留意自己的血量。</p>
        </div>
      </div>
      <div class="machine-bottom">
        <span>WASD / ↑ ↓ ← →</span><span>SPACE TO FIRE</span><span>SELECT YOUR MODE</span>
      </div>
    </section>
  </main>
</template>

<style scoped>
.game-menu-page {
  max-width: 1160px;
}
.menu-breadcrumb {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 22px;
  color: var(--color-muted);
  font-size: 12px;
}
.menu-breadcrumb a {
  color: inherit;
  text-decoration: none;
}
.menu-machine {
  padding: 18px;
  background: #17231e;
  border: 1px solid #31443a;
  border-radius: 20px;
  box-shadow: 0 18px 50px #11261b1c;
}
.machine-top,
.machine-bottom {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 6px 20px;
  color: #a9beaf;
  font:
    11px Consolas,
    monospace;
  letter-spacing: 1.5px;
}
.machine-led {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #9bbf63;
  box-shadow: 0 0 9px #8dbb6480;
}
.machine-edition {
  margin-left: auto;
  color: #7d9685;
}
.menu-screen {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: 42px;
  padding: 40px;
  background: radial-gradient(ellipse at 85% 25%, #1c2c1e 0, #0e160f 60%);
  border: 1px solid #526346;
  border-radius: 9px;
}
.menu-content {
  min-width: 0;
}
.screen-eyebrow {
  color: #b2c495;
  font-size: 12px;
  letter-spacing: 4px;
}
.game-logo {
  display: block;
  width: 100%;
  max-width: 420px;
  height: auto;
  margin: 20px 0 25px;
  image-rendering: pixelated;
}
.menu-content h1 {
  margin: 0 0 10px;
  color: #edf1df;
  font-size: clamp(26px, 3.5vw, 34px);
}
.menu-intro {
  color: #9faf9d;
  font-size: 13px;
  line-height: 1.8;
}
.mode-list {
  display: grid;
  gap: 14px;
  margin-top: 28px;
}
.mode-option {
  display: flex;
  align-items: center;
  gap: 17px;
  padding: 19px;
  border: 1px solid #4f6947;
  border-radius: 9px;
  background: #203321;
  color: #f1f5e6;
  text-decoration: none;
  transition:
    background 0.15s,
    border-color 0.15s,
    transform 0.15s;
}
.mode-option:hover {
  background: #2b432a;
  border-color: #9db47a;
  transform: translateX(3px);
}
.mode-option:focus-visible {
  outline: 2px solid #d5dfab;
  outline-offset: 4px;
}
.online-option {
  background: #30291e;
  border-color: #675637;
}
.online-option:hover {
  background: #453622;
  border-color: #c9a862;
}
.mode-icon {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  image-rendering: pixelated;
  background-size: 76px 152px;
  background-position: 0 0;
}
.mode-copy {
  display: grid;
  gap: 5px;
}
.mode-kicker {
  color: #b5cb98;
  font:
    10px Consolas,
    monospace;
  letter-spacing: 1px;
}
.online-option .mode-kicker {
  color: #e0c98b;
}
.mode-copy strong {
  font-size: 21px;
  font-weight: 650;
}
.mode-caption {
  color: #a9b7a2;
  font-size: 11px;
}
.online-option .mode-caption {
  color: #c0b49a;
}
.mode-arrow {
  margin-left: auto;
  color: #cfdda9;
  font-size: 25px;
}
.menu-account {
  color: #a6b69e;
  font-size: 11px;
  line-height: 1.8;
  margin: 20px 0 15px;
}
.help-toggle {
  display: flex;
  align-items: center;
  gap: 14px;
  background: none;
  border: 0;
  color: #c4d1af;
  padding: 5px 0;
  font-size: 12px;
  cursor: pointer;
}
.game-help {
  color: #a6b69e;
  font-size: 11px;
  line-height: 1.9;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #334232;
}
.game-help p {
  margin: 8px 0;
}
kbd {
  font:
    10px Consolas,
    monospace;
  color: #dfebc8;
  border: 1px solid #576749;
  border-radius: 3px;
  padding: 2px 4px;
}
.menu-preview {
  align-self: center;
  min-width: 0;
}
.preview-caption {
  display: flex;
  justify-content: space-between;
  color: #b1c29b;
  font:
    10px Consolas,
    monospace;
  letter-spacing: 1px;
  margin-bottom: 12px;
}
.preview-frame {
  position: relative;
  padding: 6px;
  border: 1px solid #536844;
  background: #111710;
  box-shadow: 0 8px 28px #0004;
}
.preview-frame canvas {
  display: block;
  width: 100%;
  height: auto;
  image-rendering: pixelated;
}
.preview-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #a6b69e;
  font-size: 12px;
}
.preview-note {
  margin: 14px 0 0;
  color: #80947e;
  font-size: 11px;
  line-height: 1.8;
}
.machine-bottom {
  justify-content: space-between;
  padding: 19px 6px 2px;
  color: #7d9685;
  font-size: 10px;
}
@media (max-width: 900px) {
  .menu-screen {
    gap: 25px;
    padding: 26px;
  }
  .mode-option {
    padding: 16px;
  }
  .mode-icon {
    display: none;
  }
}
@media (max-width: 650px) {
  .menu-screen {
    grid-template-columns: 1fr;
    padding: 24px 20px;
  }
  .menu-machine {
    padding: 11px;
  }
  .menu-preview {
    max-width: 350px;
    width: 100%;
    margin: 4px auto;
  }
  .mode-icon {
    display: block;
  }
  .machine-edition {
    font-size: 9px;
    letter-spacing: 0;
  }
  .machine-top {
    font-size: 10px;
    padding-bottom: 15px;
  }
  .machine-bottom {
    font-size: 8px;
    letter-spacing: 0.3px;
  }
}
</style>
