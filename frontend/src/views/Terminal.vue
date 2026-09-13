<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { chat, wsSend, connect } from '../useChat.js'
import { tstore, poolAttach, poolDetach, createSession, attachSession, killSession, restartSession, renameSession } from '../termStore.js'
import '@xterm/xterm/css/xterm.css'

const stage = ref(null)
const activeId = ref('')
const tabMenu = ref(null) // { id, title, x }
let pressTimer = null
function tabPress(s, e) {
  const x = e.touches ? e.touches[0].clientX : e.clientX
  pressTimer = setTimeout(() => {
    if (navigator.vibrate) try { navigator.vibrate(15) } catch {}
    tabMenu.value = { id: s.id, title: s.title, x: Math.min(Math.max(x - 70, 8), window.innerWidth - 156) }
  }, 480)
}
function tabRelease() { if (pressTimer) clearTimeout(pressTimer); pressTimer = null }
const keysMode = ref('bar')     // bar（收缩：核心键） | full（展开：+扩展行）——常驻底部无悬浮球
// 编程符号（输入法难打的）——展开态横滚行
const kbSyms = ['`', '~', '|', '-', '/', '\\', ':', ';', '\'', '"', '[', ']', '{', '}', '<', '>', '(', ')', '$', '#', '%', '&', '*', '+', '=', '_', '!', '?', '@', '^', '.']
const dpadOff = ref(localStorage.getItem('xq_dpad_off') === '1')  // 悬浮方向键
// D-pad 任意位置拖动（位置持久化；球态：拖=移位 点=展开）
const dpadPos = ref(null)
try { dpadPos.value = JSON.parse(localStorage.getItem('xq_dpad_pos') || 'null') } catch {}
// localStorage 空时走 cfg 兜底（服务端落盘通道，永不受 WebView 存储策略影响）
if (!dpadPos.value) {
  fetch('/api/cfg_get', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    .then(r => r.json()).then(d => {
      const v = d?.structuredContent?.data?.dpad_pos
      if (v && !dpadPos.value) { try { dpadPos.value = JSON.parse(v) } catch {} }
    }).catch(() => {})
}
const dpadStyle = computed(() => {
  const p = dpadPos.value
  return p ? { left: p.x + 'px', top: p.y + 'px', right: 'auto', bottom: 'auto' } : {}
})
function saveDpadPos() {
  const j = JSON.stringify(dpadPos.value)
  try { localStorage.setItem('xq_dpad_pos', j) } catch {}
  try { fetch('/api/cfg_set', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ key: 'dpad_pos', value: j }) }) } catch {}
}
let dragInfo = null
// 越界救护：只在存档真跑出【窗口】可视区时才钳回（不按舞台矩形——键盘条/软键盘
// 状态会让舞台矩形变化，把好位置误"纠正"；窗口矩形恒定，误伤为零）
function sanitizeDpad() {
  const p = dpadPos.value
  if (!p) return
  const W = window.innerWidth, H = window.innerHeight
  const fixed = { x: Math.max(2, Math.min(W - 50, p.x)), y: Math.max(2, Math.min(H - 50, p.y)) }
  if (fixed.x !== p.x || fixed.y !== p.y) { dpadPos.value = fixed; saveDpadPos() }
}
function dragStart(e) {
  const t = e.touches ? e.touches[0] : e
  const r = e.currentTarget.getBoundingClientRect()
  dragInfo = { sx: t.clientX, sy: t.clientY, ox: r.left, oy: r.top, w: r.width, h: r.height, moved: false }
}
function dragMove(e) {
  if (!dragInfo) return
  e.preventDefault()
  const t = e.touches ? e.touches[0] : e
  const dx = t.clientX - dragInfo.sx, dy = t.clientY - dragInfo.sy
  if (Math.abs(dx) + Math.abs(dy) > 6) dragInfo.moved = true
  if (!dragInfo.moved) return
  // 钳制在终端舞台内（stage 矩形），不再用窗口矩形（会拖进键盘条/出屏）
  const stageNow = stage.value; const sr = stageNow?.getBoundingClientRect() || { left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight }
  const x = Math.max(sr.left + 2, Math.min(sr.right - dragInfo.w - 2, dragInfo.ox + dx))
  const y = Math.max(sr.top + 2, Math.min(sr.bottom - dragInfo.h - 2, dragInfo.oy + dy))
  dpadPos.value = { x, y }
}
function collapsePad() {
  if (ballAnchor) { dpadPos.value = ballAnchor; ballAnchor = null } // 回展开前的真实球位
  dpadOff.value = true; saveDpad(true)
}
function dragEnd() {
  if (dragInfo?.moved) { ballAnchor = null; saveDpadPos() } // 用户手动拖=新意图，锚点作废
  dragInfo = null
}
function fabEnd() {
  if (dragInfo && !dragInfo.moved) { dpadOff.value = false; saveDpad(false); clampForPad() } // 点=展开
  else if (dragInfo && dragInfo.moved) saveDpadPos() // 拖=存档（撤复位轮误删，位置"固定"的根因）
  dragInfo = null
}
// 展开后按 pad 实际尺寸钳回舞台（球可贴边，pad 大不能出界）。
// 只做视觉钳位：真实球位存档不覆写，收起（⌄）时回到原位。
let ballAnchor = null // 展开前的真实球位
function clampForPad() {
  nextTick(() => {
    const st = stage.value?.getBoundingClientRect?.()
    const p = dpadPos.value
    if (!st || !st.width || !p) return
    const el = document.querySelector('.termwrap .dpad')
    const w = el?.offsetWidth || 160, h = el?.offsetHeight || 230
    if (p.x + w > st.right - 2 || p.y + h > st.bottom - 2 || p.x < st.left || p.y < st.top) {
      ballAnchor = { x: p.x, y: p.y }
      dpadPos.value = {
        x: Math.max(st.left + 2, Math.min(st.right - w - 2, p.x)),
        y: Math.max(st.top + 2, Math.min(st.bottom - h - 2, p.y)),
      }
    } else ballAnchor = null
  })
}

