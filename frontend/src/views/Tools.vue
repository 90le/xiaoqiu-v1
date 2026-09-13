<script setup>
import { ref, computed, onMounted } from 'vue'
import { call, outText } from '../api.js'

/* ═══ 数据 ═══ */
const tools = ref([])     // [{name, desc, schema:{properties:{}, required:[]}}]
const kw = ref('')
const cur = ref(null)     // 当前打开的工具 {name, desc, schema, args:{}, result, busy}
const favs = ref(JSON.parse(localStorage.getItem('xq_tool_favs') || '[]'))
const recents = ref(JSON.parse(localStorage.getItem('xq_tool_recents') || '[]'))
const hist = ref([])

/* 语义能力域 v2：精确名优先 → 前缀 → 兜底。8 域各司其职 */
const CATS = [
  ['device', '📱 设备与系统', ['battery', 'device', 'screen_state', 'screen_', 'brightness', 'volume', 'flashlight', 'vibrate', 'sensors', 'network', 'location', 'media', 'sysctl', 'settings_write', 'settings_', 'ime_', 'clipboard', 'ui_back', 'ui_home', 'ui_recents', 'screenshot', 'floatball']],
  ['ui', '🖐 界面操作', ['ui_']],
  ['eye', '👁 视觉理解', ['vision', 'ocr', 'shot_diff']],
  ['msg', '💬 消息与联系人', ['notify', 'sms', 'calllog', 'contacts']],
  ['voice', '🗣 语音', ['tts', 'stt', 'mic_', 'mic', 'wake_service', 'voice_chat', 'voice_digest']],
  ['brain', '🧠 智能与记忆', ['chat_fast', 'chat_', 'memory', 'ai_humanize', 'pi_rpc']],
  ['auto', '⚡ 自动化与应用', ['macro', 'xhs', 'intent', 'alarm', 'timer', 'apps_launch', 'apps_list', 'apps_']],
  ['dev', '🔧 文件与开发', ['files', 'env_', 'env', 'termux', 'l2_', 'cfg', 'tools', 'setkey', 'perm_', 'open_perm', 'app_request', 'voice_bus', 'app_']],
]
const EXACT = { // 精确名归位（纠正前缀误伤）
  voice_bus: 'dev', voice_digest: 'voice', voice_chat: 'voice',
  apps_list: 'auto', apps_launch: 'auto',
  screenshot: 'device', media_play_pause: 'device',
  xhs_search_direct: 'auto', macro_from_session: 'auto',
  env_run: 'dev', env_status: 'dev', env_install: 'dev',
  clipboard_read: 'device', clipboard_write: 'device',
}
function catOf(name) {
  if (EXACT[name]) return EXACT[name]
  for (const [id, , prefixes] of CATS) for (const p of prefixes) if (name.startsWith(p)) return id
  return 'dev'
}
const CATNAME = Object.fromEntries(CATS.map(x => [x[0], x[1]]))

onMounted(load) // ⚠️ 永久钉在这：区间替换曾两次吞掉此行

/* 标题/副标题提取：'查电池：电量、充电状态' → ['查电池','电量、充电状态'] */
function splitDesc(desc) {
  const d = String(desc || '')
  const i = d.search(/[：:(（]/)
  if (i > 1 && i < 16) return [d.slice(0, i).trim(), d.slice(i + 1).replace(/^[)）]\s*/, '').trim()]
  return [d.length > 16 ? d.slice(0, 15) + '…' : d, '']
}

async function load() {
  const r = await call('tools_list', { fmt: 'full' })
  const raw = r.ok ? (Array.isArray(r.data) ? r.data : []) : []
  tools.value = raw.filter(x => x && x.name).map(x => {
    const [title, sub] = splitDesc(x.desc)
    return {
      name: x.name,
      title: title || x.name,
      sub,
      fullDesc: x.desc || '',
      props: x.schema?.properties || {},
      required: x.schema?.required || [],
    }
  })
}

/* ── 过滤分组 ── */
const filtered = computed(() => {
  const q = kw.value.trim().toLowerCase()
  return tools.value.filter(t => !q || t.name.toLowerCase().includes(q) || t.desc.toLowerCase().includes(q))
})
const grouped = computed(() => {
  const g = {}
  for (const t of filtered.value) (g[catOf(t.name)] = g[catOf(t.name)] || []).push(t)
  return g
})
const favTools = computed(() => tools.value.filter(t => favs.value.includes(t.name)))
const recentTools = computed(() => recents.value.map(n => tools.value.find(t => t.name === n)).filter(Boolean))

