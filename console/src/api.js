// 控制台后端接口封装(SPEC-P8 FR-853 ~ FR-860)。
// 所有请求都打到同一个 FE 端口(默认 8080),token 放 localStorage。

const TOKEN_KEY = 'tangdb_console_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

async function request(path, { method = 'GET', body = null, token = getToken() } = {}) {
  const headers = {}
  if (body !== null) {
    headers['Content-Type'] = 'application/json'
  }
  if (token) {
    headers['X-Console-Token'] = token
  }
  const response = await fetch(path, {
    method,
    headers,
    body: body === null ? undefined : JSON.stringify(body)
  })
  let payload = null
  const text = await response.text()
  try {
    payload = text ? JSON.parse(text) : {}
  } catch (e) {
    payload = { ok: false, error: { code: 0, message: text || '响应不是合法 JSON' } }
  }
  if (!response.ok) {
    const error = payload && payload.error ? payload.error : {}
    const wrapped = new Error(error.message || `HTTP ${response.status}`)
    wrapped.code = error.code || 0
    wrapped.sessionId = error.session_id
    wrapped.status = response.status
    wrapped.payload = payload
    throw wrapped
  }
  return payload
}

export const api = {
  login(username, password) {
    return request('/api/login', { method: 'POST', body: { username, password }, token: '' })
  },
  logout() {
    return request('/api/logout', { method: 'POST' })
  },
  tree() {
    return request('/api/tree')
  },
  query(sql, confirm = false) {
    return request('/api/query', { method: 'POST', body: { sql, confirm } })
  },
  explain(sql) {
    return request('/api/explain', { method: 'POST', body: { sql } })
  },
  status() {
    return request('/api/status')
  }
}

// 判断一条 SQL 是否会写(与服务端同一份规则,仅用于界面提示;
// 真正的门禁在服务端:未带 confirm 的写请求一律 409)。
const WRITE_KEYWORDS = [
  'INSERT',
  'UPDATE',
  'DELETE',
  'CREATE',
  'DROP',
  'TRUNCATE',
  'ALTER',
  'RENAME',
  'BACKUP',
  'RESTORE',
  'REINDEX',
  'REBUILD',
  'LOAD',
  'REPLACE',
  'GRANT',
  'REVOKE'
]

export function writeKeyword(sql) {
  const text = String(sql || '')
  const statements = text.split(';')
  for (const statement of statements) {
    const trimmed = statement.trim()
    if (!trimmed) {
      continue
    }
    const upper = trimmed.toUpperCase()
    for (const keyword of WRITE_KEYWORDS) {
      if (
        upper === keyword ||
        upper.startsWith(keyword + ' ') ||
        upper.startsWith(keyword + '(') ||
        upper.startsWith(keyword + '\n') ||
        upper.startsWith(keyword + '\t')
      ) {
        return keyword
      }
    }
  }
  return null
}

export function toCsv(columns, rows) {
  const escape = (value) => {
    if (value === null || value === undefined) {
      return ''
    }
    const text = String(value)
    return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
  }
  const lines = [columns.map(escape).join(',')]
  for (const row of rows) {
    lines.push(row.map(escape).join(','))
  }
  return lines.join('\n')
}