function saveDpad(off) { try { localStorage.setItem('xq_dpad_off', off ? '1' : '0') } catch {} }
// 读剪贴板：原生桥优先（WebView 无 navigator.clipboard 权限），标准 API 兜底
async function readClip() {
  try {
    const t = window.XiaoqiuBridge?.clipRead?.()
    if (t) return t
  } catch {}
  try { return await navigator.clipboard.readText() || '' } catch { return '' }
}
async function pasteClip() {
  const t = await readClip()
  if (t) { raw(t); try { window.XiaoqiuBridge?.toast?.('📋 已粘贴') } catch {} }
  else { try { window.XiaoqiuBridge?.toast?.('剪贴板为空') } catch {} }
}
const ctrlOn = ref(false), altOn = ref(false)
const renaming = ref(''), renameId = ref('')
const confirmKill = ref('')
let killTimer = null
function resetConfirm(id) { if (killTimer) clearTimeout(killTimer); killTimer = setTimeout(() => { if (confirmKill.value === id) confirmKill.value = '' }, 3000) }

const sessions = computed(() => tstore.order.map(id => tstore.sessions[id]).filter(s => s && !s.remote))
// AI 命令终端：内嵌版 terminal_list 无 agentBash 标志（源码 info() 实证）——
// ai-bash 终端特征：id='ai-bash' / title='AI bash'；命令标签带 command 字段
const isAiTerm = (t) => !!(t.agentBash || t.command || t.id === 'ai-bash' || t.title === 'AI bash')
const aiTerms = computed(() => (chat.terminals || []).filter(isAiTerm))
// M2: AI 终端退出 → 5 秒后自动从列表移除（防死标签堆积）
watch(() => chat.terminals.map(t => `${t.id}:${t.running}`).join(','), () => {
  const dead = (chat.terminals || []).filter(t => isAiTerm(t) && !t.running && t.exitCode != null)
  for (const d of dead) {
    setTimeout(() => {
      const idx = chat.terminals.findIndex(t => t.id === d.id)
      if (idx >= 0 && !chat.terminals[idx].running) {
        chat.terminals.splice(idx, 1)
        const s = tstore.sessions[d.id]
        if (s) killSession(d.id)
      }
    }, 5000)
  }
})
function openAi(t) {
  attachSession(t.id, t.title || (t.command?.name || t.command?.command || 'AI 命令').slice(0, 12), t.cwd)
  nextTick(() => activate(t.id))
}

function openShellDrawer() { window.dispatchEvent(new Event('xq-open-drawer')) }
function showKb() { try { window.XiaoqiuBridge && window.XiaoqiuBridge.showKeyboard() } catch {} }
/* ══ 命令快捷面板（.pi/commands.json 任务复用）═══ */
import * as UC from '../useChat.js' // api 直用
const cmdPanel = ref(false)
const cmdEdit = ref(null)   // 编辑草稿 { name, command, cwd } | null
const cmdDel = ref('')
function openCmds() { cmdPanel.value = true; UC.api.listCommands() }
function runCmd(def) {
  cmdPanel.value = false
  const cwd = (def.cwd || '').replace('\${pwd}', tstore.sessions[activeId.value]?.cwd || '')
  const s = createSession(cwd || undefined, { title: def.name || '命令', command: def.command })
  if (s) nextTick(() => activate(s.id))
}
function editCmd(def, idx) {
  cmdEdit.value = def ? { idx, name: def.name, command: def.command, cwd: def.cwd || '' } : { idx: -1, name: '', command: '', cwd: '' }
}
function delCmd(def, idx) {
  if (cmdDel.value !== def?.name) { cmdDel.value = def?.name || ''; setTimeout(() => cmdDel.value = '', 2500); return }
  const next = chat.commands.filter((_, i) => i !== idx)
  UC.api.saveCommands(next)
  cmdDel.value = ''
}
function saveCmd() {
  const d = cmdEdit.value
  if (!d || !d.name.trim() || !d.command.trim()) return
  const cur = [...chat.commands]
  if (d.idx >= 0) cur[d.idx] = { name: d.name.trim(), command: d.command.trim(), ...(d.cwd.trim() ? { cwd: d.cwd.trim() } : {}) }
  else cur.push({ name: d.name.trim(), command: d.command.trim(), ...(d.cwd.trim() ? { cwd: d.cwd.trim() } : {}) })
  UC.api.saveCommands(cur)
  cmdEdit.value = null
}
/* ══ 选区复制（浏览器式）：长按选词 → 双拖柄调整 → 复制 ══
 * xterm 画布无原生选择，自绘：像素↔单元格换算 + 高亮框 + 起止拖柄 */
