// 小丘工作台 ↔ pi-web-ui 引擎 的原生 WS 客户端
// 严格复刻 webui use-chat.ts 协议语义：send() 布尔返回（失败由调用方保留文本）；
// 无发送队列、无 ACK——连接 OPEN 才发，断线时按钮禁用；
// 30s 心跳看门狗 + 指数退避(1s→10s) + rev/seq 断链防抖对账(get_state)。
import { reactive } from 'vue'

const WSI = 'ws://127.0.0.1:8182/ws'
let ws = null
let retries = 0
let lastBeat = 0
let alive = true // 页面存活标记（卸载后停重连）

const clientId = localStorage.getItem('xq_cid') || ('xq-' + Math.random().toString(36).slice(2, 10))
localStorage.setItem('xq_cid', clientId)

export const chat = reactive({
  status: 'connecting', ready: false, retryIn: 0,
  state: null,            // UiState: messages/streamingMessage/model/thinkingLevel/stats...
  models: [],             // ModelInfo[]
  settings: null,         // 引擎设置全量（get_settings/settings 消息）
  providers: [],          // 内置供应商状态（providers_status）
  commands: [],           // 用户命令快捷面板（list_commands → commands 消息）
  modelsCfg: [],          // 自定义模型服务（models_config → UiProviderConfig[]）
  sessions: [],           // SessionSummary[]
  conversations: [], activeConvId: '',
  slashCommands: [],
  terminals: [],
  projects: [],
  pathCompletions: [],
  sessionSearch: null,
  notices: [],
  liveTools: {},          // toolCallId -> {name, text, done, isError, exitCode}
  dialog: null,           // 活动询问（select/confirm/input）——pi 引擎 dialog 推送
  bgServers: [],          // AI 启动的后台服务器
  updatesAll: null,       // 全源更新检查结果（check_updates_all）
})

// webui scheduleResync：300ms 防抖的全量对账（snapshot_delta rev 断链 / message_delta 丢序共用）
let resyncTimer = null
function scheduleResync() {
  if (resyncTimer) return
  resyncTimer = setTimeout(() => { resyncTimer = null; send({ type: 'get_state' }) }, 300)
}

// webui send()：仅当连接 OPEN 时发送，返回布尔；false 时调用方保留输入文本
export function send(msg) {
  if (ws && ws.readyState === 1) {
    try { ws.send(JSON.stringify(msg)); return true } catch { return false }
  }
  return false
}
export const wsSend = send // 兼容既有引用

// ── 终端桥：terminalId -> {write, onExit}（Terminal.vue 注册）──
const termWriters = {}
export function termRegister(id, w) { termWriters[id] = w; return () => delete termWriters[id] }

// message_delta：打进 streamingMessage（文本/思考/工具调用增量）
function applyDelta(st, msg) {
  const e = msg.assistantMessageEvent
  if (!st.streamingMessage || !e) return
  const blocks = st.streamingMessage.content || (st.streamingMessage.content = [])
  if (e.type === 'text' || e.type === 'thinking') {
    let b = blocks[e.contentIndex ?? blocks.length - 1]
    if (!b || b.type !== e.type) { b = e.type === 'text' ? { type: 'text', text: '' } : { type: 'thinking', thinking: '' }; blocks.push(b) }
    const key = e.type === 'text' ? 'text' : 'thinking'
    b[key] = (b[key] || '') + (e.delta || '')
  } else if (e.type === 'toolCall') {
    blocks.push({ type: 'toolCall', id: e.id || ('t-' + Math.random()), name: e.name || 'tool', argumentsText: e.delta || '' })
  }
}

// 每会话 delta 序号（webui 丢序检测：跳号=有 delta 丢失→防抖对账）
const lastDeltaSeq = new Map()
// ws 未连上时的设置类消息排队（ready 后冲刷）——防"点了开关但没发出去"
const pendingQ = []
function flushQ() { while (pendingQ.length) { const m = pendingQ.shift(); try { send(m) } catch {} } }
// 文件读取一次性等待表（read_file → file_content 按路径匹配）
const fileWaiters = new Map()
// reqId 匹配的一次性等待表（fetch_models_result / clone_provider_result）
const reqWaiters = new Map()
let reqSeq = 0
function noteDeltaSeq(conversationId, seq) {
  const last = lastDeltaSeq.get(conversationId)
  if (last !== undefined && seq !== last + 1 && chat.state?.conversationId === conversationId) scheduleResync()
  lastDeltaSeq.set(conversationId, seq)
}

let VSnoteActivity = () => {}
export function setVsNoteActivity(fn) { VSnoteActivity = fn }