/* ── 打开工具（schema→表单） ── */
function openTool(t) {
  const args = {}
  for (const [k, p] of Object.entries(t.props)) {
    if (p?.type === 'boolean') args[k] = false
    else args[k] = ''
  }
  cur.value = { ...t, args, result: null, busy: false }
  if (!recents.value.includes(t.name)) {
    recents.value = [t.name, ...recents.value.filter(n => n !== t.name)].slice(0, 8)
    try { localStorage.setItem('xq_tool_recents', JSON.stringify(recents.value)) } catch {}
  }
}
function closeTool() { cur.value = null }
function toggleFav(name) {
  favs.value = favs.value.includes(name) ? favs.value.filter(n => n !== name) : [...favs.value, name]
  try { localStorage.setItem('xq_tool_favs', JSON.stringify(favs.value)) } catch {}
}
/* 参数控件类型 */
function ptype(p) {
  const t = p?.type || 'string'
  if (t === 'number' || t === 'integer') return 'number'
  if (t === 'boolean') return 'switch'
  return 'text'
}
const propDesc = p => (p?.description || p?.desc || '').split('；')[0]

/* ── 执行 ── */
async function run() {
  const c = cur.value
  if (!c) return
  const params = {}
  for (const [k, v] of Object.entries(c.args)) {
    if (v === '' || v === false) continue
    params[k] = c.props[k]?.type === 'number' ? Number(v) : v
  }
  for (const r of c.required) { // 必填校验
    if (params[r] === undefined) { c.result = { ok: false, err: '必填参数缺失：' + r }; return }
  }
  c.busy = true
  const r = await call(c.name, params)
  c.busy = false
  c.result = { ok: r.ok, data: r.data, err: r.ok ? null : outText(r) }
  hist.value.unshift({ name: c.name, params, ok: r.ok, at: Date.now() })
  if (hist.value.length > 20) hist.value.pop()
}
/* 结果美化渲染 */
function resultView(res) {
  if (!res) return null
  if (!res.ok) return { type: 'err', text: res.err || '失败' }
  const d = res.data
  if (d == null) return { type: 'ok', text: '✅ 成功' }
  if (Array.isArray(d)) {
    if (!d.length) return { type: 'ok', text: '✅ 空' }
    if (typeof d[0] === 'string' && d[0].includes(' — ')) return { type: 'kv', list: d.map(s => { const i = s.indexOf(' — '); return [s.slice(0, i), s.slice(i + 4)] }) }
    return { type: 'list', list: d.map(x => typeof x === 'string' ? x : JSON.stringify(x)) }
  }
  if (typeof d === 'object') {
    if (d.png || d.path || d.file || d.shot) return { type: 'text', text: JSON.stringify(d, null, 2) }
    const ks = Object.keys(d)
    if (ks.length && ks.every(k => ['v', 't'].includes(k) === false) && ks.length <= 30 && ks.every(k => typeof d[k] !== 'object')) {
      return { type: 'kv', list: ks.map(k => [k, String(d[k])]) }
    }
    return { type: 'text', text: JSON.stringify(d, null, 2) }
  }
  return { type: 'ok', text: '✅ ' + String(d) }
}
function copyRes() {
  try { navigator.clipboard.writeText(JSON.stringify(cur.value?.result, null, 2)); } catch {}
}
</script>