const sel = reactive({ on: false, a: null, b: null, bar: null, busy: '' }) // a/b = {col,row} buffer 绝对
let lpT = null, lpXY = null, selDrag = null, selScrollT = 0
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))
function selSession() { return tstore.sessions[activeId.value] }
function cellFromXY(x, y, s) {
  const r = s.el.getBoundingClientRect()
  const buf = s.term.buffer.active
  return {
    col: clamp(Math.floor((x - r.left) / r.width * s.term.cols), 0, s.term.cols - 1),
    row: clamp(buf.viewportY + Math.floor((y - r.top) / r.height * s.term.rows), 0, buf.length - 1),
  }
}
function selEnds() { // 排序后的起止
  if (!sel.a || !sel.b) return null
  const ka = sel.a.row * 1000 + sel.a.col, kb = sel.b.row * 1000 + sel.b.col
  return ka <= kb ? { s: sel.a, e: sel.b } : { s: sel.b, e: sel.a }
}
const selRects = computed(() => { // 高亮框（viewport 像素坐标）
  const s = selSession(), ends = selEnds()
  if (!sel.on || !s || !ends) return []
  const r = s.el.getBoundingClientRect(), buf = s.term.buffer.active, out = []
  for (let row = ends.s.row; row <= ends.e.row; row++) {
    const vis = row - buf.viewportY
    if (vis < 0 || vis >= s.term.rows) continue
    const x1 = row === ends.s.row ? ends.s.col / s.term.cols * r.width : 0
    const x2 = row === ends.e.row ? (ends.e.col + 1) / s.term.cols * r.width : r.width
    out.push({ left: x1, top: vis / s.term.rows * r.height, width: Math.max(4, x2 - x1), height: r.height / s.term.rows })
  }
  return out
})
const selHandles = computed(() => { // 起止拖柄（a 起点/b 终点各自渲染）
  const s = selSession(), ends = selEnds()
  if (!sel.on || !s || !ends) return []
  const r = s.el.getBoundingClientRect(), buf = s.term.buffer.active
  return [ends.s, ends.e].map((cell, i) => ({
    key: i, side: i ? 'e' : 's',
    left: (cell.col + (i ? 1 : 0)) / s.term.cols * r.width - 11,
    top: (cell.row - buf.viewportY + 1) / s.term.rows * r.height - 22,
  }))
})
function selText() {
  const s = selSession(), ends = selEnds()
  if (!s || !ends) return ''
  const buf = s.term.buffer.active, lines = []
  for (let row = ends.s.row; row <= ends.e.row; row++) {
    const ln = buf.getLine(row)
    if (!ln) continue
    const str = ln.translateToString(true)
    const from = row === ends.s.row ? ends.s.col : 0
    const to = row === ends.e.row ? Math.min(ends.e.col + 1, str.length) : str.length
    lines.push(str.slice(from, to).trimEnd())
  }
  return lines.join('\n').replace(/\n+$/, '')
}
function wordAt(cell, s) {
  const ln = s.term.buffer.active.getLine(cell.row)
  if (!ln) return null
  const str = ln.translateToString(true)
  if (cell.col >= str.length || /\s/.test(str[cell.col] || ' ')) return null
  let l = cell.col, r = cell.col
  while (l > 0 && !/\s/.test(str[l - 1])) l--
  while (r < str.length - 1 && !/\s/.test(str[r + 1])) r++
  return { col: l, row: cell.row, end: { col: r, row: cell.row } }
}
function selStart(x, y) {
  const s = selSession()
  if (!s) return
  try { navigator.vibrate && navigator.vibrate(15) } catch {}
  const cell = cellFromXY(x, y, s)
  const w = wordAt(cell, s)
  sel.on = true; sel.busy = ''
  sel.a = w ? { col: w.col, row: w.row } : cell
  sel.b = w ? w.end : cell
  selDrag = 'b' // 之后拖动 = 调终点
  placeBar(x, y)
}
function placeBar(x, y) {
  sel.bar = { x: clamp(x - 90, 8, window.innerWidth - 190), y: clamp(y - 64, 54, window.innerHeight - 120) }
}
function selMove(x, y, ev) {
  if (!selDrag) return
  if (ev) ev.preventDefault()
  const s = selSession()
  if (!s) return
  // 边缘自动滚动
  const r = s.el.getBoundingClientRect()
  const now = Date.now()
  if (now - selScrollT > 50 && (y < r.top + 28 || y > r.bottom - 28)) {
    s.term.scrollLines(y < r.top + 28 ? -1 : 1)
    selScrollT = now
  }
  const cell = cellFromXY(x, y, s)
  if (selDrag === 'a') sel.a = cell; else sel.b = cell
}
function selEndDrag() { selDrag = null }
function selClose() { sel.on = false; sel.a = sel.b = null; sel.bar = null; sel.busy = '' }
function selCopy() {
  const t = selText()
  if (!t) { sel.busy = '没选中内容'; setTimeout(selClose, 800); return }
  navigator.clipboard.writeText(t).then(
    () => { sel.busy = '✅ 已复制'; setTimeout(selClose, 700) },
    () => { sel.busy = '❌ 复制失败'; setTimeout(selClose, 1200) })
}
async function selPaste() {
  sel.busy = '读剪贴板…'
  const t = await readClip()
  if (t) { raw(t); selClose() }
  else { sel.busy = '剪贴板为空'; setTimeout(selClose, 1200) }
}
function selAll() {
  const s = selSession()
  if (!s) return
  sel.a = { col: 0, row: 0 }
  sel.b = { col: s.term.cols - 1, row: s.term.buffer.active.length - 1 }
  sel.bar = { x: clamp(window.innerWidth / 2 - 90, 8, window.innerWidth - 190), y: 120 }
}
// 舞台触摸：长按启动选词；已有选区时拖空白=调整终点
// 手势三态：短按=无 / 竖滑=滚屏(接管+惯性) / 长按460ms=选词
let scrInfo = null // { lastY, mode, prevY, prevT, vel }
let flingRaf = null, flingAcc = 0
const scrolledUp = ref(false)
let scrollHook = null
function updateScrolledUp(s) {
  try { const b = s.term.buffer.active; scrolledUp.value = b.ybase - b.viewportY > 1 } catch {}
}
watch(activeId, id => {
  scrollHook?.dispose?.()
  const s = tstore.sessions[id]
  if (!s) return
  scrollHook = s.term.onScroll(() => updateScrolledUp(s))
  updateScrolledUp(s)
}, { immediate: true }) // 声明后才执行 immediate 回调（TDZ 教训第三弹）
function goBottom() {
  cancelFling()
  const s = selSession()
  if (s) { try { s.term.scrollToBottom() } catch {}; updateScrolledUp(s) }
}
function cancelFling() { if (flingRaf) { cancelAnimationFrame(flingRaf); flingRaf = null } flingAcc = 0 }
function startFling(velPxMs) { // vel: px/ms
  cancelFling()
  let v = velPxMs, lastT = performance.now()
  const step = (now) => {
    const dt = Math.min(48, now - lastT); lastT = now
    v *= Math.pow(0.9985, dt) // 摩擦衰减
    const s = selSession()
    if (!s || Math.abs(v) < 0.02) { flingRaf = null; return }
    const r = s.el.getBoundingClientRect()
    const cellH = r.height / s.term.rows
    flingAcc += v * dt / cellH
    const n = Math.trunc(flingAcc); flingAcc -= n
    if (n) s.term.scrollLines(n)
    updateScrolledUp(s)
    flingRaf = requestAnimationFrame(step)
  }
  flingRaf = requestAnimationFrame(step)
}
function stageTouchStart(e) {
  cancelFling() // 新触摸立即停惯性
  const t = e.touches[0]
  lpXY = { x: t.clientX, y: t.clientY }
  scrInfo = { lastY: t.clientY, mode: false, prevY: t.clientY, prevT: performance.now(), vel: 0 }
  if (sel.on) { sel.b = cellFromXY(t.clientX, t.clientY, selSession()); selDrag = 'b'; placeBar(t.clientX, t.clientY); return }
  lpT = setTimeout(() => { lpT = null; selStart(t.clientX, t.clientY) }, 460)
}
function stageTouchMove(e) {
  const t = e.touches[0]
  if (selDrag) { selMove(t.clientX, t.clientY, e); placeBar(t.clientX, t.clientY); return }
  if (!lpXY) return
  const dx = t.clientX - lpXY.x, dy = t.clientY - lpXY.y
  // 进入滚屏态：竖向位移>14px 且纵向为主
  if (scrInfo && !scrInfo.mode && Math.abs(dy) > 14 && Math.abs(dy) > Math.abs(dx) * 1.1) {
    scrInfo.mode = true
    if (lpT) { clearTimeout(lpT); lpT = null }
  }
  if (scrInfo && scrInfo.mode) {
    e.preventDefault() // 接管后阻止默认（含 xterm 自带滚动）
    const s = selSession()
    if (s) {
      const r = s.el.getBoundingClientRect()
      const cellH = r.height / s.term.rows
      const n = Math.round((t.clientY - scrInfo.lastY) / cellH)
      if (n) { s.term.scrollLines(n); scrInfo.lastY += n * cellH; updateScrolledUp(s) }
      const now = performance.now(), dt = now - scrInfo.prevT
      if (dt > 4) { scrInfo.vel = scrInfo.vel * 0.72 + ((t.clientY - scrInfo.prevY) / dt) * 0.28; scrInfo.prevY = t.clientY; scrInfo.prevT = now }
    }
    return
  }
  if (lpT && Math.abs(dx) + Math.abs(dy) > 12) { clearTimeout(lpT); lpT = null }
}
function stageTouchEnd() {
  if (lpT) { clearTimeout(lpT); lpT = null }
  selEndDrag()
  if (scrInfo && scrInfo.mode && Math.abs(scrInfo.vel) > 0.35) startFling(scrInfo.vel) // 甩动惯性
  scrInfo = null
}
// 拖柄触摸（.stop 防穿透舞台）
function hTouchStart(side, e) {
  const t = e.touches[0]
  selDrag = side
  try { navigator.vibrate && navigator.vibrate(10) } catch {}
  placeBar(t.clientX, t.clientY)
}
function hTouchMove(side, e) {
  const t = e.touches[0]
  selMove(t.clientX, t.clientY, e)
  placeBar(t.clientX, t.clientY)
}
function activate(id) {
  const s = tstore.sessions[id]
  if (!s) return
  activeId.value = id
  // 关键：新建的 pane 在隐藏池里，必须搬进舞台（否则黑屏——只切类不搬家）
  if (stage.value && s.el.parentNode !== stage.value) stage.value.appendChild(s.el)
  for (const sid of tstore.order) tstore.sessions[sid]?.el.classList.toggle('act', sid === id)
  requestAnimationFrame(() => setTimeout(() => {
    try { s.fit.fit() } catch {}
    s.sendDims && s.sendDims() // 切换会话：立即上报真实尺寸（隐藏池创建时是占位列数）
    s.term.focus()
  }, 80))
}

