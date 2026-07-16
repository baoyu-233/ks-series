// ====== 第61轮：实体详情抽屉 + 列表搜索 ======
async function openMaterialDrawer(material){
  var off=(typeof officialPrices!=='undefined'&&officialPrices[material])||{};
  var vol=null;
  try{
    if(!window.lastVolatilitySnapshot||!window.lastVolatilitySnapshot.items){window.lastVolatilitySnapshot=await api('GET','/api/admin/price-volatility');}
    (window.lastVolatilitySnapshot.items||[]).forEach(function(it){if(it.material===material)vol=it;});
  }catch(e){}
  var tl=(typeof trendLabel!=='undefined'&&vol&&trendLabel[vol.trend])||(vol&&vol.trend)||'—';
  var feed='<div class="hub-feed">'
    +'<div class="hub-feed-item"><span>材料</span><b style="font-family:var(--mono);">'+escapeHtml(material)+'</b></div>'
    +'<div class="hub-feed-item"><span>基础价</span><b>'+(vol?fmt(vol.basePrice):(off.basePrice!=null?fmt(off.basePrice):'—'))+'</b></div>'
    +'<div class="hub-feed-item"><span>官方收购价</span><b style="color:#00E5FF;">'+(vol?fmt(vol.buyPrice):(off.buyPrice!=null&&off.buyPrice!==''?fmt(off.buyPrice):'未定价'))+'</b></div>'
    +'<div class="hub-feed-item"><span>趋势</span><b>'+tl+'</b></div>'
    +'<div class="hub-feed-item"><span>随机漂移</span><b>'+(vol?((vol.driftValue||0)*100).toFixed(1)+'%':'—')+'</b></div>'
    +'<div class="hub-feed-item"><span>大手导向</span><b>'+(vol&&vol.trendBias?((vol.trendBias*100).toFixed(0)+'%'):'无')+'</b></div>'
    +'<div class="hub-feed-item"><span>供需压力</span><b>'+(vol?((vol.supplyPressure||0)*100).toFixed(1)+'%':'—')+'</b></div>'
    +'</div>';
  if(!vol)feed+='<p style="color:#4E6B85;font-size:11px;margin-top:8px;">暂无该材料的波动快照数据（未参与动态定价，或快照尚未生成）。</p>';
  feed+='<h4 style="margin-top:14px;color:#FF3DF2;letter-spacing:2px;font-size:10px;">操作 // ACTIONS</h4>'
    +'<div class="form-row"><label>大手导向 %<br><input id="mdBias" type="number" step="1" value="'+(vol&&vol.trendBias?(vol.trendBias*100).toFixed(0):0)+'" style="width:90px;"/></label>'
    +'<button class="btn btn-sm" onclick="mdApplyBias(\''+material+'\')">应用导向</button>'
    +'<button class="btn btn-sm" onclick="mdClearBias(\''+material+'\')">清除导向</button></div>'
    +'<div class="form-row"><label>强制定价（立即覆盖收购价）<br><input id="mdForce" type="number" step="0.1" value="'+(vol?vol.buyPrice:(off.buyPrice||''))+'" style="width:110px;"/></label>'
    +'<button class="btn btn-sm btn-warn" onclick="mdForcePrice(\''+material+'\')">立即生效</button></div>'
    +'<p style="color:#4E6B85;font-size:10.5px;">导向影响后续价格漂移方向；批量修改基准收购价请在价格表输入后统一保存。</p>';
  feed+='<h4 style="margin-top:14px;color:#00E5FF;letter-spacing:2px;font-size:10px;">30 日成交价走势 // TRADE HISTORY</h4><div id="mdTradeHist"><div class="ks-spark-empty">加载中…</div></div>';
  showModal('材料档案 // '+escapeHtml(material),feed);
  api('GET','/api/eco/trade-history?material='+encodeURIComponent(material)+'&days=30').then(function(h){
    var box=document.getElementById('mdTradeHist');if(!box)return;
    var pts=(h.points||[]);
    var html=(typeof ksSpark==='function')?ksSpark(pts.map(function(p){return p.avgPrice})):'';
    if(pts.length){
      html+='<div class="ks-card-fields" style="margin-top:8px;">'+pts.slice(-5).map(function(p){
        return '<div class="kcf"><span>'+new Date(p.day*1000).toLocaleDateString('zh-CN')+'</span><b>均价 '+fmt(p.avgPrice)+' · 量 '+fmt(p.volume)+' · '+fmt(p.trades)+' 笔</b></div>';
      }).join('')+'</div>';
    }
    box.innerHTML=html||'<div class="ks-spark-empty">暂无历史数据 // NO SERIES</div>';
  }).catch(function(){var box=document.getElementById('mdTradeHist');if(box)box.innerHTML='<div class="ks-spark-empty">暂无历史数据 // NO SERIES</div>';});
}
async function mdApplyBias(material){
  var bias=(parseFloat(document.getElementById('mdBias').value)||0)/100;
  var d=await api('POST','/api/admin/price-volatility/bias',{material:material,trendBias:bias});
  if(d.message){toast(d.message,'ok');window.lastVolatilitySnapshot=null;if(typeof loadPriceVolatility==='function')loadPriceVolatility();}
  else toast(d.error||'设置失败','err');
}
async function mdClearBias(material){
  var d=await api('POST','/api/admin/price-volatility/bias',{material:material,trendBias:0});
  if(d.message){toast('导向已清除','ok');window.lastVolatilitySnapshot=null;var b=document.getElementById('mdBias');if(b)b.value=0;}
  else toast(d.error||'清除失败','err');
}
async function mdForcePrice(material){
  var price=parseFloat(document.getElementById('mdForce').value);
  if(isNaN(price)||price<0){toast('价格无效','err');return;}
  var d=await api('POST','/api/admin/force-price',{material:material,price:price});
  if(d.message){
    toast(d.message,'ok');
    if(typeof officialPrices!=='undefined'){officialPrices[material]=officialPrices[material]||{};officialPrices[material].buyPrice=price;
      var ps=document.getElementById('priceSearch');if(typeof renderPrices==='function')renderPrices(ps?ps.value:'');}
    window.lastVolatilitySnapshot=null;
  }else toast(d.error||'设价失败','err');
}
function openListingDrawer(id){
  var l=(window._listingsCache||{})[id];if(!l)return;
  var label=l.listingAssetType==='PROPERTY'?'🏠 商品房':(l.chineseName||l.itemMaterial||'');
  var feed='<div class="hub-feed">'
   +'<div class="hub-feed-item"><span>物品</span><b>'+escapeHtml(label)+'</b></div>'
   +'<div class="hub-feed-item"><span>类型</span><b>'+(l.listingAssetType==='PROPERTY'?'房产':(l.listingMode==='BARTER'?'换物':'出售'))+'</b></div>'
   +'<div class="hub-feed-item"><span>材质</span><b style="font-family:var(--mono);font-size:11px;">'+escapeHtml(l.itemMaterial||'—')+'</b></div>'
   +'<div class="hub-feed-item"><span>数量</span><b>'+fmt(l.quantity)+'</b></div>'
   +'<div class="hub-feed-item"><span>单价</span><b>'+fmt(l.unitPrice)+'</b></div>'
   +'<div class="hub-feed-item"><span>总价</span><b style="color:#00E5FF;">'+fmt(l.totalPrice)+'</b></div>'
   +'<div class="hub-feed-item"><span>卖家</span><b>'+escapeHtml(l.sellerName||'?')+' <span style="color:#4E6B85;font-size:10px;">'+(l.sellerUuid||'').substring(0,13)+'…</span></b></div>'
   +'<div class="hub-feed-item"><span>创建时间</span><b>'+new Date(l.createdAt*1000).toLocaleString('zh-CN')+'</b></div>'
   +'</div>';
  if(l.displayName)feed+='<p style="font-size:12px;color:#7DF3FF;margin-top:8px;">显示名：'+escapeHtml(l.displayName)+'</p>';
  if(l.lore&&l.lore.length)feed+='<div style="font-size:11px;color:#8FB4CC;background:rgba(0,229,255,.04);border:1px solid rgba(0,229,255,.12);padding:8px 10px;margin-top:6px;line-height:1.7;">'+l.lore.map(escapeHtml).join('<br>')+'</div>';
  feed+='<h4 style="margin-top:14px;color:#FF3DF2;letter-spacing:2px;font-size:10px;">管理动作 // ACTIONS</h4>'
   +'<div class="form-row">'
   +'<button type="button" class="btn btn-sm btn-danger" data-listing-action="cancel" data-close-modal="1" data-listing-id="'+escapeAttr(l.id)+'" data-seller-name="'+escapeAttr(l.sellerName||'?')+'">强制撤单（退回暂存箱）</button>'
   +'<button type="button" class="btn btn-sm btn-danger" data-listing-action="destroy" data-close-modal="1" data-listing-id="'+escapeAttr(l.id)+'" data-seller-name="'+escapeAttr(l.sellerName||'?')+'">永久销毁</button>'
   +'</div>';
  showModal('挂单档案 // LISTING',feed);
}
function openMoDrawer(id){
  var o=(typeof moAdminCache!=='undefined'&&moAdminCache[id]);if(!o)return;
  var p=Math.max(0,Math.min(1,Number(o.progressPct||0)));
  var jsId=escapeAttr(JSON.stringify(o.id||''));
  var feed='<div class="hub-feed">'
   +'<div class="hub-feed-item"><span>标题</span><b>'+escapeHtml(o.title||'')+'</b></div>'
   +'<div class="hub-feed-item"><span>状态</span><b>'+escapeHtml(o.status||'ACTIVE')+'</b></div>'
   +'<div class="hub-feed-item"><span>指标类型</span><b>'+escapeHtml(o.metricType||o.metric||'—')+'</b></div>'
   +'<div class="hub-feed-item"><span>进度</span><b>'+fmt(o.currentValue||0)+' / '+fmt(o.targetValue||0)+' ('+Math.round(p*100)+'%)</b></div>'
   +(o.industry?'<div class="hub-feed-item"><span>行业</span><b>'+escapeHtml(o.industry)+'</b></div>':'')
   +(o.purpose?'<div class="hub-feed-item"><span>用途</span><b>'+escapeHtml(o.purpose)+'</b></div>':'')
   +(o.loanRateMultiplier!=null?'<div class="hub-feed-item"><span>贷款利率倍率</span><b>'+o.loanRateMultiplier+'</b></div>':'')
   +(o.reserveDelta!=null?'<div class="hub-feed-item"><span>准备金增量</span><b>'+o.reserveDelta+'</b></div>':'')
   +'</div>'
   +'<div style="height:10px;background:rgba(0,229,255,.08);margin:12px 0;overflow:hidden;"><div style="height:100%;width:'+(p*100).toFixed(1)+'%;background:linear-gradient(90deg,#00E5FF,#FF3DF2);box-shadow:0 0 12px rgba(0,229,255,.6);"></div></div>'
   +(o.description?'<p style="font-size:12px;color:#8FB4CC;line-height:1.7;">'+escapeHtml(o.description)+'</p>':'')
   +'<div class="form-row" style="margin-top:12px;">'
   +'<button class="btn btn-sm" onclick="closeModal();fillMo('+jsId+')">载入编辑器</button>'
   +'<button class="btn btn-sm btn-success" onclick="closeModal();setMoStatus('+jsId+',\'COMPLETED\')">标记完成</button>'
   +'<button class="btn btn-sm btn-danger" onclick="closeModal();setMoStatus('+jsId+',\'ARCHIVED\')">归档</button>'
   +'</div>';
  showModal('主任务档案 // MAJOR ORDER',feed);
}
function openProposalDrawer(id){
  var p=(window._propsCache||{})[id];if(!p)return;
  var rows=[['标题',p.title],['类型',p.proposalType],['提案者',p.proposerName],['状态',p.status],['结果',p.resultSummary]];
  if(p.votesFor!=null)rows.push(['赞成票',p.votesFor]);
  if(p.votesAgainst!=null)rows.push(['反对票',p.votesAgainst]);
  if(p.createdAt)rows.push(['发起时间',new Date(p.createdAt*1000).toLocaleString('zh-CN')]);
  var feed='<div class="hub-feed">';
  rows.forEach(function(r){if(r[1]==null||r[1]==='')return;feed+='<div class="hub-feed-item"><span>'+r[0]+'</span><b>'+escapeHtml(String(r[1]))+'</b></div>';});
  feed+='</div>';
  if(p.payload)feed+='<pre style="font-size:10.5px;color:#8FB4CC;background:rgba(0,229,255,.04);border:1px solid rgba(0,229,255,.12);padding:10px;margin-top:10px;white-space:pre-wrap;word-break:break-all;">'+escapeHtml(typeof p.payload==='string'?p.payload:JSON.stringify(p.payload,null,2))+'</pre>';
  showModal('提案档案 // PROPOSAL '+escapeHtml(String(p.id)),feed);
}
function ksTableSearch(inputEl,bodyId){
  var q=(inputEl.value||'').toLowerCase();
  document.querySelectorAll('#'+bodyId+' tr').forEach(function(tr){
    tr.style.display=!q||tr.textContent.toLowerCase().indexOf(q)>=0?'':'none';
  });
}
function ksAddSearch(bodyId,placeholder){
  var body=document.getElementById(bodyId);if(!body)return;
  var wrap=body.closest('.table-wrap');if(!wrap)return;
  var prev=wrap.previousElementSibling;
  if(prev&&prev.classList&&prev.classList.contains('ks-search-row'))return;
  var row=document.createElement('div');row.className='ks-search-row';
  var inp=document.createElement('input');
  inp.placeholder=placeholder;inp.style.width='280px';
  inp.addEventListener('input',function(){ksTableSearch(inp,bodyId);});
  row.appendChild(inp);
  wrap.parentNode.insertBefore(row,wrap);
}
(function(){
  /* bank/ent lists now use card grids with their own ksCardSearch inputs */
  ksAddSearch('listingBody','⌕ 搜索挂单 卖家 / 物品…');
  ksAddSearch('rePlotListBody','⌕ 搜索地块 ID / 区域 / 所有者…');
  function delegate(bodyId,attr,fn){
    var el=document.getElementById(bodyId);if(!el)return;
    el.addEventListener('click',function(e){
      if(e.target.closest('button')||e.target.closest('a')||e.target.closest('input'))return;
      var tr=e.target.closest('tr');if(!tr||!tr.dataset[attr])return;
      fn(tr.dataset[attr]);
    });
  }
  delegate('listingBody','lid',openListingDrawer);
  delegate('moAdminBody','moid',openMoDrawer);
  delegate('apropBody','pid',openProposalDrawer);
})();
