<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { call, outText, cfgAll, cfgSet } from '../api.js'

/* ═══════════ 子页导航（同设置页模式：pushState+返回手势） ═══════════ */
const page = ref(null)
const pages = { home: '播报', sources: '来源管理', dnd: '免打扰', behavior: '播报行为', feed: '通知流' }
let subPushed = false
function openPage(p) {
  page.value = p
  try { history.pushState({ sub: p }, ''); subPushed = true } catch { subPushed = false }
}
function onPop() { if (page.value) { page.value = null; subPushed = false } }
function back() { if (subPushed) { try { history.back(); return } catch {} } page.value = null; subPushed = false }
onMounted(() => { window.addEventListener('popstate', onPop); load() })
onUnmounted(() => { window.removeEventListener('popstate', onPop); if (subPushed) { try { history.back() } catch {} } })

/* ═══════════ 配置与数据 ═══════════ */
const cfg = ref({})
const logs = ref([])
const apps = ref([])         // 应用选择器
const appPicker = ref(null)  // { target: 'white'|'black' }
const appQ = ref('')
const digest = ref(''), busy = ref(false), out = ref('')

async function load() {
  cfg.value = await cfgAll()
  if (String(cfg.value.notify_announce_pkgs ?? '').trim() === '') { // 首次：写入默认白名单（此后可自由清空）
    cfg.value.notify_announce_pkgs = 'com.tencent.mm'
    await cfgSet('notify_announce_pkgs', 'com.tencent.mm')
  }
  const r = await call('notify_read', { limit: 50 })
  logs.value = r.ok && Array.isArray(r.data) ? r.data : (r.ok && r.data?.items) || []
}
async function save(k, v) { await cfgSet(k, v); out.value = '已保存'; setTimeout(() => out.value = '', 1500) }

/* ── 模式 ── */
const mode = computed(() => cfg.value.notify_announce_mode || 'whitelist')
async function setMode(m) { cfg.value.notify_announce_mode = m; await save('notify_announce_mode', m) }

/* ── 名单（逗号分隔包名 ↔ 数组） ── */
const whiteList = computed({
  get: () => String(cfg.value.notify_announce_pkgs ?? '').split(',').map(s => s.trim()).filter(Boolean),
  set: (v) => { cfg.value.notify_announce_pkgs = v.join(','); save('notify_announce_pkgs', v.join(',')) },
})
const blackList = computed({
  get: () => String(cfg.value.notify_announce_black_pkgs ?? '').split(',').map(s => s.trim()).filter(Boolean),
  set: (v) => { cfg.value.notify_announce_black_pkgs = v.join(','); save('notify_announce_black_pkgs', v.join(',')) },
})
async function addPkg(target, pkg) {
  if (!pkg) return
  const list = target === 'white' ? [...whiteList.value] : [...blackList.value]
  const hit = list.includes(pkg)
  const next = hit ? list.filter(p => p !== pkg) : [...list, pkg]
  if (target === 'white') whiteList.value = next; else blackList.value = next
  flash(hit ? '已移出' + (target === 'white' ? '白' : '黑') + '名单' : '已加入' + (target === 'white' ? '白' : '黑') + '名单')
}
function flash(t) { out.value = t; setTimeout(() => out.value = '', 1600) }
async function removePkg(target, pkg) {
  if (target === 'white') whiteList.value = whiteList.value.filter(p => p !== pkg)
  else blackList.value = blackList.value.filter(p => p !== pkg)
}

/* ── 应用选择器（包名快捷获取：搜索已装应用点选） ── */
async function openPicker(target) {
  appPicker.value = { target }
  appQ.value = ''
  await loadApps('')
}
async function loadApps(q) {
  const r = await call('apps_list', { filter: q || '', limit: 200 })
  const raw = r.ok ? (Array.isArray(r.data) ? r.data : r.data?.items || r.data?.apps || []) : []
  apps.value = raw.map(it => {
    if (typeof it === 'string') {
      const m = it.match(/^(.*?)\s*\((.+)\)\s*$/)   // "微信 (com.tencent.mm)"
      return m ? { label: m[1], pkg: m[2] } : { label: it, pkg: it }
    }
    return { label: it.label || it.name || it.app, pkg: it.pkg || it.package || it.packageName }
  })
}
watch(appQ, q => { if (appPicker.value && (q === '' || q.length >= 1)) loadApps(q) })

