// Minimal SafeLedger chat page: demo login, chat, and Confirm / Cancel buttons.
// Confirm and Cancel call the API directly; they never go through the model.
const headers = { 'Content-Type': 'application/json', 'X-Requested-With': 'SafeLedger' };
const $ = (id) => document.getElementById(id);

async function api(method, path, body) {
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  return { ok: res.ok, status: res.status, data };
}

function add(cls, text) {
  const div = document.createElement('div');
  div.className = 'msg ' + cls;
  div.textContent = text;
  $('messages').appendChild(div);
  div.scrollIntoView({ block: 'end' });
  return div;
}

function addAction(action) {
  const box = document.createElement('div');
  box.className = 'action';
  const text = document.createElement('div');
  text.textContent = 'Waiting for your confirmation: ' + action.summary;
  const buttons = document.createElement('div');
  buttons.className = 'buttons';
  const confirm = document.createElement('button');
  confirm.className = 'primary';
  confirm.textContent = 'Confirm';
  const cancel = document.createElement('button');
  cancel.textContent = 'Cancel';
  buttons.append(confirm, cancel);
  box.append(text, buttons);
  $('messages').appendChild(box);
  box.scrollIntoView({ block: 'end' });

  const finish = (cls, message) => {
    box.className = 'action ' + cls;
    text.textContent = message;
    buttons.remove();
  };
  confirm.onclick = async () => {
    confirm.disabled = cancel.disabled = true;
    const r = await api('POST', `/api/actions/${action.id}/confirm`);
    if (r.ok && r.data.status === 'EXECUTED') finish('done', 'Done: ' + action.summary);
    else if (r.ok) finish('failed', 'The bank did not accept this: ' + (r.data.result?.detail || r.data.status));
    else finish('failed', 'Not done: ' + (r.data?.detail || 'this request can no longer be confirmed'));
  };
  cancel.onclick = async () => {
    confirm.disabled = cancel.disabled = true;
    const r = await api('POST', `/api/actions/${action.id}/cancel`);
    finish('failed', r.ok ? 'Cancelled: ' + action.summary : 'Could not cancel');
  };
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
  for (const a of r.data.pendingActions) addAction(a);
};

$('logout').onclick = async () => {
  await api('DELETE', '/api/session');
  location.reload();
};

start();
