// ====== ksDrill: 通用下钻右滑面板（移动现有DOM节点，关闭时归位） ======
var _ksDrillMoved=[],_ksDrillFocus=null;
function ksDrill(title,ids){
  ksDrillClose();
  _ksDrillFocus=document.activeElement;
  var ov=document.createElement('div');ov.id='ksDrillOverlay';
  ov.addEventListener('click',function(e){if(e.target===ov)ksDrillClose();});
  var host=document.createElement('div');host.className='drill-host';host.setAttribute('role','dialog');host.setAttribute('aria-modal','true');
  host.innerHTML='<button class="drill-close" onclick="ksDrillClose()">✕ ESC</button><div class="drill-eyebrow">DRILL-DOWN // 下钻面板</div><div class="drill-title"></div><div class="drill-body"></div>';
  host.querySelector('.drill-title').textContent=title;
  ov.appendChild(host);document.body.appendChild(ov);
  host.querySelector('.drill-close').focus();
  var body=host.querySelector('.drill-body');
  (ids||[]).forEach(function(id){
    var el=document.getElementById(id);if(!el)return;
    var ph=document.createElement('div');ph.style.display='none';
    el.parentNode.insertBefore(ph,el);
    _ksDrillMoved.push({el:el,ph:ph,hadOpen:el.classList.contains('drill-open')});
    el.classList.add('drill-open');
    body.appendChild(el);
  });
}
function ksDrillClose(){
  _ksDrillMoved.forEach(function(r){
    if(!r.hadOpen)r.el.classList.remove('drill-open');
    if(r.ph.parentNode)r.ph.parentNode.replaceChild(r.el,r.ph);
  });
  _ksDrillMoved=[];
  var ov=document.getElementById('ksDrillOverlay');if(ov)ov.remove();
  if(_ksDrillFocus&&typeof _ksDrillFocus.focus==='function')_ksDrillFocus.focus();
  _ksDrillFocus=null;
}
document.addEventListener('keydown',function(e){if(e.key==='Escape')ksDrillClose();});
(function(){
  var zb=document.getElementById('reZoneListBody');
  if(zb)zb.addEventListener('click',function(e){
    if(e.target.closest('button')||e.target.closest('a')||e.target.closest('input'))return;
    var tr=e.target.closest('tr');if(!tr||!tr.cells||tr.cells.length<2)return;
    var zid=(tr.cells[0].textContent||'').trim();if(!zid)return;
    var f=document.getElementById('rePlotFilterZone');if(f)f.value=zid;
    if(typeof loadRePlots==='function')loadRePlots();
    ksDrill('领地下钻 // ZONE '+zid,['ks-card-replots']);
  });
  var pb=document.getElementById('bbPoolListBody');
  if(pb)pb.addEventListener('click',function(e){
    if(e.target.closest('button')||e.target.closest('a')||e.target.closest('input'))return;
    var tr=e.target.closest('tr');if(!tr||!tr.cells||tr.cells.length<2)return;
    var pid=(tr.cells[0].textContent||'').trim();if(!pid)return;
    var f=document.getElementById('bbLootPoolId');if(f)f.value=pid;
    if(typeof loadBbLoot==='function')loadBbLoot();
    ksDrill('卡池下钻 // POOL '+pid,['ks-card-bbloot']);
  });
})();
