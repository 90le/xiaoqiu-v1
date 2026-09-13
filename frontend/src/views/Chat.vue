<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { marked } from 'marked'
import { chat, api, connect, wsSend, startWatchdog } from '../useChat.js'
import { vs, vsIgnite } from '../voiceSession.js'

marked.setOptions({ breaks: true, gfm: true })

const input = ref('')
const listEl = ref(null)
const menu = ref('')          // '' | model | think | sessions
const ttsOn = ref(localStorage.getItem('xq_tts2') !== 'false')
const recording = ref(false)
const voiceState = ref('')
const attachments = ref([])
const fileEl = ref(null)
const openTools = ref({})     // toolCallId -> bool（默认折叠）
const openThink = ref({})     // msgId:blockIdx -> bool（默认折叠）

const SRC = {
  builtin: { label: '内置', c: 'var(--hill)' },
  extension: { label: '扩展', c: 'var(--hill)' },
  prompt: { label: '模板', c: 'var(--dawn)' },
  skill: { label: '技能', c: 'var(--hill)' },
  plugin: { label: '插件', c: 'var(--bad)' },
}
const slashHint = computed(() => {
  const v = input.value
  if (!v.startsWith('/') || v.includes('\n') || v.length > 30) return []
  const q = v.slice(1).toLowerCase()
  const all = chat.slashCommands || []
  const hit = q ? all.filter(x => (x.name + ' ' + (x.description || '')).toLowerCase().includes(q)) : all
  return hit.slice(0, 30)
})
function pickSlash(c2) { input.value = '/' + c2.name + ' ' }

const ctxCls = computed(() => {
  const p = chat.state?.stats?.contextUsage?.percent || 0
  return p >= 85 ? 'hot' : p >= 60 ? 'warm' : ''
})
function fT(n) { n = n || 0; return n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n) }
function fmtCost(c) { c = c || 0; return c >= 1 ? c.toFixed(2) : c >= 0.01 ? c.toFixed(3) : c.toFixed(4) }
const ctxTxt = computed(() => {
  const cu = chat.state?.stats?.contextUsage
  if (!cu || cu.tokens == null) return (cu?.percent ?? 0) + '%'
  return fT(cu.tokens) + '/' + fT(cu.contextWindow) + ' ' + Math.round(cu.percent || 0) + '%'
})
const cachePct = computed(() => {
  const t = chat.state?.stats?.tokens
  if (!t || !t.cacheRead) return 0
  const total = (t.cacheRead || 0) + (t.input || 0)
  return total ? Math.round(t.cacheRead * 100 / total) : 0
})
const cacheCls = computed(() => cachePct.value >= 80 ? 'hi' : cachePct.value >= 50 ? 'mid' : 'lo')
const qTotal = computed(() => {
  const q = chat.state?.queue
  return (q?.steering?.length || 0) + (q?.followUp?.length || 0)
})
const st = computed(() => chat.state)
const msgs = computed(() => st.value?.messages || [])
const lastAid = computed(() => { const a = msgs.value.filter(x => x.role === 'assistant'); return a.length ? a[a.length - 1].id : '' })
const convTitle = computed(() => {
  const c = chat.conversations.find(x => x.id === chat.activeConvId)
  return c?.title || '小丘'
})
const streaming = computed(() => st.value?.isStreaming ? (st.value?.streamingMessage || { role: 'assistant', content: [] }) : null)
// webui 同款：全部 7 档 + 中文标签；不支持的档位置灰禁用（而非隐藏——SDK 会钳制无效请求）
const THINKING_ALL = [
  { v: 'off', label: '关闭' }, { v: 'minimal', label: '极简' }, { v: 'low', label: '低' },
  { v: 'medium', label: '中' }, { v: 'high', label: '高' }, { v: 'xhigh', label: '极高' }, { v: 'max', label: '最大' },
]
const levels = computed(() => {
  const avail = st.value?.availableThinkingLevels
  const ok = Array.isArray(avail) && avail.length ? new Set(avail) : null
  return THINKING_ALL.map(l => ({ ...l, ok: ok ? ok.has(l.v) : true }))
})
const thinkLabel = (v) => (THINKING_ALL.find(l => l.v === v) || {}).label || v || '—'
// 模型使用次数（webui modelUsage 同款：localStorage 轻量记账，下拉按热度排序）
const MU_KEY = 'xq_model_usage'
function saveTts(v) { try { localStorage.setItem('xq_tts2', v) } catch {} }
function recordModelUsage(id) { try { const u = JSON.parse(localStorage.getItem(MU_KEY) || '{}'); u[id] = (u[id] || 0) + 1; localStorage.setItem(MU_KEY, JSON.stringify(u)) } catch {} }
const modelUsage = JSON.parse(localStorage.getItem(MU_KEY) || '{}')
const modelFilter = ref('')
const modelScrollEl = ref(null)
const filteredModels = computed(() => {
  const q = modelFilter.value.trim().toLowerCase()
  const list = q ? chat.models.filter(m => (m.name + ' ' + m.provider).toLowerCase().includes(q)) : chat.models
  return [...list].sort((a, b) => (modelUsage[b.id] || 0) - (modelUsage[a.id] || 0))
})
function refreshModels() {
  chat.models = [] // 清空显示"清单载入中…"（webui reqLoading 同款反馈）
  api.listModels()
}
function pickModel(m) {
  api.setModel(m.id)
  recordModelUsage(m.id)
  menu.value = ''
}
// webui 同款：打开时把当前选中模型滚入视野
watch(() => menu.value, (v) => {
  if (v !== 'model') return
  nextTick(() => {
    const el = modelScrollEl.value?.querySelector('.di.sel')
    el?.scrollIntoView({ block: 'nearest' })
  })
})

function toolResultOf(id) { return msgs.value.find(x => x.role === 'toolResult' && x.toolCallId === id) }
function thinkKey(m, i) { return (m.id || '') + ':' + i }
function thinkOpen(m, i) { return !!openThink.value[thinkKey(m, i)] }
function toggleThink(m, i) { openThink.value[thinkKey(m, i)] = !thinkOpen(m, i) }
function toolOpen(id) { return !!openTools.value[id] }
function toggleTool(id) { openTools.value[id] = !openTools.value[id] }
function thinkPreview(t, live) { const s = (t || '').trim(); return live ? s.slice(-80) : (s.split('\n')[0] || '').slice(0, 80) }

function md(txt) {
  const html = marked.parse(txt || '')
  return html.replace(/<script[\s\S]*?<\/script>/gi, '')
}
// 代码块复制钮：每次消息渲染后注入（事件委托，轻量）
function injectCopyBtns() {
  nextTick(() => {
    document.querySelectorAll('.md pre').forEach(pre => {
      if (pre.querySelector('.ccopy')) return
      pre.style.position = 'relative'
      const b = document.createElement('button')
      b.className = 'ccopy'
      b.textContent = '复制'
      b.onclick = () => {
        navigator.clipboard?.writeText(pre.innerText.replace(/^复制\n?/, ''))
        b.textContent = '✓'
        setTimeout(() => b.textContent = '复制', 1200)
      }
      pre.appendChild(b)
    })
  })
}
function fmtTs(t) { return t ? new Date(t).toLocaleTimeString('zh', { hour: '2-digit', minute: '2-digit' }) : '' }
function scroll() { nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight }) }
// 滚到底悬浮钮：离底 >200px 出现（流式时智能跟随）
const atBottom = ref(true)
function onListScroll() {
  const el = listEl.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 200
}
watch(() => [msgs.value.length, st.value?.streamingMessage?.content?.length], () => { if (atBottom.value) scroll() })
watch(() => [msgs.value.length, st.value?.streamingMessage?.content?.length], () => { injectCopyBtns() })
let copyTimer = null
watch(() => st.value?.streamingMessage?.content?.map(b => b.text || b.thinking || '').join('').length, () => {
  if (copyTimer) clearTimeout(copyTimer)
  copyTimer = setTimeout(injectCopyBtns, 600) // 流式中节流注入
})
watch(() => st.value?.isStreaming, (b, old) => {
  if (b === false && old === true) {
    if (ttsOn.value) speakLast()
  }
})