/* ── 通知流标记（三层口径统一：按当前模式计算每条的命运） ── */
function markOf(l) {
  const pkg = l.pkg || ''
  if (getPackageNameSelf() === pkg) return { t: '⬜', why: '自己' }
  const kw = String(cfg.value.notify_announce_exclude ?? '验证码,快递,取件').split(',').filter(k => k.trim())
  const body = (l.title || '') + (l.text || '')
  for (const k of kw) if (k.trim() && body.includes(k.trim())) return { t: '🚫', why: '关键词「' + k.trim() + '」' }
  if (blackList.value.includes(pkg)) return { t: '🚫', why: '黑名单' } // 黑名单两种模式下都置顶可见
  if (mode.value === 'blacklist') return { t: '✅', why: '将播报' }
  return whiteList.value.includes(pkg) ? { t: '✅', why: '白名单' } : { t: '⬜', why: '不在名单' }
}
function getPackageNameSelf() { return 'com.pihost' }

/* ── 总览计数 ── */
const stats = computed(() => {
  let will = 0, block = 0, off = 0
  for (const l of logs.value) {
    const m = markOf(l)
    if (m.t === '✅') will++
    else if (m.t === '🚫') block++
    else off++
  }
  return { will, block, off }
})

/* ── 免打扰 ── */
const kwDraft = ref('')
async function addKw() {
  const k = kwDraft.value.trim()
  if (!k) return
  const cur = String(cfg.value.notify_announce_exclude ?? '').split(',').map(s => s.trim()).filter(Boolean)
  if (!cur.includes(k)) { cur.push(k); cfg.value.notify_announce_exclude = cur.join(','); await save('notify_announce_exclude', cur.join(',')) }
  kwDraft.value = ''
}
async function delKw(k) {
  const cur = String(cfg.value.notify_announce_exclude ?? '').split(',').map(s => s.trim()).filter(Boolean).filter(x => x !== k)
  cfg.value.notify_announce_exclude = cur.join(',')
  await save('notify_announce_exclude', cur.join(','))
}
const silentOn = computed({
  get: () => String(cfg.value.notify_silent || '').trim() !== '',
  set: async (v) => {
    cfg.value.notify_silent = v ? silentRange.value : ''
    await save('notify_silent', cfg.value.notify_silent)
  },
})
const silentRange = ref({ start: '23:00', end: '07:00' })
try { // 从存档恢复时段
  const sv = String(cfg.value.notify_silent || '')
  const mm = sv.match(/(\d{1,2}:\d{2})-(\d{1,2}:\d{2})/)
  if (mm) silentRange.value = { start: mm[1], end: mm[2] }
} catch {}
async function saveSilent() {
  const v = silentOn.value ? silentRange.value.start + '-' + silentRange.value.end : ''
  cfg.value.notify_silent = v
  await save('notify_silent', v)
}

/* ── 摘要 ── */
async function doDigest() {
  busy.value = true
  const r = await call('voice_digest', {})
  digest.value = r.ok ? r.data.digest : '失败：' + outText(r)
  busy.value = false
}
</script>