export function connect() {
  if (!alive) return
  chat.status = 'connecting'
  const sock = new WebSocket(WSI)
  ws = sock

  sock.onopen = () => {
    if (ws !== sock) return // 过期 socket（重连竞赛中已被替代）
    chat.status = 'open'
    retries = 0
    lastBeat = Date.now()
    sock.send(JSON.stringify({ type: 'hello', clientId }))
  }

  sock.onmessage = (ev) => {
    if (ws !== sock) return
    lastBeat = Date.now() // 任何服务端消息都证明连接活着
    let m
    try { m = JSON.parse(ev.data) } catch { return }
    switch (m.type) {
      case 'ready':
        chat.ready = true
        // webui 同款：ready 后全量对账（snapshot 是权威状态）
        send({ type: 'get_state' })
        send({ type: 'list_models' })
        send({ type: 'get_commands' })
        send({ type: 'list_projects' })
        send({ type: 'list_sessions' })
        send({ type: 'get_settings' })
        flushQ()
        break
      case 'snapshot':
        // 权威快照：整体替换 + 重启 delta 序号追踪（webui 同款）
        chat.state = m.state
        if (m.state?.conversationId) chat.activeConvId = m.state.conversationId
        lastDeltaSeq.clear()
        break
      case 'snapshot_delta': {
        // 增量检查点：仅当链得上当前 rev 才应用；断链→防抖全量对账（webui 同款）
        const cur = chat.state
        if (!cur || cur.conversationId !== m.conversationId || cur.rev !== m.baseRev) { scheduleResync(); break }
        chat.state = { ...m.state, messages: [...(cur.messages || []), ...(m.appended || [])] }
        break
      }
      case 'message_delta':
        try { VSnoteActivity() } catch {}
        // 服务端只流式活动会话；他对话的迟到增量不得污染当前视图（webui 同款过滤）
        if (!chat.state || chat.state.conversationId !== m.conversationId) break
        noteDeltaSeq(m.conversationId, m.seq)
        applyDelta(chat.state, m)
        chat.state = { ...chat.state } // 触发响应式
        break
      case 'tool_delta': {
        const t = chat.liveTools[m.toolCallId] || (chat.liveTools[m.toolCallId] = { name: m.toolName, text: '', done: false })
        t.name = m.toolName; t.text += m.delta || ''
        break
      }
      case 'tool_status': {
        const t = chat.liveTools[m.toolCallId] || (chat.liveTools[m.toolCallId] = { name: m.toolName, text: '' })
        t.done = true; t.isError = m.isError; t.exitCode = m.exitCode; t.durationMs = m.durationMs
        break
      }
      case 'models': chat.models = m.models || []; break
      case 'settings_state': chat.settings = m.settings || m; break
      case 'file_content': {
        const w = fileWaiters.get(m.path)
        if (w) { w(m); fileWaiters.delete(m.path) }
        break
      }
      case 'providers_status': chat.providers = m.providers || []; break
      case 'models_config': chat.modelsCfg = m.providers || []; break
      case 'commands': chat.commands = m.commands || []; break
      case 'fetch_models_result':
      case 'clone_provider_result': {
        const w = reqWaiters.get(m.reqId)
        if (w) { w(m); reqWaiters.delete(m.reqId) }
        break
      }
      case 'sessions': chat.sessions = m.sessions || []; break
      case 'conversations': chat.conversations = m.conversations || []; chat.activeConvId = m.activeId; break
      case 'slash_commands': chat.slashCommands = m.commands || []; break
      case 'heartbeat': break
      case 'notice': chat.notices.push({ level: m.level, text: m.text, id: Date.now() + Math.random() }); if (chat.notices.length > 5) chat.notices.shift(); break
      case 'terminal_output': {
        const w = termWriters[m.terminalId]
        if (w) w.write(m.data)
        break
      }
      case 'terminal_exit': {
        const w = termWriters[m.terminalId]
        if (w && w.onExit) w.onExit(m.exitCode)
        break
      }
      case 'terminal_list': chat.terminals = m.terminals || []; break
      case 'projects': chat.projects = (m.projects || []).sort((a, b) => (b.lastUsed || 0) - (a.lastUsed || 0)); break
      case 'path_completions': chat.pathCompletions = m.completions || []; break
      case 'session_search_results': chat.sessionSearch = m.results || []; break
      case 'dialog': chat.dialog = { id: m.id, kind: m.kind, title: m.title, args: m.args }; break
      case 'dialog_closed': chat.dialog = null; break
      case 'update_status_all': chat.updatesAll = m.items || []; break
      case 'bg_servers': chat.bgServers = m.servers || []; break
      default: break
    }
  }

  sock.onclose = () => {
    if (ws === sock) ws = null // 新连接已接管时不动状态
    if (ws !== null && ws !== sock) return
    chat.status = 'closed'; chat.ready = false
    if (!alive) return
    // webui 同款指数退避：1s → 2s → 4s → … 封顶 10s
    const delay = Math.min(1000 * 2 ** retries, 10000)
    retries += 1
    chat.retryIn = Math.round(delay / 1000)
    setTimeout(connect, delay)
  }
  sock.onerror = () => { /* onclose 随后触发重连——这里不做任何事（webui 同款） */ }
}