<template>
  <div class="h1">工具</div>
  <div class="sub">{{ tools.length }} 项原生能力 · 点开即用（表单自动生成）</div>

  <!-- 搜索 -->
  <div class="searchbox">
    <span>🔍</span>
    <input v-model="kw" placeholder="搜索能力名或描述" class="search-in">
    <button v-if="kw" class="search-x tap" @click="kw = ''">✕</button>
  </div>

  <!-- 收藏/最近 -->
  <template v-if="!kw">
    <template v-if="favTools.length">
      <div class="secl">⭐ 常用</div>
      <div class="grp-card">
        <div v-for="t in favTools" :key="t.name" class="trow tap" @click="openTool(t)">
          <div class="trow-txt"><b>{{ t.title }}</b><div class="trow-d">{{ t.sub || t.name }}</div></div>
          <span class="ar">›</span>
        </div>
      </div>
    </template>
    <template v-if="recentTools.length">
      <div class="secl">🕘 最近</div>
      <div class="grp-card">
        <div v-for="t in recentTools.slice(0, 4)" :key="t.name" class="trow tap" @click="openTool(t)">
          <div class="trow-txt"><b>{{ t.title }}</b><div class="trow-d">{{ t.sub || t.name }}</div></div>
          <span class="ar">›</span>
        </div>
      </div>
    </template>
  </template>

  <!-- 全部（按能力域） -->
  <template v-for="(arr, cat) in grouped" :key="cat">
    <div class="secl">{{ CATNAME[cat] || cat }} <em>{{ arr.length }}</em></div>
    <div class="grp-card">
      <div v-for="t in arr" :key="t.name" class="trow tap" @click="openTool(t)">
        <div class="trow-txt">
          <b>{{ t.title }}<span v-if="favs.includes(t.name)" class="favn">⭐</span></b>
          <div class="trow-d">{{ t.sub }}<span v-if="t.sub && Object.keys(t.props).length"> · </span><span v-if="Object.keys(t.props).length" class="pnum">{{ Object.keys(t.props).length }} 参</span></div>
        </div>
        <span class="ar">›</span>
      </div>
    </div>
  </template>
  <div v-if="!filtered.length" class="empty2">没有匹配「{{ kw }}」的能力</div>
  <div style="height:16px;"></div>

  <!-- 工具详情（底部大弹层：schema 表单） -->
  <div v-if="cur" class="toolview" @click="closeTool">
    <div class="toolb" @click.stop>
      <div class="toolh">
        <div style="flex:1;min-width:0;">
          <b style="font-size:16px;">{{ cur.title }}</b>
          <div class="mono" style="font-size:11px;color:var(--muted);margin-top:2px;">{{ cur.name }}</div>
        </div>
        <button class="minib tap" :class="{ ong: favs.includes(cur.name) }" @click="toggleFav(cur.name)">⭐</button>
        <button class="minib tap" @click="closeTool">✕</button>
      </div>
      <div class="tooldesc">{{ cur.fullDesc }}</div>

      <!-- schema 表单 -->
      <div class="form">
        <div v-if="!Object.keys(cur.props).length" class="empty2">无需参数，直接执行</div>
        <div v-for="(p, k) in cur.props" :key="k" class="frow">
          <div class="flabel">
            <span class="mono">{{ k }}</span>
            <span v-if="cur.required.includes(k)" class="req">必填</span>
            <span v-else class="opt">选填</span>
          </div>
          <input v-if="ptype(p) === 'text'" v-model="cur.args[k]" :placeholder="propDesc(p)">
          <input v-else-if="ptype(p) === 'number'" v-model="cur.args[k]" type="number" :placeholder="propDesc(p)">
          <div v-else class="swrow">
            <div :class="['sw', 'tap', { on: !!cur.args[k] }]" @click="cur.args[k] = !cur.args[k]"><div class="knob"></div></div>
            <span class="mini-hint">{{ propDesc(p) }}</span>
          </div>
        </div>
      </div>

      <button class="runb tap" :disabled="cur.busy" @click="run">{{ cur.busy ? '执行中…' : '▶ 执行' }}</button>

      <!-- 结果 -->
      <template v-if="cur.result">
        <div class="secl2">结果
          <button class="minib tap" style="margin-left:auto;" @click="copyRes">复制</button>
        </div>
        <div class="resbox">
          <template v-if="resultView(cur.result)?.type === 'err'">
            <div class="res-err">❌ {{ resultView(cur.result).text }}</div>
          </template>
          <template v-else-if="resultView(cur.result)?.type === 'ok'">
            <div class="res-ok">{{ resultView(cur.result).text }}</div>
          </template>
          <template v-else-if="resultView(cur.result)?.type === 'kv'">
            <div v-for="[k, v] in resultView(cur.result).list" :key="k" class="kvrow">
              <span class="kvk">{{ k }}</span><span class="kvv">{{ v }}</span>
            </div>
          </template>
          <template v-else-if="resultView(cur.result)?.type === 'list'">
            <div v-for="(x, i) in resultView(cur.result).list.slice(0, 100)" :key="i" class="lirow">{{ x }}</div>
          </template>
          <template v-else>
            <pre class="respre">{{ resultView(cur.result)?.text }}</pre>
          </template>
        </div>
      </template>
      <div style="height:14px;"></div>
    </div>
  </div>
</template>

