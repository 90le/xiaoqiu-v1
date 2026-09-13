<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { call, outText } from '../api.js'
import { vs, vsIgnite } from '../voiceSession.js'
import QiuLogo from '../components/QiuLogo.vue'

/* ═══ 状态 ═══ */
const bat = ref(null), net = ref(null), env = ref(null), wake = ref(null), notifs = ref([])
const macroN = ref(null), memN = ref(null), vdAlive = ref(false)
const shotPath = ref('')
let timer = null

async function refresh() {
  call('battery_status', {}).then(r => { if (r.ok) bat.value = r.data })
  call('network_info', {}).then(r => { if (r.ok) net.value = r.data })
  call('env_status', {}).then(r => { if (r.ok) env.value = r.data })
  call('wake_service', { action: 'status' }).then(r => { if (r.ok) wake.value = r.data })
  call('vd', { action: 'info' }).then(r => { vdAlive.value = !!(r.ok && r.data?.alive) })
  call('notify_read', { limit: 3 }).then(r => {
    if (r.ok) notifs.value = (Array.isArray(r.data) ? r.data : r.data?.items || []).slice(0, 3)
  })
  call('macro_list', {}).then(r => { if (r.ok) macroN.value = (Array.isArray(r.data) ? r.data : r.data?.items || []).length })
  call('memory_list', {}).then(r => { if (r.ok) memN.value = (Array.isArray(r.data) ? r.data : r.data?.items || []).length })
}

