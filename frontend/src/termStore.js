// 终端会话池：模块级生存（切页面不杀会话），DOM 挂到 body 隐藏池
import { reactive } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { wsSend, termRegister } from './useChat.js'

export const tstore = reactive({ sessions: {}, order: [] }) // id -> session

// 隐藏池：视图卸载时 pane 移进来（xterm 实例活着，输出持续写入不丢）
const pool = typeof document !== 'undefined' ? document.createElement('div') : null
if (pool) {
  pool.className = 'xq-term-pool'
  document.body.appendChild(pool)
}

export function poolAttach(stage) {
  for (const id of tstore.order) {
    const s = tstore.sessions[id]
    if (s && s.el.parentNode !== stage) stage.appendChild(s.el)
  }
}
export function poolDetach() {
  for (const id of tstore.order) {
    const s = tstore.sessions[id]
    if (s && s.el.parentNode !== pool) pool.appendChild(s.el)
  }
}

/** 最小可用编号（终端 1 关了再新建还是 1，不会涨到 47） */
export function nextTitle() {
  const used = new Set()
  for (const s of Object.values(tstore.sessions)) {
    const n = parseInt(String(s.title).replace(/\D/g, ''), 10)
    if (n) used.add(n)
  }
  let n = 1
  while (used.has(n)) n++
  return '终端 ' + n
}

export function createSession(cwd, opts = {}) {
  const id = opts.id || ('xq-t' + Date.now().toString(36) + Math.random().toString(36).slice(2, 5))
  const title = opts.title || nextTitle()
  const el = document.createElement('div')
  el.className = 'tpane'
  pool.appendChild(el)
  const term = new Terminal({
    fontSize: 13,
    fontFamily: 'ui-monospace, "Cascadia Mono", Menlo, monospace',
    theme: { background: '#1a1e17', foreground: '#d4dcc8', cursor: '#7db88f', selectionBackground: 'rgba(62,124,89,.3)', green: '#7db88f', brightGreen: '#9ccf9e' },
    cursorBlink: true, scrollback: 2000, convertEol: true,
  })
  const fit = new FitAddon()
  term.loadAddon(fit)
  term.open(el)
  const unreg = termRegister(id, {
    write: (d) => { term.write(d); if (tstore.sessions[id]) tstore.sessions[id].lastOut = Date.now() },
    onExit: (code) => { if (tstore.sessions[id]) { tstore.sessions[id].alive = false; tstore.sessions[id].exitCode = code } },
  })
  // WebView IME 组合重发修复（探针实证：onData 收到 h/he/hel/hell/hello 增长整串）。
  // v1 失败原因：lastComp 设了 ≤3 字符上限，第 4 字起判定失效整串重发。
  // v2：无条件跟踪 + 双向差分（增长发增量 / 缩短发退格），2 秒空闲重置。
  let lastComp = '', lastCompAt = 0
  term.onData((d) => {
    const now = Date.now()
    if (now - lastCompAt > 2000) lastComp = '' // 停顿超 2s：按新输入上下文处理
    let send = d
    if (lastComp) {
      if (d.startsWith(lastComp) && d.length > lastComp.length && d.length - lastComp.length <= 3) {
        send = d.slice(lastComp.length) // 组合增长：只发增量
      } else if (lastComp.startsWith(d) && d.length < lastComp.length) {
        send = '\x7f'.repeat(lastComp.length - d.length) // 组合删字：发退格
      }
      // 其余（空格提交后新词/回车/粘贴/整体替换）：发整串
    }
    lastComp = d
    lastCompAt = now
    if (send) wsSend({ type: 'terminal_input', terminalId: id, data: send })
  })
  tstore.sessions[id] = { id, title, cwd: cwd || '/data/data/com.pihost/files/home', el, term, fit, unreg, alive: true, lastOut: Date.now(), exitCode: null, remote: !!opts.attach }
  tstore.order.push(id)
  // fit 后必须通知服务端改 PTY 尺寸（webui sendDims 同款）——否则 TUI 按 80 列重绘
  // 在手机实际 ~52 列屏上折行 → 光标掉行 → 楼梯式重复（pi TUI 整行重绘才显形）
  let rzT = null, lastCols = 0, lastRows = 0
  const sendDims = () => {
    try {
      wsSend({ type: 'terminal_resize', terminalId: id, cols: term.cols, rows: term.rows })
      lastCols = term.cols; lastRows = term.rows
    } catch {}
  }
  if (tstore.sessions[id]) tstore.sessions[id].sendDims = sendDims // 定义后挂载（避免 TDZ）
  const ro = new ResizeObserver(() => {
    if (el.offsetWidth > 0 && el.classList.contains('act')) {
      try { fit.fit() } catch { return }
      if (term.cols !== lastCols || term.rows !== lastRows) {
        if (rzT) clearTimeout(rzT)
        rzT = setTimeout(sendDims, 150) // 防抖：resize 风暴合并
      }
    }
  })
  ro.observe(el)
  if (opts.command) {
    // 命令终端：创建即跑（webui run_command 同款）
    wsSend({ type: 'run_command', terminalId: id, command: opts.command, cols: term.cols || 80, rows: term.rows || 24, conversationId: '' })
  } else if (!opts.attach) {
    wsSend({ type: 'terminal_create', terminalId: id, title, locale: 'zh', cwd: cwd || '/data/data/com.pihost/files/home', cols: term.cols || 80, rows: term.rows || 24 })
  }
  return tstore.sessions[id]
}

/** 附着服务端已有终端（agent bash / 命令标签）：只建 pane 挂输出，不发 create。
 *  注：附着点之前的输出引擎只在重连/切换会话时回放，这里打条本地横幅防"空白"困惑 */
export function attachSession(id, title, cwd) {
  if (tstore.sessions[id]) return tstore.sessions[id]
  const s = createSession(cwd, { id, title: title || id, attach: true })
  try { s.term.write('\x1b[90m⟳ 已附着「' + (title || id) + '」，实时显示后续输出\x1b[0m\r\n') } catch {}
  return s
}

export function killSession(id) {
  const s = tstore.sessions[id]
  if (!s) return
  if (!s.remote) try { wsSend({ type: 'terminal_kill', terminalId: id }) } catch {} // agent 终端归引擎管，不 kill
  s.ro?.disconnect?.(); s.unreg?.()
  try { s.term.dispose() } catch {}
  s.el.remove()
  delete tstore.sessions[id]
  const i = tstore.order.indexOf(id)
  if (i >= 0) tstore.order.splice(i, 1)
}

export function restartSession(id) {
  const s = tstore.sessions[id]
  if (!s) return
  wsSend({ type: 'terminal_kill', terminalId: id })
  s.term.reset(); s.alive = true; s.exitCode = null
  setTimeout(() => wsSend({ type: 'terminal_create', terminalId: id, title: s.title, locale: 'zh', cwd: '/data/data/com.pihost/files/home', cols: s.term.cols || 80, rows: s.term.rows || 24 }), 600)
}

export function renameSession(id, title) {
  const s = tstore.sessions[id]
  if (!s || !title?.trim()) return
  s.title = title.trim()
  wsSend({ type: 'rename_terminal', terminalId: id, title: s.title })
}

/** 全部关闭（服务退出兜底时用） */
export function killAll() { for (const id of [...tstore.order]) killSession(id) }
