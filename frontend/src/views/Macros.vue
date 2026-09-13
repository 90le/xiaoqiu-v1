<script setup>
import { ref, computed, onMounted } from 'vue'
import { call, outText } from '../api.js'

/* ═══ 数据 ═══ */
const macros = ref([])
const out = ref('')
const busy = ref('')
const runDlg = ref(null)      // 运行弹层 {name, desc, p1,p2,p3, speak}
const editDlg = ref(null)     // 编辑/新建 {name, desc, steps, isNew}
const extractDlg = ref(null)  // 从会话提取 {name, lean}
const delConfirm = ref('')
const lastRun = ref(null)     // {name, text, at}
const exportAll = ref(false)

async function load() {
  const r = await call('macro_list', {})
  const raw = r.ok ? (Array.isArray(r.data) ? r.data : r.data?.macros || r.data?.items || []) : []
  macros.value = raw.map(m => typeof m === 'string'
    ? { name: m, desc: '', steps: [] }
    : { name: m.name, desc: m.desc || '', steps: Array.isArray(m.steps) ? m.steps : (typeof m.steps === 'string' ? safeJson(m.steps) : []), n: m.steps?.length }
  )
}
function safeJson(s) { try { return JSON.parse(s) || [] } catch { return [] } }
function flash(t) { out.value = t; setTimeout(() => out.value = '', 2000) }

/* ── 统计 ── */
const stats = computed(() => ({ total: macros.value.length, steps: macros.value.reduce((a, m) => a + (m.steps?.length || m.n || 0), 0) }))

/* ── 运行 ── */
function openRun(m) { runDlg.value = { name: m.name, desc: m.desc, p1: '', p2: '', p3: '', speak: true } }
async function doRun() {
  const d = runDlg.value
  if (!d) return
  busy.value = d.name
  const args = { name: d.name }
  if (d.p1) args.p1 = d.p1
  if (d.p2) args.p2 = d.p2
  if (d.p3) args.p3 = d.p3
  args.speak = d.speak
  runDlg.value = null
  const r = await call('macro_run', args)
  busy.value = ''
  lastRun.value = { name: d.name, text: outText(r), ok: r.ok, at: Date.now() }
}

/* ── 编辑/新建 ── */
function openEdit(m) {
  if (m) editDlg.value = { name: m.name, desc: m.desc, steps: JSON.stringify(m.steps || [], null, 1), isNew: false }
  else editDlg.value = { name: '', desc: '', steps: '[]', isNew: true }
}
async function saveEdit() {
  const d = editDlg.value
  if (!d || !d.name.trim() || !d.steps.trim()) return
  try { JSON.parse(d.steps) } catch { editDlg.value.err = true; return }
  const r = await call('macro_save', { name: d.name.trim(), desc: d.desc.trim() || d.name.trim(), steps: d.steps.trim() })
  flash(r.ok ? '✅ 已保存' : '❌ ' + outText(r))
  editDlg.value = null
  await load()
}
async function del(m) {
  if (delConfirm.value !== m.name) { delConfirm.value = m.name; setTimeout(() => delConfirm.value = '', 2500); return }
  delConfirm.value = ''
  await call('macro_del', { name: m.name })
  flash('已删除 ' + m.name)
  await load()
}

/* ── 从会话提取（复利飞轮） ── */
function openExtract() { extractDlg.value = { name: '', lean: true } }
async function doExtract() {
  const d = extractDlg.value
  if (!d || !d.name.trim()) return
  flash('⏳ 正在从最近会话提取…')
  const r = await call('macro_from_session', { name: d.name.trim(), lean: d.lean })
  extractDlg.value = null
  lastRun.value = { name: d.name, text: outText(r), ok: r.ok, at: Date.now() }
  await load()
}

/* ── 导入导出 ── */
async function exp(m, all) {
  const r = await call('macro_export', { name: all ? 'all' : m.name })
  flash(r.ok ? '✅ 已导出到 pibridge/macros' : '❌ ' + outText(r))
}
async function imp() {
  const r = await call('macro_import', { name: 'all' })
  flash(r.ok ? '✅ 已导入' : '❌ ' + outText(r))
  await load()
}

