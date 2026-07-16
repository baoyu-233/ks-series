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
function ksBankDrill(bankId){
  ['mgmtBankId','inviteBankId2','setRateBankId'].forEach(function(id){var el=document.getElementById(id);if(el)el.value=bankId;});
  if(typeof loadBankMembers==='function')loadBankMembers();
  ksDrill('银行管理 // BANK CONTROL — '+bankId,['bank-sub-manage']);
}
function ksEntDrill(entId){
  var el=document.getElementById('mgmtEntId');if(el)el.value=entId;
  ['entFinanceId','efEnterpriseId','salaryEnterpriseId','inviteEntId2'].forEach(function(id){var f=document.getElementById(id);if(f)f.value=entId;});
  if(typeof loadEntMembers==='function')loadEntMembers();
  ksDrill('企业管理 // CORP CONTROL — '+entId,['ent-sub-manage']);
  var trendCard=document.getElementById('ks-ent-divtrend'),trendBox=document.getElementById('entDivTrend');
  if(trendCard&&trendBox){
    trendCard.style.display='block';trendBox.innerHTML='<div class="ks-spark-empty">加载中…</div>';
    api('GET','/api/enterprise/dividends?enterpriseId='+encodeURIComponent(entId)).then(function(d){
      var rows=(d.dividends||[]).slice().reverse();
      var amounts=rows.map(function(r){return Number(r.amount)||0});
      var html=ksSpark(amounts);
      if(rows.length){
        html+='<div class="ks-card-fields" style="margin-top:8px;">'+rows.slice(-5).map(function(r){
          return '<div class="kcf"><span>'+(r.declared_at?new Date(r.declared_at*1000).toLocaleDateString('zh-CN'):'—')+'</span><b>'+fmt(r.amount)+'（税 '+fmt(r.tax||r.tax_amount||0)+'）</b></div>';
        }).join('')+'</div>';
      }
      trendBox.innerHTML=html;
    }).catch(function(){trendBox.innerHTML=ksSpark([]);});
  }
}
(function(){
  var pb=document.getElementById('myRePlotsBody');
  if(pb)pb.addEventListener('click',function(e){
    if(e.target.closest('button')||e.target.closest('a'))return;
    var tr=e.target.closest('tr');if(!tr||!tr.cells||tr.cells.length<9)return;
    var plotId=tr.cells[0].textContent.trim(),plot=(window.myRePlotCache||[]).find(function(p){return String(p.id||p.plotId)===plotId;});
    if(!plot){
      var bounds=(tr.cells[4].textContent||'').match(/\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]\s*-\s*\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]/);
      if(bounds)plot={id:plotId,zoneId:tr.cells[2].textContent.trim(),world:tr.cells[3].textContent.trim(),x1:Number(bounds[1]),z1:Number(bounds[2]),x2:Number(bounds[3]),z2:Number(bounds[4]),price:Number((tr.cells[5].textContent||'').replace(/[^\d.-]/g,''))||0,dungeonTemplateId:tr.cells[8].textContent.trim()};
    }
    if(plot&&typeof openEmpirePlotData==='function'){openEmpirePlotData(plot);return;}
    var lbl=['地块ID','所有者','区域','世界','范围','购入价','年税','购入时间','副本权限'];
    var html='';
    for(var i=0;i<tr.cells.length&&i<lbl.length;i++){
      html+='<div style="display:flex;justify-content:space-between;gap:14px;border-bottom:1px solid rgba(0,229,255,.08);padding:9px 2px;">'
        +'<span style="color:#4E6B85;font-size:11px;letter-spacing:1px;">'+lbl[i]+'</span>'
        +'<span style="font-family:var(--mono);color:#7DF3FF;text-align:right;">'+tr.cells[i].innerHTML+'</span></div>';
    }
    html+='<p style="color:#4E6B85;font-size:11px;margin-top:14px;">领地保护 / 信任名单管理请在游戏内使用 <code style="color:#00E5FF;">/land</code> 打开地块 GUI。</p>';
    showModal('地块详情 // PLOT DETAIL',html);
  });
})();