const sesQ = ref(''), newCwd = ref('')
let sesT = null
function sesDeb() {
  if (sesT) clearTimeout(sesT)
  sesT = setTimeout(() => {
    if (sesQ.value.trim()) api.searchSessions(sesQ.value.trim())
    else chat.sessionSearch = null
  }, 500)
}
let cwdT = null
function cwdDeb() { if (cwdT) clearTimeout(cwdT); cwdT = setTimeout(() => newCwd.value && api.completePath(newCwd.value), 450) }
const editId = ref('')
// 附件卡片（customType:"file" aside）
const openedAtts = ref(new Set())
// 附件归属：aside 紧跟在用户消息之后（nextTurn 投递）——合并回【前一条】用户消息
const renderMsgs = computed(() => {
  const out = []
  for (const m of msgs.value) {
    if (m.role === 'user') { out.push({ ...m, attImgs: [], attFiles: [] }); continue } // 副本：aside 挂副本，绝不污染源数据
    if (m.role === 'custom' && m.customType === 'file') {
      const prev = out.length ? out[out.length - 1] : null
      if (prev && prev.role === 'user') {
        const u = imgOf(m)
        if (u) prev.attImgs.push(u)
        else prev.attFiles.push(m)
      } else {
        out.push({ id: m.id + '-orph', role: 'orphanAtts', atts: [m] })
      }
      continue
    }
    out.push(m)
  }
  return out
})
function toggleAtt(m) {
  if (m.details?.mode === 'reference') return // 仅引用模式不展开（webui 同款）
  const s = new Set(openedAtts.value)
  s.has(m.id) ? s.delete(m.id) : s.add(m.id)
  openedAtts.value = s
}
function imgOf(m) {
  const b = (m.content || []).find(x => x.type === 'image')
  if (!b) return ''
  return b.dataUrl || (b.data ? 'data:' + (b.mimeType || 'image/png') + ';base64,' + b.data : '')
}
function fmtSize(n) { return !n ? '' : n < 1024 ? n + 'B' : n < 1048576 ? (n / 1024).toFixed(1) + 'KB' : (n / 1048576).toFixed(1) + 'MB' }
function attModeLabel(m) {
  const d = m.details || {}
  if (imgOf(m)) return '图片'
  if (d.mode === 'reference') return (d.type === 'folder' ? '目录引用' : '仅引用 · ' + fmtSize(d.size))
  if (d.mode === 'bridged') return '视觉桥接'
  if (d.mode === 'lines') return '内联 ' + (d.startLine || 1) + '-' + (d.endLine || d.lines || '?') + ' 行'
  if (d.lines) return '内联 ' + d.lines + ' 行'
  return '内联'
}
function stripFileWrapper(m) {
  return (m.content || []).filter(b => b.type === 'text').map(b => b.text).join('\n')
    .replace(/^\n?<file path="[^"]*">\n?```\n?/, '').replace(/\n?```\n?<\/file>\n?$/, '')
    .replace(/^<file path="[^"]*" size="\d+"\s*\/>$/gm, '')
}
// 图片全屏灯箱（手势版：双指缩放 / 单指拖动 / 双击 1x↔2.5x）
const imgViewer = ref('')
function viewImg(u) { imgViewer.value = u; ivReset() }
const iv = reactive({ s: 1, x: 0, y: 0, sd: 0, ss: 1, sx: 0, sy: 0, ox: 0, oy: 0, moved: false, lastTap: 0 })
function ivReset() { iv.s = 1; iv.x = 0; iv.y = 0; iv.moved = false }
function ivDist(t) { const dx = t[0].clientX - t[1].clientX, dy = t[0].clientY - t[1].clientY; return Math.hypot(dx, dy) }
function ivStart(e) {
  if (e.touches.length === 1) {
    const t = e.touches[0]
    iv.sx = t.clientX; iv.sy = t.clientY; iv.ox = iv.x; iv.oy = iv.y; iv.moved = false
  } else if (e.touches.length === 2) {
    iv.sd = ivDist(e.touches); iv.ss = iv.s
  }
}
function ivMove(e) {
  e.preventDefault() // 手势期间锁页面滚动
  if (e.touches.length === 1) {
    const t = e.touches[0], dx = t.clientX - iv.sx, dy = t.clientY - iv.sy
    if (Math.abs(dx) + Math.abs(dy) > 6) iv.moved = true
    iv.x = iv.ox + dx; iv.y = iv.oy + dy
  } else if (e.touches.length === 2 && iv.sd > 0) {
    iv.moved = true
    iv.s = Math.min(8, Math.max(0.4, iv.ss * ivDist(e.touches) / iv.sd))
  }
}
function ivEnd(e) {
  if (e.touches.length > 0) return
  if (!iv.moved && iv.s === 1) {
    const now = Date.now()
    if (now - iv.lastTap < 300) { iv.s = 2.5 } // 双击放大
    iv.lastTap = now
  }
  if (iv.s < 1) { iv.s = 1; iv.x = 0; iv.y = 0 } // 回弹
}
// 文件灯箱：内容预览（内联文本剥 <file> 包装；仅引用给元信息）
const fileViewer = ref(null)
function viewFile(f) { fileViewer.value = f }
function fileBody(f) {
  return (f.content || []).filter(b => b.type === 'text').map(b => b.text).join('\n')
    .replace(/^\n?<file path="[^"]*">\n?```\n?/, '').replace(/\n?```\n?<\/file>\n?$/, '')
}
function isTextAtt(f) {
  const d = f.details || {}
  return d.mode === 'inline' || d.mode === 'lines' || d.mode === 'bridged'
}
// pi 引擎询问面板（extension ui.select/confirm/input → dialog 推送）
const dlg = reactive({ id: 0, kind: '', title: '', args: [], input: '', sel: 0, show: false })
// ⋯ 更多菜单：更新检查（webui check_updates_all）+ 后台任务（bg_servers）
const updatesAll = reactive({ loading: false, items: [], at: 0 })
watch(() => chat.updatesAll, (v) => { if (v) { updatesAll.items = v; updatesAll.loading = false; updatesAll.at = Date.now() } })
const hasUpdates = computed(() => updatesAll.items.some(u => !u.upToDate && !u.error))
// webui buildUpdateCommand 规则：package→pi update npm:<name>；pi-core/webui→npm i -g <name>@latest
const updCmd = u => u.kind === 'package' ? 'pi update npm:' + u.name : 'npm i -g ' + u.name + '@latest'
function copyText(txt, tip) {
  navigator.clipboard?.writeText(txt).then(() => warn(tip), () => warn('复制失败'))
}
function copyUpdateCmd(u) {
  if (u.upToDate || u.error) return
  copyText(updCmd(u), '已复制：' + updCmd(u))
}
function copyAllUpdates() {
  const list = updatesAll.items.filter(u => !u.upToDate && !u.error)
  copyText(list.map(updCmd).join('; '), '已复制 ' + list.length + ' 条更新命令')
}
function fmtAge(ts) {
  const s = Math.max(0, Math.floor((Date.now() - ts) / 1000))
  return s < 60 ? s + '秒' : s < 3600 ? Math.floor(s / 60) + '分' : Math.floor(s / 3600) + '时'
}
function copyAddr(s) { copyText('http://127.0.0.1:' + s.port, '已复制 ' + s.port + ' 地址') }
function openUpdates(force) {
  menu.value = 'updates'
  updatesAll.loading = true; updatesAll.items = []
  wsSend({ type: 'check_updates_all', force: force === true })
}
function openBgTasks() { menu.value = 'bgtasks'; wsSend({ type: 'list_bg_servers' }) }
function killBg(s) { wsSend({ type: 'kill_background_server', port: s.port, taskId: s.taskId }); setTimeout(openBgTasks, 600) }
function goSettings() { menu.value = ''; location.hash = '#settings' } // App.vue hash 路由 → 设置页
watch(() => chat.dialog, (d) => {
  if (!d) { dlg.show = false; return }
  Object.assign(dlg, d, { input: '', sel: 0, show: true })
})
function dlgRespond(value) {
  wsSend({ type: 'dialog_response', id: dlg.id, value })
  dlg.show = false
}
function dlgSelect(i) {
  const opts = dlg.args[0] || []
  dlgRespond(typeof opts[i] === 'string' ? opts[i] : String(opts[i] ?? ''))
}
function startEdit(m) {
  editId.value = m.id
  input.value = (m.content || []).filter(b => b.type === 'text').map(b => b.text).join('\n')
  // 附件回填（webui 编辑同款）：图片按 dataUrl 恢复、文件按路径引用重发——可删可增
  const vision = !!chat.state?.model?.vision
  const imgs = m.attImgs || []
  const files = m.attFiles || []
  const fileAtts = files.map(f => ({ name: f.details?.name || String(f.details?.path || '附件').split('/').pop(), path: f.details?.path }))
  if (imgs.length && !vision) {
    attachments.value = fileAtts
    setTimeout(() => warn('原消息含 ' + imgs.length + ' 张图，当前模型不支持图片，已剥离（可换 👁 模型重编辑）'), 350)
  } else {
    attachments.value = [
      ...imgs.map((u, i) => ({ name: '原图' + (i + 1), dataUrl: u, mime: (u.slice(5, u.indexOf(';')) || 'image/png'), base64: String(u).split(',')[1] })),
      ...fileAtts,
    ]
  }
  autoGrow()
  setTimeout(() => { if (taEl.value) { taEl.value.focus(); taEl.value.setSelectionRange(input.value.length, input.value.length) } }, 150)
}
function cancelEdit() { editId.value = ''; input.value = ''; attachments.value = []; autoGrow() }
let submitLock = 0
function submitOnce() {
  const now = Date.now()
  if (now - submitLock < 400) return
  submitLock = now
  if (!editId.value) return
  if (attachments.value.some(a => a.base64) && !chat.state?.model?.vision) {
    warn('含图片但当前模型不支持：已自动去掉图片发送文字')
    attachments.value = []
  }
  submitEdit()
}
function submitEdit() {
  const text = input.value.trim()
  if (!text || !editId.value) return
  const atts = buildAtts()
  if (atts === false) return
  // webui 同款：edit_message = fork（退回该消息/丢弃其后/重发）；
  // 服务端 fork 后经 conversations+snapshot 自动切换视图，客户端无条件退出编辑态
  api.editMessage(editId.value, text, atts && atts.length ? atts : undefined)
  cancelEdit()
}
// 快脑=语音专用：悬浮临时气泡+打字机流式+语音同步；文字直发慢脑
function recentCtx() {
  const ms = (chat.state?.messages || []).slice(-8)
  return ms.map(m => (m.role === 'user' ? '用户:' : '小丘:') +
    (m.content || []).filter(b => b.type === 'text').map(b => b.text).join(' ').slice(0, 80)).join('\n')
}
let typeTimer = null, vbHide = null
async function speakText(t) {
  if (!t) return
  let say = t.length > 200 ? t.slice(0, 200) + '……' : t
  try { // 拟人化：像朋友告诉你结果，不复读机
    const r = await fetch('/api/ai_humanize', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ app: 'com.pihost', title: '回复', text: t, kind: 'reply' }) })
    const d = (await r.json()).structuredContent
    if (d.ok && d.data && d.data.say && d.data.say.indexOf('ERR') !== 0) say = d.data.say
  } catch {}
  try { await fetch('/api/tts_speak', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: say }) }) } catch {}
}
const quickPhrases = ref(JSON.parse(localStorage.getItem('xq_quick') || '["继续","总结一下","检查结果","换个思路","更简洁"]'))
function sendQuick(q) { if (q) api.prompt(q) }