/* ── 步骤预览 ── */
const openSteps = ref('')
function toggleSteps(n) { openSteps.value = openSteps.value === n ? '' : n }
const stepTool = s => s?.tool || s?.name || '?'
const stepArgs = s => { try { return JSON.stringify(s?.args ?? s?.arguments ?? {}) } catch { return '' } }
</script>

<template>
  <div class="h1">自动化</div>
  <div class="sub">宏 = 跑通即固化的技能 · {{ stats.total }} 个 / {{ stats.steps }} 步</div>

  <!-- 新建入口 -->
  <div class="grp-card">
    <div class="grp tap" @click="openExtract"><span class="ic">♻️</span>
      <span class="gt"><span class="tt">从最近会话提取</span><span class="ts">让小丘刚跑通的流程一键变宏（复利飞轮）</span></span><span class="ar">›</span></div>
    <div class="grp tap" @click="openEdit(null)"><span class="ic">✍️</span>
      <span class="gt"><span class="tt">手动新建</span><span class="ts">工具+参数组合（JSON 步骤）</span></span><span class="ar">›</span></div>
    <div class="grp tap" @click="imp"><span class="ic">📥</span>
      <span class="gt"><span class="tt">导入全部</span><span class="ts">从 pibridge/macros 目录</span></span><span class="ar">›</span></div>
  </div>

  <!-- 最近运行 -->
  <div v-if="lastRun" class="card">
    <div class="sec2">最近运行 · {{ lastRun.name }}
      <span :class="['pill', lastRun.ok ? 'g' : 'r']">{{ lastRun.ok ? '✅ 成功' : '❌ 失败' }}</span></div>
    <div class="outbox">{{ lastRun.text }}</div>
  </div>

  <!-- 宏列表 -->
  <div class="secl">我的宏</div>
  <div class="grp-card" v-if="macros.length">
    <template v-for="m in macros" :key="m.name">
      <div class="srow">
        <div class="srow-txt tap" @click="toggleSteps(m.name)">
          <div class="st">⚡ {{ m.desc || m.name }}<span class="stepp">· {{ m.steps?.length || m.n || '?' }} 步</span></div>
          <div class="sd mono">{{ m.name }}</div>
        </div>
        <button class="runb tap" :disabled="busy === m.name" @click="openRun(m)">{{ busy === m.name ? '⟳' : '▶' }}</button>
        <button class="minib tap" @click="openEdit(m)">✎</button>
        <button class="minib tap" :class="{ r: delConfirm === m.name }" @click="del(m)">{{ delConfirm === m.name ? '确认?' : '🗑' }}</button>
      </div>
      <!-- 步骤预览 -->
      <div v-if="openSteps === m.name" class="steps">
        <div v-for="(s, i) in m.steps" :key="i" class="step">
          <span class="stepn">{{ i + 1 }}</span>
          <span class="stept mono">{{ stepTool(s) }}</span>
          <span class="stepa mono">{{ stepArgs(s).slice(0, 60) }}</span>
        </div>
        <div class="stepops">
          <button class="minib tap" @click="exp(m)">📤 导出</button>
        </div>
      </div>
    </template>
  </div>
  <div v-else class="card empty2">
    暂无宏。<br>让小丘跑通一个流程（对话或语音），然后「从最近会话提取」。<br>跑通的每一步都会变成可复用的技能。
  </div>
  <div v-if="macros.length > 1" class="expall tap" @click="exp(null, true)">📤 导出全部宏</div>
  <div style="height:16px;"></div>

  <!-- 运行弹层 -->
  <div v-if="runDlg" class="mask" @click="runDlg = null">
    <div class="sheet" @click.stop>
      <div class="sh"><b style="flex:1;">▶ 运行 · {{ runDlg.desc || runDlg.name }}</b><button class="minib tap" @click="runDlg = null">✕</button></div>
      <div v-if="runDlg.desc" class="sd" style="padding:0 4px;">{{ runDlg.desc }}</div>
      <div class="pgrid">
        <input v-model="runDlg.p1" placeholder="参数 p1">
        <input v-model="runDlg.p2" placeholder="参数 p2">
        <input v-model="runDlg.p3" placeholder="参数 p3">
      </div>
      <div class="hint" v-pre>宏里用 {{p1}}/{{p2}}/{{p3}} 占位的地方会被替换；不用的留空即可。</div>
      <div class="swrow">
        <span style="font-size:14px;">完成后语音播报</span>
        <div :class="['sw', 'tap', { on: runDlg.speak }]" @click="runDlg.speak = !runDlg.speak"><div class="knob"></div></div>
      </div>
      <button class="go tap" @click="doRun">▶ 执行</button>
    </div>
  </div>

  <!-- 编辑弹层 -->
  <div v-if="editDlg" class="mask" @click="editDlg = null">
    <div class="sheet" @click.stop>
      <div class="sh"><b style="flex:1;">{{ editDlg.isNew ? '新建宏' : '编辑 · ' + editDlg.name }}</b><button class="minib tap" @click="editDlg = null">✕</button></div>
      <input v-if="editDlg.isNew" v-model="editDlg.name" placeholder="宏名（英文标识 如 morning_brief）" class="mono" style="margin-bottom:8px;">
      <input v-model="editDlg.desc" placeholder="中文说明（如：早报播报）" style="margin-bottom:8px;">
      <textarea v-model="editDlg.steps" rows="10" class="mono ta" :class="{ err: editDlg.err }"
        placeholder='[{"tool":"tts_speak","args":{"text":"你好"}}]'></textarea>
      <div v-if="editDlg.err" class="errline">❌ steps 不是合法 JSON</div>
      <div class="hint" v-pre>每步 {"tool":"工具名","args":{参数}}。支持 {{p1}}~{{p3}} 占位、{{prev}} 上步结果。</div>
      <div class="rowb">
        <button class="minib tap" style="flex:1;padding:11px;" @click="editDlg = null">取消</button>
        <button class="go tap" style="flex:1;" @click="saveEdit">💾 保存</button>
      </div>
    </div>
  </div>

  <!-- 提取弹层 -->
  <div v-if="extractDlg" class="mask" @click="extractDlg = null">
    <div class="sheet" @click.stop>
      <div class="sh"><b style="flex:1;">♻️ 从最近会话提取宏</b><button class="minib tap" @click="extractDlg = null">✕</button></div>
      <input v-model="extractDlg.name" placeholder="宏名（英文标识）" class="mono" style="margin-bottom:8px;">
      <div class="swrow">
        <span style="font-size:14px;">精简模式</span>
        <div :class="['sw', 'tap', { on: extractDlg.lean }]" @click="extractDlg.lean = !extractDlg.lean"><div class="knob"></div></div>
      </div>
      <div class="hint">读取小丘最近一次会话的操作记录，自动转成宏步骤。精简模式会剔除验证性截图/等待步骤。</div>
      <button class="go tap" @click="doExtract">♻️ 提取</button>
    </div>
  </div>
  <div v-if="out" class="toast">{{ out }}</div>