<style scoped>
.h1 { font-size: 21px; font-weight: 700; margin: 6px 4px 2px; }
.sub { font-size: 13px; color: var(--muted); margin: 0 4px 12px; }
.searchbox { display: flex; align-items: center; gap: 8px; background: var(--card); border: 1px solid var(--line); border-radius: 13px; padding: 0 12px; margin-bottom: 6px; box-shadow: var(--shadow); }
.search-in { border: 0; background: none; padding: 11px 0; font-size: 14px; flex: 1; }
.search-x { border: 0; background: #EFEDE6; border-radius: 50%; width: 20px; height: 20px; font-size: 11px; color: var(--muted); }
.secl { margin: 14px 4px 7px; font-size: 12px; font-weight: 700; color: var(--muted); letter-spacing: 2px; display: flex; align-items: center; gap: 6px; }
.secl em { font-style: normal; font-weight: 400; letter-spacing: 0; font-size: 11px; opacity: .85; }
.grp-card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; overflow: hidden; box-shadow: var(--shadow); margin-bottom: 8px; }
.trow { display: flex; align-items: center; gap: 10px; padding: 11px 14px; border-bottom: 1px solid var(--line); }
.trow:last-child { border-bottom: 0; }
.trow:active { background: var(--bg); }
.trow-txt { flex: 1; min-width: 0; }
.trow-txt b { font-size: 14px; }
.trow-d { font-size: 11px; color: var(--muted); margin-top: 2px; }
.mono { font-family: ui-monospace, monospace; }
.pnum { background: var(--hill-soft); color: var(--hill); border-radius: 6px; padding: 0 5px; font-size: 10px; margin-left: 6px; }
.favn { margin-left: 4px; font-size: 12px; }
.ar { color: #B9B4A6; font-size: 18px; }
.empty2 { padding: 24px 16px; text-align: center; font-size: 13px; color: var(--muted); }
.toolview { position: fixed; inset: 0; z-index: 70; background: rgba(24,30,20,.5); display: flex; align-items: flex-end; }
.toolb { background: var(--bg); border-radius: 20px 20px 0 0; width: 100%; max-height: 88vh; overflow-y: auto; padding: 14px 14px 10px; animation: upin .2s ease; }
@keyframes upin { from { transform: translateY(40px); } }
.toolh { display: flex; align-items: center; gap: 8px; padding-bottom: 8px; border-bottom: 1px solid var(--line); }
.tooldesc { font-size: 12px; color: var(--muted); line-height: 1.6; padding: 8px 2px; }
.minib { border: 1px solid var(--line); background: var(--card); border-radius: 9px; padding: 5px 10px; font-size: 12px; font-weight: 600; color: var(--ink); }
.minib.ong { background: var(--hill-soft); border-color: var(--hill); color: var(--hill); }
.form { margin-top: 4px; }
.frow { margin-bottom: 10px; }
.flabel { display: flex; align-items: center; gap: 6px; margin-bottom: 5px; font-size: 12px; }
.req { background: #F8E8E5; color: var(--bad); font-size: 10px; border-radius: 5px; padding: 1px 6px; font-weight: 700; }
.opt { background: var(--bg); color: var(--muted); font-size: 10px; border-radius: 5px; padding: 1px 6px; }
.frow input { width: 100%; }
.swrow { display: flex; align-items: center; gap: 10px; }
.mini-hint { font-size: 11px; color: var(--muted); }
.sw { width: 46px; height: 28px; border-radius: 99px; background: #d8d6cd; position: relative; transition: background .2s; flex-shrink: 0; }
.sw.on { background: var(--hill); }
.sw .knob { position: absolute; top: 3px; left: 3px; width: 22px; height: 22px; border-radius: 50%; background: #fff; transition: left .18s; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
.sw.on .knob { left: 21px; }
.runb { width: 100%; border: 0; border-radius: 14px; background: var(--hill); color: #fff; font-size: 15px; font-weight: 700; padding: 13px; margin-top: 4px; }
.runb:disabled { opacity: .5; }
.runb:active { opacity: .85; }
.secl2 { margin: 14px 0 8px; font-size: 12px; font-weight: 700; color: var(--muted); display: flex; align-items: center; }
.resbox { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 10px; max-height: 40vh; overflow-y: auto; }
.res-ok { color: var(--hill); font-size: 14px; padding: 6px; }
.res-err { color: var(--bad); font-size: 13px; padding: 6px; line-height: 1.6; }
.kvrow { display: flex; gap: 10px; padding: 6px 4px; border-bottom: 1px dashed var(--line); font-size: 13px; }
.kvrow:last-child { border-bottom: 0; }
.kvk { color: var(--muted); font-family: ui-monospace, monospace; font-size: 12px; min-width: 30%; }
.kvv { flex: 1; word-break: break-all; }
.lirow { padding: 6px 4px; border-bottom: 1px dashed var(--line); font-size: 13px; }
.lirow:last-child { border-bottom: 0; }
.respre { font: 11px ui-monospace, monospace; white-space: pre-wrap; word-break: break-all; color: var(--ink); }
</style>