function newTerm() {
  const s = createSession(chat.state?.cwd)
  activate(s.id)
  setTimeout(() => { try { s.fit.fit(); s.sendDims && s.sendDims(); s.term.focus() } catch {} }, 200)
}

function raw(data) {
  wsSend({ type: 'terminal_input', terminalId: activeId.value, data })
  tstore.sessions[activeId.value]?.term.focus()
}
const shiftOn = ref(false)
function press(k) {
  if (k === 'CTRL') { ctrlOn.value = !ctrlOn.value; altOn.value = false; shiftOn.value = false; return }
  if (k === 'ALT') { altOn.value = !altOn.value; ctrlOn.value = false; shiftOn.value = false; return }
  if (k === 'SHIFT') { shiftOn.value = !shiftOn.value; ctrlOn.value = false; altOn.value = false; return }
  let data = k
  if (ctrlOn.value && data.length === 1) {
    const c = data.toUpperCase().charCodeAt(0)
    if (c >= 64 && c <= 95) data = String.fromCharCode(c - 64)
  } else if (altOn.value && data.length === 1) data = '\x1b' + data
  else if (shiftOn.value && data.length === 1) data = data.toUpperCase()
  ctrlOn.value = false; altOn.value = false; shiftOn.value = false
  raw(data)
}
const combos = [
  ['^C', '\x03'], ['^D', '\x04'], ['^Z', '\x1a'], ['^L', '\x0c'], ['^U', '\x15'], ['^W', '\x17'],
  ['^R', '\x12'], ['^A', '\x01'], ['^E', '\x05'], ['^K', '\x0b'], ['^Y', '\x19'], ['^P', '\x10'], ['^N', '\x0e'],
]
const alts = [['ALT+B', '\x1bb'], ['ALT+F', '\x1bf'], ['ALT+D', '\x1bd'], ['ALT+.', '\x1b.']]
const syms = ['|','-','/','\\','~','`','$','#','%','^','&','*','(',')','[',']','{','}','<','>','=','+','.',',',';',':','\'','"','!','?','_','@']

