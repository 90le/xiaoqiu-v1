<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { call, outText } from '../api.js'

/* ═══ 子页导航 ═══ */
const page = ref(null)
const pages = { apps: '应用管理', vd: '副屏控制台', sensor: '传感器' }
let subPushed = false
function openPage(p) { page.value = p; try { history.pushState({ sub: p }, ''); subPushed = true } catch { subPushed = false } }
function onPop() { if (page.value) { page.value = null; subPushed = false } }
function back() { if (subPushed) { try { history.back(); return } catch {} } page.value = null; subPushed = false }

/* ═══ 总览数据 ═══ */
const bat = ref(null), dev = ref(null), net = ref(null), vol = ref(null), screen = ref(null)
const sysState = ref({})
const flashOn = ref(false)
const bright = ref(null), brightAuto = ref(false)
const out = ref('')
let timer = null

async function loadAll() {
  const jobs = [
    call('battery_status', {}).then(r => { if (r.ok) bat.value = r.data }),
    call('device_info', {}).then(r => { if (r.ok) dev.value = r.data }),
    call('network_info', {}).then(r => { if (r.ok) net.value = r.data }),
    call('volume_get', {}).then(r => { if (r.ok) vol.value = r.data }),
    call('screen_state', {}).then(r => { if (r.ok) screen.value = r.data }),
    call('sysctl', { action: 'read' }).then(r => {
      if (r.ok && typeof r.data === 'object') {
        sysState.value = {
          wifi: r.data.wifi === '1' || r.data.wifi === true,
          bluetooth: r.data.bluetooth === '1' || r.data.bluetooth === true,
          dnd: r.data.dnd === '1' || r.data.dnd === true,
          rotate: r.data.rotate === '1' || r.data.rotate === true,
        }
        brightAuto.value = r.data.brightness_auto === '1' || r.data.brightness_auto === true
      }
    }),
  ]
  await Promise.allSettled(jobs)
}
function flash(t) { out.value = t; setTimeout(() => out.value = '', 1600) }

const batPct = computed(() => {
  const v = bat.value
  if (!v) return null
  return v.percent ?? v.level ?? v.battery_pct ?? null
})
const batCharging = computed(() => {
  const v = bat.value
  return !!(v && (v.charging === true || v.charging === 'true' || v.status === 'charging' || v.plugged))
})
const storage = computed(() => {
  const d = dev.value
  if (!d) return null
  const free = d.storage_free_mb ?? d.free_mb, total = d.storage_total_mb ?? d.total_mb
  if (free && total) return { freeGB: (free / 1024).toFixed(1), totalGB: (total / 1024).toFixed(1), pct: Math.round((1 - free / total) * 100) }
  return null
})
const netName = computed(() => {
  const n = net.value
  if (!n) return '—'
  if (n.wifi_on === false && n.airplane === true) return '✈️ 飞行模式'
  if (n.online === false) return '📵 离线'
  if (n.wifi === true || n.wifi_on === true || n.ssid) return '📶 ' + (n.ssid || 'Wi-Fi')
  if (n.mobile === true || n.mobile_data === true) return '📡 移动网络'
  return '📶 网络'
})

/* ── 快捷开关 ── */
async function toggleSys(k, label) {
  const cur = sysState.value[k]
  const r = await call('sysctl', { action: k, on: !cur })
  flash((r.ok ? '✅ ' : '❌ ') + label + '已' + (!cur ? '开' : '关'))
  await loadAll()
}
async function toggleFlash() {
  flashOn.value = !flashOn.value
  await call('flashlight', { on: flashOn.value })
  if (!flashOn.value) flash('🔦 已关')
}
async function toggleBrightAuto() {
  const r = await call('sysctl', { action: 'brightness_auto', on: !brightAuto.value })
  flash(r.ok ? '✅ 自动亮度已' + (!brightAuto.value ? '开' : '关') : '❌ 失败')
  await loadAll()
}
async function applyBright() {
  const r = await call('brightness_set', { value: Number(bright.value) })
  if (!r.ok) flash('❌ ' + outText(r))
}
async function setVol(stream, v) {
  await call('volume_set', { stream, percent: Number(v) })
}

