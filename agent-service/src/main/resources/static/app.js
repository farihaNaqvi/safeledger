// Minimal SafeLedger chat page: demo login, chat, and Confirm / Cancel buttons.
// Confirm and Cancel call the API directly; they never go through the model.
// Pending actions sit in their own panel, outside the chat, and show only stored values.
const headers = { 'Content-Type': 'application/json', 'X-Requested-With': 'SafeLedger' };
const $ = (id) => document.getElementById(id);

async function api(method, path, body, extra) {
  const res = await fetch(path, {
    method, headers: { ...headers, ...extra }, body: body ? JSON.stringify(body) : undefined });
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  return { ok: res.ok, status: res.status, data, traceId: res.headers.get('X-Trace-Id') };
}

// W3C trace context, so Confirm and Cancel join the trace of the turn that created the action.
function sameTrace(traceId) {
  if (!traceId) return {};
  const span = [...crypto.getRandomValues(new Uint8Array(8))]
    .map((b) => b.toString(16).padStart(2, '0')).join('');
  return { traceparent: `00-${traceId}-${span}-01` };
}

// Replies use a little Markdown: bold, bullet lists and pipe tables. The text is HTML-escaped
// before any markup is added, so text from bank data can never become HTML.
function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function inline(s) {
  return escapeHtml(s).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
}

function markdown(text) {
  const lines = text.split('\n');
  const isRow = (l) => /^\s*\|.*\|\s*$/.test(l);
  const isRule = (l) => /^\s*\|[\s:|-]+\|\s*$/.test(l);
  const isItem = (l) => /^\s*[-*] /.test(l);
  const cells = (l) => l.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
  let html = '';
  for (let i = 0; i < lines.length;) {
    if (isRow(lines[i])) {
      const rows = [];
      while (i < lines.length && isRow(lines[i])) rows.push(lines[i++]);
      const header = rows.length > 1 && isRule(rows[1]);
      html += '<table>' + rows.filter((r) => !isRule(r)).map((r, n) => {
        const tag = header && n === 0 ? 'th' : 'td';
        return '<tr>' + cells(r).map((c) => `<${tag}>${inline(c)}</${tag}>`).join('') + '</tr>';
      }).join('') + '</table>';
    } else if (isItem(lines[i])) {
      html += '<ul>';
      while (i < lines.length && isItem(lines[i])) {
        html += '<li>' + inline(lines[i++].replace(/^\s*[-*] /, '')) + '</li>';
      }
      html += '</ul>';
    } else if (/^#{1,6} /.test(lines[i])) {
      html += '<p><strong>' + inline(lines[i++].replace(/^#+ /, '')) + '</strong></p>';
    } else if (lines[i].trim() === '') {
      i++;
    } else {
      const para = [];
      while (i < lines.length && lines[i].trim() !== '' && !isRow(lines[i]) && !isItem(lines[i])) {
        para.push(inline(lines[i++]));
      }
      html += '<p>' + para.join('<br>') + '</p>';
    }
  }
  return html;
}

function add(cls, text) {
  const div = document.createElement('div');
  div.className = 'msg ' + cls;
  if (cls === 'bot') div.innerHTML = markdown(text);
  else div.textContent = text;
  $('messages').appendChild(div);
  div.scrollIntoView({ block: 'end' });
  return div;
}

// Open cards by action ID, so a card the server no longer holds as pending can be closed.
const cards = new Map();

function addAction(action, traceId) {
  const box = document.createElement('div');
  box.className = 'action';
  const text = document.createElement('div');
  text.className = 'summary';
  text.textContent = action.summary;
  box.append(text);
  if (action.payeeNote) {
    const note = document.createElement('div');
    note.className = 'note';
    note.textContent = 'Note sent to the payee, written by the assistant: "' + action.payeeNote + '"';
    box.append(note);
  }
  const buttons = document.createElement('div');
  buttons.className = 'buttons';
  const confirm = document.createElement('button');
  confirm.className = 'primary';
  confirm.textContent = 'Confirm';
  const cancel = document.createElement('button');
  cancel.textContent = 'Cancel';
  buttons.append(confirm, cancel);
  box.append(buttons);
  $('pending-list').appendChild(box);
  $('pending').hidden = false;
  box.scrollIntoView({ block: 'nearest' });

  const finish = (cls, message) => {
    cards.delete(action.id);
    box.remove();
    $('pending').hidden = !$('pending-list').children.length;
    const done = document.createElement('div');
    done.className = 'action ' + cls;
    done.textContent = message;
    $('messages').appendChild(done);
    done.scrollIntoView({ block: 'end' });
  };
  confirm.onclick = async () => {
    confirm.disabled = cancel.disabled = true;
    const r = await api('POST', `/api/actions/${action.id}/confirm`, null, sameTrace(traceId));
    if (r.ok && r.data.status === 'EXECUTED') finish('done', 'Done: ' + action.summary);
    else if (r.ok) finish('failed', 'The bank did not accept this: ' + (r.data.result?.detail || r.data.status));
    else finish('failed', 'Not done: ' + (r.data?.detail || 'this request can no longer be confirmed'));
  };
  cancel.onclick = async () => {
    confirm.disabled = cancel.disabled = true;
    const r = await api('POST', `/api/actions/${action.id}/cancel`, null, sameTrace(traceId));
    finish('failed', r.ok ? 'Cancelled: ' + action.summary : 'Could not cancel');
  };
  cards.set(action.id, (status) =>
    finish('failed', (status === 'SUPERSEDED' ? 'Replaced by a newer request: ' : 'No longer waiting: ') + action.summary));
}

async function closeStaleCards() {
  if (!cards.size) return;
  const r = await api('GET', '/api/actions');
  if (!r.ok) return;
  for (const a of r.data) {
    if (a.status !== 'PENDING' && cards.has(a.id)) cards.get(a.id)(a.status);
  }
}

function showChat(session) {
  $('login').hidden = true;
  $('chat').hidden = false;
  $('who').hidden = false;
  $('who-name').textContent = 'Logged in as ' + session.customerName;
  $('message').focus();
}

async function start() {
  const current = await api('GET', '/api/session');
  if (current.ok) return showChat(current.data);
  const customers = await api('GET', '/api/customers');
  for (const c of customers.data) {
    const b = document.createElement('button');
    b.textContent = c.name;
    b.onclick = async () => {
      const r = await api('POST', '/api/session', { customerId: c.id });
      if (r.ok) showChat(r.data);
    };
    $('customers').appendChild(b);
  }
}

$('composer').onsubmit = async (e) => {
  e.preventDefault();
  const message = $('message').value.trim();
  if (!message) return;
  $('message').value = '';
  add('user', message);
  const thinking = add('app', 'Thinking...');
  const r = await api('POST', '/api/chat', { message });
  thinking.remove();
  if (!r.ok) return add('app', r.data?.detail || 'Something went wrong.');
  add('bot', r.data.reply);
  for (const a of r.data.pendingActions) addAction(a, r.traceId);
  closeStaleCards();
};

$('logout').onclick = async () => {
  await api('DELETE', '/api/session');
  location.reload();
};

start();
