<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { call, outText, cfgAll, cfgSet } from '../api.js'

/* ═══ 子页导航（同标准模式）═══ */
const page = ref(null)
const pages = { all: '全部记忆', core: '核心记忆', clean: '清理', settings: '设置' }
let subPushed = false
function openPage(p) { page.value = p; try { history.pushState({ sub: p }, ''); subPushed = true } catch { subPushed = false } }
function onPop() { if (page.value) { page.value = null; subPushed = false } }
function back() { if (subPushed) { try { history.back(); return } catch {} } page.value = null; subPushed = false }
onMounted(() => { window.addEventListener('popstate', onPop); load() })
onUnmounted(() => { window.removeEventListener('popstate', onPop); if (subPushed) { try { history.back() } catch {} } })

/* ═══ 数据 ═══ */
const items = ref([])     // [{key,v,t,cat,pin,src}]
const kw = ref('')
const cfg = ref({})
const out = ref('')
const editDlg = ref(null) // {key, v, pin}
const confirmClean = ref('')
const CATS = { user: ['👤', '用户偏好'], app: ['📱', '应用知识'], voice: ['🗣', '播报记忆'], person: ['👥', '人物'], fact: ['📌', '事实'], skill: ['⚡', '技能'], other: ['📦', '其他'] }

async function load() {
  cfg.value = await cfgAll()
  const r = await call('memory_list', {})
  const raw = r.ok ? (Array.isArray(r.data) ? r.data : r.data?.items || []) : []
  items.value = raw.map(x => (typeof x === 'string'
    ? { key: x, v: '', t: 0, cat: 'other', pin: false, src: 'manual' }
    : { key: x.key, v: x.v, t: x.t || 0, cat: x.cat || (x.key.split('.')[0] in CATS ? x.key.split('.')[0] : 'other'), pin: !!x.pin, src: x.src || 'manual' }))
}
function flash(t) { out.value = t; setTimeout(() => out.value = '', 1600) }

/* ── 统计 ── */
const stats = computed(() => {
  const byCat = {}
  let pinned = 0
  for (const it of items.value) { byCat[it.cat] = (byCat[it.cat] || 0) + 1; if (it.pin) pinned++ }
  return { byCat, pinned, total: items.value.length }
})
const timeline = computed(() => [...items.value].filter(x => x.t).sort((a, b) => b.t - a.t).slice(0, 8))

/* ── 搜索过滤 ── */
const filtered = computed(() => {
  const q = kw.value.trim().toLowerCase()
  return [...items.value]
    .sort((a, b) => (b.pin - a.pin) || (b.t - a.t))
    .filter(x => !q || x.key.toLowerCase().includes(q) || (x.v || '').toLowerCase().includes(q))
})
const grouped = computed(() => {
  const g = {}
  for (const x of filtered.value) (g[x.cat] = g[x.cat] || []).push(x)
  return g
})
const pinnedList = computed(() => items.value.filter(x => x.pin))

/* ── 操作 ── */
async function togglePin(x) {
  const r = await call('memory_pin', { key: x.key, on: !x.pin })
  if (r.ok) { x.pin = !x.pin; flash(x.pin ? '📌 已置顶为核心记忆' : '已取消置顶') }
}
function openEdit(x) { editDlg.value = { key: x.key, v: x.v, pin: x.pin } }
async function saveEdit() {
  const d = editDlg.value
  if (!d) return
  const r = await call('memory_edit', { key: d.key, value: d.v })
  if (r.ok) { flash('已保存'); editDlg.value = null; await load() }
}
async function del(x) {
  const r = await call('memory_del', { key: x.key })
  if (r.ok) { flash('已删除'); await load() }
}
async function doCleanNow(cat, days) {
  const r = await call('memory_clean', { cat: cat || '', olderThanDays: days || 0 })
  flash(r.ok ? `已清理 ${r.data?.cleaned ?? 0} 条` : '失败')
  await load()
}
async function setCfg(k, v) { await cfgSet(k, v); flash('已保存') }
function doClean30() { if (confirmClean.value !== 'v30') { confirmClean.value = 'v30'; setTimeout(() => confirmClean.value = '', 2500); return } confirmClean.value = ''; doCleanNow('voice', 30) }
function doClean7() { if (confirmClean.value !== 'all7') { confirmClean.value = 'all7'; setTimeout(() => confirmClean.value = '', 2500); return } confirmClean.value = ''; doCleanNow('', 7) }
function doCleanCat(cat) { if (confirmClean.value !== cat) { confirmClean.value = cat; setTimeout(() => confirmClean.value = '', 2500); return } confirmClean.value = ''; doCleanNow(cat, 0) }
const ago = t => {
  if (!t) return ''
  const s = (Date.now() - t) / 1000
  return s < 3600 ? Math.floor(s / 60) + '分前' : s < 86400 ? Math.floor(s / 3600) + '时前' : Math.floor(s / 86400) + '天前'
}
</script>

