const peers = [
  {id:'tlm:device:11pro-a8f2',alias:'Adwin iPhone',rssi:-43,target:-43,angle:300,trusted:true,gateway:false,internet:false,caps:['BLE','Wi‑Fi ready'],lastSeen:0},
  {id:'tlm:node:studio-01',alias:'Studio Node',rssi:-56,target:-56,angle:28,trusted:true,gateway:true,internet:true,caps:['BLE','Gateway','Internet'],lastSeen:0},
  {id:'tlm:device:relay-73c1',alias:'Relay Van',rssi:-65,target:-65,angle:118,trusted:true,gateway:true,internet:false,caps:['BLE','Mesh ready','Gateway'],lastSeen:0},
  {id:'tlm:device:cafe-91b8',alias:'Cafe Peer',rssi:-72,target:-72,angle:196,trusted:false,gateway:false,internet:false,caps:['BLE'],lastSeen:0},
  {id:'tlm:node:sos-e21d',alias:'Emergency Node',rssi:-79,target:-79,angle:248,trusted:false,gateway:true,internet:true,caps:['BLE','LoRa node','Internet'],lastSeen:0}
];

let currentView='list';
let filter='all';
let frozen=false;
let selectedPeer=null;
const $=s=>document.querySelector(s);
const $$=s=>[...document.querySelectorAll(s)];

function proximity(rssi){
  if(rssi>=-48)return 'Very near';
  if(rssi>=-60)return 'Near';
  if(rssi>=-72)return 'Around';
  return 'Weak signal';
}
function signalLevel(rssi){return rssi>=-50?4:rssi>=-62?3:rssi>=-75?2:1}
function initials(name){return name.split(/\s+/).slice(0,2).map(v=>v[0]).join('').toUpperCase()}
function visiblePeer(p){if(filter==='all')return true;if(filter==='trusted')return p.trusted;if(filter==='gateway')return p.gateway;if(filter==='internet')return p.internet;return true}
function ago(ms){if(ms<2500)return 'now';if(ms<12000)return `${Math.round(ms/1000)}s ago`;return 'recently'}
function badges(p){
  const out=[];
  if(p.trusted)out.push('<span class="badge good">TRUSTED</span>');
  if(p.gateway)out.push('<span class="badge gateway">GATEWAY</span>');
  if(p.internet)out.push('<span class="badge good">INTERNET</span>');
  p.caps.filter(c=>!['Gateway','Internet'].includes(c)).slice(0,2).forEach(c=>out.push(`<span class="badge">${c}</span>`));
  return out.join('');
}
function renderList(){
  const now=Date.now();
  const list=peers.filter(visiblePeer).sort((a,b)=>b.rssi-a.rssi);
  $('#peerCount').textContent=list.length;
  $('#strongestPeer').textContent=list[0]?.alias||'None';
  $('#bestRoute').textContent=list.some(p=>p.gateway&&p.internet)?'Gateway available':'Direct BLE';
  $('#peerList').innerHTML=list.map(p=>`<article class="peer-card" data-id="${p.id}">
    <div class="peer-avatar ${p.trusted?'trusted':''} ${p.gateway?'gateway':''}">${initials(p.alias)}</div>
    <div class="peer-main">
      <div class="peer-line"><span class="peer-name">${p.alias}</span><span class="rssi">${Math.round(p.rssi)} dBm</span></div>
      <div class="peer-id">…${p.id.slice(-8)}</div>
      <div class="badges">${badges(p)}</div>
      <div class="peer-foot"><span class="proximity"><span class="signal-bars s${signalLevel(p.rssi)}"><i></i><i></i><i></i><i></i></span>${proximity(p.rssi)}</span><span class="last-seen">Seen ${ago(now-p.lastSeen)}</span></div>
    </div>
  </article>`).join('')||'<div class="insight-card"><div class="insight-icon">⌁</div><div><strong>No peers match this filter</strong><p>Change the filter to see more nearby devices.</p></div></div>';
  $$('.peer-card').forEach(card=>card.onclick=()=>openSheet(card.dataset.id));
}
function radarRadius(rssi){
  const normalized=Math.max(0.18,Math.min(.88,(-rssi-38)/52));
  return normalized*43;
}
function renderRadar(){
  const list=peers.filter(visiblePeer);
  $('#peerCount').textContent=list.length;
  $('#radarNodes').innerHTML=list.map(p=>{
    const radius=radarRadius(p.rssi);
    const rad=p.angle*Math.PI/180;
    const x=50+Math.cos(rad)*radius;
    const y=50+Math.sin(rad)*radius;
    return `<button class="radar-node ${p.trusted?'trusted':''} ${p.gateway?'gateway':''}" data-id="${p.id}" style="left:${x}%;top:${y}%" aria-label="${p.alias}"><span class="node-dot"></span><span class="node-label">${p.alias} · ${Math.round(p.rssi)}</span></button>`;
  }).join('');
  $$('.radar-node').forEach(node=>node.onclick=()=>openSheet(node.dataset.id));
}
function render(){renderList();renderRadar()}