function doAbort() { api.abort() } // webui 同款：只发 abort；后续 prompt 是普通追加
const toasts = ref([])
watch(() => chat.notices.length, () => {
  const list = chat.notices.splice(0) // 消费掉
  for (const n of list) {
    const t = { id: n.id || Date.now() + Math.random(), level: n.level, text: n.text || n.textEn || '' }
    toasts.value.push(t)
    if (toasts.value.length > 3) toasts.value.shift()
    setTimeout(() => { toasts.value = toasts.value.filter(x => x.id !== t.id) }, 4600)
  }
})
const errN = ref('')
let errT = null
function warn(msg) { errN.value = msg; if (errT) clearTimeout(errT); errT = setTimeout(() => errN.value = '', 4500) }
function buildAtts() {
  if (!attachments.value.length) return undefined
  const hasImg = attachments.value.some(a => a.base64)
  if (hasImg && !chat.state?.model?.vision) {
    warn('当前模型不支持图片，请先切换视觉模型（🤖下拉选 👁 标记的）')
    return false
  }
  // webui 附件格式：图片 imageData / 文件 fileData / 路径引用 path
  return attachments.value.map(a => a.path
    ? { path: a.path, mode: 'inline' }
    : a.base64
      ? { path: '', imageData: a.base64, mimeType: a.mime, name: a.name }
      : { path: '', fileData: a.fileB64, mimeType: a.mime, name: a.name, size: a.size })
}
// webui submit(queue) 同款：queue=false → steer（插队：当前回合结束立即处理，跳过剩余工具调用）
//                        queue=true  → followUp（排队：整个回复运行结束后才发送）
function submit(queue = false) {
  if (editId.value) { submitEdit(); return } // 编辑态优先：绝不落入普通发送
  const text = input.value.trim()
  const hasAtt = attachments.value.some(a => a.base64 || a.fileB64)
  if (chat.status !== 'open' || (!text && !hasAtt)) return // webui：断线/空内容不发
  const atts = buildAtts()
  if (atts === false) return
  // 发送成功才清空输入（失败保留文本重试）
  if (api.prompt(text, atts, queue || undefined)) {
    if (st.value?.model?.id) recordModelUsage(st.value.model.id)
    input.value = ''; attachments.value = []
    autoGrow()
  }
}
// 📎 动作面板：文件(多) / 拍照 / 录像
function pickFile() { menu.value = ''; nextTick(() => fileEl.value?.click()) }
const camEl = ref(null), vidEl = ref(null)
function takePhoto() { menu.value = ''; nextTick(() => camEl.value?.click()) }
function takeVideo() { menu.value = ''; nextTick(() => vidEl.value?.click()) }
// webui handleFiles 同款：多选 → 栅格图片走视觉管线（非视觉模型拒绝）+ 其它文件 b64 直传
function onFile(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = '' // 允许重复选同一文件
  const isImg = f => /^image\/(png|jpe?g|gif|webp|bmp)$/.test(f.type) // 视频走文件通道
  const imgs = files.filter(isImg), others = files.filter(f => !isImg(f))
  if (imgs.length && st.value?.model && !st.value.model.vision) {
    warn('当前模型不支持图片，已跳过 ' + imgs.length + ' 张图（可换 👁 视觉模型）')
  } else {
    for (const f of imgs) {
      const r = new FileReader()
      r.onload = () => attachments.value.push({ name: f.name, dataUrl: r.result, mime: f.type, base64: String(r.result).split(',')[1] })
      r.readAsDataURL(f)
    }
  }
  for (const f of others) {
    if (f.size > 20 * 1024 * 1024) { warn(f.name + ' 超过 20MB 上限'); continue }
    const r = new FileReader()
    r.onload = () => attachments.value.push({ name: f.name, fileB64: String(r.result).split(',')[1], mime: f.type || 'application/octet-stream', size: f.size })
    r.readAsDataURL(f)
  }
}
function rmAtt(i) { attachments.value.splice(i, 1) }
const taEl = ref(null)
function autoGrow() {
  const el = taEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 110) + 'px'
}
function onPaste(e) {
  const items = e.clipboardData?.items || []
  for (const it of items) {
    if (it.type.startsWith('image/')) {
      e.preventDefault()
      const f = it.getAsFile()
      if (!f) continue
      const r = new FileReader()
      r.onload = () => attachments.value.push({ name: '粘贴图片', dataUrl: r.result, mime: f.type, base64: String(r.result).split(',')[1] })
      r.readAsDataURL(f)
    }
  }
}

async function speakLast() {
  const last = [...msgs.value].reverse().find(m => m.role === 'assistant')
  if (!last) return
  const text = (last.content || []).filter(b => b.type === 'text').map(b => b.text).join(' ')
  if (!text) return
  const s = text.length > 220 ? text.slice(0, 220) + '……' : text
  try { await fetch('/api/tts_speak', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: s }) }) } catch {}
}

function openShellDrawer() { window.dispatchEvent(new Event('xq-open-drawer')) }
function micDown() {
  if (recording.value) return
  if (!window.XiaoqiuBridge) { voiceState.value = '桥未就绪'; return }
  recording.value = true; voiceState.value = '录音中…松手结束'
  vsIgnite('mic') // 统一引擎点火（与喊"小丘"完全同一会话循环）
  voiceState.value = vs.state !== 'off' ? '🎙 待命中，请说…' : ''
}
function micUp() { if (recording.value) { recording.value = false; window.XiaoqiuBridge?.stopVoice() } }
watch(() => vs.state, s => { // 引擎状态投影到旧 UI 位（P3 换成 vb 气泡统一）
  if (s === 'off') { if (voiceState.value === '🎙 待命中，请说…') voiceState.value = '' }
  else if (s === 'listening' && !voiceState.value) voiceState.value = '🎙 待命中，请说…'
})
window.__voiceResult = (t) => { voiceState.value = ''; if (t) voiceFlow(t) }
window.__voiceStatus = (s) => {
  if (s === 'recording') { recording.value = true; voiceState.value = '🎙 录音中…' }
  else if (s === 'thinking') { recording.value = false; voiceState.value = '识别中…' }
  else if (s === 'empty') { voiceState.value = '没听清，再试一次'; setTimeout(() => voiceState.value = '', 1800) }
  else if (s.startsWith('error')) { voiceState.value = s; setTimeout(() => voiceState.value = '', 2500) }
  else voiceState.value = ''
}

onMounted(() => { connect(); startWatchdog(); scroll() })
onUnmounted(() => { delete window.__voiceResult; delete window.__voiceStatus })
</script>