/* ═══ 应用管理（字符串解析版）═══ */
const apps = ref([])
const appKw = ref('')
const appKwTimer = ref(null)
async function loadApps() {
  const r = await call('apps_list', { filter: appKw.value || '', limit: 200 })
  const raw = r.ok ? (Array.isArray(r.data) ? r.data : r.data?.items || []) : []
  apps.value = raw.map(it => {
    if (typeof it === 'string') { const m = it.match(/^(.*?)\s*\((.+)\)\s*$/); return m ? { label: m[1], pkg: m[2] } : { label: it, pkg: it } }
    return { label: it.label || it.name, pkg: it.pkg || it.package }
  })
}
function onAppKw() { if (appKwTimer.value) clearTimeout(appKwTimer.value); appKwTimer.value = setTimeout(loadApps, 300) }
async function appOp(pkg, op) {
  const r = op === 'launch'
    ? await call('apps_launch', { pkg })
    : await call('l2_exec', { cmd: `am force-stop ${pkg}` })
  flash((r.ok ? '✅ ' : '❌ ') + pkg.slice(pkg.lastIndexOf('.') + 1) + (op === 'launch' ? ' 已启动' : ' 已强停'))
}

/* ═══ 副屏控制台 ═══ */
const vd = ref({ alive: false })
const vdLaunch = ref('')
const imgData = ref('')
const tapX = ref(500), tapY = ref(500)
const vdText = ref('')
const busy = ref(false)
async function refreshVd() {
  const r = await call('vd', { action: 'info' })
  vd.value = r.ok && r.data?.alive ? r.data : { alive: false }
}
async function vdOp(action, extra = {}) {
  busy.value = true
  const r = await call('vd', { action, ...extra })
  flash(outText(r).slice(0, 60))
  busy.value = false
  await refreshVd()
}
async function shot() {
  const r = await call('vd', { action: 'shot' })
  if (r.ok && r.data?.png_b64) { imgData.value = 'data:image/png;base64,' + r.data.png_b64 }
  else flash('截图失败')
}
async function vdtap() { await call('vd', { action: 'tap', x: Number(tapX.value), y: Number(tapY.value) }); setTimeout(shot, 500) }
async function vdinput() { if (!vdText.value) return; await call('vd', { action: 'text', text: vdText.value }); setTimeout(shot, 500) }

/* ═══ 传感器 ═══ */
const sensors = ref(null)
async function loadSensors() { const r = await call('sensors_read', {}); if (r.ok) sensors.value = r.data }

onMounted(() => {
  window.addEventListener('popstate', onPop)
  loadAll(); loadApps(); refreshVd()
  timer = setInterval(loadAll, 30000)
})
onUnmounted(() => { window.removeEventListener('popstate', onPop); if (timer) clearInterval(timer); if (subPushed) { try { history.back() } catch {} } })
</script>

