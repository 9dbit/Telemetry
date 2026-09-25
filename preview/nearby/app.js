const peers = [
  {id:'tlm:device:11pro-a8f2',alias:'Adwin iPhone',rssi:-43,target:-43,angle:300,trusted:true,gateway:false,internet:false,caps:['BLE','Wi‑Fi ready'],lastSeen:Date.now(),pinned:false},
  {id:'tlm:node:studio-01',alias:'Studio Node',rssi:-56,target:-56,angle:28,trusted:true,gateway:true,internet:true,caps:['BLE','Gateway','Internet'],lastSeen:Date.now(),pinned:false},
  {id:'tlm:device:relay-73c1',alias:'Relay Van',rssi:-65,target:-65,angle:118,trusted:true,gateway:true,internet:false,caps:['BLE','Mesh ready','Gateway'],lastSeen:Date.now(),pinned:false},
  {id:'tlm:device:cafe-91b8',alias:'Cafe Peer',rssi:-72,target:-72,angle:196,trusted:false,gateway:false,internet:false,caps:['BLE'],lastSeen:Date.now(),pinned:false},
  {id:'tlm:node:sos-e21d',alias:'Emergency Node',rssi:-79,target:-79,angle:248,trusted:false,gateway:true,internet:true,caps:['BLE','LoRa node','Internet'],lastSeen:Date.now(),pinned:false}
];

const $=s=>document.querySelector(s);
const $$=s=>[...document.querySelectorAll(s)];
let currentView='list', filter='all', frozen=false, selectedPeer=null;
let panX=0, panY=0, zoom=1;
const pointers=new Map();
let lastSingle=null, pinchStart=null, dragged=false;

