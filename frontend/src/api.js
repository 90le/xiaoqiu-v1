// 小丘工作台 API 层：所有能力经 /api/<tool> 调 MCP 工具
export async function call(tool, args = {}) {
  const r = await fetch('/api/' + tool, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(args)
  })
  const j = await r.json()
  return j.structuredContent || { ok: false, error: { message: '空响应' } }
}

export function fmt(d, max = 900) {
  const s = typeof d === 'string' ? d : JSON.stringify(d, null, 1)
  return s.length > max ? s.slice(0, max) + '…' : s
}

export function outText(res) {
  if (!res) return ''
  if (res.ok) {
    const d = res.data
    return (typeof d === 'string' ? d : fmt(d))
  }
  const e = res.error || {}
  return '❌ ' + (e.code || 'FAIL') + ' ' + (e.message || '')
}

export async function cfgAll() {
  const r = await call('cfg_get', {})
  return (r.ok && typeof r.data === 'object' && !Array.isArray(r.data)) ? r.data : {}
}
export async function cfgSet(key, value) {
  return call('cfg_set', { key, value })
}