/* 问候 */
const greet = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const dateStr = computed(() => {
  const d = new Date()
  const w = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getMonth() + 1}月${d.getDate()}日 周${w}`
})
const batPct = computed(() => bat.value ? (bat.value.percent ?? bat.value.level) : null)
const batChg = computed(() => !!(bat.value && (bat.value.charging === true || bat.value.status === 'charging')))
const netLab = computed(() => {
  const n = net.value
  if (!n) return ''
  if (n.airplane) return '✈️'
  if (n.online === false) return '📵'
  return (n.wifi || n.wifi_on || n.ssid) ? '📶' : '📡'
})
const wakeOn = computed(() => !!(wake.value?.running || wake.value?.on))

/* 快捷动作 */
const busy = ref('')
const actOut = ref('')
async function act(icon, tool, args) {
  busy.value = icon
  const r = await call(tool, args || {})
  busy.value = ''
  if (tool === 'screenshot' && r.ok) {
    actOut.value = '📸 已截图'
    if (r.data?.png || r.data?.path || r.data?.file) shotPath.value = String(r.data.png || r.data.path || r.data.file)
  } else if (tool === 'voice_digest' && r.ok) {
    actOut.value = '🗣 ' + (r.data?.digest || '').slice(0, 120)
  } else if (tool === 'flashlight') {
    actOut.value = icon + ' 完成'
  } else {
    actOut.value = icon + ' ' + outText(r).slice(0, 150)
  }
  setTimeout(() => actOut.value = '', 5000)
}
function go(hash) { location.hash = '#' + hash }

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 30000)
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh() }) // M3: 回到前台刷新
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <!-- 问候区 -->
  <div class="hero">
    <QiuLogo :size="46" />
    <div class="hero-txt">
      <div class="hero-g">{{ greet }}</div>
      <div class="hero-d">{{ dateStr }}</div>
    </div>
    <!-- 状态 chips -->
    <div class="chips">
      <span class="chip" :class="{ chg: batChg }">🔋{{ batPct ?? '—' }}%</span>
      <span class="chip">{{ netLab }}</span>
      <span class="chip" :class="{ ok: env?.ready }">{{ env?.ready ? '🧠 就绪' : '🧠…' }}</span>
      <span class="chip" :class="{ ok: wakeOn }">{{ wakeOn ? '👂 待命' : '👂 关' }}</span>
    </div>
  </div>

  <!-- 一键语音 -->
  <div class="voicebar tap" :class="{ on: vs.state !== 'off' }" @click="vsIgnite('mic')">
    <span class="vmic">{{ vs.state === 'off' ? '🎙' : '⏹' }}</span>
    <span class="vtxt">{{ vs.state === 'off' ? '和小丘说话（连续对话）' :
      vs.state === 'listening' ? '🎤 请说…' :
      vs.state === 'thinking' ? '🤔 想想…' :
      vs.state === 'executing' ? '⚙️ 执行中…' : '💬 回答中' }}</span>
    <span v-if="vs.state !== 'off'" class="vdot"></span>
  </div>

  <!-- 快捷动作 -->
  <div class="acts">
    <div class="act tap" @click="busy !== '📸' && act('📸', 'screenshot')">
      <span class="ai">{{ busy === '📸' ? '⟳' : '📸' }}</span><span class="at">截屏</span></div>
    <div class="act tap" @click="busy !== '🗣' && act('🗣', 'voice_digest')">
      <span class="ai">{{ busy === '🗣' ? '⟳' : '🗣' }}</span><span class="at">错过的事</span></div>
    <div class="act tap" @click="act('🔦', 'flashlight', { on: true })">
      <span class="ai">🔦</span><span class="at">手电筒</span></div>
    <div class="act tap" @click="go('terminal')">
      <span class="ai">💻</span><span class="at">终端</span></div>
  </div>
  <div v-if="actOut" class="actout">{{ actOut }}</div>

  <!-- 速览三卡 -->
  <div class="secl">速览</div>
  <div class="grp-card">
    <div class="row tap" @click="go('broadcast')">
      <span class="ric">📰</span>
      <span class="rt"><span class="rt1">最新通知</span>
        <span class="rt2" v-if="notifs.length">{{ notifs[0].title || notifs[0].pkg }}：{{ (notifs[0].text || '').slice(0, 24) }}</span>
        <span class="rt2" v-else>暂无新通知</span></span>
      <span class="ar">›</span>
    </div>
    <div class="row tap" @click="go('macros')">
      <span class="ric">⚡</span>
      <span class="rt"><span class="rt1">自动化</span>
        <span class="rt2">{{ macroN ?? '…' }} 个宏待命</span></span>
      <span class="ar">›</span>
    </div>
    <div class="row tap" @click="go('memory')">
      <span class="ric">🧠</span>
      <span class="rt"><span class="rt1">记忆</span>
        <span class="rt2">{{ memN ?? '…' }} 条 · 越来越懂你</span></span>
      <span class="ar">›</span>
    </div>
    <div class="row tap" @click="go('device')">
      <span class="ric">🖥</span>
      <span class="rt"><span class="rt1">副屏</span>
        <span class="rt2">{{ vdAlive ? '运行中，可后台操控' : '未创建' }}</span></span>
      <span class="ar">›</span>
    </div>
  </div>
  <div style="height:16px;"></div>
</template>

<style scoped>
.hero { display: flex; align-items: center; gap: 12px; margin: 10px 4px 12px; flex-wrap: wrap; }
.hero-txt { flex: 1; min-width: 100px; }
.hero-g { font-size: 22px; font-weight: 800; letter-spacing: .5px; }
.hero-d { font-size: 12px; color: var(--muted); margin-top: 2px; }
.chips { display: flex; gap: 6px; flex-wrap: wrap; }
.chip { font-size: 11px; padding: 4px 9px; border-radius: 99px; background: var(--card); border: 1px solid var(--line); color: var(--muted); }
.chip.ok { background: var(--hill-soft); color: var(--hill); border-color: var(--hill); font-weight: 700; }
.chip.chg { background: #F4FAF6; color: var(--hill); border-color: var(--hill); }
.voicebar { display: flex; align-items: center; gap: 12px; background: var(--card); border: 1.5px solid var(--line); border-radius: 18px; padding: 14px 16px; margin-bottom: 12px; box-shadow: var(--shadow); }
.voicebar.on { border-color: var(--hill); background: #F4FAF6; }
.vmic { font-size: 24px; }
.vtxt { flex: 1; font-size: 15px; font-weight: 700; }
.vdot { width: 9px; height: 9px; border-radius: 50%; background: var(--hill); animation: blink 1.2s infinite; }
@keyframes blink { 50% { opacity: .25; } }
.acts { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 8px; }
.act { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 12px 4px; display: flex; flex-direction: column; align-items: center; gap: 5px; box-shadow: var(--shadow); }
.act:active { background: var(--bg); }
.ai { font-size: 21px; }
.at { font-size: 12px; font-weight: 600; color: var(--muted); }
.actout { font-size: 12px; color: var(--muted); background: var(--card); border: 1px dashed var(--line); border-radius: 10px; padding: 8px 12px; margin-bottom: 6px; white-space: pre-wrap; word-break: break-all; }
.secl { margin: 12px 4px 7px; font-size: 12px; font-weight: 700; color: var(--muted); letter-spacing: 2px; }
.grp-card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; overflow: hidden; box-shadow: var(--shadow); }
.row { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border-bottom: 1px solid var(--line); }
.row:last-child { border-bottom: 0; }
.row:active { background: var(--bg); }
.ric { font-size: 17px; width: 34px; height: 34px; display: flex; align-items: center; justify-content: center; background: var(--hill-soft); border-radius: 10px; }
.rt { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.rt1 { font-size: 14px; font-weight: 700; }
.rt2 { font-size: 12px; color: var(--muted); margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ar { color: #B9B4A6; font-size: 18px; }
</style>
