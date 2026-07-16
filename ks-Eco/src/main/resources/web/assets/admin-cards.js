/* 63rd round: card grid / KPI / sparkline helpers. Real data only — ksSpark renders an empty state below 2 points. */
function ksSpark(values,color){
  if(!Array.isArray(values))values=[];
  values=values.map(Number).filter(function(v){return isFinite(v)});
  if(values.length<2)return '<div class="ks-spark-empty">暂无历史数据 // NO SERIES</div>';
  var w=120,h=36,min=Math.min.apply(null,values),max=Math.max.apply(null,values),span=(max-min)||1;
  var pts=values.map(function(v,i){return (i*(w/(values.length-1))).toFixed(1)+','+(h-3-((v-min)/span)*(h-8)).toFixed(1)}).join(' ');
  var stroke=color||'var(--cyan)';
  return '<div class="ks-card-spark"><svg viewBox="0 0 '+w+' '+h+'" preserveAspectRatio="none">'+
    '<polygon class="ks-spark-fill" style="fill:'+stroke+';opacity:.12" points="0,'+h+' '+pts+' '+w+','+h+'"/>'+
    '<polyline class="ks-spark-line" style="stroke:'+stroke+'" points="'+pts+'"/></svg></div>';
}
function ksKpiRow(containerId,items){
  var el=document.getElementById(containerId);if(!el)return;
  el.innerHTML=(items||[]).map(function(it){
    return '<div class="stat-card kpi-icon-tile">'+(it.icon?'<div class="ks-kpi-ico">'+it.icon+'</div>':'')+
      '<div class="kit-txt"><div class="stat-val"'+(it.accent?' style="color:'+escapeAttr(it.accent)+'"':'')+'>'+escapeHtml(it.value)+'</div>'+
      '<div class="stat-label">'+escapeHtml(it.label)+'</div>'+(it.sub?'<div class="stat-sub">'+escapeHtml(it.sub)+'</div>':'')+'</div></div>';
  }).join('');
}
function ksCard(o){
  var fields=(o.fields||[]).map(function(f){return '<div class="kcf"><span>'+escapeHtml(f[0])+'</span><b>'+escapeHtml(f[1])+'</b></div>'}).join('');
  var actions=(o.actions||[]).map(function(a){return '<button class="btn btn-sm'+(a.cls?' '+a.cls:'')+'" onclick="event.stopPropagation();'+a.onclick+'">'+escapeHtml(a.label)+'</button>'}).join('');
  var badge=o.badge?'<span class="kec-badge'+(o.badgeCls?' '+o.badgeCls:'')+'">'+escapeHtml(o.badge)+'</span>':'';
  return '<div class="ks-entity-card"'+(o.onclick?' onclick="'+o.onclick+'"':'')+'>'+
    '<div class="kec-head"><div class="kec-name">'+escapeHtml(o.title)+'</div>'+badge+'</div>'+
    (fields?'<div class="ks-card-fields">'+fields+'</div>':'')+(o.spark||'')+
    (actions?'<div class="ks-card-actions">'+actions+'</div>':'')+'</div>';
}
function ksGrid(containerId,cards,emptyMsg){
  var el=document.getElementById(containerId);if(!el)return;
  el.innerHTML=cards&&cards.length?cards.join(''):'<div class="ks-spark-empty" style="height:70px;grid-column:1/-1">'+escapeHtml(emptyMsg||'暂无数据 // EMPTY')+'</div>';
}
function ksOddsBars(rows){
  var total=0;rows.forEach(function(r){total+=Number(r.weight)||0});
  if(!total)return '<div class="ks-spark-empty">暂无掉落数据 // EMPTY</div>';
  return rows.map(function(r){
    var p=(Number(r.weight)||0)/total*100;
    return '<div class="ks-odds-bar"><div class="kob-name">'+escapeHtml(r.name)+'</div><div class="kob-track"><div class="kob-fill" style="width:'+p.toFixed(2)+'%"></div></div><div class="kob-pct">'+p.toFixed(2)+'%</div></div>';
  }).join('');
}
function ksCardSearch(inputId,gridId){
  var inp=document.getElementById(inputId),grid=document.getElementById(gridId);if(!inp||!grid)return;
  inp.addEventListener('input',function(){
    var q=inp.value.trim().toLowerCase();
    grid.querySelectorAll('.ks-entity-card').forEach(function(c){c.style.display=!q||c.textContent.toLowerCase().indexOf(q)>=0?'':'none'});
  });
}