// 半开连接看门狗（webui 同款）：5s 巡检，>30s 无服务端消息 → close 走正常重连
let wdTimer = null
export function startWatchdog() {
  if (wdTimer) return
  wdTimer = setInterval(() => {
    if (ws && ws.readyState === 1 && Date.now() - lastBeat > 30000) {
      try { ws.close() } catch {}
    }
  }, 5000)
}

// ── 高层操作（prompt/editMessage 返回布尔：false=没发出，调用方保留文本）──
export const api = {
  prompt(text, attachments, queue) { return send({ type: 'prompt', text, attachments: attachments || undefined, queue: queue || undefined }) },
  editMessage(messageId, text, attachments) { return send({ type: 'edit_message', messageId, text, attachments: attachments && attachments.length ? attachments : undefined }) },
  abort() { return send({ type: 'abort' }) },
  newChat() { return send({ type: 'new_chat' }) },
  setModel(modelId) { return send({ type: 'set_model', modelId }) },
  getSettings() { return send({ type: 'get_settings' }) },
  abortBash() { return send({ type: 'abort_bash' }) },        // 终止正在执行的 bash（语音"停下"联动）
  retryLast() { return send({ type: 'retry_last' }) },         // 重试上一条消息
  cycleModel() { return send({ type: 'cycle_model' }) },       // 循环切换模型
  cycleThinking() { return send({ type: 'cycle_thinking' }) }, // 循环切换思考档
  setSettings(patch) {
    const msg = { type: 'set_settings', ...patch }
    if (chat.status === 'open') return send(msg)
    pendingQ.push(msg); return false // 排队，连接就绪自动发
  },
  sendSafe(msg) {
    if (chat.status === 'open') return send(msg)
    pendingQ.push(msg); return false
  },
  readFile(path) { return new Promise(res => { fileWaiters.set(path, res); send({ type: 'read_file', path }) }) },
  writeFile(path, text) { send({ type: 'write_file', path, text }); return true },
  reloadExtensions() { return send({ type: 'extensions_reload' }) },
  listCommands() { return send({ type: 'list_commands' }) },
  saveCommands(commands) { return sendSafe({ type: 'save_commands', commands }) },
  runCommand(terminalId, command, cols, rows) { return send({ type: 'run_command', terminalId, command, cols, rows, conversationId: chat.activeConvId || chat.state?.conversationId || '' }) },
  listProviders() { return send({ type: 'list_providers' }) },
  listModelsConfig() { return send({ type: 'list_models_config' }) },
  setProviderKey(provider, apiKey) { return send({ type: 'set_provider_api_key', provider, apiKey }) },
  clearProviderKey(provider) { return send({ type: 'clear_provider_api_key', provider }) },
  saveModelConfig(providerId, config) { return send({ type: 'save_model_config', providerId, config }) },
  deleteModelConfig(providerId) { return send({ type: 'delete_model_config', providerId }) },
  fetchModels(baseUrl, apiKey, authHeader, api) {
    const reqId = 'fm' + (++reqSeq)
    return new Promise(res => { reqWaiters.set(reqId, res); send({ type: 'fetch_models', reqId, baseUrl, apiKey, authHeader, api }) })
  },
  cloneProvider(provider) {
    const reqId = 'cp' + (++reqSeq)
    return new Promise(res => { reqWaiters.set(reqId, res); send({ type: 'clone_provider', provider, reqId }) })
  },
  setThinking(level) { return send({ type: 'set_thinking', level }) },
  listSessions() { return send({ type: 'list_sessions' }) },
  listModels() { return send({ type: 'list_models' }) },
  switchSession(path) { return send({ type: 'switch_session', path }) },
  deleteSession(path) { return send({ type: 'delete_session', path }) },
  switchConversation(id) { return send({ type: 'switch_conversation', id }) },
  getState() { return send({ type: 'get_state' }) },
  setCwd(path) { return send({ type: 'set_cwd', path }) },
  listProjects() { return send({ type: 'list_projects' }) },
  searchSessions(q) { return send({ type: 'search_sessions', reqId: Date.now(), query: q }) },
  completePath(p) { return send({ type: 'complete_path', path: p }) },
  makeDir(p) { return send({ type: 'make_dir', path: p }) },
  compact() { return send({ type: 'prompt', text: '/compact' }) },
}