</template>

<style scoped>
.h1 { font-size: 21px; font-weight: 700; margin: 6px 4px 2px; }
.sub { font-size: 13px; color: var(--muted); margin: 0 4px 14px; }
.grp-card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; overflow: hidden; box-shadow: var(--shadow); margin-bottom: 10px; }
.grp { display: flex; align-items: center; gap: 12px; padding: 13px 14px; border-bottom: 1px solid var(--line); }
.grp:last-child { border-bottom: 0; }
.grp:active { background: var(--bg); }
.ic { font-size: 18px; width: 36px; height: 36px; display: flex; align-items: center; justify-content: center; background: var(--hill-soft); border-radius: 11px; }
.gt { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.tt { font-size: 15px; font-weight: 700; }
.ts { font-size: 12px; color: var(--muted); margin-top: 2px; }
.ar { color: #B9B4A6; font-size: 19px; }
.secl { margin: 14px 4px 7px; font-size: 12px; font-weight: 700; color: var(--muted); letter-spacing: 2px; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 14px; margin-bottom: 10px; box-shadow: var(--shadow); }
.sec2 { font-weight: 700; font-size: 14px; display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.pill { font-size: 10px; padding: 2px 8px; border-radius: 99px; font-weight: 700; }
.pill.g { background: var(--hill-soft); color: var(--hill); }
.pill.r { background: #F8E8E5; color: var(--bad); }
.outbox { font-size: 13px; white-space: pre-wrap; word-break: break-all; background: var(--bg); border-radius: 10px; padding: 10px; max-height: 180px; overflow-y: auto; }
.srow { display: flex; align-items: center; gap: 8px; padding: 11px 12px; border-bottom: 1px solid var(--line); }
.srow-txt { flex: 1; min-width: 0; }
.st { font-size: 14.5px; font-weight: 600; }
.stepp { font-size: 11px; color: var(--muted); font-weight: 400; margin-left: 6px; }
.sd { font-size: 11px; color: var(--muted); margin-top: 2px; }
.mono { font-family: ui-monospace, monospace; }
.runb { border: 0; background: var(--hill); color: #fff; border-radius: 11px; width: 42px; height: 38px; font-size: 16px; font-weight: 700; flex-shrink: 0; }
.runb:disabled { opacity: .5; }
.minib { border: 1px solid var(--line); background: var(--card); border-radius: 9px; padding: 6px 10px; font-size: 12px; font-weight: 600; color: var(--ink); flex-shrink: 0; }
.minib.r { background: #F8E8E5; border-color: var(--bad); color: var(--bad); }
.steps { background: var(--bg); padding: 10px 14px; border-bottom: 1px solid var(--line); }
.step { display: flex; gap: 8px; align-items: baseline; padding: 4px 0; }
.stepn { font-size: 10px; color: var(--muted); width: 14px; }
.stept { font-size: 12px; color: var(--hill); font-weight: 700; }
.stepa { font-size: 10px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.stepops { margin-top: 6px; }
.empty2 { text-align: center; color: var(--muted); font-size: 13px; line-height: 1.8; }
.expall { text-align: center; font-size: 12px; color: var(--muted); padding: 8px; }
.mask { position: fixed; inset: 0; z-index: 70; background: rgba(24,30,20,.5); display: flex; align-items: flex-end; }
.sheet { background: var(--bg); border-radius: 20px 20px 0 0; width: 100%; padding: 14px 14px 18px; animation: upin .2s ease; max-height: 85vh; overflow-y: auto; }
@keyframes upin { from { transform: translateY(40px); } }
.sh { display: flex; align-items: center; gap: 8px; padding-bottom: 10px; border-bottom: 1px solid var(--line); margin-bottom: 10px; font-size: 15px; }
.pgrid { display: flex; gap: 8px; margin: 8px 0; }
.pgrid input { flex: 1; min-width: 0; }
.hint { font-size: 12px; color: var(--muted); line-height: 1.6; margin: 6px 0 10px; }
.swrow { display: flex; align-items: center; justify-content: space-between; padding: 8px 4px 12px; }
.sw { width: 46px; height: 28px; border-radius: 99px; background: #d8d6cd; position: relative; transition: background .2s; flex-shrink: 0; }
.sw.on { background: var(--hill); }
.sw .knob { position: absolute; top: 3px; left: 3px; width: 22px; height: 22px; border-radius: 50%; background: #fff; transition: left .18s; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
.sw.on .knob { left: 21px; }
.go { width: 100%; border: 0; border-radius: 14px; background: var(--hill); color: #fff; font-size: 15px; font-weight: 700; padding: 13px; margin-top: 6px; }
.go:active { opacity: .85; }
.ta { width: 100%; border: 1px solid var(--line); border-radius: 10px; padding: 10px; font-size: 12px; line-height: 1.6; }
.ta.err { border-color: var(--bad); }
.errline { color: var(--bad); font-size: 12px; margin-top: 4px; }
.rowb { display: flex; gap: 8px; margin-top: 8px; }
.toast { position: fixed; left: 50%; bottom: 90px; transform: translateX(-50%); z-index: 80; background: rgba(34,48,31,.92); color: #fff; font-size: 13px; padding: 9px 18px; border-radius: 99px; max-width: 86vw; }
</style>
