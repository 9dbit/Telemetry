const $ = (selector) => document.querySelector(selector);
const tokenKey = 'telemetry-admin-token';
let token = sessionStorage.getItem(tokenKey) || '';

const metricDefs = [
  ['Downloads','downloads','Tracked app downloads'],['Installs','installs','Registered installs'],['Active now','active_now','Seen in 5 minutes'],['DAU','dau','Seen in 24 hours'],
  ['WAU','wau','Seen in 7 days'],['MAU','mau','Seen in 30 days'],['Paired contacts','paired_contacts','Aggregate count'],['Screen time','screen_seconds','Aggregate duration']
];

for (const button of document.querySelectorAll('.nav')) button.addEventListener('click', () => switchView(button.dataset.view));
$('#authButton').addEventListener('click', () => { $('#tokenInput').value = token; $('#authDialog').showModal(); });
$('#saveToken').addEventListener('click', () => { token = $('#tokenInput').value.trim(); sessionStorage.setItem(tokenKey, token); setTimeout(loadCurrent, 0); });
$('#refreshDevices').addEventListener('click', loadDevices);
$('#refreshField').addEventListener('click', loadField);

function switchView(id) {
  document.querySelectorAll('.nav').forEach(x => x.classList.toggle('active', x.dataset.view === id));
  document.querySelectorAll('.view').forEach(x => x.classList.toggle('active', x.id === id));
  if (id === 'devices') loadDevices();
  if (id === 'field') loadField();
}

function loadCurrent() { loadOverview(); const active = $('.nav.active')?.dataset.view; if (active === 'devices') loadDevices(); if (active === 'field') loadField(); }
async function api(path, options = {}) {
  if (!token) throw new Error('Admin token required');
  const response = await fetch(path, { ...options, headers: { 'content-type':'application/json', authorization:`Bearer ${token}`, ...(options.headers||{}) } });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
  return payload;
}

async function loadOverview() {
  try {
    const { overview } = await api('/api/v1/admin/overview');
    status('Connected', true);
    $('#metricGrid').innerHTML = metricDefs.map(([label,key,note]) => `<div class="metric"><span>${label}</span><strong>${formatMetric(key, overview[key])}</strong><small>${note}</small></div>`).join('');
    const usage = [
      ['Messages sent', Number(overview.messages_sent||0)], ['Messages received', Number(overview.messages_received||0)], ['Media transfers', Number(overview.media_transfers||0)], ['Voice calls', Number(overview.voice_calls||0)], ['Video calls', Number(overview.video_calls||0)],
      ['Media bytes', Number(overview.media_bytes||0)], ['Transport sent', Number(overview.transport_bytes_sent||0)], ['Transport received', Number(overview.transport_bytes_received||0)], ['CPU ms', Number(overview.cpu_ms||0)], ['Radio seconds', Number(overview.radio_seconds||0)]
    ];
    const max = Math.max(1, ...usage.map(([,v]) => v));
    $('#usageBars').innerHTML = usage.map(([label,value]) => `<div class="bar-row"><span>${label}</span><div class="bar-track"><div class="bar-fill" style="width:${Math.max(2,(value/max)*100)}%"></div></div><span class="bar-value">${formatNumber(value)}</span></div>`).join('');
  } catch (error) { status(error.message, false); renderEmptyOverview(); }
}

async function loadDevices() {
  try {
    const { devices } = await api('/api/v1/admin/devices?limit=200'); status('Connected', true);
    $('#deviceRows').innerHTML = devices.length ? devices.map(deviceRow).join('') : `<tr><td colspan="8">No devices recorded yet.</td></tr>`;
    for (const button of document.querySelectorAll('[data-action]')) button.addEventListener('click', () => setStatus(button.dataset.id, button.dataset.action));
  } catch (error) { status(error.message, false); $('#deviceRows').innerHTML = `<tr><td colspan="8">${escapeHtml(error.message)}</td></tr>`; }
}

function deviceRow(d) {
  const name = d.display_name || 'Telemetry peer';
  const short = String(d.install_id).slice(0,16);
  const action = d.status === 'suspended' ? 'release' : 'suspend';
  const photo = d.avatar_url ? `<img src="${escapeAttr(d.avatar_url)}" alt="" />` : escapeHtml(name.slice(0,1).toUpperCase());
  return `<tr><td><div class="profile-cell"><div class="avatar">${photo}</div><div>${escapeHtml(name)}<small>${escapeHtml(short)}</small></div></div></td><td>${escapeHtml(d.platform||'—')}<br><small>${escapeHtml(d.app_version||'')}</small></td><td>${formatDate(d.last_seen_at)}</td><td>${formatNumber(d.paired_contact_count||0)}</td><td>${duration(d.total_screen_seconds||0)}</td><td><code>${escapeHtml(d.last_ip||'—')}</code></td><td><span class="badge ${d.status}">${escapeHtml(d.status)}</span></td><td><button class="action" data-id="${escapeAttr(d.install_id)}" data-action="${action}">${action}</button></td></tr>`;
}

async function setStatus(id, action) {
  if (!confirm(`${action === 'suspend' ? 'Suspend' : 'Release'} this cloud device? Offline P2P capability is not silently revoked.`)) return;
  try { await api(`/api/v1/admin/devices/${encodeURIComponent(id)}/${action}`, { method:'POST', body:JSON.stringify({ reason:'Admin dashboard action' }) }); await loadDevices(); }
  catch (error) { alert(error.message); }
}

async function loadField() {
  try {
    const { peers } = await api('/api/v1/admin/field'); status('Connected', true); renderField(peers);
  } catch (error) { status(error.message, false); renderField([]); }
}
function renderField(peers) {
  document.querySelectorAll('.peer-dot,.peer-label').forEach(x => x.remove());
  $('#fieldEmpty').style.display = peers.length ? 'none' : 'grid';
  for (const peer of peers) {
    const x = ((Number(peer.longitude)+180)/360)*100;
    const y = (1-((Number(peer.latitude)+90)/180))*100;
    const dot = document.createElement('div'); dot.className='peer-dot'; dot.style.left=`${x}%`; dot.style.top=`${y}%`;
    const label = document.createElement('div'); label.className='peer-label'; label.style.left=`${x}%`; label.style.top=`${y}%`; label.textContent=peer.display_name || String(peer.install_id).slice(0,12);
    $('#fieldMap').append(dot,label);
  }
}

function renderEmptyOverview(){ $('#metricGrid').innerHTML=metricDefs.map(([label,,note])=>`<div class="metric"><span>${label}</span><strong>—</strong><small>${note}</small></div>`).join(''); $('#usageBars').innerHTML='<p style="color:#93a8c2">Connect admin API to load telemetry.</p>'; }
function status(text, ok){ $('#apiStatus').textContent=text; $('#apiStatus').style.color=ok?'#42d79e':'#93a8c2'; }
function formatMetric(key,v){ return key==='screen_seconds'?duration(v):formatNumber(v); }
function formatNumber(v){ return new Intl.NumberFormat('en-US',{notation:Number(v)>9999?'compact':'standard',maximumFractionDigits:1}).format(Number(v||0)); }
function duration(v){ const s=Number(v||0); const h=Math.floor(s/3600); const m=Math.floor((s%3600)/60); return h?`${h}h ${m}m`:`${m}m`; }
function formatDate(v){ return v?new Date(v).toLocaleString():'—'; }
function escapeHtml(v){ return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function escapeAttr(v){ return escapeHtml(v); }
renderEmptyOverview(); if(token) loadCurrent();