<template>
  <!-- ═══ 首页 ═══ -->
  <div v-show="!page">
    <div class="h1">记忆</div>
    <div class="sub">小丘知道的一切 · {{ stats.total }} 条（核心 {{ stats.pinned }}）</div>

    <!-- 核心记忆速览（注入上下文的那些） -->
    <div class="secl">核心认知 <em>每次对话自动携带</em></div>
    <div class="grp-card">
      <div v-for="x in pinnedList.slice(0, 4)" :key="x.key" class="srow">
        <span class="pinmark">📌</span>
        <div class="srow-txt"><div class="srow-v">{{ x.v }}</div></div>
      </div>
      <div v-if="!pinnedList.length" class="empty2" @click="openPage('all')">还没有核心记忆 · 去全部记忆里 📌 置顶几条，小丘就怎么认识你</div>
      <div v-else class="grp more tap" @click="openPage('core')"><span class="grp-s">查看全部 {{ pinnedList.length }} 条 ›</span></div>
    </div>

    <!-- 分类 -->
    <div class="secl">记忆分类</div>
    <div class="grp-card">
      <div v-for="(meta, cat) in CATS" :key="cat" class="grp tap" @click="kw = cat === 'other' ? '' : cat + '.'; openPage('all')">
        <span class="grp-ic">{{ meta[0] }}</span>
        <span class="grp-txt"><span class="grp-t">{{ meta[1] }}</span>
          <span class="grp-s">{{ stats.byCat[cat] || 0 }} 条</span></span>
        <span class="grp-ar">›</span>
      </div>
    </div>

    <!-- 最近沉淀（透明性时间线） -->
    <div class="secl">最近沉淀</div>
    <div class="card">
      <div v-for="x in timeline" :key="x.key" class="tline">
        <span class="tdot" :class="x.src"></span>
        <div style="flex:1;min-width:0;">
          <div class="tl-v">{{ x.v }}</div>
          <div class="tl-m">{{ CATS[x.cat]?.[0] }} {{ ago(x.t) }} · {{ x.src === 'auto' ? '小丘自动记的' : x.src === 'voice' ? '来自播报' : x.src === 'app' ? '应用操作学习' : '手动' }}</div>
        </div>
      </div>
      <div v-if="!timeline.length" class="empty2">暂无</div>
    </div>

    <div class="grp-card">
      <div class="grp tap" @click="openPage('clean')"><span class="grp-ic">🧹</span>
        <span class="grp-txt"><span class="grp-t">清理</span><span class="grp-s">过期播报记忆/按分类清</span></span><span class="grp-ar">›</span></div>
      <div class="grp tap" @click="openPage('settings')"><span class="grp-ic">⚙️</span>
        <span class="grp-txt"><span class="grp-t">设置</span><span class="grp-s">自动沉淀 · 注入</span></span><span class="grp-ar">›</span></div>
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

    <template v-if="page === 'all' || page === 'core'">
      <div class="searchbox">
        <span>🔍</span>
        <input v-model="kw" placeholder="搜索记忆" class="search-in">
        <button v-if="kw" class="search-x tap" @click="kw = ''">✕</button>
      </div>
      <template v-for="(arr, cat) in grouped" :key="cat">
        <div class="secl">{{ CATS[cat]?.[0] }} {{ CATS[cat]?.[1] || cat }} <em>{{ arr.length }}</em></div>
        <div class="grp-card">
          <div v-for="x in arr" :key="x.key" class="mrow">
            <div class="mrow-txt tap" @click="openEdit(x)">
              <div class="mrow-v">{{ x.pin ? '📌 ' : '' }}{{ x.v }}</div>
              <div class="mrow-k">{{ x.key }} · {{ ago(x.t) }}</div>
            </div>
            <button :class="['minib', 'tap', { ong: x.pin }]" @click="togglePin(x)">📌</button>
            <button class="minib tap" style="color:var(--bad);" @click="del(x)">🗑</button>
          </div>
          <div v-if="!arr.length" class="empty2">无匹配</div>
        </div>
      </template>
      <div v-if="!filtered.length" class="empty2" style="padding:30px;">没有记忆。去对话里告诉小丘你的偏好，或等播报/应用学习自动沉淀。</div>
    </template>

    <template v-else-if="page === 'clean'">
      <div class="hint">清理不会动 📌 置顶的核心记忆。</div>
      <div class="secl">按时间</div>
      <div class="grp-card">
        <div class="srow"><div class="srow-txt"><div class="srow-v">30 天前的播报记忆</div><div class="mrow-k">过期通知已无价值</div></div>
          <button :class="['minib', 'tap', confirmClean === 'v30' ? 'onr' : '']" @click="doClean30">{{ confirmClean === 'v30' ? '确认?' : '清理' }}</button></div>
        <div class="srow"><div class="srow-txt"><div class="srow-v">7 天前的全部记忆</div><div class="mrow-k">大扫除（保留置顶）</div></div>
          <button :class="['minib', 'tap', confirmClean === 'all7' ? 'onr' : '']" @click="doClean7">{{ confirmClean === 'all7' ? '确认?' : '清理' }}</button></div>
      </div>
      <div class="secl">按分类全清</div>
      <div class="grp-card">
        <div v-for="(meta, cat) in CATS" :key="cat" class="srow">
          <div class="srow-txt"><div class="srow-v">{{ meta[0] }} {{ meta[1] }}</div><div class="mrow-k">{{ stats.byCat[cat] || 0 }} 条</div></div>
          <button :class="['minib', 'tap', confirmClean === cat ? 'onr' : '']" @click="doCleanCat(cat)">{{ confirmClean === cat ? '确认?' : '清空' }}</button>
        </div>
      </div>
    </template>

    <template v-else-if="page === 'settings'">
      <div class="grp-card">
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-v">自动沉淀 <em class="mini-hint">对话后小丘自动提取值得记住的事</em></div>
            <div class="mrow-k">提取用快脑，每轮 ≤5 条，同主题自动更新不堆叠</div>
          </div>
          <div :class="['sw', 'tap', { on: cfg.memory_auto !== 'false' }]" @click="setCfg('memory_auto', cfg.memory_auto === 'false' ? 'true' : 'false')"><div class="knob"></div></div>
        </div>
        <div class="srow">
          <div class="srow-txt">
            <div class="srow-v">核心记忆注入 <em class="mini-hint">对话自动携带 📌 条目</em></div>
            <div class="mrow-k">关闭则小丘"失忆"（只靠检索）</div>
          </div>
          <div :class="['sw', 'tap', { on: cfg.memory_inject !== 'false' }]" @click="setCfg('memory_inject', cfg.memory_inject === 'false' ? 'true' : 'false')"><div class="knob"></div></div>
        </div>
      </div>
      <div class="hint">注入开关生效于引擎侧下一步接线；当前 📌 条目已自动携带。</div>
    </template>
    <div style="height:24px;"></div>
  </div>

  <!-- 编辑弹层 -->
  <div v-if="editDlg" class="editview" @click="editDlg = null">
    <div class="editb" @click.stop>
      <div class="skview-h">
        <div class="skview-tt" style="flex:1;"><b>编辑记忆</b></div>
        <button class="minib tap" @click="editDlg = null">✕</button>
      </div>
      <div class="mrow-k" style="margin:8px 0 4px;">{{ editDlg.key }}</div>
      <textarea v-model="editDlg.v" rows="5" style="width:100%;border:1px solid var(--line);border-radius:10px;padding:10px;font-size:14px;"></textarea>
      <div style="display:flex;gap:8px;margin-top:10px;">
        <button class="minib tap" style="flex:1;padding:11px;" @click="editDlg = null">取消</button>
        <button class="btn2 tap" style="flex:1;" @click="saveEdit">保存</button>
      </div>
    </div>
  </div>
  <div v-if="out" class="toast">{{ out }}</div>
