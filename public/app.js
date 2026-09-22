async function boot(){
  const health=document.getElementById('health');
  const transports=document.getElementById('transports');
  try{
    const h=await fetch('/health').then(r=>r.json());
    health.textContent=h.ok?'online':'degraded';
    const c=await fetch('/api/v1/capabilities').then(r=>r.json());
    transports.textContent=c.transports.map(t=>t.id).join(' · ');
  }catch(error){
    health.textContent='offline';
    transports.textContent='capabilities unavailable';
    console.error(error);
  }
}
boot();