function fmtAgo(t) {
  const s = Math.floor((Date.now() - t) / 1000)
  if (s < 60) return s + '秒前'
  if (s < 3600) return Math.floor(s / 60) + '分前'
  return Math.floor(s / 3600) + '时前'
}

onMounted(() => {
  setTimeout(sanitizeDpad, 450) // 布局稳定后钳回出界 D-pad
  if (chat.status !== 'open') connect()
  nextTick(() => {
    poolAttach(stage.value)
    if (!tstore.order.length) newTerm()
    else activate(tstore.order[tstore.order.length - 1])
  })
})
onUnmounted(() => {
  poolDetach() // 只藏不杀：会话活着，回来还在
})
</script>

<template>
  <div class="termwrap">
    <!-- 顶栏：☰ | 快速标签 | 管理 | 新建 -->
    <header class="tabs">
      <button class="tb tap" title="工作台" @click="openShellDrawer">☰</button>
      <div class="tabscroller">
        <template v-if="aiTerms.length">
          <span class="aisep">🤖</span>
          <div v-for="t in aiTerms" :key="t.id" class="tab ai tap" :class="{ act: t.id === activeId }" @click="openAi(t)">
            <span class="tdot" :class="{ on: t.running }"></span>
            <span class="tlab">{{ (t.title || t.command?.name || 'AI').slice(0, 14) }}</span>
          </div>
        </template>
        <div v-for="s in sessions" :key="s.id" class="tab tap" :class="{ act: s.id === activeId, dead: !s.alive }"
          @click="activate(s.id)"
          @touchstart.passive="tabPress(s, $event)" @touchend="tabRelease" @touchmove="tabRelease"
          @mousedown="tabPress(s, $event)" @mouseup="tabRelease" @mouseleave="tabRelease">
          <span class="tdot" :class="{ on: s.alive }"></span>
          <span class="tlab">{{ renameId === s.id ? '' : s.title }}</span>
          <input v-if="renameId === s.id" v-model="renaming" class="tin" autofocus @click.stop
            @keydown.enter="renameSession(s.id, renaming); renameId = ''"
            @blur="renameSession(s.id, renaming); renameId = ''" />
        </div>
      </div>
      <button class="tb newb tap" @click="newTerm">＋</button>
      <button class="tb tap" title="命令面板" @click="openCmds">⌘</button>
    </header>

    <!-- 终端舞台：pane 由会话池借入（长按=选词复制，拖柄=调范围） -->
    <div ref="stage" class="stage" @touchstart="stageTouchStart" @touchmove="stageTouchMove" @touchend="stageTouchEnd" @touchcancel="stageTouchEnd">
      <!-- 选区高亮 + 双拖柄 -->
      <template v-if="sel.on">
        <div v-for="(r, i) in selRects" :key="i" class="selr" :style="{ left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' }"></div>
        <div v-for="h in selHandles" :key="h.key" class="selh" :class="h.side"
          :style="{ left: h.left + 'px', top: h.top + 'px' }"
          @touchstart.stop.prevent="hTouchStart(h.side, $event)" @touchmove.stop.prevent="hTouchMove(h.side, $event)" @touchend.stop="selEndDrag"></div>
      </template>
    </div>
    <!-- 命令快捷面板 -->
    <div v-if="cmdPanel" class="skview2" @click="cmdPanel = false">
      <div class="skview-b2" @click.stop>
        <div class="skview-h2">
          <b>命令快捷面板</b>
          <button class="minib tap" @click="editCmd(null)">＋ 新建</button>
          <button class="minib tap" @click="cmdPanel = false">✕</button>
        </div>
        <!-- 编辑/新建 -->
        <div v-if="cmdEdit" class="cmdform">
          <input v-model="cmdEdit.name" placeholder="名称（如：看日志）">
          <input v-model="cmdEdit.command" placeholder="命令（如：tail -f ~/pui.log）" style="font-family:ui-monospace,monospace;font-size:12px;">
          <input v-model="cmdEdit.cwd" placeholder="工作目录（可选，${pwd}=当前）" style="font-family:ui-monospace,monospace;font-size:12px;">
          <div style="display:flex;gap:8px;margin-top:8px;">
            <button class="minib tap" style="flex:1;padding:9px;" @click="cmdEdit = null">取消</button>
            <button class="goonb tap" style="flex:1;padding:9px;border:0;border-radius:9px;background:var(--hill,#3E7C59);color:#fff;font-size:13px;font-weight:700;" @click="saveCmd">保存</button>
          </div>
        </div>
        <!-- 列表 -->
        <template v-else>
          <div v-for="(def, i) in chat.commands" :key="def.name + i" class="cmdrow">
            <div class="cmdrow-t tap" @click="runCmd(def)">
              <b>{{ def.name }}</b>
              <span class="cmddim">{{ def.command }}</span>
            </div>
            <button class="minib tap" @click="editCmd(def, i)">✎</button>
            <button class="minib tap" :style="cmdDel === def.name ? { background:'#C24B3C', color:'#fff', borderColor:'#C24B3C' } : { color:'#C24B3C' }" @click="delCmd(def, i)">🗑</button>
          </div>
          <div v-if="!chat.commands.length" class="cmdempty">暂无命令 · 「＋新建」把常用命令固化为一键任务</div>
        </template>
      </div>
    </div>

    <!-- 回到底部（滚离时出现） -->
    <button v-if="scrolledUp" class="tobottom tap" @touchstart.prevent="goBottom">↓ 最新</button>
    <!-- 选区工具条 -->
    <div v-if="sel.on && sel.bar" class="selbar" :style="{ left: sel.bar.x + 'px', top: sel.bar.y + 'px' }">
      <span v-if="sel.busy" class="selbusy">{{ sel.busy }}</span>
      <template v-else>
        <button class="selbtn tap" @click="selCopy">复制</button>
        <button class="selbtn tap" @click="selAll">全选</button>
        <button class="selbtn tap" @click="selPaste">粘贴</button>
        <button class="selbtn tap" @click="selClose">✕</button>
      </template>
    </div>
    <!-- 虚拟按键 v7：职责分离——打字归输入法，这里只放修饰/组合/功能/符号 -->
    <div class="vkeys">
      <!-- 展开态：两行横滚（编程符号 + 次级功能） -->
      <div v-if="keysMode === 'full'" class="kwrap extra">
        <div class="kscroll">
          <button v-for="s2 in kbSyms" :key="s2" class="vk sym tap" @click="press(s2)">{{ s2 }}</button>
        </div>
        <div class="kscroll">
          <button class="vk alt tap" @click="raw('\x1bb')">A·B</button>
          <button class="vk alt tap" @click="raw('\x1bf')">A·F</button>
          <button class="vk alt tap" @click="raw('\x1bd')">A·D</button>
          <button class="vk alt tap" @click="raw('\x1b.')">A·.</button>
          <button class="vk cc tap" @click="raw('\x12')">^R</button>
          <button class="vk cc tap" @click="raw('\x0b')">^K</button>
          <button class="vk cc tap" @click="raw('\x19')">^Y</button>
          <button class="vk cc tap" @click="raw('\x10')">^P</button>
          <button class="vk cc tap" @click="raw('\x0e')">^N</button>
          <button class="vk tap" @click="raw('\x1b[2~')">INS</button>
          <button class="vk tap" @click="raw('\x7f')">DEL</button>
          <button class="vk tap" @click="raw(' ')">SPC</button>
        </div>
      </div>
      <!-- 常态：两行编程高频 -->
      <div class="kwrap">
        <div class="kscroll">
          <button class="vk kb tap" @click="showKb">⌨</button>
          <button class="vk mod tap" :class="{ on: ctrlOn }" @click="press('CTRL')">CTRL</button>
          <button class="vk mod tap" :class="{ on: altOn }" @click="press('ALT')">ALT</button>
          <button class="vk mod tap" :class="{ on: shiftOn }" @click="press('SHIFT')">SHIFT</button>
          <button class="vk tap" @click="raw('\x1b')">ESC</button>
          <button class="vk tap" @click="raw('\t')">TAB</button>
          <button class="vk enter tap" @click="raw('\r')">⏎</button>
          <button class="vk cc tap" @click="raw('\x03')">^C</button>
          <button class="vk tap" @click="raw('\x7f')">⌫</button>
        </div>
        <div class="kscroll">
          <button class="vk cc tap" @click="raw('\x04')">^D</button>
          <button class="vk cc tap" @click="raw('\x1a')">^Z</button>
          <button class="vk cc tap" @click="raw('\x15')">^U</button>
          <button class="vk cc tap" @click="raw('\x17')">^W</button>
          <button class="vk cc tap" @click="raw('\x0c')">^L</button>
          <button class="vk tap" @click="raw('\x1b[H')">HOME</button>
          <button class="vk tap" @click="raw('\x1b[F')">END</button>
          <button class="vk tap" @click="raw('\x1b[5~')">PGUP</button>
          <button class="vk tap" @click="raw('\x1b[6~')">PGDN</button>
          <button class="vk tog tap" :class="{ on: keysMode === 'full' }" @click="keysMode = keysMode === 'full' ? 'bar' : 'full'">{{ keysMode === 'full' ? '▴' : '▾' }}</button>
        </div>
      </div>
    </div>

        <!-- 悬浮方向键 D-pad v3：可拖动任意位置（⠿拖柄 / 球态拖=移 点=开） -->
    <button v-if="dpadOff" class="dpad-fab" :style="dpadStyle" title="方向键（拖=移位 点=展开）"
      @touchstart="dragStart" @touchmove="dragMove" @touchend="fabEnd">
      ✥
    </button>
    <div v-else class="dpad" :style="dpadStyle">
      <div class="dpdrag" @touchstart.passive="dragStart" @touchmove="dragMove" @touchend="dragEnd">⠿⠿</div>
      <button class="dp-x tap" @touchstart.prevent="collapsePad">⌄</button>
      <button class="dp tap" @touchstart.prevent="raw('\x1b[A')">↑</button>
      <div class="dpb">
        <button class="dp tap" @touchstart.prevent="raw('\x1b[D')">←</button>
        <button class="dp tap" @touchstart.prevent="raw('\x1b[B')">↓</button>
        <button class="dp tap" @touchstart.prevent="raw('\x1b[C')">→</button>
      </div>
      <div class="dpb wide2">
        <button class="dp paste tap" @touchstart.prevent="pasteClip">📋</button>
        <button class="dp enter tap" @touchstart.prevent="raw('\r')">⏎</button>
      </div>
    </div>

    <!-- 长按标签：行内管理菜单 -->
    <div v-if="tabMenu" class="tmask tap" @touchstart.prevent="tabMenu = null"></div>
    <div v-if="tabMenu" class="tmenu" :style="{ left: tabMenu.x + 'px' }">
      <div class="tmi tap" @touchstart.prevent="renameId = tabMenu.id; renaming = tabMenu.title; tabMenu = null">✎ 重命名</div>
      <div class="tmi tap" @touchstart.prevent="restartSession(tabMenu.id); tabMenu = null">↻ 重启</div>
      <div class="tmi danger tap" :class="{ confirm: confirmKill === tabMenu.id }"
        @touchstart.prevent="confirmKill === tabMenu.id ? (killSession(tabMenu.id), tabMenu = null, confirmKill = '') : (confirmKill = tabMenu.id, resetConfirm(tabMenu.id))">
        {{ confirmKill === tabMenu.id ? '⚠ 再点确认关闭' : '🗑 关闭会话' }}
      </div>
    </div>
  </div>
