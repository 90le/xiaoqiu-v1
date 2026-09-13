import { reactive, watch } from 'vue'
import { chat, api } from './useChat.js'

/**
 * 统一语音会话引擎（页面侧唯一状态机权威）。
 * 两个入口（🎙按钮 / 喊"小丘"）只是点火器，行为完全一致。
 * 纪律：零 setTimeout——推进只靠 ws 消息 watch / fetch 回调 / native 注入回调
 * （后台 WebView 节流 timer，事件不节流）。
 */
export const vs = reactive({
  state: 'off',      // off|listening|thinking|replying|executing|conclusion
  from: '',          // wake|mic
  lastHeard: '',
  turnN: 0,
})

let speakToken = 0
let speakResolver = null
let streamWatchStop = null

/** 带超时 fetch：后台 WebView 网络栈可能挂起（实测 chat_fast 无限挂），AbortController 兜底 */
function fetchT(url, opts = {}, ms = 30000) {
  const ac = new AbortController()
  const t = setTimeout(() => { try { ac.abort() } catch {} }, ms)
  return fetch(url, { ...opts, signal: ac.signal }).finally(() => clearTimeout(t))
}

const bus = (payload) => {
  try { return fetchT('/api/voice_bus', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }, 5000).catch(() => {}) }
  catch { return Promise.resolve() }
}
const glow = (mode) => { bus({ action: 'glow', mode }) }

/** 统一发声称：文本送到 :kws 播（它持有打断麦克风），完成经 __ttsDone 注入解锁 */
function speak(text) {
  const t = String(text || '').slice(0, 400)
  if (!t) return Promise.resolve()
  const token = 'tk' + (++speakToken)
  console.log('[VS] speak[' + token + ']: ' + t.slice(0, 20))
  return new Promise((resolve) => {
    speakResolver = { token, resolve }
    bus({ action: 'speak', text: t, token })
  })
}
/** 原文直发+humanize 标志（:kws 服务端总结） */
function speakRaw(text, humanize) {
  const t = String(text || '').slice(0, 2000)
  if (!t) return Promise.resolve()
  const token = 'tk' + (++speakToken)
  return new Promise((resolve) => {
    speakResolver = { token, resolve }
    bus({ action: 'speak', text: t, token, humanize: !!humanize })
  })
}
export function vsTtsDone(token) {
  console.log('[VS] ttsDone: ' + token)
  if (speakResolver && speakResolver.token === token) { const r = speakResolver; speakResolver = null; r.resolve() }
}

/* ── 入口 ── */
export function vsIgnite(from) {
  if (vs.state !== 'off') { vsStop(); return } // 再点=结束（切换语义）
  vs.from = from; vs.turnN = 0; vs.state = 'listening'
  glow('listen')
  bus({ action: 'session', cmd: 'start', from })
}
export function vsStop() {
  streamWatchStop?.(); streamWatchStop = null
  vs.state = 'off'; glow('off')
  bus({ action: 'session', cmd: 'stop' })
}
/** :kws 收尾（超时/退出词）→ SESSION_END → 注入 */
export function vsEnd() {
  streamWatchStop?.(); streamWatchStop = null
  speakResolver?.resolve(); speakResolver = null
  vs.state = 'off'; glow('off')
}

/* ── 一轮 ── */
export async function vsTurn(text, from, pre, prePrompt) {
  console.log('[VS] turn: ' + text + (pre ? ' (预分类)' : ''))
  bus({ action: 'ack' }) // 回执：告诉 :kws 页面活着（握手自愈协议）
  vs.lastHeard = text; vs.turnN++
  if (pre) { await exec(null, prePrompt || text, true); return } // :kws 已说确认语，跳过重复
  vs.state = 'thinking'; glow('think')
  let data = null
  try {
    const r = await fetchT('/api/chat_fast', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ q: text, context: recentCtx() }) }, 25000)
    const d = (await r.json())?.structuredContent
    if (d?.ok) data = d.data
  } catch (e) { console.log('[VS] 快脑失败: ' + (e && e.name)) }
  console.log('[VS] 意图: ' + (data ? data.type : 'null'))
  if (data && data.type === 'chat') { await reply(data.answer); return }
  if (!data) { // 快脑超时/失败：轻任务直接重说，重任务原话直发慢脑
    if (text.length <= 6) { await speak('没想明白，再说一次'); done(); return }
    await exec(null, text)
    return
  }
  await exec(data, (data && data.prompt) ? data.prompt : text)
}