<template>
  <div class="chatwrap">
    <!-- ═══ 顶栏：历史 | 模型▾ | 思考▾ | 新对话 ═══ -->
    <header class="top">
      <button class="tb tap" title="工作台" @click="openShellDrawer">☰</button>
      <button class="tb tap" title="会话历史" @touchstart.prevent="menu = 'sessions'; api.listSessions()">🗂</button>
      <button class="tb title tap" title="会话历史" @touchstart.prevent="menu = 'sessions'; api.listSessions()">
        <span v-if="st?.isStreaming" class="tdot"></span>{{ convTitle }}
      </button>
      <button v-if="chat.status !== 'open'" class="tb warn tap" @click="connect()">↻{{ chat.retryIn || 1 }}s</button>
      <button v-if="st?.isStreaming" class="tb stop tap" @click="doAbort">⏹</button>
      <button v-if="!st?.isStreaming && chat.state?.messages?.length" class="tb tap" title="重试上一条" @click="api.retryLast()">↻</button>
      <button class="tb tap" title="新对话" @click="api.newChat()">✚</button>
      <button class="tb tap" title="更多" @click="menu = menu === 'more' ? '' : 'more'">⋯</button>
    </header>

    <!-- 图片全屏灯箱（双指缩放/拖动/双击放大） -->
    <div v-if="imgViewer" class="imgviewer" @click.self="imgViewer = ''">
      <img :src="imgViewer" class="ivfull" :style="{ transform: 'translate(' + iv.x + 'px,' + iv.y + 'px) scale(' + iv.s + ')' }"
        @touchstart="ivStart" @touchmove.prevent="ivMove" @touchend="ivEnd" />
      <button class="ivx tap" @touchstart.prevent="imgViewer = ''">✕</button>
      <button class="ivr tap" @touchstart.prevent="ivReset">↺</button>
      <span v-if="iv.s !== 1" class="ivzoom">{{ Math.round(iv.s * 100) }}%</span>
    </div>

    <!-- 文件灯箱：内容预览 -->
    <div v-if="fileViewer" class="fvwrap tap" @touchstart.prevent="fileViewer = null">
      <div class="fvcard tap" @touchstart.stop>
        <div class="fvhead">
          <span class="fvico">{{ fileViewer.details?.type === 'folder' ? '📁' : '📄' }}</span>
          <div class="fvmeta">
            <b>{{ fileViewer.details?.name || '附件' }}</b>
            <span>{{ fileViewer.details?.path }}<template v-if="fileViewer.details?.size"> · {{ fmtSize(fileViewer.details.size) }}</template><template v-if="fileViewer.details?.lines"> · {{ fileViewer.details.lines }} 行</template></span>
          </div>
          <button class="fvx tap" @touchstart.prevent="fileViewer = null">✕</button>
        </div>
        <div class="fvbody">
          <pre v-if="isTextAtt(fileViewer)" class="fvpre">{{ fileBody(fileViewer) }}</pre>
          <div v-else class="fvref">
            <p>📎 路径引用（未内联，模型按需读取）</p>
            <p class="fvpath">{{ fileViewer.details?.path }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- 📎 附件动作面板 -->
    <div v-if="menu === 'attach'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'attach'" class="sheet">
      <div class="shd">插入附件</div>
      <div class="sgrid">
        <button class="sitem tap" @touchstart.prevent="pickFile"><span class="sico">📁</span>文件（可多选）</button>
        <button class="sitem tap" @touchstart.prevent="takePhoto"><span class="sico">📷</span>拍照</button>
        <button class="sitem tap" @touchstart.prevent="takeVideo"><span class="sico">🎬</span>录像</button>
      </div>
      <button class="scancel tap" @touchstart.prevent="menu = ''">取消</button>
    </div>

    <!-- ⋯ 更多菜单（webui TopBar 溢出菜单同款） -->
    <div v-if="menu === 'more'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'more'" class="drop small moremenu">
      <div class="di tap" @click="openUpdates">⬇ 检查更新</div>
      <div class="di tap" @click="openBgTasks">▤ 后台任务</div>
      <div class="di tap" @click="goSettings">⚙ 设置</div>
    </div>

    <!-- 检查更新弹窗 -->
    <div v-if="menu === 'updates'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'updates'" class="drop small updbox">
      <div class="dh">组件更新检查<span class="dhsub">{{ updatesAll.at ? '· ' + new Date(updatesAll.at).toLocaleTimeString('zh', { hour12: false }) : '' }}</span></div>
      <div v-if="updatesAll.loading" class="dh muted">检查中…</div>
      <div v-for="(u, i) in updatesAll.items" :key="i" class="updrow tap" :class="{ stale: !u.upToDate && !u.error }"
        @touchstart.prevent="copyUpdateCmd(u)">
        <b>{{ u.name }}</b>
        <span class="updm">{{ u.current }}{{ u.latest ? ' → ' + u.latest : '' }}</span>
        <span class="updst" :class="u.error ? 'err' : u.upToDate ? 'ok' : 'avail'">{{ u.error ? '失败' : u.upToDate ? '✓ 最新' : '点复制命令' }}</span>
      </div>
      <div v-if="!updatesAll.loading && !updatesAll.items.length" class="dh muted">无组件信息</div>
      <div class="dfoot">
        <button v-if="hasUpdates" class="dfb main tap" @touchstart.prevent="copyAllUpdates">📋 复制全部更新命令</button>
        <button class="dfb tap" @touchstart.prevent="openUpdates(true)">↻ 重新检查</button>
      </div>
    </div>

    <!-- 后台任务弹窗 -->
    <div v-if="menu === 'bgtasks'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'bgtasks'" class="drop small updbox">
      <div class="dh">后台任务<span class="dhsub">AI 启动的本地服务器</span></div>
      <div v-for="(s, i) in chat.bgServers" :key="i" class="updrow">
        <b class="bgdot">●</b><b>:{{ s.port }}</b>
        <span class="updm">{{ s.name || 'server' }}<i v-if="s.startedAt" class="bgage">· 已运行 {{ fmtAge(s.startedAt) }}</i></span>
        <button class="updk tap" @touchstart.prevent="copyAddr(s)">📋 地址</button>
        <button class="updk kill tap" @touchstart.prevent="killBg(s)">停止</button>
      </div>
      <div v-if="!chat.bgServers?.length" class="dh muted">当前没有后台任务</div>
      <div class="dfoot"><button class="dfb tap" @touchstart.prevent="openBgTasks">↻ 刷新</button></div>
    </div>

    <!-- 模型选择（底部 sheet） -->
    <div v-if="menu === 'model'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'model'" class="sheet msheet">
      <div class="dh">选择模型</div>
      <input v-model="modelFilter" class="msearch tap" placeholder="🔍 搜索模型…" />
      <div class="mscroll" ref="modelScrollEl">
        <div v-for="m in filteredModels" :key="m.id" class="di tap" :class="{ sel: m.id === st?.model?.id }" @click="pickModel(m)">
          <div class="din"><b>{{ m.name }}</b><span class="dim">{{ m.provider }}</span></div>
          <span class="tag">
            <i v-if="modelUsage[m.id]" class="muse">{{ modelUsage[m.id] }}次</i>
            {{ m.vision ? '👁' : '' }}{{ m.reasoning ? '🧠' : '' }}
          </span>
        </div>
        <div v-if="!chat.models.length" class="dh muted">清单载入中…</div>
        <div v-else-if="!filteredModels.length" class="dh muted">无匹配模型</div>
      </div>
      <div class="dfoot">
        <button class="dfb tap" @touchstart.prevent="refreshModels">↻ 刷新清单</button>
        <button class="dfb tap" @touchstart.prevent="menu = ''">关闭</button>
      </div>
    </div>

    <!-- 思考强度（底部 sheet） -->
    <div v-if="menu === 'think'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'think'" class="sheet msheet">
      <div class="dh">思考深度</div>
      <div v-for="l in levels" :key="l.v" class="di tap" :class="{ sel: l.v === st?.thinkingLevel, dis: !l.ok }"
        :title="l.ok ? '' : '当前模型不支持此档位'" @click="l.ok && api.setThinking(l.v); l.ok && (menu = '')">
        <b>{{ l.label }}</b><span v-if="l.v === st?.thinkingLevel" class="oks">✓</span>
      </div>
      <button class="scancel tap" @touchstart.prevent="menu = ''">取消</button>
    </div>

    <!-- 服务端通知吐司 -->
    <div class="toasts">
      <transition-group name="tst">
        <div v-for="t in toasts" :key="t.id" class="toast" :class="t.level" @click="toasts = toasts.filter(x => x.id !== t.id)">
          {{ t.level === 'error' ? '⛔' : t.level === 'warning' ? '⚠' : 'ℹ' }} {{ t.text }}
        </div>
      </transition-group>
    </div>

    <!-- 消息流 -->
    <div class="flow" ref="listEl" @scroll.passive="onListScroll">
      <div v-if="!msgs.length && !streaming" class="empty">
        <div class="logo">🏔</div>
        <div class="et">和小丘说话，或打字</div>
        <div class="muted">操作手机 · 跑命令 · 写代码 · 查记忆</div>
      </div>

      <template v-for="m in renderMsgs" :key="m.id">
        <!-- 孤儿附件（aside 后无用户消息——异常兜底，右侧胶囊） -->
        <div v-if="m.role === 'orphanAtts'" class="mrow urow">
          <div class="ub slim">
            <div v-for="(f, fi) in m.atts" :key="'oa' + fi" class="achip">
              <span class="acico">{{ imgOf(f) ? '🖼' : '📄' }}</span>
              <span class="acname">{{ f.details?.name || '附件' }}</span>
            </div>
          </div>
        </div>
        <!-- 用户 -->
        <div v-else-if="m.role === 'user'" class="mrow urow">
          <div class="ub">
            <div v-if="m.attImgs?.length || m.attFiles?.length" class="a2row" :class="{ one: m.attImgs?.length === 1 && !m.attFiles?.length }">
              <img v-for="(u, ii) in (m.attImgs || [])" :key="'ag' + ii" :src="u" class="a2thumb tap" @click="viewImg(u)" />
              <div v-for="(f, fi) in (m.attFiles || [])" :key="'af' + fi" class="a2chip tap" @click="viewFile(f)">
                <span class="a2ico">{{ f.details?.type === 'folder' ? '📁' : (f.details?.mode === 'bridged' ? '🔤' : '📄') }}</span>
                <span class="a2name">{{ f.details?.name || f.details?.path || '附件' }}</span>
              </div>
              <span v-if="(m.attImgs?.length || 0) + (m.attFiles?.length || 0) > 4" class="amore">››</span>
            </div>
            <img v-for="(b, bi) in (m.content||[]).filter(b => b.type === 'image' && b.dataUrl)" :key="bi" :src="b.dataUrl" class="uimg" />
            <div class="ut">{{ (m.content||[]).filter(b => b.type === 'text').map(b => b.text).join('\n') }}</div>
          </div>
          <div class="uact">
            <button v-if="!busy" class="ua tap" @click="startEdit(m)">✎ 编辑</button>
            <button class="ua tap" @click="navigator.clipboard?.writeText((m.content||[]).filter(b => b.type === 'text').map(b => b.text).join('\n'))">📋</button>
          </div>
        </div>

        <!-- 助手 -->
        <div v-else-if="m.role === 'assistant'" class="mrow arow">
          <div class="ab">
            <template v-for="(b, bi) in (m.content || [])" :key="bi">
              <!-- 思考：折叠块 -->
              <div v-if="b.type === 'thinking'" class="think" :class="{ open: thinkOpen(m, bi) }">
                <button class="th-toggle tap" @click="toggleThink(m, bi)">
                  <span class="chev">{{ thinkOpen(m, bi) ? '▾' : '▸' }}</span> 💭 {{ thinkOpen(m, bi) ? '思考过程' : thinkPreview(b.thinking) || '思考过程' }}<span v-if="b.durationMs" class="th-dur">· {{ (b.durationMs / 1000).toFixed(1) }}s</span>
                </button>
                <div v-if="thinkOpen(m, bi)" class="th-body">{{ b.thinking }}</div>
              </div>
              <!-- 工具调用：折叠卡 -->
              <div v-else-if="b.type === 'toolCall'" class="tc" :class="{ open: toolOpen(b.id) }">
                <button class="tc-toggle tap" @click="toggleTool(b.id)">
                  <span class="chev">{{ toolOpen(b.id) ? '▾' : '▸' }}</span>
                  <span class="tc-dot" :class="toolResultOf(b.id) ? (toolResultOf(b.id).isError ? 'err' : 'ok') : 'run'"></span>
                  <b>{{ b.name }}</b>
                  <span class="muted tc-sum">{{ (b.argumentsText || '').replace(/\s+/g, ' ').slice(0, 46) }}</span>
                </button>
                <div v-if="toolOpen(b.id)" class="tc-body">
                  <div class="tc-sec">参数</div>
                  <pre class="tc-pre">{{ b.argumentsText }}</pre>
                  <template v-if="toolResultOf(b.id)">
                    <div class="tc-sec">结果 <span :class="toolResultOf(b.id).isError ? 'errt' : ''">{{ toolResultOf(b.id).isError ? '· 出错' : '' }}</span></div>
                    <pre class="tc-pre">{{ String((toolResultOf(b.id).content?.[0]?.text ?? toolResultOf(b.id).content?.[0]) ?? '').slice(0, 3000) }}</pre>
                  </template>
                </div>
              </div>
              <!-- 正文：markdown -->
              <div v-else-if="b.type === 'text' && b.text" class="md" v-html="md(b.text)"></div>
            </template>
            <div v-if="m.id === lastAid" class="ameta">{{ fmtTs(m.timestamp) }}<template v-if="m.model"> · {{ m.model }}</template></div>
          </div>
        </div>
      </template>

      <!-- 滚到底悬浮钮 -->
      <transition name="tst">
        <button v-if="!atBottom" class="jumpbtn tap" @touchstart.prevent="scroll(); atBottom = true">↓</button>
      </transition>
      <!-- 排队/插队气泡 -->
      <div v-if="st?.queue">
        <div v-for="(q, i) in (st.queue.steering || [])" :key="'s' + i" class="mrow urow">
          <div class="ub queued"><span class="qtag steer">插队</span>{{ q }}</div>
        </div>
        <div v-for="(q, i) in (st.queue.followUp || [])" :key="'f' + i" class="mrow urow">
          <div class="ub queued"><span class="qtag follow">排队</span>{{ q }}</div>
        </div>
      </div>

      <!-- 流式 -->
      <div v-if="streaming" class="mrow arow">
        <div class="ab live">
          <template v-for="(b, bi) in (streaming.content || [])" :key="bi">
            <div v-if="b.type === 'thinking'" class="think">
              <button class="th-toggle tap" @click="toggleThink(streaming, bi)">
                <span class="chev">{{ thinkOpen(streaming, bi) ? '▾' : '▸' }}</span> 💭 <span class="th-live">思考中<span class="dots">…</span></span> <span class="muted">{{ thinkPreview(b.thinking, true) }}</span>
              </button>
              <div v-if="thinkOpen(streaming, bi)" class="th-body">{{ b.thinking }}</div>
            </div>
            <div v-else-if="b.type === 'toolCall'" class="tc">
              <button class="tc-toggle tap" @click="toggleTool(b.id)">
                <span class="chev">{{ toolOpen(b.id) ? '▾' : '▸' }}</span><span class="tc-dot run"></span><b>{{ b.name }}</b>
              </button>
              <div v-if="toolOpen(b.id)" class="tc-body"><pre class="tc-pre">{{ b.argumentsText }}</pre></div>
            </div>
            <div v-else-if="b.type === 'text' && b.text" class="md" v-html="md(b.text)"></div>
          </template>
          <span class="caret">▍</span>
        </div>
      </div>
    </div>

    <!-- 状态条 -->
    <div class="foot muted">
      <template v-if="st?.stats">
        <button class="ctx tap" :class="ctxCls" :title="'上下文 ' + ctxTxt + '，点击压缩'" @click="api.compact()">
          <span class="ctxbar"><i :style="{ width: Math.min(st.stats.contextUsage.percent || 0, 100) + '%' }"></i></span>
          <span class="ctxnum">{{ ctxTxt }}</span>
        </button>
        <span class="sep">·</span>
        <span title="累计成本">${{ fmtCost(st.stats.cost) }}</span>
        <span class="sep">·</span>
        <span :title="'缓存读 ' + fT(st.stats.tokens.cacheRead) + ' / 写 ' + fT(st.stats.tokens.cacheWrite)">缓存<b class="cacheP" :class="cacheCls">{{ cachePct }}%</b></span>
        <span class="sep">·</span>
        <span title="消息数">{{ st.stats.totalMessages }} 条</span>
        <span v-if="busy" class="working"><span class="wspin"></span>处理中<template v-if="qTotal"> ⏳{{ qTotal }}</template></span>
      </template>
      <span class="sp"></span>
      <button class="fb tap" :title="ttsOn ? '朗读开' : '朗读关'" @click="ttsOn = !ttsOn; saveTts(ttsOn)">{{ ttsOn ? '🔊' : '🔇' }}</button>
    </div>

    <!-- 语音状态浮条 -->
    <div v-if="voiceState" class="vbar">{{ recording ? '⏺ ' : '' }}{{ voiceState }}</div>

    <!-- 快捷短语 -->

    <div v-if="quickPhrases.length && !st?.isStreaming" class="qp-bar">

      <button v-for="q in quickPhrases" :key="q" class="qp tap" @click="sendQuick(q)">{{ q }}</button>

    </div>

    <!-- 输入区 -->
    <!-- 快脑悬浮临时气泡（语音专用） -->
    <transition name="vbf">
          </transition>

    <!-- 询问面板（pi 引擎 ui.select/confirm/input）——内联非模态，对话保持可见 -->
    <div v-if="dlg.show" class="dlgin">
      <div class="dlgh">
        <span class="dlgb">🤖 请求确认</span>
        <span v-if="dlg.title" class="dlgt">{{ dlg.title }}</span>
        <button class="dlgx tap" @touchstart.prevent="dlgRespond(null)">✕</button>
      </div>
      <div v-if="dlg.kind === 'select'" class="dlgopts">
        <button v-for="(o, i) in (dlg.args[0] || [])" :key="i" class="dlgopt tap"
          @touchstart.prevent="dlgSelect(i)">{{ o }}</button>
      </div>
      <p v-else-if="dlg.kind === 'confirm'" class="dlgmsg">{{ dlg.args[0] }}</p>
      <div v-if="dlg.kind === 'confirm'" class="dlgopts two">
        <button class="dlgopt no tap" @touchstart.prevent="dlgRespond(false)">取消</button>
        <button class="dlgopt ok tap" @touchstart.prevent="dlgRespond(true)">确认</button>
      </div>
      <div v-if="dlg.kind === 'input'" class="dlginrow">
        <input v-model="dlg.input" class="dlgi" :placeholder="dlg.args[0] || '输入…'"
          @keyup.enter="dlg.input.trim() && dlgRespond(dlg.input.trim())" />
        <button class="dlgopt ok tap" @touchstart.prevent="dlg.input.trim() && dlgRespond(dlg.input.trim())">提交</button>
      </div>
    </div>

    <div class="composer">
      <div v-if="errN" class="errbn">⚠ {{ errN }}</div>
      <div v-if="editId" class="editbn">
        <span class="ebtxt">✎ 编辑消息 · ✓ 发送后从这里重新生成</span>
        <button class="tap" @touchstart.prevent="cancelEdit">取消</button>
      </div>
      <div v-if="attachments.length" class="attrow">
        <div v-for="(a, i) in attachments" :key="i" class="attc" :title="a.dataUrl ? '图片：' + a.name : '文件：' + (a.path || a.name)">
          <img v-if="a.dataUrl" :src="a.dataUrl" class="attimg2" />
          <span v-else class="attfile2">{{ (a.path || a.name || '').endsWith('/') ? '📁' : '📄' }}<i>{{ a.name }}</i></span>
          <button class="attx tap" @click="rmAtt(i)">✕</button>
        </div>
        <button class="attadd tap" @click="pickFile">＋</button>
        <span class="atthint">将随下一条消息发送</span>
      </div>
      <div v-if="slashHint.length" class="slash">
        <div v-for="sc in slashHint" :key="sc.name" class="si2 tap" @mousedown.prevent="pickSlash(sc)">
          <b class="sname2">/{{ sc.name }}</b>
          <span class="stag" :class="sc.source">{{ SRC[sc.source]?.label || sc.source }}</span>
          <span class="sdesc2">{{ sc.description || '' }}<i v-if="sc.argumentHint" class="shint">{{ sc.argumentHint }}</i></span>
        </div>
      </div>
      <input ref="fileEl" type="file" multiple hidden @change="onFile" />
      <input ref="camEl" type="file" accept="image/*" capture="environment" hidden @change="onFile" />
      <input ref="vidEl" type="file" accept="video/*" capture="environment" hidden @change="onFile" />
      <div class="crow">
        <textarea ref="taEl" v-model="input" rows="1" placeholder="发消息…" @paste="onPaste" @input="autoGrow"></textarea>
      </div>
      <!-- 工具条（webui composer-tools 整合）：附件/思考/模型 左 · 语音/排队/发送 右 -->
      <div class="ctools">
        <button class="chip tap" title="附件" @touchstart.prevent="menu = menu === 'attach' ? '' : 'attach'">📎</button>
        <button class="chip tap" :class="{ on: st?.thinkingLevel && st?.thinkingLevel !== 'off' }" @touchstart.prevent="menu = menu === 'think' ? '' : 'think'">
          🧠<span class="ctxt">{{ thinkLabel(st?.thinkingLevel) }}</span>
        </button>
        <button class="chip model tap" @touchstart.prevent="menu = menu === 'model' ? '' : 'model'">
          {{ st?.model?.name || '模型' }}{{ st?.model?.vision ? ' 👁' : '' }} <span class="car">▾</span>
        </button>
        <span class="sp"></span>
        <button class="cb mic tap" :class="{ rec: recording }" title="按住说话"
          @touchstart.prevent="micDown" @touchend.prevent="micUp" @mousedown="micDown" @mouseup="micUp">
          <span v-if="recording" class="recdot"></span><template v-else>🎙</template>
        </button>
        <button v-if="busy && input.trim() && !editId" class="cb q tap" title="排队：整个回复结束后再发送" @touchstart.prevent="submit(true)">⏳</button>
        <button class="cb send tap" :class="{ edit: editId, dim: !input.trim() || chat.status !== 'open' }" @touchstart.prevent="input.trim() && (editId ? submitOnce() : submit())">
          <template v-if="editId">✓</template><template v-else-if="busy">⤴</template><template v-else>➤</template>
        </button>
      </div>
    </div>

    <!-- 历史会话抽屉 -->
    <div v-if="menu === 'sessions'" class="backdrop tap" @click="menu = ''"></div>
    <div v-if="menu === 'sessions'" class="drawer">
      <div class="dh big">历史会话 <span class="muted">({{ (chat.sessionSearch ?? chat.sessions).length }})</span></div>
      <div class="ssearch">
        <input v-model="sesQ" placeholder="搜索会话内容（全文）…" @input="sesDeb" />
      </div>
      <div class="slist">
        <div v-for="s in (chat.sessionSearch ?? chat.sessions)" :key="s.path" class="si tap" @click="api.switchSession(s.path); menu = ''">
          <div class="sin"><b>{{ s.name || s.firstMessage?.slice(0, 26) || '会话' }}</b>
            <span v-if="s.source === 'tui'" class="tag">CLI</span></div>
          <div class="dim">{{ s.messageCount }} 条 · {{ new Date(s.modified).toLocaleString('zh', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }}</div>
          <span class="sdel tap" @click.stop="api.deleteSession(s.path); setTimeout(() => api.listSessions(), 500)">🗑 删</span>
        </div>
      </div>
      <div class="ws">
        <div class="dh big">工作区</div>
        <div class="cwdnow muted">📁 {{ st?.cwd || '—' }}</div>
        <div v-for="p in chat.projects" :key="p.path" class="prow tap" :class="{ cur: p.path === st?.cwd }" @click="api.setCwd(p.path)">
          <span class="pico">📂</span>
          <span class="ppath">{{ p.path }}</span>
          <b v-if="p.path === st?.cwd" class="pcur">当前</b>
        </div>
        <div class="pinput">
          <input v-model="newCwd" placeholder="输入新路径…" @input="cwdDeb" />
          <button class="tap" @click="api.setCwd(newCwd); newCwd = ''">切换</button>
        </div>
        <div v-if="chat.pathCompletions.length" class="pcomp">
          <div v-for="pc in chat.pathCompletions.slice(0, 6)" :key="pc.path" class="pci tap" @click="newCwd = pc.path; api.completePath(pc.path)">
            {{ pc.type === 'dir' ? '📁' : '📄' }} {{ pc.name }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
.ccopy { position: absolute; top: 6px; right: 6px; background: var(--line); color: var(--hill); border: 1px solid var(--line);
  border-radius: 6px; padding: 3px 9px; font-size: 11px; }
.qp-bar { display:flex; gap:6px; padding:6px 12px; overflow-x:auto; scrollbar-width:none; }
.qp { border:1px solid var(--line); background:var(--card); border-radius:99px; padding:6px 14px; font-size:13px; color:var(--muted); white-space:nowrap; flex-shrink:0; }
.qp:active { background:var(--hill-soft); color:var(--hill); }
</style>

<style scoped>

/* 更新/后台任务弹窗细节 */
.updbox { max-width: 340px; }
.dh .dhsub { font-size: 11px; color: var(--muted); font-weight: 400; margin-left: 6px; }
.updrow.stale { background: var(--hill-soft); border-radius: 9px; }
.updrow.stale:active { background: var(--hill-soft); }
.dfb.main { color: var(--dawn); font-weight: 700; }
.bgdot { color: var(--hill); font-size: 9px; }
.bgage { font-style: normal; color: var(--muted); margin-left: 4px; font-size: 10px; }
.updk.kill { background: var(--bad); }

/* 气泡内附件（ChatGPT/LobeChat 模式：直显不折叠） */
.th-dur { font-size: 10px; color: var(--muted); margin-left: 4px; }
/* 滚到底悬浮钮 */
.jumpbtn { position: absolute; right: 14px; bottom: 130px; z-index: 30; width: 40px; height: 40px; border-radius: 50%;
  background: var(--card); border: 1px solid var(--line); color: var(--ink); font-size: 18px; box-shadow: 0 4px 14px rgba(34,48,31,.2); }

/* 附件横滚行（微信/ChatGPT 模式）：小方图+小胶囊统一流，多了横滑 */
.a2row { display: flex; align-items: center; gap: 4px; overflow-x: auto; margin-bottom: 6px; scroll-snap-type: x proximity; -webkit-overflow-scrolling: touch; }
.a2row::-webkit-scrollbar { display: none; }
.a2thumb { width: 56px; height: 56px; object-fit: cover; border-radius: 9px; flex-shrink: 0; background: var(--bg); border: 1px solid rgba(34,48,31,.1); scroll-snap-align: start; }
.a2row.one .a2thumb { width: 118px; height: 118px; }
.a2chip { display: inline-flex; align-items: center; gap: 5px; background: rgba(12,14,20,.5); border: 1px solid rgba(255,255,255,.08); border-radius: 9px; padding: 6px 9px; flex-shrink: 0; scroll-snap-align: start; }
.a2ico { font-size: 14px; }
.a2name { font-size: 11px; color: var(--ink); max-width: 28vw; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.amore { color: rgba(255,255,255,.4); font-size: 13px; flex-shrink: 0; padding: 0 2px; }
.achips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.achip { display: inline-flex; align-items: center; gap: 6px; background: rgba(12, 14, 20, .5); border: 1px solid rgba(255,255,255,.08); border-radius: 10px; padding: 7px 11px; max-width: 100%; }
.acico { font-size: 15px; }
.acname { font-size: 12px; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 40vw; }
.acsize { font-size: 10px; color: rgba(255,255,255,.45); flex-shrink: 0; }
.ub.slim { padding: 8px 10px; display: flex; flex-direction: column; gap: 6px; }
/* 文件灯箱 */
.fvwrap { position: fixed; inset: 0; z-index: 99; background: rgba(5, 6, 10, .82); display: flex; align-items: center; justify-content: center; padding: 20px; }
.fvcard { width: 100%; max-width: 420px; max-height: 82vh; background: var(--card); border: 1px solid var(--line); border-radius: 16px; display: flex; flex-direction: column; overflow: hidden; }
.fvhead { display: flex; align-items: center; gap: 10px; padding: 14px; border-bottom: 1px solid var(--line); }
.fvico { font-size: 24px; }
.fvmeta { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.fvmeta b { font-size: 14px; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fvmeta span { font-size: 11px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fvx { background: none; border: 0; color: var(--muted); font-size: 17px; padding: 4px 8px; }
.fvbody { overflow-y: auto; flex: 1; }
.fvpre { margin: 0; padding: 14px; font-size: 12px; line-height: 1.6; color: var(--ink); white-space: pre-wrap; word-break: break-all; font-family: ui-monospace, monospace; }
.fvref { padding: 26px 18px; text-align: center; color: var(--muted); font-size: 13px; }
.fvpath { margin-top: 8px; color: var(--hill); font-size: 12px; word-break: break-all; }
.imgviewer { position: fixed; inset: 0; z-index: 99; background: rgba(5, 6, 10, .96); display: flex; flex-direction: column; align-items: center; justify-content: center; }
.ivfull { max-width: 96vw; max-height: 88vh; object-fit: contain; transform-origin: center center; touch-action: none; transition: transform .08s linear; will-change: transform; }
.ivx { position: absolute; top: calc(14px + env(safe-area-inset-top)); right: 16px; width: 38px; height: 38px; border-radius: 50%; background: rgba(34,48,31,.15); border: 1px solid var(--line); color: var(--ink); font-size: 15px; }
.ivr { position: absolute; top: calc(14px + env(safe-area-inset-top)); left: 16px; width: 38px; height: 38px; border-radius: 50%; background: rgba(34,48,31,.15); border: 1px solid var(--line); color: var(--ink); font-size: 15px; }
.ivzoom { position: absolute; top: calc(60px + env(safe-area-inset-top)); left: 50%; transform: translateX(-50%); font-size: 11px; color: var(--muted); background: rgba(247,243,236,.85); padding: 3px 10px; border-radius: 10px; }
.ivtip { color: var(--muted); font-size: 12px; margin-top: 14px; }

/* 模型/思考底部 sheet */
.msheet { max-height: 62vh; display: flex; flex-direction: column; }
.msheet .mscroll { max-height: 44vh; overflow-y: auto; overscroll-behavior: contain; padding: 0 6px; }
.msheet .msearch { width: calc(100% - 12px); margin: 4px 6px 8px; }
.msheet .dh { padding: 0 14px 4px; }

/* 底部动作面板（附件等） */
.sheet { position: fixed; left: 0; right: 0; bottom: 0; z-index: 72; background: var(--card); border-radius: 18px 18px 0 0; padding: 14px 14px calc(14px + env(safe-area-inset-bottom)); }
.shd { text-align: center; font-size: 12px; color: var(--muted); margin-bottom: 12px; }
.sgrid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.sitem { background: var(--card); border: 1px solid var(--line); border-radius: 14px; color: var(--ink); font-size: 12.5px; padding: 14px 6px; display: flex; flex-direction: column; align-items: center; gap: 8px; }
.sitem .sico { font-size: 26px; }
.scancel { width: 100%; margin-top: 10px; background: none; border: 0; color: var(--muted); font-size: 14px; padding: 10px; }

/* ⋯ 更多菜单/更新/后台任务 */
.moremenu { min-width: 180px; }
.updrow { display: flex; align-items: center; gap: 8px; padding: 9px 14px; font-size: 13px; border-bottom: 1px solid var(--line); }
.updrow b { color: var(--ink); font-size: 12.5px; }
.updm { color: var(--muted); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.updst.ok { color: var(--hill); } .updst.avail { color: var(--dawn); font-weight: 700; } .updst.err { color: var(--bad); }
.updk { margin-left: auto; background: var(--bad); border: 0; color: var(--bad); border-radius: 7px; font-size: 11px; padding: 4px 8px; }

/* 询问面板（dialog-inline 语义） */
.dlgin { margin: 0 10px 6px; background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px; }
.dlgh { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.dlgb { font-size: 11px; color: var(--hill); background: var(--hill-soft); padding: 2px 8px; border-radius: 6px; flex-shrink: 0; }
.dlgt { font-size: 12.5px; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dlgx { margin-left: auto; background: none; border: 0; color: var(--muted); font-size: 14px; padding: 2px 6px; }
.dlgopts { display: flex; flex-direction: column; gap: 6px; }
.dlgopts.two { flex-direction: row; }
.dlgopt { background: var(--card); border: 1px solid var(--line); color: var(--ink); font-size: 13px; padding: 10px; border-radius: 9px; text-align: left; }
.dlgopt.ok { background: var(--hill-soft); border-color: var(--hill); color: var(--hill); font-weight: 700; }
.dlgopt.no { background: var(--bad); border-color: #6b3e3e; color: var(--bad); font-weight: 700; }
.dlgmsg { font-size: 13px; color: var(--muted); margin: 2px 0 8px; }
.dlginrow { display: flex; gap: 8px; }
.dlgi { flex: 1; background: #171a20; border: 1px solid #3a3f4a; border-radius: 8px; color: var(--ink); padding: 9px 10px; font-size: 13px; outline: none; }

/* 模型/思考下拉升级（webui 对齐） */
.di.dis { opacity: .5; color: var(--muted); pointer-events: none; text-decoration: line-through; text-decoration-thickness: 1px; }
.di .oks { margin-left: auto; color: var(--hill); font-weight: 700; }
.msearch { width: calc(100% - 20px); margin: 4px 10px 6px; padding: 7px 10px; border: 1px solid #3a3f4a; border-radius: 8px; background: #171a20; color: var(--ink); font-size: 13px; outline: none; }
.mscroll { max-height: 46vh; overflow-y: auto; overscroll-behavior: contain; }
.dfoot { display: flex; gap: 8px; padding: 8px 12px; border-top: 1px solid var(--line); }
.dfb { flex: 1; background: none; border: 0; color: var(--hill); font-size: 12.5px; padding: 6px 0; }
.muse { font-style: normal; font-size: 10px; color: var(--muted); margin-right: 4px; }
.atthint { align-self: center; font-size: 10.5px; color: var(--muted); white-space: nowrap; }

/* 队列气泡/编辑/排队钮 */
.queued { display: flex; align-items: center; gap: 6px; font-size: 13px; opacity: .85; }
.qtag { font-size: 10px; padding: 2px 6px; border-radius: 8px; flex-shrink: 0; }
.qtag.steer { background: #3b2f18; color: var(--dawn); }
.qtag.follow { background: var(--hill-soft); color: var(--hill); }
.qrm { color: var(--muted); padding: 0 2px; }
.uact { display: flex; gap: 4px; margin-top: 3px; justify-content: flex-end; }
.ua { background: none; border: 0; color: var(--muted); font-size: 11px; padding: 2px 6px; border-radius: 6px; }
.ua:active { color: var(--hill); }
.editbar { display: flex; align-items: center; justify-content: space-between; background: #2a2418; color: var(--dawn);
  font-size: 12px; border-radius: 9px; padding: 7px 12px; margin-bottom: 7px; }
.cb.q { background: #FFF3E0; border-color: var(--dawn); color: var(--dawn); font-size: 14px; }
/* 斜杠候选 */
.slash { position: absolute; bottom: 100%; left: 10px; right: 10px; max-height: 208px; overflow-y: auto;
  background: var(--card); border: 1px solid var(--line);
  border-radius: 12px; box-shadow: 0 -8px 28px rgba(34,48,31,.3); margin-bottom: 4px; z-index: 5; }
.si2 { display: flex; align-items: center; gap: 8px; padding: 9px 13px; font-size: 13px; color: var(--ink); border-bottom: 1px solid var(--line); }
.si2:last-child { border-bottom: 0; }
.si2:active { background: var(--bg); }
.sname2 { color: var(--hill); flex-shrink: 0; max-width: 40%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.stag { flex-shrink: 0; font-size: 10px; padding: 2px 7px; border-radius: 7px; border: 1px solid; line-height: 1.2; }
.stag.builtin { color: var(--hill); border-color: #24405a; background: #16222c; }
.stag.extension { color: var(--hill); border-color: var(--line); background: var(--bg); }
.stag.prompt { color: var(--dawn); border-color: #4a3c26; background: #241f18; }
.stag.skill { color: var(--hill); border-color: var(--hill); background: var(--hill-soft); }
.stag.plugin { color: var(--bad); border-color: var(--bad); background: #F8E8E5; }
.sdesc2 { flex: 1; min-width: 0; font-size: 11.5px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.shint { font-style: normal; color: var(--muted); margin-left: 5px; }
.chatwrap { position: fixed; inset: 0; background: var(--bg); color: var(--ink);
  display: flex; flex-direction: column; z-index: 10; }

/* 顶栏 */
.top { display: flex; gap: 6px; align-items: center; padding: 8px 10px; background: rgba(247,243,236,.95); border-bottom: 1px solid var(--line); }
.tb { background: var(--card); color: var(--ink); border: 1px solid var(--line); border-radius: 18px; padding: 6px 12px; font-size: 13px; }
.tb.name { max-width: 40vw; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tb.title { flex: 1; min-width: 0; max-width: 46vw; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; color: var(--muted); background: none; border: 0; padding: 6px 8px; justify-content: center; display: flex; align-items: center; gap: 5px; }
.tdot { width: 6px; height: 6px; border-radius: 50%; background: var(--dawn); flex-shrink: 0; animation: pulse 1.2s infinite; }
.tb.stop { background: #F8E8E5; border-color: var(--bad); color: var(--bad); }
.fb { background: none; border: 0; font-size: 15px; padding: 2px 4px; }
.car { opacity: .6; font-size: 10px; }
.sp { flex: 1; }

/* 弹层 */
.backdrop { position: fixed; inset: 0; z-index: 60; }
.drop { position: fixed; top: 100px; left: 12px; right: 12px; max-width: 420px; z-index: 61;
  background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 8px; box-shadow: 0 12px 40px rgba(34,48,31,.3); }
.drop.small { right: auto; min-width: 180px; }
.dh { font-size: 12px; color: var(--muted); padding: 8px 10px 4px; }
.dh.big { font-size: 15px; color: var(--ink); font-weight: 700; }
.di { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px; border-radius: 10px; }
.di.sel { background: rgba(139,92,246,.14); }
.din { display: flex; flex-direction: column; gap: 1px; }
.dim { font-size: 11px; color: var(--muted); }
.tag { font-size: 10px; color: var(--hill); }

/* 消息流 */
.flow { flex: 1; overflow-y: auto; padding: 14px 12px 8px; position: relative; }
.empty { text-align: center; padding: 60px 20px; }
.logo { font-size: 44px; margin-bottom: 10px; }
.et { font-size: 16px; font-weight: 700; margin-bottom: 4px; }
.mrow { margin: 12px 0; }
.urow { display: flex; justify-content: flex-end; }
.ub { max-width: 85%; background: linear-gradient(135deg, rgba(139,92,246,.42), rgba(139,92,246,.28));
  border: 1px solid rgba(139,92,246,.35); padding: 10px 14px; border-radius: 16px 16px 4px 16px; }
.ut { white-space: pre-wrap; font-size: 14.5px; line-height: 1.6; }
.uimg { max-width: 180px; border-radius: 10px; margin-bottom: 6px; }
.ameta { font-size: 9.5px; color: var(--muted); margin-top: 8px; padding-top: 6px; border-top: 1px solid rgba(34,48,31,.08); letter-spacing: .3px; }
.ab { max-width: 92%; }
.ab.live .caret { color: var(--hill); animation: blink 1s step-start infinite; }
@keyframes blink { 50% { opacity: 0; } }

/* markdown 正文 */
.md { font-size: 14.5px; line-height: 1.65; white-space: normal; }
.md :deep(pre) { background: rgba(247,243,236,.95); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; overflow-x: auto; font-size: 12.5px; margin: 8px 0; }
.md :deep(code) { font-family: ui-monospace, monospace; color: #c9b8f8; }
.md :deep(p) { margin: 6px 0; }
.md :deep(ul), .md :deep(ol) { padding-left: 20px; margin: 6px 0; }
.md :deep(h1), .md :deep(h2), .md :deep(h3) { margin: 12px 0 6px; font-size: 15px; }
.md :deep(table) { border-collapse: collapse; margin: 8px 0; }
.md :deep(th), .md :deep(td) { border: 1px solid var(--line); padding: 4px 10px; font-size: 12.5px; }

/* 思考折叠 */
.think { margin: 6px 0; }
.th-toggle { background: none; border: 0; color: var(--muted); font-size: 12px; text-align: left;
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 3px 0; display: block; }
.chev { display: inline-block; width: 14px; }
.th-body { border-left: 2px solid var(--line); padding: 4px 0 4px 10px; margin: 4px 0 4px 6px;
  font-size: 12px; color: #9ea3ad; white-space: pre-wrap; max-height: 300px; overflow-y: auto; }
.th-live { color: var(--hill); }
.dots::after { content: '…'; animation: dots 1.2s steps(4) infinite; }
@keyframes dots { 0% { content: ''; } 25% { content: '.'; } 50% { content: '..'; } 75% { content: '...'; } }

/* 工具卡折叠 */
.tc { background: rgba(247,243,236,.95); border: 1px solid var(--line); border-radius: 12px; margin: 6px 0; overflow: hidden; }
.tc-toggle { width: 100%; background: none; border: 0; color: var(--ink); text-align: left; padding: 9px 12px;
  font-size: 13px; display: flex; align-items: center; gap: 7px; }
.tc-sum { font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.tc-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.tc-dot.run { background: #e8a33d; animation: pulse 1s infinite; }
.tc-dot.ok { background: #3ecf72; }
.tc-dot.err { background: #e05555; }
@keyframes pulse { 50% { opacity: .4; } }
.tc-body { border-top: 1px solid var(--line); padding: 8px 12px; }
.tc-sec { font-size: 10.5px; color: var(--muted); margin: 6px 0 3px; }
.tc-pre { background: #f0ede6; border-radius: 8px; padding: 8px 10px; font-size: 11.5px;
  white-space: pre-wrap; word-break: break-all; max-height: 220px; overflow-y: auto; color: #b8bcc6; margin: 0; }
.errt { color: #e05555; }

/* 状态条 */
.foot { display: flex; align-items: center; padding: 4px 14px; font-size: 11px; background: rgba(247,243,236,.95); border-top: 1px solid var(--line); }
.ttsc { display: flex; align-items: center; gap: 4px; color: var(--muted); }
.ttsc input { width: auto; }

/* 语音浮条 */
.vbar { position: fixed; bottom: 132px; left: 50%; transform: translateX(-50%); z-index: 70;
  background: var(--card); border: 1px solid var(--line); color: var(--hill); font-size: 13px; font-weight: 700;
  border-radius: 20px; padding: 8px 18px; box-shadow: 0 8px 24px rgba(34,48,31,.3); }

/* 输入区 */
.composer { position: relative; background: rgba(247,243,236,.95); border-top: 1px solid var(--line); padding: 10px 12px calc(10px + env(safe-area-inset-bottom)); }
.errbn { background: #2a181c; color: var(--bad); font-size: 12.5px; border-radius: 10px; padding: 8px 12px; margin-bottom: 8px; border: 1px solid var(--bad); }
.editbn { display: flex; align-items: center; justify-content: space-between; background: #2a2418; color: var(--dawn);
  font-size: 12px; border-radius: 10px; padding: 7px 12px; margin-bottom: 8px; border: 1px solid #4a3c26; }
.editbn { gap: 6px; }
.ebtxt { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.editbn button { background: none; border: 0; color: var(--dawn); font-size: 12.5px; flex-shrink: 0; padding: 4px 8px; }
.attrow { display: flex; gap: 8px; overflow-x: auto; scrollbar-width: none; padding: 2px 2px 8px; }
.attc { position: relative; flex-shrink: 0; width: 62px; height: 62px; border-radius: 11px; overflow: hidden;
  background: var(--card); border: 1px solid var(--line); display: flex; align-items: center; justify-content: center; }
.attimg2 { width: 100%; height: 100%; object-fit: cover; }
.attfile2 { font-size: 16px; color: var(--muted); display: flex; flex-direction: column; align-items: center; gap: 1px; }
.attfile2 i { font-style: normal; font-size: 8.5px; max-width: 54px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attx { position: absolute; top: 2px; right: 2px; width: 17px; height: 17px; border-radius: 50%;
  background: rgba(34,48,31,.15); color: #fff; border: 0; font-size: 9px; line-height: 1; }
.attadd { flex-shrink: 0; width: 62px; height: 62px; border-radius: 11px; border: 1px dashed #3a3e48;
  background: none; color: var(--muted); font-size: 19px; }
.cb.dim { opacity: .35; }
.cb.edit { background: var(--dawn); border-color: var(--dawn); }
.recdot { display: block; width: 12px; height: 12px; border-radius: 50%; background: #fff; animation: pulse 1s infinite; }
.ctx { display: inline-flex; align-items: center; gap: 5px; background: none; border: 0; color: var(--muted); font-size: 11px; padding: 0; }
.ctxnum { font-variant-numeric: tabular-nums; }
.sep { margin: 0 5px; opacity: .5; }
.cacheP { font-weight: 700; margin-left: 2px; }
.cacheP.hi { color: var(--hill); }
.cacheP.mid { color: var(--dawn); }
.cacheP.lo { color: var(--bad); }
.working { display: inline-flex; align-items: center; gap: 5px; color: var(--hill); }
.wspin { width: 10px; height: 10px; border: 2px solid var(--line); border-top-color: var(--hill); border-radius: 50%; animation: rot 1s linear infinite; }
@keyframes rot { to { transform: rotate(360deg) } }
/* 服务端通知吐司 */
.toasts { position: absolute; top: 52px; left: 0; right: 0; z-index: 55; display: flex; flex-direction: column; align-items: center; gap: 6px; pointer-events: none; }
.toast { pointer-events: auto; max-width: 86%; background: rgba(26,29,38,.97); border: 1px solid var(--line); color: var(--ink);
  font-size: 12.5px; border-radius: 12px; padding: 9px 14px; box-shadow: 0 8px 24px rgba(34,48,31,.3); line-height: 1.45; }
.toast.error { border-color: var(--bad); color: var(--bad); }
.toast.warning { border-color: #4a3c26; color: var(--dawn); }
.toast.info { border-color: var(--line); color: var(--hill); }
.tst-enter-active, .tst-leave-active { transition: all .25s ease; }
.tst-enter-from, .tst-leave-to { opacity: 0; transform: translateY(-10px); }
/* 快脑悬浮气泡 */
.vbub { position: absolute; left: 50%; transform: translateX(-50%); bottom: calc(100% + 10px); z-index: 40;
  max-width: 86%; background: rgba(26,29,38,.97); border: 1px solid var(--line); border-radius: 16px;
  padding: 11px 15px; font-size: 14px; line-height: 1.6; color: #e8e9ec;
  box-shadow: 0 10px 36px rgba(34,48,31,.15); display: flex; align-items: baseline; gap: 7px; }
.vbadge { color: var(--hill); font-size: 13px; flex-shrink: 0; }
.vstate { color: var(--hill); font-size: 13px; }
.dots2::after { content: '…'; animation: kdots 1.2s steps(4) infinite; }
@keyframes kdots { 0% { content: ''; } 25% { content: '.'; } 50% { content: '..'; } 75% { content: '...'; } }
.vtxt { white-space: pre-wrap; word-break: break-all; }
.vcur { color: var(--hill); animation: blink2 .9s step-start infinite; }
@keyframes blink2 { 50% { opacity: 0; } }
.vbf-enter-active, .vbf-leave-active { transition: all .2s ease; }
.vbf-enter-from, .vbf-leave-to { opacity: 0; transform: translateX(-50%) translateY(12px); }
.ctxbar { width: 56px; height: 5px; border-radius: 3px; background: var(--line); overflow: hidden; display: inline-block; }
.ctxbar i { display: block; height: 100%; background: var(--hill); border-radius: 3px; transition: width .3s; }
.ctx.warm .ctxbar i { background: var(--dawn); }
.ctx.hot .ctxbar i { background: #e05555; }
.ctx.hot { color: var(--bad); }
.ssearch { padding: 0 2px 8px; }
.ssearch input { width: 100%; background: var(--card); border: 1px solid var(--line); color: var(--ink); border-radius: 9px; padding: 8px 11px; font-size: 13px; }
.ws { border-top: 1px solid var(--line); margin-top: 10px; padding-top: 8px; }
.cwdnow { font-size: 11px; padding: 0 4px 6px; word-break: break-all; }
.prow { display: flex; align-items: center; gap: 7px; padding: 8px 6px; border-radius: 8px; font-size: 12.5px; }
.prow.cur { background: rgba(139,92,246,.12); }
.pico { flex-shrink: 0; }
.ppath { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink); }
.pcur { color: var(--hill); font-size: 10px; flex-shrink: 0; }
.pinput { display: flex; gap: 6px; padding: 8px 2px 4px; }
.pinput input { flex: 1; background: var(--card); border: 1px solid var(--line); color: var(--ink); border-radius: 8px; padding: 7px 10px; font-size: 12px; min-width: 0; }
.pinput button { background: var(--hill); border: 0; color: #fff; border-radius: 8px; padding: 0 13px; font-size: 12px; }
.pcomp { padding: 4px 2px; }
.pci { font-size: 11.5px; color: var(--muted); padding: 5px 6px; border-radius: 6px; font-family: ui-monospace, monospace; }
/* 胶囊 composer：输入行 + 工具条（webui composer-tools 模式） */
.crow { display: flex; align-items: flex-end; background: var(--card); border: 1px solid var(--line);
  border-radius: 20px; padding: 2px 14px; margin-top: 8px; }
.crow:focus-within { border-color: #4a5064; }
.crow textarea { flex: 1; background: none; border: 0; color: var(--ink);
  font-size: 15px; font-family: inherit; resize: none; line-height: 22px;
  height: 40px; min-height: 40px; max-height: 110px; overflow-y: auto; padding: 9px 0; outline: none; }
.ctools { display: flex; align-items: center; gap: 7px; padding: 9px 2px 0; }
.chip { display: inline-flex; align-items: center; gap: 4px; background: var(--card); border: 1px solid var(--line); border-radius: 15px; padding: 7px 11px; font-size: 12px; color: var(--muted); max-width: 34vw; }
.chip:active { background: var(--bg); border-color: var(--hill); }
.chip.on { color: #fff; background: var(--hill); border-color: var(--hill); }
.chip .ctxt { font-size: 11px; }
.chip.model { color: var(--ink); }
.chip.model b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.ctools .sp { flex: 1; }
.ctools .cb { width: 36px; height: 36px; font-size: 14.5px; }
.ctools .cb.send { width: 40px; height: 40px; }
input[type="checkbox"] { accent-color: var(--hill); }
.cb { width: 38px; height: 38px; border-radius: 50%; border: 1px solid var(--line); background: var(--card);
  color: var(--muted); font-size: 15.5px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; }
.cb:active { background: var(--bg); border-color: var(--hill); }
.cb.mic.rec { background: var(--bad); color: #fff; border-color: var(--bad); animation: pulse 1s infinite; }
.cb.q { background: #FFF3E0; border-color: var(--dawn); color: var(--dawn); font-size: 14px; }
.cb.send { background: var(--hill); color: #fff; border-color: var(--hill); }
.cb.send.edit { background: var(--dawn); }
.cb.send.dim, .cb.dim { opacity: .35; }

/* 会话抽屉 */
.drawer { position: fixed; top: 52px; bottom: 0; left: 0; width: 84vw; max-width: 340px; z-index: 61;
  background: rgba(247,243,236,.95); border-right: 1px solid var(--line); padding: 14px 12px; overflow-y: auto;
  box-shadow: 12px 0 40px rgba(34,48,31,.15); }
.slist { margin-top: 8px; }
.si { padding: 11px 8px; border-bottom: 1px solid #1e2128; position: relative; border-radius: 8px; }
.sin { font-size: 14px; padding-right: 30px; }
.sdel { position: absolute; right: 8px; top: 12px; color: var(--bad); font-size: 12px; padding: 3px 8px; border: 1px solid var(--bad); border-radius: 6px; background: #F8E8E5; }
</style>