<template>
  <!-- ═══ 首页：分组列表 ═══ -->
  <div v-show="!page">
    <div class="h1">播报</div>
    <div class="sub">小丘的听觉：谁在找你、说了什么</div>

    <div class="grp-card">
      <div class="grp tap" @click="openPage('sources')">
        <span class="grp-ic">📥</span>
        <span class="grp-txt"><span class="grp-t">来源管理</span>
          <span class="grp-s">{{ mode === 'blacklist' ? '全播但黑名单 · 拦 ' + blackList.length : '仅白名单 · ' + whiteList.length + ' 个应用' }}</span></span>
        <span class="grp-ar">›</span>
      </div>
      <div class="grp tap" @click="openPage('dnd')">
        <span class="grp-ic">🌙</span>
        <span class="grp-txt"><span class="grp-t">免打扰</span>
          <span class="grp-s">{{ silentOn ? '静默 ' + silentRange.start + '-' + silentRange.end : '关键词 ' + (String(cfg.notify_announce_exclude ?? '').split(',').filter(x => x.trim()).length + ' 条') }}</span></span>
        <span class="grp-ar">›</span>
      </div>
      <div class="grp tap" @click="openPage('behavior')">
        <span class="grp-ic">🎛</span>
        <span class="grp-txt"><span class="grp-t">播报行为</span>
          <span class="grp-s">{{ cfg.notify_announce_ai === 'true' ? 'AI 拟人化' : '原文播报' }} · 平稳 {{ (cfg.notify_announce_wait || 10000) / 1000 }}s</span></span>
        <span class="grp-ar">›</span>
      </div>
      <div class="grp tap" @click="openPage('feed')">
        <span class="grp-ic">📰</span>
        <span class="grp-txt"><span class="grp-t">通知流</span>
          <span class="grp-s">✅{{ stats.will }} 🚫{{ stats.block }} ⬜{{ stats.off }}（近 {{ logs.length }} 条）</span></span>
        <span class="grp-ar">›</span>
      </div>
    </div>

    <div class="card">
      <div class="sec2">错过了什么</div>
      <button class="btn" :disabled="busy" @click="doDigest">{{ busy ? '整理中…' : '生成摘要' }}</button>
      <div v-if="digest" class="digest">🗣 {{ digest }}</div>
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

    <!-- 来源管理 -->
    <template v-if="page === 'sources'">
      <div class="hint">两种口径二选一：安静（仅白名单播）或激进（全播但黑名单拦）。VPN/下载进度类建议黑名单。</div>
      <div class="modecard tap" :class="{ on: mode === 'whitelist' }" @click="setMode('whitelist')">
        <div class="mode-ic">🤫</div>
        <div class="mode-txt"><div class="mode-t">仅白名单 <span class="pill ok-pill">安静·默认</span></div>
          <div class="mode-d">只播名单里的应用（微信/QQ 等），新应用一律不吵</div></div>
        <span class="radio" :class="{ on: mode === 'whitelist' }"></span>
      </div>
      <div class="modecard tap" :class="{ on: mode === 'blacklist' }" @click="setMode('blacklist')">
        <div class="mode-ic">📢</div>
        <div class="mode-txt"><div class="mode-t">全部但黑名单 <span class="pill dim-pill">激进</span></div>
          <div class="mode-d">什么都播，只拦黑名单里的（VPN/系统更新/下载器）</div></div>
        <span class="radio" :class="{ on: mode === 'blacklist' }"></span>
      </div>

      <!-- 白名单 -->
      <div class="secl">白名单 <em>{{ whiteList.length }} 个应用</em>
        <span class="secl-r"><button class="minib tap" @click="openPicker('white')">＋ 从应用选</button></span></div>
      <div class="grp-card">
        <div v-for="p in whiteList" :key="p" class="srow">
          <div class="srow-txt"><div class="srow-t mono-s">{{ p }}</div></div>
          <button class="minib tap" style="color:var(--bad);" @click="removePkg('white', p)">✕</button>
        </div>
        <div v-if="!whiteList.length" class="empty">空（白名单模式下将不播任何应用）</div>
      </div>

      <!-- 黑名单 -->
      <div class="secl">黑名单 <em>{{ blackList.length }} 个应用</em>
        <span class="secl-r"><button class="minib tap" @click="openPicker('black')">＋ 从应用选</button></span></div>
      <div class="grp-card">
        <div v-for="p in blackList" :key="p" class="srow">
          <div class="srow-txt"><div class="srow-t mono-s">{{ p }}</div></div>
          <button class="minib tap" style="color:var(--bad);" @click="removePkg('black', p)">✕</button>
        </div>
        <div v-if="!blackList.length" class="empty">空</div>
      </div>
    </template>

    <!-- 免打扰 -->
    <template v-else-if="page === 'dnd'">
      <div class="secl">关键词免打扰</div>
      <div class="card">
        <div class="hint">标题或内容包含关键词的通知不播（验证码/取件码等隐私&噪声）</div>
        <div class="kwline">
          <span v-for="k in String(cfg.notify_announce_exclude ?? '').split(',').filter(x => x.trim())" :key="k" class="kwchip tap" @click="delKw(k.trim())">{{ k.trim() }} ✕</span>
        </div>
        <div class="row2">
          <input v-model="kwDraft" placeholder="输入关键词回车添加" @keydown.enter="addKw">
          <button class="minib tap" @click="addKw">＋</button>
        </div>
      </div>

      <div class="secl">夜间静默时段</div>
      <div class="card">
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-t">静默时段 <em class="mini-hint">时段内不播报（通知照记，摘要可补看）</em></div>
          </div>
          <div :class="['sw', 'tap', { on: silentOn }]" @click="silentOn = !silentOn"><div class="knob"></div></div>
        </div>
        <div v-if="silentOn" class="row2" style="margin-top:10px;">
          <input v-model="silentRange.start" type="time">
          <span style="align-self:center;color:var(--muted);">至</span>
          <input v-model="silentRange.end" type="time">
          <button class="minib tap" @click="saveSilent">存</button>
        </div>
      </div>
    </template>

    <!-- 播报行为 -->
    <template v-else-if="page === 'behavior'">
      <div class="grp-card">
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-t">AI 拟人化改写</div>
            <div class="srow-d">像朋友转述，不是复读机</div>
          </div>
          <div :class="['sw', 'tap', { on: cfg.notify_announce_ai === 'true' }]" @click="cfg.notify_announce_ai = cfg.notify_announce_ai === 'true' ? 'false' : 'true'; save('notify_announce_ai', cfg.notify_announce_ai)"><div class="knob"></div></div>
        </div>
        <div class="srow" style="flex-direction:column;align-items:stretch;gap:8px;">
          <div class="srow-t">聚合平稳期 <em class="mini-hint">等消息消停多久再一起播</em></div>
          <div class="row2">
            <input type="number" step="1000" :value="cfg.notify_announce_wait || 10000" @change="e => { cfg.notify_announce_wait = Number(e.target.value); save('notify_announce_wait', String(e.target.value)) }">
            <span style="align-self:center;font-size:12px;color:var(--muted);">毫秒</span>
          </div>
        </div>
        <div class="srow" style="flex-direction:column;align-items:stretch;gap:8px;">
          <div class="srow-t">播报引擎覆盖 <em class="mini-hint">空=跟随全局语音设置</em></div>
          <input :value="cfg.notify_announce_engine || ''" placeholder="留空跟随全局" @change="e => { cfg.notify_announce_engine = e.target.value; save('notify_announce_engine', e.target.value) }">
        </div>
      </div>
      
    </template>

    <!-- 通知流 -->
    <template v-else-if="page === 'feed'">
      <div v-if="out" class="msg ok" style="position:sticky;top:56px;z-index:2;background:var(--hill-soft);">{{ out }}</div>
      <div class="hint">按当前模式实时计算每条通知的命运。点行尾按钮一键配置来源。</div>
      <div class="grp-card">
        <div v-for="(l, i) in logs" :key="i" class="feedrow">
          <div class="feedmark">{{ markOf(l).t }}</div>
          <div class="srow-txt">
            <div class="srow-t">{{ l.title || l.pkg }} <span class="mini-hint">{{ markOf(l).why }}</span></div>
            <div class="srow-d">{{ l.text }}<br><span class="mono-s">{{ l.pkg }}</span> · {{ new Date(l.time).toLocaleTimeString() }}</div>
          </div>
          <div class="feedacts">
            <button :class="['minib', 'tap', { ong: whiteList.includes(l.pkg) }]" title="切换白名单" @click="addPkg('white', l.pkg)">✅</button>
            <button :class="['minib', 'tap', { onr: blackList.includes(l.pkg) }]" title="切换黑名单" @click="addPkg('black', l.pkg)">🚫</button>
          </div>
        </div>
        <div v-if="!logs.length" class="empty">暂无通知记录</div>
      </div>
    </template>
    <div style="height:24px;"></div>
  </div>

  <!-- 应用选择器（包名快捷获取） -->
  <div v-if="appPicker" class="pkview" @click="appPicker = null">
    <div class="pkview-b" @click.stop>
      <div class="skview-h">
        <div class="skview-tt" style="flex:1;"><b>选择应用 → 加入{{ appPicker.target === 'white' ? '白' : '黑' }}名单</b></div>
        <button class="minib tap" @click="appPicker = null">✕</button>
      </div>
      <div class="searchbox2">
        <span>🔍</span>
        <input v-model="appQ" placeholder="搜索应用名或包名" class="search-in2">
      </div>
      <div class="apklist">
        <div v-for="a in apps" :key="a.pkg || a.package" class="apkrow tap" @click="addPkg(appPicker.target, a.pkg || a.package); appPicker = null">
          <div class="srow-txt">
            <div class="srow-t">{{ a.label || a.name }}</div>
            <div class="srow-d mono-s">{{ a.pkg || a.package }}</div>
          </div>
          <span class="pill ok-pill">＋ 加入</span>
        </div>
        <div v-if="!apps.length" class="empty">输入关键词搜索</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.grp-card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; overflow: hidden; box-shadow: var(--shadow); margin-bottom: 12px; }