async function reply(answer) {
  vs.state = 'replying'; glow('speak')
  const say = await humanize(answer, 'reply')
  await speak(say)
  extract(vs.lastHeard, say) // 自动沉淀（后台，不阻塞）
  done()
}
async function exec(data, prompt, skipAck) {
  vs.state = 'executing'
  if (!skipAck) { glow('speak'); await speak((data && data.reply) || '好嘞，这就办') } // :kws 未说时页面安排
  glow('exec')
  const ok = api.prompt(prompt) // 优化后指令 → 当前活动会话
  console.log('[VS] prompt(' + ok + '): ' + prompt.slice(0, 30))
  if (!ok) { await speak('连接断了，打开小丘再试一次'); done(); return }
  // 执行期进度：①工具名上报（药丸）②智能进度播报（12s节流，数据驱动措辞，非固定话术）
  let lastTool = '', lastProgAt = 0, progN = 0
  const stopProg = watch(() => chat.state?.streamingMessage, (m) => {
    const blocks = m?.content || []
    const tools = blocks.filter(b => b.type === 'toolCall')
    const t = tools[tools.length - 1]?.name
    if (t && t !== lastTool) { lastTool = t; bus({ action: 'prog', text: t }) }
    // 智能进度：AI 自组织语言（不写死模板，把实时上下文交给快脑即兴说）
    const now = Date.now()
    if (now - lastProgAt > 15000 && lastProgAt > 0) {
      lastProgAt = now
      const chars = blocks.filter(b => b.type === 'text').reduce((a, b) => a + (b.text || '').length, 0)
      const ctx = `工具:${tools.map(x => x.name).join(',') || '无'} 当前:${t || '?'} 已产出文字:${chars}字`
      // 用快脑即兴生成一句自然的进度播报（不限制格式/长度，让 AI 自己判断）
      fetchT('/api/chat_fast', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ q: `[进度播报] ${ctx}——根据当前执行状态，用一句自然的中文口语告诉用户进展。简短（AI自判），像同事随口说"查到了正在整理"这种。只输出口播文本。`, context: `原任务：${vs.lastHeard}` }) }, 8000)
        .then(r => r.json()).then(d => {
          const say = d?.structuredContent?.data?.answer
          if (say && say.length > 2 && say.length < 100) bus({ action: 'psay', text: say })
        }).catch(() => {})
    } else if (lastProgAt === 0) lastProgAt = now
  })
  try { await streamEnd() } finally { stopProg() }
  console.log('[VS] 流结束')
  vs.state = 'conclusion'; glow('speak')
  const text = lastAssistantText()
  if (text) {
    await speakRaw(text, true) // 服务端总结（:kws ai_humanize——后台页面 humanize 不可靠的根治）
    extract(vs.lastHeard, text.slice(0, 300)) // 自动沉淀
  }
  done()
}
function done() {
  console.log('[VS] done → 续听')
  vs.state = 'listening'; glow('listen')
  bus({ action: 'done' }) // :kws 续听
}

/** 流结束：必须先等到"开始流式"（曾因起跑竞态秒判完成→假 done→6s收尾）。
 *  一直没开始（发送失败/引擎忙）由 :kws 150s 护栏兜底。 */
function streamEnd() {
  return new Promise((resolve) => {
    let started = false
    const stop = watch(() => !!chat.state?.streamingMessage, (v) => {
      if (v) { started = true; return }
      if (!v && started) { stop(); streamWatchStop = null; resolve() } // 真结束
    }, { immediate: true })
    streamWatchStop = stop
  })
}

/* ── 工具 ── */
function lastAssistantText() {
  const msgs = chat.state?.messages || []
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i]?.role === 'assistant') {
      return (msgs[i].content || []).map(b => (b.type === 'text' ? b.text : '')).join('').trim()
    }
  }
  return ''
}
async function humanize(text, kind) {
  const t = String(text || '').trim()
  if (!t || t.length <= 90) return t || '好了'
  try {
    const r = await fetchT('/api/ai_humanize', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ kind: kind || 'reply', text: t.slice(0, 4000) }) }, 15000)
    const d = (await r.json())?.structuredContent
    if (d?.ok && d?.data) return String(d.data)
  } catch {}
  return t.slice(0, 120) + '……'
}
/** 对话后自动沉淀（mem0 模式：快脑提取→同key更新；失败静默） */
function extract(text, replyText) {
  try {
    fetch('/api/memory_extract', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: String(text || '').slice(0, 500), reply: String(replyText || '').slice(0, 300) }) }).catch(() => {})
  } catch {}
}

const TOOL_ZH = {
  bash: '跑命令', read: '读文件', edit: '改文件', write: '写文件', grep: '搜索代码',
  web_search: '查资料', fetch_content: '看网页', mcp__xiaoqiu_screenshot: '看屏幕',
  screenshot: '截屏', vision_ask: '看图', vision_elements: '识别界面',
  mcp__xiaoqiu_ui_tap_text: '点手机', mcp__xiaoqiu_apps_launch: '开应用',
  mcp__xiaoqiu_notify_read: '看通知', mcp__xiaoqiu_memory_save: '记事情',
  terminal: '跑终端',
}
function recentCtx() {
  const msgs = (chat.state?.messages || []).slice(-6)
  return msgs.map(m => (m.role === 'user' ? '用户:' : '小丘:') +
    (m.content || []).map(b => (b.type === 'text' ? b.text : '')).join('').slice(0, 80)).join('\n')
}