function clamp(v,min,max){return Math.max(min,Math.min(max,v))}
function proximity(rssi){if(rssi>=-48)return 'Very near';if(rssi>=-60)return 'Near';if(rssi>=-72)return 'Around';return 'Weak signal'}
function signalLevel(rssi){return rssi>=-50?4:rssi>=-62?3:rssi>=-75?2:1}
function initials(name){return name.split(/\s+/).slice(0,2).map(v=>v[0]).join('').toUpperCase()}
function visiblePeer(p){if(filter==='all')return true;if(filter==='trusted')return p.trusted;if(filter==='gateway')return p.gateway;if(filter==='internet')return p.internet;return true}
function ago(ms){if(ms<2500)return 'now';if(ms<12000)return `${Math.round(ms/1000)}s ago`;return 'recently'}
function badges(p){
  const out=[];
  if(p.trusted)out.push('<span class="badge good">TRUSTED</span>');
  if(p.gateway)out.push('<span class="badge gateway">GATEWAY</span>');
  if(p.internet)out.push('<span class="badge internet">INTERNET</span>');
  p.caps.filter(c=>!['Gateway','Internet'].includes(c)).slice(0,3).forEach(c=>out.push(`<span class="badge">${c}</span>`));
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
      <div class="peer-line"><span class="peer-name">${p.alias}${p.pinned?' · PINNED':''}</span><span class="rssi">${Math.round(p.rssi)} dBm</span></div>
      <div class="peer-id">…${p.id.slice(-8)}</div>
      <div class="badges">${badges(p)}</div>
      <div class="peer-foot"><span class="proximity"><span class="signal-bars s${signalLevel(p.rssi)}"><i></i><i></i><i></i><i></i></span>${proximity(p.rssi)}</span><span class="last-seen">Seen ${ago(now-p.lastSeen)}</span></div>
    </div>
  </article>`).join('')||'<div class="insight-card"><div class="insight-icon">⌁</div><div><strong>No peers match this filter</strong><p>Change the filter to see more nearby devices.</p></div></div>';
  $$('.peer-card').forEach(card=>card.onclick=()=>openSheet(card.dataset.id));
}

function peerPosition(p){
  const strength=clamp((-p.rssi-38)/52,.08,.96);
  const radius=120+strength*720;
  const rad=p.angle*Math.PI/180;
  return {x:1300+Math.cos(rad)*radius,y:1300+Math.sin(rad)*radius};
}
function renderField(){
  const list=peers.filter(visiblePeer);
  $('#peerCount').textContent=list.length;
  $('#fieldNodes').innerHTML=list.map(p=>{
    const pos=peerPosition(p);
    return `<button class="field-node ${p.trusted?'trusted':''} ${p.gateway?'gateway':''} ${p.internet?'internet':''} ${p.pinned?'pinned':''}" data-id="${p.id}" style="left:${pos.x}px;top:${pos.y}px" aria-label="${p.alias}">
      <span class="peer-pulse"></span><span class="node-dot"></span>
      <span class="node-label"><b>${p.alias}</b><small>${Math.round(p.rssi)} dBm · ${proximity(p.rssi)}</small></span>
    </button>`;
  }).join('');
  $$('.field-node').forEach(node=>{
    node.onpointerdown=e=>e.stopPropagation();
    node.onclick=e=>{e.stopPropagation();if(!dragged)openSheet(node.dataset.id)};
  });
}
function applyTransform(){
  $('#fieldWorld').style.transform=`translate(-50%,-50%) translate(${panX}px,${panY}px) scale(${zoom})`;
  $('#zoomLabel').textContent=`${Math.round(zoom*100)}%`;
}
function render(){renderList();renderField();applyTransform()}

function openSheet(id){
  const p=peers.find(x=>x.id===id);if(!p)return;selectedPeer=p;
  $('#sheetContent').innerHTML=`<div class="sheet-title"><div><h3>${p.alias}</h3><p>${p.id}</p></div><span class="sheet-rssi">${Math.round(p.rssi)} dBm</span></div>
    <div class="profile-status"><span class="status-dot ${p.trusted?'trusted':''}"></span>${p.trusted?'Trusted identity':'Untrusted nearby peer'}</div>
    <div class="badges">${badges(p)}</div>
    <div class="detail-grid">
      <div class="detail"><small>Proximity</small><b>${proximity(p.rssi)}</b></div>
      <div class="detail"><small>Last seen</small><b>${ago(Date.now()-p.lastSeen)}</b></div>
      <div class="detail"><small>Route role</small><b>${p.gateway?'Gateway capable':'Direct peer'}</b></div>
      <div class="detail"><small>Internet</small><b>${p.internet?'Available':'Not shared'}</b></div>
    </div>
    <div class="profile-note">Signal position is relative telemetry space, not a precise physical location.</div>`;
  $('#pinPeerBtn').textContent=p.pinned?'Unpin peer':'Pin peer';
  $('#peerSheet').classList.add('open');$('#peerSheet').setAttribute('aria-hidden','false');$('#sheetBackdrop').hidden=false;
}
function closeSheet(){selectedPeer=null;$('#peerSheet').classList.remove('open');$('#peerSheet').setAttribute('aria-hidden','true');$('#sheetBackdrop').hidden=true}

function setView(view){
  currentView=view;
  $$('.view-tab').forEach(b=>b.classList.toggle('active',b.dataset.view===view));
  $('#listView').classList.toggle('active',view==='list');
  $('#fieldView').classList.toggle('active',view==='field');
  $('.nearby-shell').classList.toggle('field-mode',view==='field');
  if(view==='field')requestAnimationFrame(applyTransform);
}
$$('.view-tab').forEach(btn=>btn.onclick=()=>setView(btn.dataset.view));
$$('.chip').forEach(btn=>btn.onclick=()=>{filter=btn.dataset.filter;$$('.chip').forEach(b=>b.classList.toggle('active',b===btn));render()});
$('#closeSheetBtn').onclick=closeSheet;$('#sheetBackdrop').onclick=closeSheet;
$('#openChatBtn').onclick=()=>{if(!selectedPeer)return;const name=selectedPeer.alias;closeSheet();setTimeout(()=>alert(`Preview only: Open conversation with ${name}\n\nNative integration will bind this to the existing trusted chat thread.`),60)};
$('#pinPeerBtn').onclick=()=>{if(!selectedPeer)return;selectedPeer.pinned=!selectedPeer.pinned;$('#pinPeerBtn').textContent=selectedPeer.pinned?'Unpin peer':'Pin peer';render()};
$('#freezeBtn').onclick=()=>{frozen=!frozen;$('#freezeBtn').textContent=frozen?'▶':'Ⅱ'};

function setZoom(next){zoom=clamp(next,.55,2.5);applyTransform()}
$('#zoomInBtn').onclick=()=>setZoom(zoom*1.2);
$('#zoomOutBtn').onclick=()=>setZoom(zoom/1.2);
$('#centerBtn').onclick=()=>{panX=0;panY=0;zoom=1;applyTransform()};
$('#youNode').onclick=()=>{panX=0;panY=0;zoom=1;applyTransform()};

const viewport=$('#fieldViewport');
viewport.onpointerdown=e=>{
  if(currentView!=='field')return;
  viewport.setPointerCapture?.(e.pointerId);
  pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
  dragged=false;
  if(pointers.size===1){lastSingle={x:e.clientX,y:e.clientY}}
  if(pointers.size===2){
    const pts=[...pointers.values()];
    pinchStart={dist:Math.hypot(pts[0].x-pts[1].x,pts[0].y-pts[1].y),zoom,panX,panY,mid:{x:(pts[0].x+pts[1].x)/2,y:(pts[0].y+pts[1].y)/2}};
  }
};
viewport.onpointermove=e=>{
  if(!pointers.has(e.pointerId))return;
  const prev=pointers.get(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
  if(pointers.size===1&&lastSingle){
    const dx=e.clientX-lastSingle.x,dy=e.clientY-lastSingle.y;
    if(Math.abs(dx)+Math.abs(dy)>2)dragged=true;
    panX+=dx;panY+=dy;lastSingle={x:e.clientX,y:e.clientY};applyTransform();
  }else if(pointers.size>=2&&pinchStart){
    dragged=true;
    const pts=[...pointers.values()].slice(0,2);
    const dist=Math.hypot(pts[0].x-pts[1].x,pts[0].y-pts[1].y);
    const mid={x:(pts[0].x+pts[1].x)/2,y:(pts[0].y+pts[1].y)/2};
    zoom=clamp(pinchStart.zoom*(dist/pinchStart.dist),.55,2.5);
    panX=pinchStart.panX+(mid.x-pinchStart.mid.x);panY=pinchStart.panY+(mid.y-pinchStart.mid.y);applyTransform();
  }
  prev;
};
function endPointer(e){
  pointers.delete(e.pointerId);
  if(pointers.size===1){const p=[...pointers.values()][0];lastSingle={x:p.x,y:p.y};pinchStart=null}
  else if(!pointers.size){lastSingle=null;pinchStart=null;setTimeout(()=>{dragged=false},40)}
}
viewport.onpointerup=endPointer;viewport.onpointercancel=endPointer;
viewport.onwheel=e=>{if(currentView!=='field')return;e.preventDefault();setZoom(zoom*(e.deltaY>0?.92:1.08))};

function simulate(){
  if(frozen)return;
  peers.forEach((p,i)=>{
    if(Math.random()>.55)p.target=clamp(p.target+(Math.random()*10-5),-86,-39);
    p.rssi+=(p.target-p.rssi)*.28;
    if(!p.pinned)p.angle=(p.angle+(Math.random()*3-1.5)+(i%2?.12:-.12)+360)%360;
    p.lastSeen=Date.now()-Math.random()*1700;
  });
  render();
}
render();
setInterval(simulate,1450);