<template>
  <!-- ═══ 首页：设备总览 ═══ -->
  <div v-show="!page">
    <div class="h1">设备</div>
    <div class="sub">小丘看到的这部手机</div>

    <!-- 状态行 -->
    <div class="strow3">
      <div class="stcard" :class="{ chg: batCharging }">
        <div class="stbig">{{ batPct != null ? batPct + '%' : '—' }}</div>
        <div class="stlab">{{ batCharging ? '⚡充电中' : '🔋 电量' }}<template v-if="bat?.temperature"> · {{ bat.temperature }}°</template></div>
      </div>
      <div class="stcard">
        <div class="stbig">{{ netName }}</div>
        <div class="stlab">{{ screen?.state === 'on' || screen?.screen === 'on' ? '💡 屏幕亮' : '🌑 屏幕灭' }}</div>
      </div>
      <div class="stcard">
        <div class="stbig">{{ storage ? storage.pct + '%' : '—' }}</div>
        <div class="stlab">📦 存储<template v-if="storage"> · 剩 {{ storage.freeGB }}G</template></div>
      </div>
    </div>

    <!-- 快捷开关 -->
    <div class="secl">快捷开关</div>
    <div class="grp-card">
      <div class="srow">
        <div class="srow-txt"><div class="st">📶 Wi-Fi</div></div>
        <div :class="['sw', 'tap', { on: sysState.wifi }]" @click="toggleSys('wifi', 'Wi-Fi ')"><div class="knob"></div></div>
      </div>
      <div class="srow">
        <div class="srow-txt"><div class="st">🔵 蓝牙</div></div>
        <div :class="['sw', 'tap', { on: sysState.bluetooth }]" @click="toggleSys('bluetooth', '蓝牙 ')"><div class="knob"></div></div>
      </div>
      <div class="srow">
        <div class="srow-txt"><div class="st">🌙 勿扰</div></div>
        <div :class="['sw', 'tap', { on: sysState.dnd }]" @click="toggleSys('dnd', '勿扰 ')"><div class="knob"></div></div>
      </div>
      <div class="srow">
        <div class="srow-txt"><div class="st">🔄 自动旋转</div></div>
        <div :class="['sw', 'tap', { on: sysState.rotate }]" @click="toggleSys('rotate', '旋转 ')"><div class="knob"></div></div>
      </div>
      <div class="srow">
        <div class="srow-txt"><div class="st">☀️ 自动亮度</div></div>
        <div :class="['sw', 'tap', { on: brightAuto }]" @click="toggleBrightAuto"><div class="knob"></div></div>
      </div>
      <div class="srow">
        <div class="srow-txt"><div class="st">🔦 手电筒</div></div>
        <div :class="['sw', 'tap', { on: flashOn }]" @click="toggleFlash"><div class="knob"></div></div>
      </div>
    </div>

    <!-- 亮度/音量 -->
    <div class="secl">亮度与音量</div>
    <div class="card">
      <div class="sldrow"><span class="sldlab">亮度</span><input type="range" min="10" max="255" v-model.number="bright" @change="applyBright" :disabled="brightAuto"><span class="sldv">{{ bright ?? '—' }}</span></div>
      <div v-for="s in ['media', 'ring', 'alarm']" :key="s" class="sldrow">
        <span class="sldlab">{{ { media: '媒体', ring: '铃声', alarm: '闹钟' }[s] }}</span>
        <input type="range" min="0" max="100" :value="vol?.[s] ?? vol?.[s + '_pct'] ?? 50" @change="e => setVol(s, e.target.value)">
        <span class="sldv">{{ vol?.[s] ?? vol?.[s + '_pct'] ?? '—' }}</span>
      </div>
    </div>

    <!-- 入口 -->
    <div class="secl">更多</div>
    <div class="grp-card">
      <div class="grp tap" @click="openPage('apps')"><span class="ic">📱</span>
        <span class="gt"><span class="tt">应用管理</span><span class="ts">搜索 · 启动 · 强停（{{ apps.length }} 个）</span></span><span class="ar">›</span></div>
      <div class="grp tap" @click="openPage('vd')"><span class="ic">🖥</span>
        <span class="gt"><span class="tt">副屏控制台</span><span class="ts">{{ vd.alive ? '运行中 #' + vd.displayId : '未创建' }}</span></span><span class="ar">›</span></div>
      <div class="grp tap" @click="openPage('sensor'); loadSensors()"><span class="ic">🧭</span>
        <span class="gt"><span class="tt">传感器</span><span class="ts">光照 · 接近 · 加速度</span></span><span class="ar">›</span></div>
    </div>
    <div style="height:16px;"></div>
  </div>

  <!-- ═══ 子页 ═══ -->
  <div v-if="page" class="subp">
    <div class="subbar">
      <button class="backb tap" @click="back">‹</button>
      <span class="subbar-t">{{ pages[page] }}</span>
      <span class="subbar-r"></span>
    </div>

    <!-- 应用管理 -->
    <template v-if="page === 'apps'">
      <div class="searchbox"><span>🔍</span>
        <input v-model="appKw" placeholder="搜索应用名或包名" class="sin" @input="onAppKw">
        <button v-if="appKw" class="sx tap" @click="appKw = ''; loadApps()">✕</button>
      </div>
      <div class="grp-card">
        <div v-for="a in apps" :key="a.pkg" class="srow">
          <div class="srow-txt"><div class="st">{{ a.label }}</div><div class="sd mono">{{ a.pkg }}</div></div>
          <button class="minib tap g" @click="appOp(a.pkg, 'launch')">▶ 启动</button>
          <button class="minib tap r" @click="appOp(a.pkg, 'stop')">⏹ 强停</button>
        </div>
        <div v-if="!apps.length" class="empty2">输入关键词搜索</div>
      </div>
    </template>

    <!-- 副屏控制台 -->
    <template v-else-if="page === 'vd'">
      <div class="card">
        <div class="rowline"><b>隐形副屏</b>
          <span class="pill" :class="{ g: vd.alive }">{{ vd.alive ? '运行中 #' + vd.displayId : '未创建' }}</span></div>
        <div class="row2">
          <button class="btn2" :disabled="busy || vd.alive" @click="vdOp('create')">创建</button>
          <button class="btn2 r" :disabled="busy || !vd.alive" @click="vdOp('stop')">销毁</button>
        </div>
        <template v-if="vd.alive">
          <label class="lb">发射应用到副屏</label>
          <div class="row2">
            <input v-model="vdLaunch" placeholder="包名 或 搜下方应用点发射" class="mono">
            <button class="minib tap g" :disabled="!vdLaunch" @click="vdOp('launch', { pkg: vdLaunch })">🚀 发射</button>
          </div>
          <div class="applist-mini">
            <span v-for="a in apps.slice(0, 30)" :key="a.pkg" class="chip tap" @click="vdLaunch = a.pkg; vdOp('launch', { pkg: a.pkg })">{{ a.label }}</span>
          </div>
          <label class="lb">实时预览</label>
          <div class="preview">
            <img v-if="imgData" :src="imgData">
            <div v-else class="empty2">点「📸 截图」获取副屏画面</div>
          </div>
          <div class="row2">
            <button class="btn2" @click="shot">📸 截图</button>
            <button class="btn2" @click="refreshVd">↻ 状态</button>
          </div>
          <label class="lb">点击（千分比 0-1000）</label>
          <div class="row2">
            <input v-model="tapX" type="number" placeholder="x"><input v-model="tapY" type="number" placeholder="y">
            <button class="minib tap g" @click="vdtap">👆</button>
          </div>
          <label class="lb">输入文字</label>
          <div class="row2">
            <input v-model="vdText" placeholder="要打的字">
            <button class="minib tap g" @click="vdinput">⌨️</button>
          </div>
        </template>
      </div>
    </template>

    <!-- 传感器 -->
    <template v-else-if="page === 'sensor'">
      <div class="card">
        <button class="btn2" @click="loadSensors">↻ 读取当前值</button>
        <template v-if="sensors">
          <div class="kv"><span>☀️ 光照</span><b>{{ sensors.light ?? sensors.lux ?? '—' }} lux</b></div>
          <div class="kv"><span>📡 接近</span><b>{{ sensors.proximity ?? (sensors.near ? '近' : '远') }}</b></div>
          <div class="kv"><span>🧭 加速度</span><b>{{ sensors.acc ?? sensors.accelerometer ?? '—' }}</b></div>
          <div class="kv"><span>🌍 重力</span><b>{{ sensors.gravity ?? '—' }}</b></div>
        </template>
        <div v-else class="empty2">点上方按钮读取</div>
      </div>
    </template>
    <div style="height:24px;"></div>
  </div>
  <div v-if="out" class="toast">{{ out }}</div>