</template>

<style scoped>
.grp-card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; overflow: hidden; box-shadow: var(--shadow); margin-bottom: 12px; }
.grp { display: flex; align-items: center; gap: 12px; padding: 13px 14px; border-bottom: 1px solid var(--line); }
.grp:last-child { border-bottom: 0; }
.grp:active { background: var(--bg); }
.grp.more { justify-content: center; padding: 10px; }
.grp-ic { font-size: 19px; width: 38px; height: 38px; display: flex; align-items: center; justify-content: center; background: var(--hill-soft); border-radius: 11px; }
.grp-txt { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.grp-t { font-size: 15px; font-weight: 700; }
.grp-s { font-size: 12px; color: var(--muted); margin-top: 2px; }
.grp-ar { color: #B9B4A6; font-size: 20px; font-weight: 300; }
.h1 { font-size: 21px; font-weight: 700; margin: 6px 4px 2px; }
.sub { font-size: 13px; color: var(--muted); margin: 0 4px 14px; }
.secl { margin: 16px 4px 7px; font-size: 12px; font-weight: 700; color: var(--muted); letter-spacing: 2px; display: flex; align-items: center; gap: 6px; }
.secl em { font-style: normal; font-weight: 400; letter-spacing: 0; font-size: 11px; opacity: .85; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 14px; margin-bottom: 12px; box-shadow: var(--shadow); }
.srow { display: flex; align-items: center; gap: 10px; padding: 11px 14px; border-bottom: 1px solid var(--line); }
.srow:last-child { border-bottom: 0; }
.srow-txt { flex: 1; min-width: 0; }
.srow-v { font-size: 14px; font-weight: 600; line-height: 1.5; }
.mrow { display: flex; align-items: center; gap: 8px; padding: 11px 14px; border-bottom: 1px solid var(--line); }
.mrow:last-child { border-bottom: 0; }
.mrow-txt { flex: 1; min-width: 0; }
.mrow-v { font-size: 13.5px; line-height: 1.55; word-break: break-all; }
.mrow-k { font-size: 11px; color: var(--muted); font-family: ui-monospace, monospace; margin-top: 3px; }
.pinmark { font-size: 15px; }
.empty2 { padding: 18px 16px; text-align: center; font-size: 13px; color: var(--muted); line-height: 1.7; }
.tline { display: flex; gap: 10px; padding: 7px 0; }
.tdot { width: 8px; height: 8px; border-radius: 50%; background: var(--line); margin-top: 6px; flex-shrink: 0; }
.tdot.auto { background: var(--hill); }
.tdot.voice { background: var(--dawn); }
.tdot.app { background: #3D6BE8; }
.tl-v { font-size: 13px; line-height: 1.5; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tl-m { font-size: 11px; color: var(--muted); margin-top: 2px; }
.minib { border: 1px solid var(--line); background: var(--card); border-radius: 9px; padding: 5px 10px; font-size: 12px; font-weight: 600; color: var(--ink); }
.minib.ong { background: var(--hill-soft); border-color: var(--hill); color: var(--hill); }
.minib.onr { background: #F8E8E5; border-color: var(--bad); color: var(--bad); }
.mini-hint { font-style: normal; font-size: 11px; color: var(--muted); font-weight: 400; }
.hint { font-size: 12px; color: var(--muted); line-height: 1.65; margin-bottom: 10px; }
.sw { width: 46px; height: 28px; border-radius: 99px; background: #d8d6cd; position: relative; transition: background .2s; flex-shrink: 0; }
.sw.on { background: var(--hill); }
.sw .knob { position: absolute; top: 3px; left: 3px; width: 22px; height: 22px; border-radius: 50%; background: #fff; transition: left .18s; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
.sw.on .knob { left: 21px; }
.searchbox { display: flex; align-items: center; gap: 8px; background: var(--card); border: 1px solid var(--line); border-radius: 13px; padding: 0 12px; margin: 2px 0 4px; box-shadow: var(--shadow); }
.search-in { border: 0; background: none; padding: 11px 0; font-size: 14px; flex: 1; }
.search-x { border: 0; background: #EFEDE6; border-radius: 50%; width: 20px; height: 20px; font-size: 11px; color: var(--muted); }
.subp { position: fixed; inset: 0; z-index: 60; background: var(--bg); overflow-y: auto; padding: 0 12px 60px; animation: slidein .22s ease; }
@keyframes slidein { from { transform: translateX(100%); } to { transform: none; } }
.subbar { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; gap: 4px; padding: 8px 2px; background: var(--bg); border-bottom: 1px solid var(--line); margin-bottom: 10px; min-height: 46px; }
.backb { border: 0; background: var(--card); color: var(--ink); font-size: 20px; font-weight: 700; width: 34px; height: 34px; border-radius: 11px; border: 1px solid var(--line); }
.subbar-t { font-size: 17px; font-weight: 800; margin: 0 auto; }
.subbar-r { min-width: 34px; }
.editview { position: fixed; inset: 0; z-index: 70; background: rgba(24,30,20,.5); display: flex; align-items: center; justify-content: center; padding: 24px; }
.editb { background: var(--card); border-radius: 18px; padding: 14px; width: 100%; max-width: 400px; }
.skview-h { display: flex; align-items: center; gap: 10px; padding-bottom: 8px; border-bottom: 1px solid var(--line); }
.skview-tt { flex: 1; min-width: 0; }
.btn2 { border: 0; border-radius: 11px; background: var(--hill); color: #fff; font-size: 14px; font-weight: 700; padding: 11px; }
.toast { position: fixed; left: 50%; bottom: 90px; transform: translateX(-50%); z-index: 80; background: rgba(34,48,31,.92); color: #fff; font-size: 13px; padding: 9px 18px; border-radius: 99px; }
</style>