</template>

<style>
.tpane { position: absolute; inset: 0; display: none; padding: 4px 6px; }
.tpane.act { display: block; }
.xq-term-pool { position: fixed; left: -9999px; top: 0; width: 100%; height: 100%; pointer-events: none; }
</style>

<style scoped>
.termwrap { position: fixed; inset: 0; background: #0d0e12; display: flex; flex-direction: column; z-index: 10; overflow: hidden; }
.tabs { display: flex; align-items: center; gap: 5px; padding: 6px 8px; background: #14161c; border-bottom: 1px solid #23262e; }
.skview2 { position: fixed; inset: 0; z-index: 42; background: rgba(8,10,14,.55); display: flex; align-items: flex-end; animation: selin .15s ease; }
.skview-b2 { background: #171a21; border: 1px solid #2a2e39; border-radius: 18px 18px 0 0; width: 100%; max-height: 72vh; overflow-y: auto; padding: 12px 12px 18px; animation: upin .2s ease; }
@keyframes upin { from { transform: translateY(40px); } }
.skview-h2 { display: flex; align-items: center; gap: 8px; padding: 4px 4px 10px; }
.skview-h2 b { flex: 1; font-size: 15px; color: #dcddde; }
.cmdform { background: #1e222b; border: 1px solid #2a2e39; border-radius: 12px; padding: 10px; display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px; }
.cmdform input { background: #12151b; border: 1px solid #2a2e39; border-radius: 8px; padding: 10px; font-size: 13px; color: #dcddde; }
.cmdrow { display: flex; align-items: center; gap: 6px; padding: 9px 4px; border-bottom: 1px solid #23262e; }
.cmdrow-t { flex: 1; min-width: 0; }
.cmdrow-t b { font-size: 14px; color: #dcddde; display: block; }
.cmddim { font-size: 11px; color: #6b7076; font-family: ui-monospace, monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; display: block; max-width: 72vw; }
.cmdempty { text-align: center; color: #6b7076; font-size: 13px; padding: 26px 10px; }

.tobottom { position: fixed; left: 50%; transform: translateX(-50%); bottom: 126px; z-index: 30; background: rgba(30,33,42,.94); color: #a78bfa; border: 1px solid #3a3560; border-radius: 99px; font-size: 12.5px; font-weight: 700; padding: 8px 18px; box-shadow: 0 6px 18px rgba(0,0,0,.4); animation: selin .16s ease; }

/* ══ 选区复制 ══ */
.selr { position: absolute; background: rgba(139, 92, 246, .32); border-radius: 2px; pointer-events: none; z-index: 5; }
.selh { position: absolute; width: 22px; height: 22px; border-radius: 50% 50% 50% 4px; background: #8b5cf6; border: 2.5px solid #fff; z-index: 7; box-shadow: 0 2px 8px rgba(0,0,0,.45); transform: rotate(-45deg); }
.selh.e { border-radius: 4px 50% 50% 50%; transform: rotate(135deg); }
.selh:active { transform: rotate(-45deg) scale(1.25); }
.selh.e:active { transform: rotate(135deg) scale(1.25); }
.selbar { position: fixed; z-index: 45; display: flex; gap: 2px; background: #232633; border: 1px solid #3a4050; border-radius: 12px; padding: 3px; box-shadow: 0 8px 26px rgba(0,0,0,.5); animation: selin .14s ease; }
@keyframes selin { from { opacity: 0; transform: translateY(6px); } }
.selbtn { border: 0; background: none; color: #dcddde; font-size: 14px; font-weight: 600; padding: 8px 13px; border-radius: 9px; }
.selbtn:active { background: #8b5cf6; color: #fff; }
.selbusy { color: #a5d6a7; font-size: 13px; padding: 8px 13px; }

.aisep { font-size: 12px; flex-shrink: 0; align-self: center; opacity: .75; margin: 0 2px; }
.tab.ai { border-style: dashed; }
.tab.ai.act { border-color: #3ecf72; background: rgba(62,207,114,.08); }
.tb { background: #1a1d26; color: #dcddde; border: 1px solid #23262e; border-radius: 9px; padding: 6px 10px; font-size: 14px; flex-shrink: 0; }
.tabscroller { display: flex; gap: 4px; overflow-x: auto; flex: 1; scrollbar-width: none; }
.tab { display: flex; align-items: center; gap: 5px; background: #1a1d26; border: 1px solid #23262e; border-radius: 9px; padding: 5px 9px; font-size: 12px; color: #8b8f98; flex-shrink: 0; }
.tab.act { border-color: #8b5cf6; color: #dcddde; background: rgba(139,92,246,.1); }
.tab.dead { opacity: .5; }
.tdot { width: 6px; height: 6px; border-radius: 50%; background: #4a4e58; flex-shrink: 0; }
.tdot.on { background: #3ecf72; }
.tdot.big { width: 9px; height: 9px; margin: 0 8px 0 2px; }
.tlab { max-width: 84px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.stage { flex: 1; position: relative; }
.stage :deep(.xterm) { height: 100%; }
.stage :deep(.xterm-viewport) { background: #0d0e12 !important; }
/* 虚拟按键 v2：单行横滚条（Termius 模式） */
.vkeys { background: #14161c; border-top: 1px solid #23262e; padding: 5px 6px calc(5px + env(safe-area-inset-bottom)); display: flex; flex-direction: column; gap: 5px; }
/* 常态：两行编程精选（各自可横滚） */
.kwrap { display: flex; flex-direction: column; gap: 5px; }
.kscroll { display: flex; gap: 5px; overflow-x: auto; scrollbar-width: none; flex: 1; min-width: 0; padding: 1px 0; align-items: center; }
.kscroll::-webkit-scrollbar { display: none; }
/* 展开态：两行横滚（与常驻同构，滑入） */
.kwrap.extra { animation: kslide .16s ease; margin-bottom: 5px; }
.vk.tog { color: #a78bfa; }
.vk.tog.on { background: #2b2440; }
/* 悬浮 D-pad（终端区右缘竖排锚定） */
.dpdrag { width: 100%; height: 16px; display: flex; align-items: center; justify-content: center; color: #565b66; font-size: 11px; letter-spacing: 3px; }
.dpad { position: absolute; right: 10px; bottom: 22%; z-index: 15; display: flex; flex-direction: column; align-items: center; gap: 5px;
  background: rgba(20, 22, 28, .82); backdrop-filter: blur(8px); border: 1px solid #323848; border-radius: 14px; padding: 6px; }
.dpad .dp { width: 44px; height: 40px; background: #1a1d26; color: #c6c9d0; border: 1px solid #2c303b; border-radius: 9px; font-size: 16px; }
.dpad .dp:active { background: #2b2440; }
.dpad .dpb { display: flex; gap: 5px; }
.dpad .dpb.wide2 { width: 100%; }
.dpad .dp.paste { color: #7db3e8; background: #16222c; border-color: #243a4a; }
.dpad .dp.enter { color: #7dd3a8; background: #16321f; border-color: #2a5a3a; flex: 1; }
.dpad .dp-x { position: absolute; top: -8px; right: -8px; width: 22px; height: 22px; border-radius: 50%; background: #2c303b; color: #8a93a3; border: 1px solid #3a4150; font-size: 11px; display: flex; align-items: center; justify-content: center; }
.dpad-fab { position: absolute; right: 10px; bottom: 22%; z-index: 15; width: 42px; height: 42px; border-radius: 50%;
  background: rgba(139, 92, 246, .85); color: #fff; border: 0; font-size: 17px; box-shadow: 0 4px 14px rgba(0,0,0,.45); }
/* 扩展行：输入法式滑入 */
.kextra { display: flex; gap: 5px; overflow-x: auto; scrollbar-width: none; padding: 2px 0; animation: kslide .16s ease; }
.kextra::-webkit-scrollbar { display: none; }
@keyframes kslide { from { transform: translateY(14px); opacity: 0; } }
.vk { flex-shrink: 0; min-width: 42px; height: 40px; background: #1a1d26; color: #c6c9d0; border: 1px solid #2c303b; border-radius: 9px; font-size: 12.5px; font-weight: 600; font-family: ui-monospace, monospace; }
.vk:active { background: #262b38; }
.vk.mod { background: #232635; color: #a78bfa; border-color: #3d3560; }
.vk.mod.on { background: #8b5cf6; color: #fff; }
.vk.cc { color: #7dd3a8; background: #182227; border-color: #24402f; }
.vk.alt { color: #e8b268; background: #241f18; border-color: #4a3c26; }
.vk.sym { min-width: 34px; font-size: 13.5px; }
.vk.kb { color: #7db3e8; background: #16222c; border-color: #243a4a; }
.vk.enter { color: #7dd3a8; background: #16321f; border-color: #2a5a3a; min-width: 46px; font-size: 15px; }
.vk.exp { color: #a78bfa; }
.vk.exp.on { background: #2b2440; }
.vk.dim { color: #666b76; }
/* 扩展面板 */
.sp { flex: 1; min-width: 8px; }
/* 长按标签行内菜单（Chrome 惯例） */
.tmask { position: fixed; inset: 0; z-index: 70; background: transparent; }
.tmenu { position: fixed; top: 46px; z-index: 71; min-width: 148px; background: #1c2027; border: 1px solid #323848; border-radius: 12px; overflow: hidden; box-shadow: 0 12px 36px rgba(0,0,0,.55); animation: mgrin .12s ease; }
.tmi { padding: 12px 15px; font-size: 13px; color: #dfe4ec; border-bottom: 1px solid #262b36; }
.tmi:active { background: #262b38; }
.tmi.danger { color: #e08585; }
.tmi.danger.confirm { background: #3a1d1d; color: #ffb4b4; font-weight: 700; }
.tin { background: #1a1d26; border: 1px solid #8b5cf6; color: #fff; border-radius: 5px; padding: 2px 6px; font-size: 12px; width: 72px; outline: none; }
@keyframes mgrin { from { transform: translateY(-8px); opacity: 0; } }
@keyframes pulse { 50% { opacity: .55; } }

/* ENTER 主操作色 */
.vk.enter { color: #7dd3a8; background: #16321f; border-color: #2a5a3a; font-weight: 700; }
/* full 面板分组标签 */
.ksec { font-size: 10px; color: #666b76; padding: 8px 12px 3px; letter-spacing: 1px; }
.kgrid.altg { grid-template-columns: repeat(4, 1fr); }
</style>