function openSheet(id){
  const p=peers.find(x=>x.id===id);if(!p)return;selectedPeer=p;
  $('#sheetContent').innerHTML=`<div class="sheet-title"><div><h3>${p.alias}</h3><p>${p.id}</p></div><span class="sheet-rssi">${Math.round(p.rssi)} dBm</span></div>
    <div class="badges">${badges(p)}</div>
    <div class="detail-grid">
      <div class="detail"><small>Proximity</small><b>${proximity(p.rssi)}</b></div>
      <div class="detail"><small>Trust</small><b>${p.trusted?'Verified':'Not trusted'}</b></div>
      <div class="detail"><small>Route role</small><b>${p.gateway?'Gateway capable':'Direct peer'}</b></div>
      <div class="detail"><small>Internet</small><b>${p.internet?'Available':'Not shared'}</b></div>
    </div>`;
  $('#peerSheet').classList.add('open');$('#peerSheet').setAttribute('aria-hidden','false');$('#sheetBackdrop').hidden=false;
}
function closeSheet(){selectedPeer=null;$('#peerSheet').classList.remove('open');$('#peerSheet').setAttribute('aria-hidden','true');$('#sheetBackdrop').hidden=true}

$$('.view-tab').forEach(btn=>btn.onclick=()=>{
  currentView=btn.dataset.view;
  $$('.view-tab').forEach(b=>b.classList.toggle('active',b===btn));
  $('#listView').classList.toggle('active',currentView==='list');
  $('#radarView').classList.toggle('active',currentView==='radar');
});
$$('.chip').forEach(btn=>btn.onclick=()=>{
  filter=btn.dataset.filter;$$('.chip').forEach(b=>b.classList.toggle('active',b===btn));render();
});
$('#closeSheetBtn').onclick=closeSheet;$('#sheetBackdrop').onclick=closeSheet;
$('#openChatBtn').onclick=()=>{if(!selectedPeer)return;const name=selectedPeer.alias;closeSheet();setTimeout(()=>alert(`Preview only: Open conversation with ${name}\n\nNative integration will bind this action to the existing trusted chat thread.`),80)};
$('#freezeBtn').onclick=()=>{frozen=!frozen;$('#freezeBtn').textContent=frozen?'▶':'Ⅱ';$('#radarSweep').classList.toggle('paused',frozen)};

function simulate(){
  if(frozen)return;
  peers.forEach((p,i)=>{
    if(Math.random()>.55)p.target=Math.max(-86,Math.min(-39,p.target+(Math.random()*10-5)));
    p.rssi+=(p.target-p.rssi)*.32;
    p.angle=(p.angle+(Math.random()*4-2)+(i%2?.15:-.15)+360)%360;
    p.lastSeen=Date.now()-Math.random()*1500;
  });
  render();
}
peers.forEach(p=>p.lastSeen=Date.now());
render();
setInterval(simulate,1450);