.grp { display: flex; align-items: center; gap: 12px; padding: 13px 14px; border-bottom: 1px solid var(--line); }
.grp:last-child { border-bottom: 0; }
.grp:active { background: var(--bg); }
.grp-ic { font-size: 19px; width: 38px; height: 38px; display: flex; align-items: center; justify-content: center; background: var(--hill-soft); border-radius: 11px; }
.grp-txt { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.grp-t { font-size: 15px; font-weight: 700; }
.grp-s { font-size: 12px; color: var(--muted); margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.grp-ar { color: #B9B4A6; font-size: 20px; font-weight: 300; }
.h1 { font-size: 21px; font-weight: 700; margin: 6px 4px 2px; }
.sub { font-size: 13px; color: var(--muted); margin: 0 4px 14px; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 14px; margin-bottom: 12px; box-shadow: var(--shadow); }
.btn { border: 0; border-radius: 14px; background: var(--hill); color: #fff; font-size: 15px; font-weight: 700; padding: 12px; width: 100%; }
.btn:active { opacity: .85; }
.sec2 { font-weight: 700; font-size: 14px; margin-bottom: 10px; }
.digest { margin-top: 12px; background: var(--hill-soft); color: var(--hill); border-radius: 12px; padding: 12px; font-size: 14px; line-height: 1.6; }
.hint { font-size: 12px; color: var(--muted); line-height: 1.65; margin-bottom: 10px; }
.modecard { display: flex; align-items: center; gap: 12px; background: var(--card); border: 2px solid var(--line); border-radius: 16px; padding: 13px 14px; margin-bottom: 10px; }
.modecard.on { border-color: var(--hill); background: #F4FAF6; }
.mode-ic { font-size: 22px; }
.mode-txt { flex: 1; }
.mode-t { font-size: 15px; font-weight: 700; display: flex; align-items: center; gap: 6px; }
.mode-d { font-size: 12px; color: var(--muted); margin-top: 3px; line-height: 1.5; }
.radio { width: 20px; height: 20px; border-radius: 50%; border: 2px solid var(--line); flex-shrink: 0; }
.radio.on { border-color: var(--hill); background: var(--hill); box-shadow: inset 0 0 0 4px #fff; }
.secl { margin: 16px 4px 7px; font-size: 12px; font-weight: 700; color: var(--muted); letter-spacing: 2px; display: flex; align-items: center; gap: 6px; }
.secl em { font-style: normal; font-weight: 400; letter-spacing: 0; font-size: 11px; opacity: .85; }
.secl-r { margin-left: auto; }
.srow { display: flex; align-items: center; gap: 10px; padding: 11px 14px; border-bottom: 1px solid var(--line); }
.srow:last-child { border-bottom: 0; }
.srow-txt { flex: 1; min-width: 0; }
.srow-t { font-size: 14px; font-weight: 700; }
.srow-d { font-size: 12px; color: var(--muted); margin-top: 3px; line-height: 1.5; word-break: break-all; }
.minib.ong { background: var(--hill-soft); border-color: var(--hill); color: var(--hill); }
.minib.onr { background: #F8E8E5; border-color: var(--bad); color: var(--bad); }
.minib { border: 1px solid var(--line); background: var(--card); border-radius: 9px; padding: 5px 10px; font-size: 12px; font-weight: 600; color: var(--ink); }
.mini-hint { font-style: normal; font-size: 11px; color: var(--muted); font-weight: 400; }
.mono-s { font-family: ui-monospace, monospace; font-size: 11px; color: var(--muted); }
.empty { padding: 20px 16px; text-align: center; font-size: 13px; color: var(--muted); }
.sw { width: 46px; height: 28px; border-radius: 99px; background: #d8d6cd; position: relative; transition: background .2s; flex-shrink: 0; }
.sw.on { background: var(--hill); }
.sw .knob { position: absolute; top: 3px; left: 3px; width: 22px; height: 22px; border-radius: 50%; background: #fff; transition: left .18s; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
.sw.on .knob { left: 21px; }
.pill { font-size: 10px; padding: 2px 8px; border-radius: 99px; font-weight: 700; }
.ok-pill { background: var(--hill-soft); color: var(--hill); }
.dim-pill { background: #EFEDE6; color: var(--muted); }
.row2 { display: flex; gap: 8px; }
.row2 input { flex: 1; }
.kwline { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.kwchip { background: var(--bg); border: 1px solid var(--line); border-radius: 99px; padding: 5px 12px; font-size: 13px; }
.msg.ok { color: var(--hill); text-align: center; font-size: 13px; margin-top: 8px; min-height: 18px; }
.subp { position: fixed; inset: 0; z-index: 60; background: var(--bg); overflow-y: auto; padding: 0 12px 60px; animation: slidein .22s ease; }
@keyframes slidein { from { transform: translateX(100%); } to { transform: none; } }
.subbar { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; gap: 4px; padding: 8px 2px; background: var(--bg); border-bottom: 1px solid var(--line); margin-bottom: 10px; min-height: 46px; }
.backb { border: 0; background: var(--card); color: var(--ink); font-size: 20px; font-weight: 700; width: 34px; height: 34px; border-radius: 11px; border: 1px solid var(--line); }
.subbar-t { font-size: 17px; font-weight: 800; margin: 0 auto; }
.subbar-r { min-width: 34px; }
.feedrow { display: flex; align-items: flex-start; gap: 10px; padding: 11px 12px; border-bottom: 1px solid var(--line); }
.feedmark { font-size: 16px; flex-shrink: 0; width: 24px; text-align: center; }
.feedacts { display: flex; gap: 6px; flex-shrink: 0; }
.pkview { position: fixed; inset: 0; z-index: 70; background: rgba(24,30,20,.5); display: flex; align-items: flex-end; animation: fadein .15s ease; }
@keyframes fadein { from { opacity: 0; } }
.pkview-b { background: var(--card); border-radius: 20px 20px 0 0; width: 100%; max-height: 80vh; display: flex; flex-direction: column; padding: 12px 12px 10px; animation: upin .2s ease; }
@keyframes upin { from { transform: translateY(40px); } }
.skview-h { display: flex; align-items: center; gap: 10px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.skview-tt { flex: 1; min-width: 0; }
.skview-tt b { font-size: 15px; }
.searchbox2 { display: flex; align-items: center; gap: 8px; background: var(--bg); border: 1px solid var(--line); border-radius: 13px; padding: 0 12px; margin: 10px 0; }
.search-in2 { border: 0; background: none; padding: 11px 0; font-size: 14px; flex: 1; }
.apklist { overflow-y: auto; max-height: 58vh; }
.apkrow { display: flex; align-items: center; gap: 10px; padding: 10px 4px; border-bottom: 1px solid var(--line); }
</style>