</template>

<style scoped>
.h1 { font-size: 21px; font-weight: 700; margin: 6px 4px 2px; }
.sub { font-size: 13px; color: var(--muted); margin: 0 4px 14px; }
.strow3 { display: grid; grid-template-columns: 1fr 1.3fr 1fr; gap: 8px; margin-bottom: 4px; }
.stcard { background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 12px 10px; box-shadow: var(--shadow); }
.stcard.chg { border-color: var(--hill); background: #F4FAF6; }
.stbig { font-size: 19px; font-weight: 800; }
.stlab { font-size: 11px; color: var(--muted); margin-top: 3px; }
.secl { margin: 14px 4px 7px; font-size: 12px; font-weight: 700; color: var(--muted); letter-spacing: 2px; }
.grp-card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; overflow: hidden; box-shadow: var(--shadow); margin-bottom: 10px; }
.grp { display: flex; align-items: center; gap: 12px; padding: 13px 14px; border-bottom: 1px solid var(--line); }
.grp:last-child { border-bottom: 0; }
.grp:active { background: var(--bg); }
.ic { font-size: 18px; width: 36px; height: 36px; display: flex; align-items: center; justify-content: center; background: var(--hill-soft); border-radius: 11px; }
.gt { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.tt { font-size: 15px; font-weight: 700; }
.ts { font-size: 12px; color: var(--muted); margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ar { color: #B9B4A6; font-size: 19px; }
.srow { display: flex; align-items: center; gap: 10px; padding: 11px 14px; border-bottom: 1px solid var(--line); }
.srow:last-child { border-bottom: 0; }
.srow-txt { flex: 1; min-width: 0; }
.st { font-size: 15px; font-weight: 600; }
.sd { font-size: 11px; color: var(--muted); margin-top: 2px; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 14px; margin-bottom: 10px; box-shadow: var(--shadow); }
.sw { width: 46px; height: 28px; border-radius: 99px; background: #d8d6cd; position: relative; transition: background .2s; flex-shrink: 0; }
.sw.on { background: var(--hill); }
.sw .knob { position: absolute; top: 3px; left: 3px; width: 22px; height: 22px; border-radius: 50%; background: #fff; transition: left .18s; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
.sw.on .knob { left: 21px; }
.sldrow { display: flex; align-items: center; gap: 10px; padding: 7px 0; }
.sldlab { font-size: 13px; width: 44px; color: var(--muted); }
.sldrow input[type=range] { flex: 1; }
.sldv { font-size: 12px; width: 30px; text-align: right; color: var(--muted); }
.searchbox { display: flex; align-items: center; gap: 8px; background: var(--card); border: 1px solid var(--line); border-radius: 13px; padding: 0 12px; margin-bottom: 8px; box-shadow: var(--shadow); }
.sin { border: 0; background: none; padding: 11px 0; font-size: 14px; flex: 1; }
.sx { border: 0; background: #EFEDE6; border-radius: 50%; width: 20px; height: 20px; font-size: 11px; color: var(--muted); }
.minib { border: 1px solid var(--line); background: var(--card); border-radius: 9px; padding: 6px 11px; font-size: 12px; font-weight: 600; color: var(--ink); }
.minib.g { background: var(--hill-soft); border-color: var(--hill); color: var(--hill); }
.minib.r { background: #F8E8E5; border-color: var(--bad); color: var(--bad); }
.empty2 { padding: 22px 16px; text-align: center; font-size: 13px; color: var(--muted); }
.subp { position: fixed; inset: 0; z-index: 60; background: var(--bg); overflow-y: auto; padding: 0 12px 60px; animation: slidein .22s ease; }
@keyframes slidein { from { transform: translateX(100%); } to { transform: none; } }
.subbar { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; gap: 4px; padding: 8px 2px; background: var(--bg); border-bottom: 1px solid var(--line); margin-bottom: 10px; min-height: 46px; }
.backb { border: 0; background: var(--card); color: var(--ink); font-size: 20px; font-weight: 700; width: 34px; height: 34px; border-radius: 11px; border: 1px solid var(--line); }
.subbar-t { font-size: 17px; font-weight: 800; margin: 0 auto; }
.subbar-r { min-width: 34px; }
.rowline { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.row2 { display: flex; gap: 8px; margin: 8px 0; align-items: center; }
.row2 input { flex: 1; min-width: 0; }
.btn2 { border: 0; border-radius: 12px; background: var(--hill); color: #fff; font-size: 14px; font-weight: 700; padding: 11px 16px; flex: 1; }
.btn2.r { background: var(--bad); }
.btn2:disabled { opacity: .4; }
.lb { display: block; font-size: 13px; color: var(--muted); margin: 12px 0 4px; }
.mono { font-family: ui-monospace, monospace; font-size: 12px; }
.applist-mini { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.chip { background: var(--bg); border: 1px solid var(--line); border-radius: 99px; padding: 5px 12px; font-size: 12px; }
.preview { background: #111; border-radius: 12px; overflow: hidden; display: flex; justify-content: center; min-height: 140px; margin-top: 6px; }
.preview img { width: 100%; object-fit: contain; }
.kv { display: flex; justify-content: space-between; font-size: 14px; padding: 10px 4px; border-bottom: 1px dashed var(--line); }
.kv:last-child { border-bottom: 0; }
.pill { font-size: 12px; padding: 4px 10px; border-radius: 12px; background: var(--bg); }
.pill.g { background: var(--hill-soft); color: var(--hill); font-weight: 700; }
.toast { position: fixed; left: 50%; bottom: 90px; transform: translateX(-50%); z-index: 80; background: rgba(34,48,31,.92); color: #fff; font-size: 13px; padding: 9px 18px; border-radius: 99px; max-width: 86vw; }
</style>
