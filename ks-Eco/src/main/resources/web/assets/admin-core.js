// ============ GLOBAL ============
var TOKEN=(new URL(location)).searchParams.get('token')||'';
if(TOKEN&&history.replaceState){var cleanUrl=new URL(location.href);cleanUrl.searchParams.delete('token');history.replaceState(null,'',cleanUrl.pathname+cleanUrl.search+cleanUrl.hash);}
var API='/ks-Eco';
var moneyChart=null;
var allItems=[], officialPrices={};
function H(){return TOKEN?{'Authorization':'Bearer '+TOKEN}:{};}
async function resolvePlayerRef(value){
  var ref=(value||'').trim();
  if(!ref||/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(ref)) return ref;
  var r=await fetch(API+'/api/players/search?q='+encodeURIComponent(ref),{headers:H()});
  var d=await r.json(); var matches=(d.players||[]).filter(function(p){return (p.name||'').toLowerCase()===ref.toLowerCase();});
  if(matches.length!==1) throw new Error('未找到唯一匹配的玩家 ID: '+ref);
  return matches[0].uuid;
}
async function normalizePlayerRefs(value,key){
  if(Array.isArray(value)) return Promise.all(value.map(function(v){return normalizePlayerRefs(v,key);}));
  if(value&&typeof value==='object'){
    var out={}; for(var k in value) out[k]=await normalizePlayerRefs(value[k],k); return out;
  }
  return typeof value==='string'&&/(uuid|uuids)$/i.test(key||'') ? resolvePlayerRef(value) : value;
}
var KS_PREVIEW=(location.search.indexOf('preview=1')>=0)&&(/^(localhost|127\.0\.0\.1)$/).test(location.hostname);
var KS_PREVIEW_SCENARIO=(new URL(location.href)).searchParams.get('previewScenario')||'normal';
function ksPreviewApplyScenario(data){
  if(KS_PREVIEW_SCENARIO!=='empty'||!data||typeof data!=='object')return data;
  var out=Array.isArray(data)?[]:Object.assign({},data);
  if(Array.isArray(out))return out;
  Object.keys(out).forEach(function(key){
    if(Array.isArray(out[key]))out[key]=[];
    else if(typeof out[key]==='number'&&/(count|total|balance|assets|amount|m0|m1|m2)/i.test(key))out[key]=0;
  });
  return out;
}
function ksPreviewApi(path){
  var zones=[
    {id:'ZN-C4',name:'新城商业区',world:'world',type:'COMMERCIAL',status:'FOR_SALE',x1:-360,z1:-240,x2:-40,z2:80,basePrice:280000,taxRate:.05,plotCount:8,maxPlots:32},
    {id:'ZN-C5',name:'港口工业区',world:'world',type:'INDUSTRIAL',status:'FOR_SALE',x1:10,z1:-270,x2:350,z2:60,basePrice:360000,taxRate:.04,plotCount:5,maxPlots:0},
    {id:'ZN-C6',name:'曙光住宅区',world:'world',type:'RESIDENTIAL',status:'STATE_OWNED',x1:-300,z1:130,x2:80,z2:400,basePrice:190000,taxRate:.03,plotCount:18,maxPlots:48},
    {id:'ZN-C7',name:'生态农业带',world:'world',type:'AGRICULTURAL',status:'SOLD',x1:120,z1:100,x2:430,z2:390,basePrice:150000,taxRate:.02,plotCount:12,maxPlots:0}
  ];
  var plots=[{id:'PLT-17A',zoneId:'ZN-C4',world:'world',x1:-230,z1:-160,x2:-100,z2:-45,ownerType:'ENTERPRISE',ownerId:'ENT-NOVA',price:182000,taxRate:.05,purchasedAt:1783900000},{id:'PLT-19B',zoneId:'ZN-C5',world:'world',x1:80,z1:-190,x2:190,z2:-80,ownerType:'PLAYER',ownerId:'baoyu_233',price:205000,taxRate:.04,purchasedAt:1783900000}];
  if(path==='/api/macro-data')return {latestM0:330182382,latestM1:412098550,latestM2:688420960,bankCount:6,totalAssets:912880440,totalLoans:110190669,enterpriseCount:18,totalTaxCollected:28419310,baseRate:.013,reserveRequirement:.12,labels:['07/06','07/07','07/08','07/09','07/10','07/11','07/12'],m0:[280,293,301,296,315,323,330],m1:[340,358,371,360,387,400,412],m2:[540,560,578,601,622,655,688]};
  if(path==='/api/tax/rates')return {base:{MARKET_TRADE:.05,OFFICIAL_TRADE:.03,ENTERPRISE_SMALL:.02,ENTERPRISE_MEDIUM:.035,ENTERPRISE_LARGE:.06,BANK_INTEREST:.08,PLAYER_TRANSFER:.01,TAX_PENALTY:.2,DIVIDEND_TAX:.1},industry:{},brackets:[]};
  if(path==='/api/admin/transfer/config')return {taxFreeAmount:1000,taxRate:.01};
  if(path==='/api/enterprise/list')return {maxEnterpriseLevel:10,enterprises:[{id:'ENT-NOVA',name:'新星联合工业',description:'工业与红石设备供应',type:'PRIVATE',industry:'INDUSTRY',level:6,registered_capital:2500000,corporate_balance:4820000,employee_count:12,region:'新城区',status:'ACTIVE'},{id:'ENT-ORBIT',name:'轨道物流集团',description:'跨区物流',type:'PRIVATE',industry:'OTHER',level:3,registered_capital:1400000,corporate_balance:2360000,employee_count:7,region:'港区',status:'ACTIVE'},{id:'ENT-AGRI',name:'曙光农业社',description:'农业生产',type:'PRIVATE',industry:'AGRICULTURE',level:4,registered_capital:900000,corporate_balance:1560000,employee_count:18,region:'生态带',status:'ACTIVE'}]};
  if(path==='/api/bank/list')return {banks:[{id:'B-AURORA',name:'曙光发展银行',type:'COMMERCIAL',total_assets:330182382,loan_rate:.02,status:'ACTIVE'},{id:'B-NOVA',name:'新星储备银行',type:'COMMERCIAL',total_assets:210093000,loan_rate:.026,status:'ACTIVE'},{id:'GUIDE-BANK',name:'城邦发展银行',type:'GUIDANCE',total_assets:96000000,loan_rate:.015,status:'ACTIVE'}]};
  if(path==='/api/realestate/zones')return {moduleLoaded:true,zones:zones};
  if(path==='/api/realestate/plots')return {plots:plots};
  if(path==='/api/market/stats')return {activeListings:46,storedItems:1280,officialBuyEnabled:true};
  if(path==='/api/eco/public-info')return {prices:[{material:'DIAMOND',chineseName:'钻石',buyPrice:130,marketAvg:124,trend:'UP'},{material:'IRON_INGOT',chineseName:'铁锭',buyPrice:7.01,marketAvg:7.65,trend:'DOWN'},{material:'GOLD_INGOT',chineseName:'金锭',buyPrice:32.38,marketAvg:29.2,trend:'UP'}]};
  if(path==='/api/listings')return {listings:[{id:'L-1',material:'DIAMOND',sellerName:'NovaMiner',quantity:32,unitPrice:148,totalPrice:4736},{id:'L-2',material:'IRON_INGOT',sellerName:'RailWorks',quantity:256,unitPrice:8,totalPrice:2048}]};
  if(path==='/api/admin/listings')return {count:2,listings:[
    {id:'L-1',sellerName:"Nova'Miner",sellerUuid:'11111111-1111-1111-1111-111111111111',itemMaterial:'DIAMOND',chineseName:'钻石',quantity:32,unitPrice:148,totalPrice:4736,listingType:'SELL',listingMode:'SELL',listingAssetType:'ITEM',createdAt:1783958400},
    {id:'L-2',sellerName:'RailWorks',sellerUuid:'22222222-2222-2222-2222-222222222222',itemMaterial:'IRON_INGOT',chineseName:'铁锭',quantity:256,unitPrice:8,totalPrice:2048,listingType:'SELL',listingMode:'SELL',listingAssetType:'ITEM',createdAt:1783953000}
  ]};
  if(path==='/api/admin/listings/force-cancel')return {message:'挂单已撤销，物品已退回卖家暂存箱'};
  if(path==='/api/admin/listings/force-destroy')return {message:'挂单已销毁（物品未退回）'};
  if(path==='/api/blindbox/pools')return {maxEnterpriseLevel:10,pools:[{id:'tech-cache',name:'科技补给箱',enabled:true,price:680,pityMax:50,pityRules:'RARE:50,EPIC:120',pityRulesText:'RARE 50 / EPIC 120',poolType:'ENTERPRISE',ownerType:'ENTERPRISE',minEnterpriseLevel:4,lootCount:14,pullCount:1240,allowedCategories:'material,tool',allowedIndustries:'INDUSTRY',requiredLandZoneTypes:'INDUSTRIAL',description:'企业工业补给'}]};
  if(path==='/api/audit/log?limit=20')return {logs:[{createdAt:1783958400,action:'BANK_RATE_SET',playerName:'baoyu_233',targetId:'B-AURORA',details:'贷款利率调整为 2.00%'},{createdAt:1783953000,action:'ZONE_PRICE_SET',playerName:'baoyu_233',targetId:'ZN-C4',details:'地块基础价格已更新'}]};
  if(path==='/api/admin/bans')return {bans:[]};
  if(path==='/api/admin/eco/features')return {bank:true,enterprise:true,realestate:true,blindbox:true};
  if(path==='/api/rankings/players')return {rankings:[{name:'baoyu_233',uuid:'afab46b2',balance:1582000,online:true},{name:'NovaMiner',uuid:'d09c8c4e',balance:1248800,online:true}]};
  if(path==='/api/rankings/enterprises')return {rankings:[]};
  if(path==='/api/rankings/banks')return {rankings:[]};
  return {};
}
async function api(method,path,body){
  if(KS_PREVIEW){
    if(KS_PREVIEW_SCENARIO==='slow')await new Promise(function(resolve){setTimeout(resolve,1200);});
    if(KS_PREVIEW_SCENARIO==='error')return {error:'测试场景：模拟 API 服务不可用',_status:503};
    var previewData=ksPreviewApi(path);
    if(method!=='GET'&&Object.keys(previewData).length===0)previewData={message:'测试模式：操作已模拟成功',success:true};
    return ksPreviewApplyScenario(previewData);
  }
  try{
    var o={method:method,headers:Object.assign({'Content-Type':'application/json'},H())};
    if(body)o.body=JSON.stringify(await normalizePlayerRefs(body,''));
    var r=await fetch(API+path,o);var text=await r.text();var data={};
    try{data=text?JSON.parse(text):{};}catch(parseError){data={error:text||('HTTP '+r.status)};}
    if(!r.ok&&!data.error)data.error='请求失败（HTTP '+r.status+'）';
    data._status=r.status;return data;
  }catch(e){return {error:e.message||'网络请求失败',_status:0};}}
function toast(msg,type){
  var b=document.getElementById('toast-box');
  var d=document.createElement('div');d.className='toast toast-'+(type||'info');d.textContent=msg;
  b.appendChild(d);setTimeout(function(){d.remove();},4000);
}
function fmt(n){return n?Number(n).toLocaleString():'0';}
function pct(n){return n!=null?(Number(n)*100).toFixed(2)+'%':'—';}
function escapeHtml(s){return s==null?'':String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}
function escapeAttr(s){return escapeHtml(s).replace(/'/g,'&#39;');}
function showModal(title,bodyHTML){
  document.getElementById('modalContent').innerHTML='<button class="close" onclick="closeModal()">✕</button><h3>'+title+'</h3>'+bodyHTML;
  document.getElementById('modalOverlay').classList.add('show');
}
function closeModal(){document.getElementById('modalOverlay').classList.remove('show');}

// ============ NAVIGATION ============
function toggleNav(id){var g=document.getElementById(id);if(g)g.classList.toggle('open');}
var TAB_ALIAS={
  'mo':'ops','eco-features':'ops','transport-config':'ops',
  'bank-guidance':'bank-cb','bank-inject':'bank-cb','bank-cb-loans':'bank-cb',
  'bank-list':'banks','bank-create':'banks','bank-loans':'banks','bank-perms':'banks',
  'ent-list':'ents','ent-create':'ents','ent-invites':'ents','ent-perms':'ents','ent-dividends':'ents',
  'ent-bidding':'bidding','ent-procurement':'bidding',
  'tax-records':'tax-rates','tax-penalties':'tax-rates',
  'market-control':'prices','market-public':'prices','market-idle':'market-overview','market-bans':'market-overview',
  'rank-players':'macro','rank-enterprises':'macro','rank-banks':'macro',
  'bb-loot':'bb-pools','bb-stats':'bb-pools',
  're-plots':'re-zones','land-perks':'re-zones',
  'dg-instances':'dg-templates','dg-grids':'dg-templates','dg-config':'dg-templates',
  'politic-proposals':'politic-offices','politic-config':'politic-offices'
};
function switchTab(tabId){
  tabId=TAB_ALIAS[tabId]||tabId;
  document.querySelectorAll('.tab-section').forEach(function(t){t.classList.remove('active');});
  var tab=document.getElementById('tab-'+tabId);if(tab)tab.classList.add('active');
  document.querySelectorAll('#sidebar .sb-nav a,#sidebar .hub-label').forEach(function(a){a.classList.remove('active');});
  document.querySelectorAll('#sidebar .nav-hub').forEach(function(h){h.classList.remove('active');});
  var nav=document.querySelector('[data-nav="'+tabId+'"]')||document.querySelector('[data-hub-nav="'+tabId+'"]');
  if(nav){nav.classList.add('active');var hub=nav.closest('.nav-hub');if(hub){hub.classList.add('active');var eb=document.getElementById('pageEyebrow');if(eb)eb.textContent=hub.getAttribute('data-label')||'KSECO CONTROL';}}
  var titles={
    'macro':'经济大盘 // MACRO OVERVIEW','corps-hub':'企业与领地 // CORPS & ZONES','protocols-hub':'核心协议 // PROTOCOLS','market-hub':'黑市与商业化 // BLACK MARKET','security-hub':'安全与审计 // SECURITY',
    'banks':'商业银行 // COMMERCIAL BANKS','bank-cb':'中央银行 // CENTRAL BANK','ents':'企业注册表 // CORPORATIONS','bidding':'招投标 // TENDERS',
    'tax-rates':'税收协议 // TAXATION','market-overview':'市场监控 // MARKET WATCH','prices':'官方定价 // OFFICIAL PRICING','price-volatility':'波动引擎 // VOLATILITY',
    'bb-pools':'盲盒卡池 // GACHA POOLS','re-zones':'领地地块 // ZONES & PLOTS','dg-templates':'副本协议 // DUNGEON PROTOCOLS','politic-offices':'元老院 // SENATE','ops':'运营配置 // OPERATIONS','audit-log':'审计日志 // AUDIT TRAIL'
  };
  document.getElementById('pageTitle').textContent=titles[tabId]||tabId;
  var loaders={
    'macro':function(){loadMacroData();if(KS_PREVIEW){setTimeout(function(){loadPlayerRankings();loadEntRankings();loadBankRankings();},0);}loadPlayerRankings();loadEntRankings();loadBankRankings();},
    'corps-hub':loadCorpsHub,
    'protocols-hub':loadProtocolsHub,
    'market-hub':loadMarketHub,
    'security-hub':loadSecurityHub,
    'banks':function(){loadBankList();},
    'bank-cb':function(){loadCbRates();loadGuidanceConfig();loadCbLoans();},
    'ents':function(){loadEntList();loadEconomicThresholds();loadInviteList();loadDivList();},
    'bidding':function(){loadProjectList();loadProcurementList();},
    'tax-rates':function(){loadTaxRates();loadTaxBrackets();loadTaxRecords();loadPenalties();},
    'market-overview':function(){loadMarketOverview();loadIdleItems();loadBans();},
    'prices':function(){loadPrices();loadAdminPublicInfo();},
    'price-volatility':loadPriceVolatility,
    'bb-pools':function(){loadBbPoolList();loadBbPoolOptions();loadBbStats();},
    're-zones':function(){loadReZones();loadRePlots();loadLandPerks();},
    'dg-templates':function(){loadDgTemplates();loadDgInstances();loadDgGrids();loadDgConfig();},
    'politic-offices':function(){loadPoliticOffices();loadAdminProposals();loadPoliticConfig();},
    'ops':function(){loadMoAdmin();loadEcoFeatures();loadTransportConfig();},
    'audit-log':loadAuditLog
  };
  if(loaders[tabId])loaders[tabId]();
}
function switchHub(tabId){
  switchTab(tabId);
  document.querySelectorAll('#sidebar .hub-label').forEach(function(el){el.classList.remove('active');});
  var label=document.querySelector('#sidebar [data-hub-nav="'+tabId+'"]');
  if(label){label.classList.add('active');var hub=label.closest('.nav-hub');if(hub)hub.classList.add('active');}
}
// Check URL hash for direct section link
(function(){
  var h=location.hash.replace('#section=','');
  if(h)switchTab(h);
})();

// ============ MAJOR ORDERS ============
var moAdminCache={};
async function loadMoAdmin(){
  var d=await api('GET','/api/admin/mo/list');
  if(d.error){toast(d.error,'err');return;}
  moAdminCache={};
  var t='';
  (d.orders||[]).forEach(function(o){
    moAdminCache[o.id]=o;
    var p=Math.max(0,Math.min(1,Number(o.progressPct||0)));
    var jsId=escapeAttr(JSON.stringify(o.id||''));
    t+='<tr data-moid="'+escapeAttr(String(o.id||''))+'" style="cursor:pointer;"><td style="font-size:10px;">'+escapeHtml(o.id||'')+'</td>';
    t+='<td>'+escapeHtml(o.title||'')+'</td>';
    t+='<td>'+escapeHtml(o.metricType||o.metric||'')+'</td>';
    t+='<td><div style="height:8px;background:#222;border-radius:4px;overflow:hidden;margin-bottom:4px;"><div style="height:100%;width:'+(p*100).toFixed(1)+'%;background:#00E5FF;"></div></div><span style="font-size:11px;color:#aaa;">'+fmt(o.currentValue||0)+' / '+fmt(o.targetValue||0)+' ('+Math.round(p*100)+'%)</span></td>';
    t+='<td><span class="badge">'+escapeHtml(o.status||'ACTIVE')+'</span></td>';
    t+='<td><button class="btn btn-sm" onclick="fillMo('+jsId+')">编辑</button> ';
    t+='<button class="btn btn-sm" onclick="setMoStatus('+jsId+',\'COMPLETED\')">完成</button> ';
    t+='<button class="btn btn-sm danger" onclick="setMoStatus('+jsId+',\'ARCHIVED\')">归档</button></td></tr>';
  });
  document.getElementById('moAdminBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无 MO</td></tr>';
}
function fillMo(id){
  var o=moAdminCache[id]; if(!o)return;
  document.getElementById('moId').value=o.id||'';
  document.getElementById('moTitle').value=o.title||'';
  document.getElementById('moMetric').value=o.metricType||o.metric||'MANUAL';
  document.getElementById('moTarget').value=o.targetValue!=null?o.targetValue:1000000;
  document.getElementById('moManual').value=o.manualValue!=null?o.manualValue:(o.currentValue||0);
  document.getElementById('moStatus').value=o.status||'ACTIVE';
  document.getElementById('moDesc').value=o.description||'';
  document.getElementById('moPolicyIndustry').value=o.policyIndustry||'ALL';
  document.getElementById('moPolicyPurpose').value=o.policyPurpose||'ALL';
  document.getElementById('moPolicyRateMultiplier').value=o.policyLoanRateMultiplier!=null?o.policyLoanRateMultiplier:1;
  document.getElementById('moPolicyReserveDelta').value=o.policyReserveDelta!=null?o.policyReserveDelta:0;
}
async function saveMo(){
  var title=document.getElementById('moTitle').value.trim();
  if(!title){toast('标题必填','err');return;}
  var body={
    id:document.getElementById('moId').value.trim(),
    title:title,
    metricType:document.getElementById('moMetric').value,
    targetValue:Number(document.getElementById('moTarget').value)||1,
    manualValue:Number(document.getElementById('moManual').value)||0,
    status:document.getElementById('moStatus').value,
    description:document.getElementById('moDesc').value.trim(),
    policyIndustry:document.getElementById('moPolicyIndustry').value,
    policyPurpose:document.getElementById('moPolicyPurpose').value,
    policyLoanRateMultiplier:Number(document.getElementById('moPolicyRateMultiplier').value),
    policyReserveDelta:Number(document.getElementById('moPolicyReserveDelta').value)
  };
  var d=await api('POST','/api/admin/mo/save',body);
  if(d.message){toast(d.message,'ok');loadMoAdmin();if(d.id)document.getElementById('moId').value=d.id;}
  else toast(d.error||'保存失败','err');
}
async function setMoStatus(id,status){
  if(status==='ARCHIVED'&&!confirm('确定归档这个 MO？'))return;
  var d=await api('POST','/api/admin/mo/status',{id:id,status:status});
  if(d.message){toast(d.message,'ok');loadMoAdmin();}
  else toast(d.error||'操作失败','err');
}

// ============ MACRO DATA ============
async function loadMacroData(){
  var d=await api('GET','/api/macro-data');
  var s='';
  s+='<div class="stat-card"><div class="stat-val">'+fmt(d.latestM0)+'</div><div class="stat-label">M0 基础货币</div></div>';
  s+='<div class="stat-card"><div class="stat-val">'+fmt(d.latestM1)+'</div><div class="stat-label">M1 狭义货币</div></div>';
  s+='<div class="stat-card"><div class="stat-val">'+fmt(d.latestM2)+'</div><div class="stat-label">M2 广义货币</div></div>';
  s+='<div class="stat-card" onclick="switchTab(\'banks\')" title="进入商业银行"><div class="stat-val">'+fmt(d.bankCount)+'</div><div class="stat-label">注册银行 →</div></div>';
  s+='<div class="stat-card"><div class="stat-val">'+fmt(d.totalAssets)+'</div><div class="stat-label">系统总资产</div></div>';
  s+='<div class="stat-card" onclick="switchTab(\'banks\')" title="进入商业银行"><div class="stat-val">'+fmt(d.totalLoans)+'</div><div class="stat-label">银行信贷·未还 →</div></div>';
  s+='<div class="stat-card" onclick="switchTab(\'ents\')" title="进入企业注册表"><div class="stat-val">'+fmt(d.enterpriseCount)+'</div><div class="stat-label">活跃企业 →</div></div>';
  s+='<div class="stat-card" onclick="switchTab(\'tax-rates\')" title="进入税收协议"><div class="stat-val">'+fmt(d.totalTaxCollected)+'</div><div class="stat-label">税收总额 →</div></div>';
  document.getElementById('macroStats').innerHTML=s;
  api('GET','/api/market/stats').then(function(m){
    if(!m||m.error)return;
    var ms='';
    ms+='<div class="stat-card" onclick="switchTab(\'market-overview\')" title="进入市场监控"><div class="stat-val">'+fmt(m.activeListings)+'</div><div class="stat-label">活跃挂单 →</div></div>';
    ms+='<div class="stat-card" onclick="switchTab(\'market-overview\')" title="进入市场监控"><div class="stat-val">'+fmt(m.storedItems)+'</div><div class="stat-label">官方仓储·暂存 →</div></div>';
    ms+='<div class="stat-card" onclick="switchTab(\'prices\')" title="进入官方定价"><div class="stat-val">'+(m.officialBuyEnabled?'开':'关')+'</div><div class="stat-label">官方收购 →</div></div>';
    var box=document.getElementById('macroStats');if(box)box.insertAdjacentHTML('beforeend',ms);
  }).catch(function(){});
  // CB rates
  document.getElementById('cbRateStats').innerHTML=
    '<div class="stat-card"><div class="stat-val">'+pct(d.baseRate)+'</div><div class="stat-label">基准利率</div></div>'+
    '<div class="stat-card"><div class="stat-val">'+pct(d.reserveRequirement)+'</div><div class="stat-label">准备金率</div></div>';
  // Chart
  if(!moneyChart&&d.labels&&d.labels.length>0){
    var ctx=document.getElementById('moneyChart').getContext('2d');
    moneyChart=new Chart(ctx,{type:'line',data:{labels:d.labels,datasets:[
      {label:'M0 基础货币',data:d.m0,borderColor:'#00E5FF',backgroundColor:'rgba(0,229,255,.14)',tension:0.3,fill:true},
      {label:'M1 狭义货币',data:d.m1,borderColor:'#FF3DF2',backgroundColor:'rgba(255,61,242,.10)',tension:0.3,fill:true},
      {label:'M2 广义货币',data:d.m2,borderColor:'#F8E71C',backgroundColor:'rgba(248,231,28,.08)',tension:0.3,fill:true}
    ]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{labels:{color:'#C9CDD6'}}},scales:{x:{ticks:{color:'#7E8595'},grid:{color:'rgba(255,255,255,.05)'}},y:{ticks:{color:'#7E8595'},grid:{color:'rgba(255,255,255,.05)'}}}}});
  }else if(moneyChart){
    moneyChart.data.labels=d.labels;moneyChart.data.datasets[0].data=d.m0;moneyChart.data.datasets[1].data=d.m1;moneyChart.data.datasets[2].data=d.m2;moneyChart.update();
  }
  // Tax overview
  var taxRates=await api('GET','/api/tax/rates');
  var labels={MARKET_TRADE:'市场交易税',OFFICIAL_TRADE:'官方交易税',ENTERPRISE_SMALL:'小微企业税',ENTERPRISE_MEDIUM:'中型企业税',ENTERPRISE_LARGE:'大型企业税',BANK_INTEREST:'银行利息税',PLAYER_TRANSFER:'玩家转账税',TAX_PENALTY:'税务罚款',DIVIDEND_TAX:'分红税'};
  var t='<table><tr><th>税种</th><th>税率</th></tr>';
  var baseRates=taxRates.base||{};
  for(var k in labels){t+='<tr><td>'+labels[k]+'</td><td>'+pct(baseRates[k])+'</td></tr>';}
  t+='</table>';document.getElementById('taxOverview').innerHTML=t;
}

// ============ BANK APIs ============
async function loadGuidanceConfig(){
  var d=await api('GET','/api/admin/bank/guidance/config');
  if(d.error){toast(d.error,'err');return;}
  document.getElementById('guidanceEnabled').checked=Number(d.enabled||0)>=0.5;
  document.getElementById('guidanceAmount').value=d.starter_loan_amount!=null?d.starter_loan_amount:'';
  document.getElementById('guidanceRate').value=d.starter_loan_rate!=null?d.starter_loan_rate:'';
  document.getElementById('guidanceTerm').value=d.starter_loan_term_days!=null?d.starter_loan_term_days:'';
  document.getElementById('guidanceTotalCap').value=d.starter_loan_total_cap!=null?d.starter_loan_total_cap:'';
  document.getElementById('guidanceDailyCap').value=d.starter_loan_daily_cap!=null?d.starter_loan_daily_cap:'';
  document.getElementById('guidanceReserveRatio').value=d.starter_loan_min_reserve_ratio!=null?d.starter_loan_min_reserve_ratio:'';
  document.getElementById('guidanceBankSummary').innerHTML='状态: <b>'+escapeHtml(d.bankStatus||'未创建')+'</b> | 当前资产: <b>'+fmt(d.assets)+'</b> | 一次性种子本金: <b>'+fmt(d.seed_capital)+'</b>';
}
async function saveGuidanceConfig(){
  var body={
    enabled:document.getElementById('guidanceEnabled').checked?1:0,
    starter_loan_amount:parseFloat(document.getElementById('guidanceAmount').value),
    starter_loan_rate:parseFloat(document.getElementById('guidanceRate').value),
    starter_loan_term_days:parseInt(document.getElementById('guidanceTerm').value),
    starter_loan_total_cap:parseFloat(document.getElementById('guidanceTotalCap').value),
    starter_loan_daily_cap:parseFloat(document.getElementById('guidanceDailyCap').value),
    starter_loan_min_reserve_ratio:parseFloat(document.getElementById('guidanceReserveRatio').value)
  };
  for(var key in body){if(Number.isNaN(body[key])){toast('请填写完整且有效的政策参数','err');return;}}
  var d=await api('POST','/api/admin/bank/guidance/config',body);
  if(d.message){toast(d.message,'ok');loadGuidanceConfig();}else toast(d.error||'保存失败','err');
}
async function loadCbRates(){
  var d=await api('GET','/api/bank/cb/rates');
  document.getElementById('cbRateCurrent').innerHTML='当前: 基准利率 <b>'+pct(d.baseRate)+'</b> | 准备金率 <b>'+pct(d.reserveRequirement)
    +'</b> | 利息结算周期 <b>'+(d.interestPeriodDays!=null?d.interestPeriodDays:7)+' 天</b> | 开行最低资本 <b>'+fmt(d.bankMinCapital!=null?d.bankMinCapital:50000)+'</b>';
  var rng=document.getElementById('cbRateRangeCurrent');
  if(rng){
    if(d.rateMin!=null||d.rateMax!=null){
      rng.innerHTML='当前商业银行利率区间: <b>'+pct(d.rateMin)+'</b> ~ <b>'+pct(d.rateMax)+'</b>';
    }else{rng.innerHTML='<span style="color:#666;">尚未设置利率区间</span>';}
  }
  if(typeof ksKpiRow==='function')ksKpiRow('cbKpis',[
    {icon:'🏛',label:'基准利率',value:pct(d.baseRate)},
    {icon:'🧱',label:'准备金率',value:pct(d.reserveRequirement)},
    {icon:'⏱',label:'利息结算周期',value:(d.interestPeriodDays!=null?d.interestPeriodDays:7)+' 天'},
    {icon:'📐',label:'商行利率走廊',value:(d.rateMin!=null||d.rateMax!=null)?(pct(d.rateMin)+' ~ '+pct(d.rateMax)):'未设置'},
    {icon:'🏦',label:'开行最低资本',value:fmt(d.bankMinCapital!=null?d.bankMinCapital:50000)}
  ]);
}
async function setCbRateRange(){
  var min=parseFloat(document.getElementById('cbRateMin').value);
  var max=parseFloat(document.getElementById('cbRateMax').value);
  if(isNaN(min)||isNaN(max)||min<0||min>1||max<0||max>1){toast('利率区间必须在 0-1','err');return;}
  if(max<min){toast('上限不能小于下限','err');return;}
  var d=await api('POST','/api/bank/cb/set-rate-range',{rateMin:min,rateMax:max});
  if(d.message){toast(d.message,'ok');loadCbRates();}else toast(d.error||'设置失败','err');
}
async function setCbRates(){
  var o={};
  var br=document.getElementById('cbBaseRate').value;if(br)o.baseRate=parseFloat(br);
  var rr=document.getElementById('cbReserveReq').value;if(rr)o.reserveRequirement=parseFloat(rr);
  var ip=document.getElementById('cbInterestPeriod').value;if(ip)o.interestPeriodDays=parseFloat(ip);
  var mc=document.getElementById('cbBankMinCapital').value;if(mc)o.bankMinCapital=parseFloat(mc);
  var d=await api('POST','/api/bank/cb/set-rates',o);
  if(d.message){toast(d.message,'ok');loadCbRates();loadMacroData();if(KS_PREVIEW){setTimeout(function(){loadPlayerRankings();loadEntRankings();loadBankRankings();},0);}}else toast(d.error,'err');
}
async function loadBankList(){
  var d=await api('GET','/api/bank/list');
  var cards=(d.banks||[]).map(function(b){
    return ksCard({
      title:b.name,badge:b.type||'',badgeCls:b.type==='CENTRAL'?'':'cyan',
      fields:[['银行ID',b.id],['总资产',fmt(b.total_assets||b.capital)],['贷款利率',pct(b.loan_rate)],['存款利率/周期',pct(b.interest_rate||b.deposit_rate)],['状态',b.status||'']],
      onclick:"openBankMgr('"+escapeAttr(b.id)+"')",
      actions:[
        {label:'⚙ 管理',onclick:"openBankMgr('"+escapeAttr(b.id)+"')"},
        {label:'📈 利率',onclick:"setBankRate('"+escapeAttr(b.id)+"')"},
        {label:'🔑 权限',onclick:"viewBankPerms('"+escapeAttr(b.id)+"')"}
      ]
    });
  });
  ksGrid('bankListGrid',cards,'暂无银行');
  if(typeof ksCardSearch==='function')ksCardSearch('bankListSearch','bankListGrid');
}

async function loadEnterpriseLoanRequests(){
  var bankId=document.getElementById('entLoanRequestBankId').value.trim();
  if(!bankId){toast('请填写银行ID','err');return;}
  var d=await api('GET','/api/bank/enterprise-loan/requests?bankId='+encodeURIComponent(bankId)+'&status=PENDING');
  var rows='';(d.requests||[]).forEach(function(r){
    rows+='<tr><td>'+escapeHtml(r.id)+'</td><td>'+escapeHtml(r.enterprise_id)+'</td><td>'+escapeHtml(r.purpose)+'</td><td>'+fmt(r.principal)+'</td><td>'+escapeHtml(r.collateral_type)+': '+escapeHtml(r.collateral_ref)+'</td><td>'+pct(r.loan_to_value)+'</td><td><button class="btn btn-sm btn-success" onclick="decideEnterpriseLoan(\''+escapeAttr(bankId)+'\',\''+escapeAttr(r.id)+'\',true)">批准</button> <button class="btn btn-sm btn-danger" onclick="decideEnterpriseLoan(\''+escapeAttr(bankId)+'\',\''+escapeAttr(r.id)+'\',false)">拒绝</button></td></tr>';
  });
  document.getElementById('entLoanRequestBody').innerHTML=rows||'<tr><td colspan="7" style="color:#666;">暂无待审企业融资</td></tr>';
}
async function decideEnterpriseLoan(bankId,requestId,approve){
  if(approve&&!confirm('批准后款项会进入企业公户并锁定抵押物，确认继续？'))return;
  var d=await api('POST','/api/bank/enterprise-loan/decide',{bankId:bankId,requestId:requestId,approve:approve});
  if(d.message){toast(d.message,'ok');loadEnterpriseLoanRequests();}else toast(d.error||'审批失败','err');
}
async function registerEnterpriseInventory(){
  var body={enterpriseId:document.getElementById('inventoryEntId').value.trim(),description:document.getElementById('inventoryDesc').value.trim(),quantity:Number(document.getElementById('inventoryQty').value),appraisedValue:Number(document.getElementById('inventoryValue').value)};
  if(!body.enterpriseId||!body.description||body.quantity<=0||body.appraisedValue<=0){toast('请完整填写企业库存估值','err');return;}
  var d=await api('POST','/api/admin/enterprise/finance/inventory',body);
  if(d.message){toast(d.message+' '+d.id,'ok');}else toast(d.error||'登记失败','err');
}

async function quickBankInvite(bankId){
  var uuid=await ksPickPlayer('选择受邀玩家（加入银行 '+bankId+'）');if(!uuid)return;
  api('POST','/api/enterprise/invite/send',{bankId:bankId,inviteeUuid:uuid}).then(function(d){
    if(d.message){toast(d.message,'ok');}else toast(d.error,'err');
  });
}

function viewBankPerms(bankId){
  document.getElementById('bankPermBankId').value=bankId;
  switchTab('bank-perms');
  loadBankPermList();
}

async function sendBankInvite(){
  var bankId=document.getElementById('inviteBankId2').value;
  var uuid=document.getElementById('inviteBankPlayerUuid').value;
  if(!bankId||!uuid){toast('请填写银行ID和玩家UUID','err');return;}
  var d=await api('POST','/api/enterprise/invite/send',{bankId:bankId,inviteeUuid:uuid});
  if(d.message){toast(d.message,'ok');}else toast(d.error,'err');
}

async function loadBankPermList(){
  var bankId=document.getElementById('bankPermBankId').value;
  if(!bankId){document.getElementById('bankPermListBody').innerHTML='<tr><td colspan="4" style="color:#666;">请先输入银行ID</td></tr>';return;}
  var d=await api('GET','/api/bank/permissions?bankId='+bankId);
  var t='';(d.permissions||[]).forEach(function(p){
    t+='<tr><td>'+p.bank_id+'</td><td>'+p.player_uuid+'</td><td>'+p.permission+'</td><td>'+p.granted_by+'</td></tr>';
  });
  document.getElementById('bankPermListBody').innerHTML=t||'<tr><td colspan="4" style="color:#666;">暂无权限</td></tr>';
}

async function setBankPermission(){
  var d=await api('POST','/api/bank/permissions/set',{
    bankId:document.getElementById('bankPermBankId').value,
    playerUuid:document.getElementById('bankPermPlayerUuid').value,
    permission:document.getElementById('bankPermPermission').value,
    enabled:document.getElementById('bankPermEnabled').value==='true'
  });
  if(d.message){toast(d.message,'ok');loadBankPermList();}else toast(d.error,'err');
}
async function createBank(){
  var d=await api('POST','/api/bank/create',{
    name:document.getElementById('bkName').value,
    ownerUuids:document.getElementById('bkOwners').value.split(',').map(function(s){return s.trim();}),
    initialCapital:parseFloat(document.getElementById('bkCapital').value)||0,
    type:document.getElementById('bkType').value
  });
  if(d.id){toast('银行创建成功: '+d.id,'ok');loadBankList();loadMacroData();if(KS_PREVIEW){setTimeout(function(){loadPlayerRankings();loadEntRankings();loadBankRankings();},0);}}else toast(d.error,'err');
}
async function setBankRate(bankId){
  var lr=prompt('贷款利率 (如 0.05 = 5%):',0.05);
  var dr=prompt('存款利率·每结算周期 (如 0.01 = 1%):',0.01);
  if(lr===null||dr===null)return;
  var d=await api('POST','/api/bank/rates/set',{bankId:bankId,loanRate:parseFloat(lr),depositRate:parseFloat(dr)});
  if(d.message){toast(d.message,'ok');loadBankList();}else toast(d.error,'err');
}
async function loadLoanList(){
  var d=await api('GET','/api/bank/loans');
  var t='';(d.loans||[]).forEach(function(l){
    t+='<tr><td>'+l.id+'</td><td>'+l.bank_id+'</td><td>'+l.borrower_uuid+'</td><td>'+fmt(l.principal)+'</td><td>'+fmt(l.remaining)+'</td><td>'+pct(l.interest_rate)+'</td><td><span class="badge '+(l.status==='ACTIVE'?'badge-active':'badge-closed')+'">'+l.status+'</span></td><td>'+(l.status==='ACTIVE'?'<span style="color:#777;">借款人还款</span>':'')+'</td></tr>';
  });
  document.getElementById('loanListBody').innerHTML=t||'<tr><td colspan="8" style="color:#666;">暂无贷款</td></tr>';
}
async function issueLoan(){
  var d=await api('POST','/api/bank/loan/issue',{
    bankId:document.getElementById('loanBankId').value,
    borrowerUuid:document.getElementById('loanBorrower').value,
    principal:parseFloat(document.getElementById('loanPrincipal').value)||0,
    termDays:parseInt(document.getElementById('loanTerm').value)||30
  });
  if(d.message){toast(d.message,'ok');loadLoanList();loadMacroData();if(KS_PREVIEW){setTimeout(function(){loadPlayerRankings();loadEntRankings();loadBankRankings();},0);}}else toast(d.error,'err');
}
async function cbInject(){
  var mode=document.getElementById('injectMode').value;
  var d=await api('POST','/api/bank/cb/inject',{
    bankId:document.getElementById('injectBankId').value,
    amount:parseFloat(document.getElementById('injectAmount').value)||0,
    mode:mode
  });
  var box=document.getElementById('injectMsg');
  if(d.message){toast(d.message,'ok');if(box)box.innerHTML='<span style="color:#4caf50;">✓ '+d.message+' (模式: '+mode+')</span>';loadMacroData();if(KS_PREVIEW){setTimeout(function(){loadPlayerRankings();loadEntRankings();loadBankRankings();},0);}}
  else{toast(d.error,'err');if(box)box.innerHTML='<span style="color:#f44;">✗ '+(d.error||'失败')+'</span>';}
}
async function loadCbLoans(){
  var bank=document.getElementById('cbLoanFilterBank').value.trim();
  var inc=document.getElementById('cbLoanIncludeRepaid').value;
  var url='/api/bank/cb/loans?includeRepaid='+inc;
  if(bank)url+='&bankId='+encodeURIComponent(bank);
  var d=await api('GET',url);
  var t='';(d.loans||[]).forEach(function(l){
    var due=l.dueAt?new Date(l.dueAt*1000).toLocaleDateString('zh-CN'):'—';
    var outstanding=l.outstanding!=null?l.outstanding:l.principal;
    var repaid=l.repaid;
    var act=repaid?'<span class="badge badge-active">已还清</span>':'<button class="btn btn-sm btn-danger" onclick="repayCbLoan(\''+l.id+'\')">全额还款</button>';
    t+='<tr><td style="font-size:10px;">'+l.id+'</td><td>'+(l.bankId||l.bank_id||'')+'</td><td>'+fmt(l.principal)+'</td><td>'+fmt(outstanding)+'</td><td>'+pct(l.interestRate!=null?l.interestRate:l.interest_rate)+'</td><td>'+(l.termDays!=null?l.termDays:l.term_days||'—')+'</td><td>'+due+'</td><td>'+act+'</td></tr>';
  });
  document.getElementById('cbLoanListBody').innerHTML=t||'<tr><td colspan="8" style="color:#666;">暂无央行贷款</td></tr>';
}
async function repayCbLoan(loanId){
  if(!confirm('确认由借款银行全额偿还这笔央行贷款？'))return;
  var d=await api('POST','/api/bank/cb/loan/repay',{id:loanId});
  if(d.message){toast(d.message,'ok');loadCbLoans();loadMacroData();if(KS_PREVIEW){setTimeout(function(){loadPlayerRankings();loadEntRankings();loadBankRankings();},0);}}else toast(d.error||'还款失败','err');
}

// ============ ENTERPRISE APIs ============
async function loadEntList(){
  var d=await api('GET','/api/enterprise/list');
  var t='';(d.enterprises||[]).forEach(function(e){
    t+='<tr><td>'+escapeHtml(e.id)+'</td><td>'+escapeHtml(e.name)+'</td><td><span class="badge '+(e.type==='STATE_OWNED'?'badge-central':'badge-active')+'">'+escapeHtml(e.type)+'</span></td><td>'+fmt(e.registered_capital)+'</td><td><a href="#" onclick="checkCorpBalance(\''+escapeAttr(e.id)+'\');return false" title="点击查看公户详情">'+fmt(e.corporate_balance||0)+'</a></td><td>'+fmt(e.employee_count)+'</td><td>'+escapeHtml(e.region||'')+'</td><td>'+escapeHtml(e.status)+' <button class="btn btn-sm" onclick="quickEntInvite(\''+escapeAttr(e.id)+'\')">邀请</button></td></tr>';
  });
  document.getElementById('entListBody').innerHTML=t||'<tr><td colspan="8" style="color:#666;">暂无企业</td></tr>';
}

async function checkCorpBalance(entId){
  var d=await api('GET','/api/enterprise/corporate/balance?enterpriseId='+entId);
  var msg='企业: '+entId+'\n银行: '+d.bankName+' ('+d.bankId+')\n公户余额: '+fmt(d.balance);
  var act=prompt(msg+'\n\n操作: 输入 "+金额" 存入 | 输入 "-金额" 提取 | 取消', '');
  if(!act)return;
  var amount=parseFloat(act);if(isNaN(amount)||amount===0)return;
  if(amount>0){
    var r=await api('POST','/api/enterprise/corporate/transfer',{enterpriseId:entId,direction:'TO_CORPORATE',amount:amount});
    if(r.message){toast(r.message,'ok');loadEntList();}else toast(r.error,'err');
  }else{
    var r=await api('POST','/api/enterprise/corporate/transfer',{enterpriseId:entId,direction:'TO_PLAYER',amount:-amount});
    if(r.message){toast(r.message,'ok');loadEntList();}else toast(r.error,'err');
  }
}

async function quickEntInvite(entId){
  var uuid=await ksPickPlayer('选择受邀玩家（加入企业 '+entId+'）');if(!uuid)return;
  api('POST','/api/enterprise/invite/send',{enterpriseId:entId,inviteeUuid:uuid}).then(function(d){
    if(d.message){toast(d.message,'ok');}else toast(d.error,'err');
  });
}
async function registerEnterprise(){
  var d=await api('POST','/api/enterprise/register',{
    name:document.getElementById('entName').value,
    ownerUuids:document.getElementById('entOwners').value.split(',').map(function(s){return s.trim();}),
    registeredCapital:parseFloat(document.getElementById('entCapital').value)||0,
    type:document.getElementById('entType').value,
    region:document.getElementById('entRegion').value
  });
  if(d.id){toast('企业注册成功: '+d.id,'ok');loadEntList();loadMacroData();if(KS_PREVIEW){setTimeout(function(){loadPlayerRankings();loadEntRankings();loadBankRankings();},0);}}
  else if(d.pendingId){toast(d.message||'合资确认已发起','ok');}
  else toast(d.error||'注册失败','err');
}
async function loadEconomicThresholds(){
  var d=await api('GET','/api/admin/economic-settings');
  if(d.enterpriseMinCapital!=null)document.getElementById('enterpriseMinCapital').value=d.enterpriseMinCapital;
  if(d.bankMinCapital!=null)document.getElementById('bankMinCapital').value=d.bankMinCapital;
  if(d.enterpriseMaxOwners!=null)document.getElementById('enterpriseMaxOwners').value=d.enterpriseMaxOwners;
  if(d.enterpriseMaxMembers!=null)document.getElementById('enterpriseMaxMembers').value=d.enterpriseMaxMembers;
}
async function saveEconomicThresholds(){
  var d=await api('POST','/api/admin/economic-settings',{enterpriseMinCapital:parseFloat(document.getElementById('enterpriseMinCapital').value),bankMinCapital:parseFloat(document.getElementById('bankMinCapital').value),enterpriseMaxOwners:parseInt(document.getElementById('enterpriseMaxOwners').value,10),enterpriseMaxMembers:parseInt(document.getElementById('enterpriseMaxMembers').value,10)});
  if(d.message)toast(d.message,'ok');else toast(d.error||'保存失败','err');
}
async function saveEnterpriseProfile(){
  var d=await api('POST','/api/admin/enterprise/edit',{enterpriseId:document.getElementById('editEnterpriseId').value,name:document.getElementById('editEnterpriseName').value,description:document.getElementById('editEnterpriseDescription').value,level:Number(document.getElementById('editEnterpriseLevel').value)||1,status:document.getElementById('editEnterpriseStatus').value});
  if(d.message){toast(d.message,'ok');loadEntList();}else toast(d.error||'保存失败','err');
}
async function dissolveEnterprise(){
  var d=await api('POST','/api/enterprise/dissolve',{
    enterpriseId:document.getElementById('dissolveEntId').value,
    requesterUuid:document.getElementById('dissolveOwner').value
  });
  if(d.message){toast(d.message,'ok');loadEntList();}else toast(d.error,'err');
}
async function loadProjectList(){
  var d=await api('GET','/api/enterprise/projects');
  var t='';(d.projects||[]).forEach(function(p){
    var dl=new Date(p.deadline*1000).toLocaleDateString('zh-CN');
    var isPrivate=p.publisher_type==='ENTERPRISE'||p.publisher_type==='PRIVATE';
    var actions='';
    if(p.status==='OPEN'){
      actions+='<button class="btn btn-sm btn-success" onclick="awardProjectById(\''+p.id+'\')" title="综合评分评标">📊</button> ';
      if(isPrivate) actions+='<button class="btn btn-sm btn-warn" onclick="showBidsForManual(\''+p.id+'\')" title="查看投标列表">📋</button>';
    }
    t+='<tr><td>'+escapeHtml(p.id)+'</td><td>'+escapeHtml(p.title)+'</td><td>'+fmt(p.budget)+'</td><td>'+fmt(p.bidCount||0)+'</td><td>'+dl+'</td><td><span class="badge '+(p.status==='OPEN'?'badge-active':p.status==='AWARDED'?'badge-pending':'badge-closed')+'">'+escapeHtml(p.status)+'</span></td><td>'+actions+'</td></tr>';
  });
  document.getElementById('projListBody').innerHTML=t||'<tr><td colspan="7" style="color:#666;">暂无项目</td></tr>';
}

async function showBidsForManual(projId){
  document.getElementById('manualAwardProjId').value=projId;
  var d=await api('GET','/api/enterprise/projects');
  // We already have the project list, just set the input
  var listHtml='<div style="max-height:200px;overflow-y:auto;border:1px solid #2a2a4a;border-radius:4px;padding:8px;margin-top:8px;">';
  // Query bids for this project from the bid submit endpoint info
  // Use the project list's bidCount to suggest manual selection
  listHtml+='<p style="color:#aaa;font-size:11px;">请在下方"私企自主挑选"区域输入投标ID</p>';
  listHtml+='<p style="color:#ff9800;font-size:11px;">提示: 投标ID可通过审计日志或查看评标返回的score_details获取</p>';
  listHtml+='</div>';
  toast('已设置项目ID: '+projId+'，请输入要指定的投标ID后点击"自主指定中标"','info');
}

async function manualAward(){
  var bidId=document.getElementById('manualAwardBidId').value;
  var projId=document.getElementById('manualAwardProjId').value;
  if(!bidId||!projId){toast('请填写投标ID和项目ID','err');return;}
  var d=await api('POST','/api/enterprise/project/award',{projectId:projId,bidId:bidId});
  if(d.id){toast('手动指定中标: '+d.id+' | 模式: '+d.award_mode,'ok');loadProjectList();}else toast(d.error,'err');
}
async function publishProject(){
  var publisherType=document.getElementById('projPubType').value;
  var publisherRef=publisherType==='OFFICIAL'?'OFFICIAL':document.getElementById('projPublisher').value.trim();
  var prepaymentRatio=publisherType==='OFFICIAL'?0:Number(document.getElementById('projPrepay').value);
  var d=await api('POST','/api/enterprise/project/publish',{
    title:document.getElementById('projTitle').value,
    publisherRef:publisherRef,
    publisherType:publisherType,
    budget:parseFloat(document.getElementById('projBudget').value)||0,
    prepaymentRatio:Number.isFinite(prepaymentRatio)?prepaymentRatio:0,
    penaltyRatio:parseFloat(document.getElementById('projPenalty').value)||0.15,
    deadline:Math.floor(Date.now()/1000)+(parseInt(document.getElementById('projDeadline').value)||7)*86400,
    location:document.getElementById('projLocation').value.trim(),allowSubcontract:true,allowConsortium:true
  });
  if(d.id){toast('项目发布成功: '+d.id,'ok');loadProjectList();}else toast(d.error,'err');
}
function updateProjectPublisherMode(){
  var enterprise=document.getElementById('projPubType').value==='ENTERPRISE';
  var label=document.getElementById('projPublisherLabel');
  label.childNodes[0].nodeValue=enterprise?'企业ID':'发布主体';
  document.getElementById('projPublisher').placeholder=enterprise?'企业ID':'官方项目无需填写';
  if(!enterprise){document.getElementById('projPublisher').value='';document.getElementById('projPrepay').value='0';}
}
function toggleBidderInput(){
  var type=document.getElementById('bidBidderType').value;
  document.getElementById('bidEntLabel').style.display=type==='ENTERPRISE'?'':'none';
  document.getElementById('bidPlayerLabel').style.display=type==='PLAYER'?'':'none';
}
function toggleSupplyBidderInput(){
  var type=document.getElementById('supplyBidderType').value;
  document.getElementById('supplyEntLabel').style.display=type==='ENTERPRISE'?'':'none';
  document.getElementById('supplyPlayerLabel').style.display=type==='PLAYER'?'':'none';
}
async function submitBid(){
  var bidderType=document.getElementById('bidBidderType').value;
  var body={
    projectId:document.getElementById('bidProjectId').value,
    bidderType:bidderType,
    bidAmount:parseFloat(document.getElementById('bidAmount').value)||0
  };
  if(bidderType==='ENTERPRISE'){
    body.enterpriseId=document.getElementById('bidEntId').value;
  }else{
    body.bidderUuid=document.getElementById('bidPlayerUuid').value;
  }
  var d=await api('POST','/api/enterprise/bid/submit',body);
  if(d.id){toast('投标成功: '+d.id+' ('+bidderType+')','ok');loadProjectList();}else toast(d.error,'err');
}
async function awardProject(){awardProjectById(document.getElementById('awardProjectId').value);}
async function awardProjectById(pid){
  var d=await api('POST','/api/enterprise/project/award',{projectId:pid});
  if(d.id){toast('中标: '+d.bidder_type+' | 模式: '+d.award_mode+' | 金额: '+fmt(d.bid_amount),'ok');loadProjectList();}else toast(d.error,'err');
}
async function loadInviteList(){
  var d=await api('GET','/api/enterprise/invites');
  var t='';(d.invites||[]).forEach(function(i){
    t+='<tr><td>'+i.id+'</td><td>'+(i.enterprise_id||i.bank_id)+'</td><td>'+i.inviter_uuid+'</td><td>'+i.invitee_uuid+'</td><td><span class="badge '+(i.status==='PENDING'?'badge-pending':i.status==='ACCEPT'?'badge-active':'badge-closed')+'">'+i.status+'</span></td><td>'+new Date(i.created_at*1000).toLocaleString('zh-CN')+'</td></tr>';
  });
  document.getElementById('inviteListBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无邀请</td></tr>';
}
async function sendInvite(){
  var entId=document.getElementById('inviteEntId').value;
  var bankId=document.getElementById('inviteBankId').value;
  var body={inviteeUuid:document.getElementById('inviteeUuid').value};
  if(entId)body.enterpriseId=entId;
  if(bankId)body.bankId=bankId;
  var d=await api('POST','/api/enterprise/invite/send',body);
  if(d.message){toast(d.message,'ok');loadInviteList();}else toast(d.error,'err');
}
async function loadPermList(){
  var entId=document.getElementById('permEntId').value;
  if(!entId){document.getElementById('permListBody').innerHTML='<tr><td colspan="4" style="color:#666;">请先输入企业ID</td></tr>';return;}
  var d=await api('GET','/api/enterprise/permissions?enterpriseId='+entId);
  var t='';(d.permissions||[]).forEach(function(p){
    t+='<tr><td>'+p.enterprise_id+'</td><td>'+p.player_uuid+'</td><td>'+p.permission+'</td><td>'+p.granted_by+'</td></tr>';
  });
  document.getElementById('permListBody').innerHTML=t||'<tr><td colspan="4" style="color:#666;">暂无权限</td></tr>';
}
async function setPermission(){
  var d=await api('POST','/api/enterprise/permissions/set',{
    enterpriseId:document.getElementById('permEntId').value,
    playerUuid:document.getElementById('permPlayerUuid').value,
    permission:document.getElementById('permPermission').value
  });
  if(d.message){toast(d.message,'ok');loadPermList();}else toast(d.error,'err');
}
async function loadDivList(){
  var d=await api('GET','/api/enterprise/dividends');
  var t='';(d.dividends||[]).forEach(function(dv){
    t+='<tr><td>'+dv.id+'</td><td>'+dv.enterprise_id+'</td><td>'+fmt(dv.amount)+'</td><td>'+pct(dv.tax_rate)+'</td><td>'+fmt(dv.tax_paid)+'</td><td>'+new Date(dv.declared_at*1000).toLocaleString('zh-CN')+'</td></tr>';
  });
  document.getElementById('divListBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无分红</td></tr>';
}
async function declareDividend(){
  var d=await api('POST','/api/enterprise/dividend/declare',{
    enterpriseId:document.getElementById('divEntId').value,
    amount:parseFloat(document.getElementById('divAmount').value)||0
  });
  if(d.message){toast(d.message+'（税: '+fmt(d.taxPaid)+', 每人: '+fmt(d.perOwner)+'）','ok');loadDivList();loadEntList();}else toast(d.error,'err');
}

// ============ TAX APIs ============
async function loadTaxRates(){
  var d=await api('GET','/api/tax/rates');
  var base=d.base||{};
  var industry=d.industry||{};
  var cats=['MARKET_TRADE','OFFICIAL_TRADE','ENTERPRISE_TAX','BANK_INTEREST','PLAYER_TRANSFER','PENALTY_TAX','DIVIDEND_TAX'];
  var labels={
    MARKET_TRADE:'市场交易税（玩家间交易）',OFFICIAL_TRADE:'官方交易税（系统收售）',
    ENTERPRISE_TAX:'企业所得税',BANK_INTEREST:'银行利息税',PLAYER_TRANSFER:'玩家转账税',
    PENALTY_TAX:'税务罚款',DIVIDEND_TAX:'分红税'
  };
  var t='<table><tr><th>税种</th><th>当前税率</th><th>新税率</th><th>操作</th></tr>';
  cats.forEach(function(c){
    t+='<tr><td>'+labels[c]+'</td><td>'+pct(base[c])+'</td><td><input id="rate_'+c+'" type="number" step="0.001" min="0" max="1" style="width:80px;" placeholder="0-1"/></td><td><button class="btn btn-sm" onclick="setTaxRate(\''+c+'\')">更新通用</button></td></tr>';
  });
  t+='</table>';document.getElementById('taxRateForm').innerHTML=t;
  if(typeof ksKpiRow==='function')ksKpiRow('taxKpis',[
    {icon:'🛒',label:'市场交易税',value:pct(base.MARKET_TRADE)},
    {icon:'🏭',label:'企业所得税',value:pct(base.ENTERPRISE_TAX)},
    {icon:'💎',label:'分红税',value:pct(base.DIVIDEND_TAX)},
    {icon:'🏦',label:'银行利息税',value:pct(base.BANK_INTEREST)}
  ]);
  // 行业列表
  var indLabels={INDUSTRY:'工业',AGRICULTURE:'农业',REAL_ESTATE:'房地产',OTHER:'其他'};
  var s='';
  Object.keys(indLabels).forEach(function(k){
    s+='<div style="margin:6px 0;padding:6px 10px;background:#0a0a1a;border-radius:4px;">';
    s+='<b style="color:#00E5FF;">'+indLabels[k]+' ('+k+')</b>: ';
    var m=industry[k]||{};
    var inner=Object.keys(m).map(function(c){return labels[c]||c+'='+pct(m[c]);}).join(' / ');
    s+=inner||'<span style="color:#666;">使用通用税率</span>';
    s+='</div>';
  });
  document.getElementById('taxIndList').innerHTML=s;
  loadTransferTaxConfig();
  // 默认阶梯（第一次进来也刷一下）
  loadTaxBrackets();
}
async function setTaxRate(c){
  var v=document.getElementById('rate_'+c).value;var r=parseFloat(v);
  if(isNaN(r)||r<0||r>1){toast('税率必须在 0-1 范围内','err');return;}
  var d=await api('POST','/api/tax/rates/set',{category:c,rate:r,industry:''});
  if(d.message){toast(d.message,'ok');loadTaxRates();}else toast(d.error,'err');
}
async function loadTransferTaxConfig(){
  var d=await api('GET','/api/admin/transfer/config');
  if(d.error)return;
  document.getElementById('transferTaxFreeAmount').value=d.taxFreeAmount!=null?d.taxFreeAmount:'';
  document.getElementById('transferTaxSummary').textContent='当前转账税率 '+pct(d.taxRate)+'；仅超出免税额的部分计税';
}
async function saveTransferTaxConfig(){
  var amount=parseFloat(document.getElementById('transferTaxFreeAmount').value);
  if(!Number.isFinite(amount)||amount<0){toast('免税额必须是非负数字','err');return;}
  var d=await api('POST','/api/admin/transfer/config',{taxFreeAmount:amount});
  if(d.message){toast(d.message,'ok');loadTransferTaxConfig();}else toast(d.error||'保存失败','err');
}
function switchTaxSub(sub){
  document.querySelectorAll('.tax-sub').forEach(function(s){s.classList.remove('active');});
  document.querySelectorAll('#tab-tax-rates .inline-tab').forEach(function(t){t.classList.remove('active');});
  document.getElementById('tax-sub-'+sub).classList.add('active');
  if(event&&event.target)event.target.classList.add('active');
  if(sub==='brackets')loadTaxBrackets();
}
async function saveTaxIndRate(){
  var ind=document.getElementById('taxIndSelect').value;
  var cat=document.getElementById('taxIndCat').value;
  var rate=parseFloat(document.getElementById('taxIndRate').value);
  if(isNaN(rate)||rate<0||rate>1){toast('税率 0-1','err');return;}
  var d=await api('POST','/api/tax/rates/set',{category:cat,rate:rate,industry:ind});
  if(d.message){toast(d.message,'ok');loadTaxRates();}else toast(d.error,'err');
}
async function loadTaxBrackets(){
  var ind=document.getElementById('bracketInd').value;
  var d=await api('GET','/api/tax/brackets?industry='+encodeURIComponent(ind));
  var t='';(d.brackets||[]).forEach(function(b){
    t+='<tr><td>'+fmt(b.profitMin)+'</td><td>'+(b.profitMax>=1e15?'∞':fmt(b.profitMax))+'</td>';
    t+='<td>'+pct(b.rate)+'</td>';
    t+='<td><button class="btn btn-sm btn-danger" onclick="deleteTaxBracket(\''+b.id+'\')">删除</button></td></tr>';
  });
  document.getElementById('taxBracketBody').innerHTML=t||'<tr><td colspan="4" style="color:#666;">该行业无阶梯</td></tr>';
}
async function saveTaxBracket(){
  var ind=document.getElementById('bracketInd').value;
  var pmin=Number(document.getElementById('bracketPmin').value);
  var pmax=Number(document.getElementById('bracketPmax').value);
  var rate=parseFloat(document.getElementById('bracketRate').value);
  if(pmax<=pmin){toast('利润上限须大于下限','err');return;}
  if(isNaN(rate)||rate<0||rate>1){toast('税率 0-1','err');return;}
  var d=await api('POST','/api/tax/bracket/upsert',{industry:ind,scope:'ENTERPRISE_TAX',profitMin:pmin,profitMax:pmax,rate:rate});
  if(d.message){toast(d.message,'ok');loadTaxBrackets();}else toast(d.error,'err');
}
async function deleteTaxBracket(id){
  if(!confirm('确认删除阶梯？'))return;
  var d=await api('POST','/api/tax/bracket/delete',{id:id});
  if(d.message){toast(d.message,'ok');loadTaxBrackets();}else toast(d.error,'err');
}
async function runTaxCalc(){
  var ind=document.getElementById('calcInd').value;
  var profit=Number(document.getElementById('calcProfit').value);
  var d=await api('POST','/api/tax/bracket/calc',{industry:ind,profit:profit,scope:'ENTERPRISE_TAX'});
  var box=document.getElementById('taxCalcResult');
  if(d.error){box.innerHTML='<span style="color:#f44;">'+d.error+'</span>';return;}
  var fromStr=d.fromBracket?'<span style="color:#4caf50;">命中阶梯</span>':'<span style="color:#ff9800;">回退基础税率</span>';
  box.innerHTML='<div style="padding:10px;background:#0a0a1a;border-radius:6px;">'+
    '<div>行业: <b>'+ind+'</b> · 利润: <b>'+fmt(d.profit)+'</b></div>'+
    '<div>应用税率: <b style="color:#00E5FF;">'+pct(d.rate)+'</b> ('+fromStr+')</div>'+
    '<div style="margin-top:6px;font-size:14px;">应缴税额: <b style="color:#ff9800;">'+fmt(d.tax)+'</b></div>'+
    '</div>';
}
async function loadTaxRecords(){
  var d=await api('GET','/api/tax/records');
  var t='';(d.records||[]).forEach(function(r){
    t+='<tr><td>'+r.id+'</td><td>'+r.payer_name+'</td><td>'+r.category+'</td><td>'+fmt(r.base_amount)+'</td><td>'+pct(r.tax_rate)+'</td><td>'+fmt(r.tax_amount)+'</td><td>'+new Date(r.collected_at*1000).toLocaleString('zh-CN')+'</td></tr>';
  });
  document.getElementById('taxRecordBody').innerHTML=t||'<tr><td colspan="7" style="color:#666;">暂无记录</td></tr>';
}
async function loadPenalties(){
  var d=await api('GET','/api/tax/penalties');
  var t='';(d.penalties||[]).forEach(function(p){
    t+='<tr><td>'+escapeHtml(p.id)+'</td><td>'+escapeHtml(p.target_name)+'</td><td>'+escapeHtml(p.penalty_type)+'</td><td>'+fmt(p.penalty_amount)+'</td><td>'+escapeHtml(p.reason)+'</td><td><span class="badge '+(p.paid?'badge-active':'badge-pending')+'">'+(p.paid?'已缴':'未缴')+'</span></td></tr>';
  });
  document.getElementById('penaltyBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无罚单</td></tr>';
}
async function issuePenalty(){
  var d=await api('POST','/api/tax/penalty/issue',{
    targetUuid:document.getElementById('penTarget').value,
    targetName:document.getElementById('penName').value,
    penaltyType:document.getElementById('penType').value,
    baseAmount:parseFloat(document.getElementById('penBase').value)||0,
    reason:document.getElementById('penReason').value
  });
  if(d.id){toast('罚单已发出: '+d.id,'ok');loadPenalties();}else toast(d.error,'err');
}

// ============ PRICES ============
async function loadPrices(){
  if(allItems.length===0){
    var ids=await api('GET','/api/prices/items');allItems=ids.items||[];
  }
  var dp=await api('GET','/api/prices/official');
  (dp.prices||[]).forEach(function(p){officialPrices[p.material]=p;});
  renderPrices();
}
function renderPrices(filter){
  var q=(filter||'').toLowerCase();
  var h='<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:4px;">';
  allItems.forEach(function(item){
    if(q&&item.id.toLowerCase().indexOf(q)<0)return;
    var off=officialPrices[item.id]||{};
    h+='<div class="item-row" style="display:flex;align-items:center;gap:4px;padding:4px 8px;border-bottom:1px solid rgba(255,255,255,.08);font-size:12px;cursor:pointer;" title="点击查看材料档案" onclick="if(event.target.tagName!==\'INPUT\')openMaterialDrawer(\''+item.id+'\')">';
    h+='<span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="'+item.id+'">'+item.id.substring(item.id.lastIndexOf('_')+1)+'</span>';
    h+='<span style="color:#666;">收</span><input id="buy_'+item.id+'" value="'+(off.buyPrice||'')+'" style="width:70px;padding:3px 6px;text-align:right;" placeholder="收购"/>';
    h+='</div>';
  });
  h+='</div>';
  document.getElementById('pricesGrid').innerHTML=h||'<p style="color:#666;">无匹配物品</p>';
}
function filterPrices(){renderPrices(document.getElementById('priceSearch').value);}
async function saveAllPrices(){
  var prices=[];
  allItems.forEach(function(item){
    var buy=document.getElementById('buy_'+item.id);
    var buyV=buy?parseFloat(buy.value)||0:0;
    if(buyV>0)prices.push({material:item.id,buyPrice:buyV,category:item.category});
  });
  if(prices.length===0){toast('请至少填写一个价格','err');return;}
  var d=await api('POST','/api/prices/official/save',{prices:prices});
  if(d.message){toast(d.message,'ok');loadPrices();}else toast(d.error,'err');
}

// ============ MARKET SYSTEM ============
async function loadMarketOverview(){
  var d=await api('GET','/api/market/stats');
  var s='';
  s+='<div class="stat-card"><div class="stat-val">'+fmt(d.activeListings)+'</div><div class="stat-label">活跃挂单</div></div>';
  s+='<div class="stat-card"><div class="stat-val">'+fmt(d.storedItems)+'</div><div class="stat-label">暂存物品数</div></div>';
  s+='<div class="stat-card"><div class="stat-val">'+(d.vaultAvailable?'✅':'❌')+'</div><div class="stat-label">Vault 经济</div></div>';
  s+='<div class="stat-card"><div class="stat-val">'+(d.officialBuyEnabled?'开':'关')+'</div><div class="stat-label">官方收购</div></div>';
  document.getElementById('marketStats').innerHTML=s;
  // 价格表
  var t='';(d.prices||[]).forEach(function(p){
    t+='<tr><td>'+escapeHtml(p.material)+'</td><td>'+fmt(p.buyPrice)+'</td><td>'+fmt(p.marketAvg)+'</td></tr>';
  });
  document.getElementById('marketPriceBody').innerHTML=t||'<tr><td colspan="3" style="color:#666;">暂无官方收购物品</td></tr>';
  loadListings();
}
async function loadListings(){ loadAdminAllListings(); }
async function loadAdminAllListings(){
  var d=await api('GET','/api/admin/listings');
  var count=d.count||0;
  document.getElementById('listingCountBadge').textContent='共 '+count+' 条活跃挂单';
  var t='';window._listingsCache={};(d.listings||[]).forEach(function(l){window._listingsCache[l.id]=l;
    var label=l.listingAssetType==='PROPERTY'?'🏠 商品房':(l.chineseName||l.itemMaterial||'');
    var typeTag=l.listingAssetType==='PROPERTY'?'<span style="color:#ce93d8;">房产</span>'
      :(l.listingMode==='BARTER'?'<span style="color:#ffb74d;">换物</span>':'<span style="color:#81c784;">卖钱</span>');
    var time=new Date(l.createdAt*1000).toLocaleString();
    t+='<tr data-lid="'+escapeAttr(l.id)+'" style="cursor:pointer;">'
      +'<td>'+escapeHtml(l.sellerName||'?')+'<br><span style="color:#666;font-size:10px;">'+l.sellerUuid.substring(0,8)+'...</span></td>'
      +'<td>'+escapeHtml(label)+' '+typeTag+'</td>'
      +'<td>'+fmt(l.quantity)+'</td>'
      +'<td>'+fmt(l.unitPrice)+'</td>'
      +'<td>'+fmt(l.totalPrice)+'</td>'
      +'<td style="font-size:11px;color:#888;">'+time+'</td>'
      +'<td style="white-space:nowrap;">'
      +'<button type="button" class="btn btn-sm" data-listing-action="cancel" data-listing-id="'+escapeAttr(l.id)+'" data-seller-name="'+escapeAttr(l.sellerName||'?')+'" style="background:#c62828;font-size:11px;margin-right:4px;">强制撤单</button>'
      +'<button type="button" class="btn btn-sm" data-listing-action="destroy" data-listing-id="'+escapeAttr(l.id)+'" data-seller-name="'+escapeAttr(l.sellerName||'?')+'" style="background:#4a0000;font-size:11px;">销毁</button>'
      +'</td>'
      +'</tr>';
  });
  document.getElementById('listingBody').innerHTML=t||'<tr><td colspan="7" style="color:#666;">暂无活跃挂单</td></tr>';
  bindListingActions();
}
function bindListingActions(){
  if(document.documentElement.dataset.listingActionsBound==='1')return;
  document.documentElement.dataset.listingActionsBound='1';
  document.addEventListener('click',function(event){
    var button=event.target.closest('button[data-listing-action]');if(!button)return;
    event.preventDefault();event.stopPropagation();
    if(button.dataset.closeModal==='1')closeModal();
    var id=button.dataset.listingId||'';var seller=button.dataset.sellerName||'?';
    if(button.dataset.listingAction==='cancel')forceCancelListing(id,seller);
    else if(button.dataset.listingAction==='destroy')forceDestroyListing(id,seller);
  });
}
async function forceCancelListing(id,sellerName){
  if(!KS_PREVIEW&&!confirm('确定要强制撤销 '+sellerName+' 的挂单？物品将退回其暂存箱。'))return;
  var d=await api('POST','/api/admin/listings/force-cancel',{listingId:id});
  if(d.message){toast(d.message,'ok');loadAdminAllListings();}
  else toast(d.error||'撤单失败','err');
}
async function forceDestroyListing(id,sellerName){
  if(!KS_PREVIEW&&!confirm('确定要永久销毁 '+sellerName+' 的挂单？\n⚠️ 物品将被彻底删除，无法恢复！'))return;
  if(!KS_PREVIEW&&!confirm('再次确认：物品销毁后无法找回，继续？'))return;
  var d=await api('POST','/api/admin/listings/force-destroy',{listingId:id});
  if(d.message){toast(d.message,'ok');loadAdminAllListings();}
  else toast(d.error||'销毁失败','err');
}
async function forcePrice(){
  var mat=document.getElementById('fpMaterial').value.trim();
  var price=parseFloat(document.getElementById('fpPrice').value);
  if(!mat){toast('请填写物品材质','err');return;}
  if(isNaN(price)||price<0){toast('价格无效','err');return;}
  var d=await api('POST','/api/admin/force-price',{material:mat,price:price});
  if(d.message){toast(d.message,'ok');loadMarketOverview();}else toast(d.error||'设价失败','err');
}
async function refreshPrices(){
  var d=await api('POST','/api/admin/refresh-prices',{});
  if(d.message){toast(d.message,'ok');loadMarketOverview();}else toast(d.error||'刷新失败','err');
}
async function simulateTrade(){
  var mat=document.getElementById('stMaterial').value.trim();
  if(!mat){toast('请填写物品材质','err');return;}
  var body={material:mat,quantity:parseInt(document.getElementById('stQty').value)||1,type:document.getElementById('stType').value};
  var pv=document.getElementById('stPrice').value;
  if(pv!=='')body.price=parseFloat(pv);
  var d=await api('POST','/api/admin/simulate-trade',body);
  if(d.message){toast(d.message,'ok');}else toast(d.error||'模拟失败','err');
}
async function loadIdleItems(){
  var d=await api('GET','/api/admin/idle-items');
  var s='';
  s+='<div class="stat-card"><div class="stat-val">'+fmt(d.totalItems)+'</div><div class="stat-label">暂存物品总数</div></div>';
  document.getElementById('idleStats').innerHTML=s;
  document.getElementById('idleMsg').textContent=d.message||'';
}

// ============ MARKET PUBLIC INFO ============
var taxDesc={
  'MARKET_TRADE':'玩家市场挂单成交时收取',
  'PROPERTY_TRADE':'购买商品房时按成交价收取',
  'OFFICIAL_TRADE':'向官方出售物品时收取',
  'ENTERPRISE_SMALL':'小型企业年度所得税',
  'ENTERPRISE_MEDIUM':'中型企业年度所得税',
  'ENTERPRISE_LARGE':'大型企业年度所得税',
  'DIVIDEND_TAX':'企业向股东分红时收取',
  'BANK_INTEREST':'银行存款利息收入时收取',
  'PLAYER_TRANSFER':'玩家转账时仅对超过单笔免税额的部分收取',
  'TAX_PENALTY':'逾期未缴税款每日加收'
};
async function loadAdminPublicInfo(){
  var d=await api('GET','/api/eco/public-info');
  var tp='';
  (d.prices||[]).forEach(function(p){
    var hasMarket=p.marketAvg&&p.marketAvg>0;
    tp+='<tr>'
      +'<td><b>'+escapeHtml(p.chineseName)+'</b></td>'
      +'<td style="color:#888;font-size:11px;">'+escapeHtml(p.material.toLowerCase())+'</td>'
      +'<td style="color:#aaa;">'+fmt(p.basePrice)+'</td>'
      +'<td style="color:#4fc3f7;font-weight:bold;">'+fmt(p.buyPrice)+'</td>'
      +'<td style="color:'+(hasMarket?'#81c784':'#555')+'">'+(hasMarket?fmt(p.marketAvg):'暂无数据')+'</td>'
      +'</tr>';
  });
  document.getElementById('adminPubPriceBody').innerHTML=tp||'<tr><td colspan="5" style="color:#666;">暂无配置，请在「官方定价」页面设置物品基础价格</td></tr>';
  var tt='';
  (d.taxRates||[]).forEach(function(t){
    tt+='<tr>'
      +'<td><b>'+escapeHtml(t.chineseName)+'</b></td>'
      +'<td style="color:#ffb74d;font-weight:bold;font-size:13px;">'+escapeHtml(t.ratePercent)+'</td>'
      +'<td style="color:#888;font-size:11px;">'+(taxDesc[t.category]||'')+'</td>'
      +'</tr>';
  });
  document.getElementById('adminPubTaxBody').innerHTML=tt||'<tr><td colspan="3" style="color:#666;">暂无税率配置</td></tr>';
  // 行业企业税率矩阵
  var im='';
  (d.industryMatrix||[]).forEach(function(r){
    im+='<tr>'
      +'<td><b>'+r.industryName+'</b></td>'
      +'<td style="color:#ffb74d;font-weight:bold;">'+r.ENTERPRISE_TAXPercent+'</td>'
      +'<td style="color:#ce93d8;font-weight:bold;">'+r.DIVIDEND_TAXPercent+'</td>'
      +'<td style="color:#81c784;font-weight:bold;">'+r.MARKET_TRADEPercent+'</td>'
      +'</tr>';
  });
  document.getElementById('adminIndustryBody').innerHTML=im||'<tr><td colspan="4" style="color:#666;">暂无行业税率配置</td></tr>';
}

// ============ PLAYER SEARCH ============
async function searchPlayers(q){
  var d=await api('GET','/api/players/search?q='+encodeURIComponent(q));
  return d.players||[];
}

// ============ INIT + AUTO REFRESH ============
loadMacroData();if(KS_PREVIEW){setTimeout(function(){loadPlayerRankings();loadEntRankings();loadBankRankings();},0);}
setInterval(function(){
  if(document.getElementById('tab-macro').classList.contains('active'))loadMacroData();if(KS_PREVIEW){setTimeout(function(){loadPlayerRankings();loadEntRankings();loadBankRankings();},0);}
},10000);
// Refresh badge animation
var dots=0;
setInterval(function(){
  dots=(dots+1)%4;document.getElementById('refreshBadge').textContent='◉ SYS.SYNC '+'▮'.repeat(dots+1)+'▯'.repeat(3-dots);
},500);

// ============ RANKINGS ============
async function loadPlayerRankings(){
  var d=await api('GET','/api/rankings/players');
  var t='';(d.rankings||[]).forEach(function(p,i){
    t+='<tr><td>'+(i+1)+'</td><td>'+(p.name||'?')+'</td><td>'+p.uuid+'</td><td>'+fmt(p.balance)+'</td><td>'+(p.online?'🟢 在线':'⚫ 离线')+'</td></tr>';
  });
  document.getElementById('rankPlayerBody').innerHTML=t||'<tr><td colspan="5" style="color:#666;">暂无数据</td></tr>';
}
async function loadEntRankings(){
  var d=await api('GET','/api/rankings/enterprises');
  var t='';(d.rankings||[]).forEach(function(e,i){
    t+='<tr><td>'+(i+1)+'</td><td>'+escapeHtml(e.name||'')+(e.description?'<div style="font-size:10px;color:#888;">'+escapeHtml(e.description)+'</div>':'')+'</td><td>'+e.id+'</td><td>'+e.type+'</td><td>'+fmt(e.registered_capital)+'</td><td>'+fmt(e.current_assets)+'</td><td>'+fmt(e.player_count||0)+'</td></tr>';
  });
  document.getElementById('rankEntBody').innerHTML=t||'<tr><td colspan="7" style="color:#666;">暂无企业</td></tr>';
}
async function loadBankRankings(){
  var d=await api('GET','/api/rankings/banks');
  var t='';(d.rankings||[]).forEach(function(b,i){
    t+='<tr><td>'+(i+1)+'</td><td>'+escapeHtml(b.name||'')+'</td><td>'+escapeHtml(b.id||'')+'</td><td>'+escapeHtml(b.type||'')+'</td><td>'+fmt(b.total_assets)+'</td><td>'+fmt(b.net_assets)+'</td><td>'+fmt(b.deposit_volume)+'</td><td>'+fmt(b.served_enterprises)+'</td><td>'+pct(b.badDebtRate)+'</td></tr>';
  });
  document.getElementById('rankBankBody').innerHTML=t||'<tr><td colspan="9" style="color:#666;">暂无银行</td></tr>';
}

// ============ PROCUREMENT ============
async function loadProcurementList(){
  var d=await api('GET','/api/enterprise/procurements');
  var t='';(d.procurements||[]).forEach(function(p){
    t+='<tr><td>'+escapeHtml(p.id)+'</td><td>'+escapeHtml(p.enterprise_id)+'</td><td>'+escapeHtml(p.title)+'</td><td>'+fmt(p.budget)+'</td><td>'+fmt(p.bidCount||0)+'</td><td><span class="badge '+(p.status==='OPEN'?'badge-active':p.status==='AWARDED'?'badge-pending':'badge-closed')+'">'+escapeHtml(p.status)+'</span></td></tr>';
  });
  document.getElementById('procListBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无采购</td></tr>';
}
async function publishProcurement(){
  var d=await api('POST','/api/enterprise/procurement/publish',{
    enterpriseId:document.getElementById('procEntId').value,
    title:document.getElementById('procTitle').value,
    itemDesc:document.getElementById('procDesc').value,
    quantity:parseInt(document.getElementById('procQty').value)||1,
    budget:parseFloat(document.getElementById('procBudget').value)||0
  });
  if(d.id){toast('采购发布: '+d.id,'ok');loadProcurementList();}else toast(d.error,'err');
}
async function submitSupplyBid(){
  var bType=document.getElementById('supplyBidderType').value;
  var body={
    procurementId:document.getElementById('supplyProcId').value,
    bidderType:bType,
    unitPrice:parseFloat(document.getElementById('supplyUnitPrice').value)||0
  };
  if(bType==='ENTERPRISE')body.enterpriseId=document.getElementById('supplyEntId').value;
  else body.bidderUuid=document.getElementById('supplyPlayerUuid').value;
  var d=await api('POST','/api/enterprise/procurement/bid',body);
  if(d.id){toast('供应投标: '+d.id,'ok');loadProcurementList();}else toast(d.error,'err');
}
async function awardProcurement(){
  var body={procurementId:document.getElementById('awardProcId').value};
  var bidId=document.getElementById('awardProcBidId').value;
  if(bidId)body.bidId=bidId;
  var d=await api('POST','/api/enterprise/procurement/award',body);
  if(d.message){toast(d.message,'ok');loadProcurementList();}else toast(d.error,'err');
}

// ============ AUDIT LOG ============
var ACTION_LABELS={
  'BANK_CREATE':'🏦 创建银行','LOAN_ISSUE':'💰 发放贷款','CB_RATES_SET':'🏛 央行调息',
  'CB_INJECT':'💉 央行注资','ENTERPRISE_REGISTER':'🏢 注册企业','BID_SUBMIT':'🎯 投标',
  'PROJECT_AWARD':'🏆 评标','DIVIDEND_DECLARE':'💸 分红','INVITE_SEND':'📨 邀请',
  'PENALTY_ISSUE':'⚠ 罚单','TAX_RATE_SET':'📊 税率调整',
  'ENTERPRISE_PERMISSION_SET':'🔑 企业权限','BANK_PERMISSION_SET':'🔑 银行权限',
  'BANK_RATE_SET':'🏦 银行利率'
};
async function loadAuditLog(){
  var action=document.getElementById('auditAction').value;
  var limit=document.getElementById('auditLimit').value;
  var url='/api/audit/log?limit='+limit;if(action)url+='&action='+action;
  var d=await api('GET',url);
  var t='';(d.logs||[]).forEach(function(l){
    var dt=new Date(l.created_at*1000).toLocaleString('zh-CN');
    t+='<tr><td>'+dt+'</td><td>'+(ACTION_LABELS[l.action]||l.action)+'</td><td>'+(l.player_name||l.player_uuid)+'</td><td>'+(l.target_type||'')+':'+(l.target_id||'')+'</td><td style="font-size:11px;color:#aaa;">'+(l.details||'')+'</td></tr>';
  });
  document.getElementById('auditLogBody').innerHTML=t||'<tr><td colspan="5" style="color:#666;">暂无操作记录</td></tr>';
}

// ============ MARKET BANS ============
async function loadBans(){
  var d=await api('GET','/api/admin/bans');
  var t='';(d.bans||[]).forEach(function(b){
    var exp=b.expiresAt>0?new Date(b.expiresAt*1000).toLocaleString('zh-CN'):'永久';
    var dt=b.createdAt?new Date(b.createdAt*1000).toLocaleString('zh-CN'):'';
    var typeLabel={'LISTING':'禁止上架','SELL_TO_OFFICIAL':'禁止官方兑换','ALL_MARKET':'全部禁止'}[b.banType]||b.banType;
    var active=b.active;
    t+='<tr style="'+(active?'':'opacity:0.5;')+'">'
      +'<td>'+escapeHtml(b.playerName||'')+'<br><span style="color:#666;font-size:10px;">'+((b.playerUuid||'').substring(0,8))+'...</span></td>'
      +'<td><span style="color:#ff8a80;">'+typeLabel+'</span></td>'
      +'<td style="font-size:11px;color:#aaa;">'+escapeHtml(b.reason||'')+'</td>'
      +'<td style="font-size:11px;">'+(b.expiresAt>0?'<span style="color:'+(active?'#ff9800':'#555')+';">'+exp+(active?'':' (已过期)')+'</span>':'<span style="color:#f44;">永久</span>')+'</td>'
      +'<td style="font-size:11px;color:#888;">'+escapeHtml(b.createdBy||'?')+'<br>'+dt+'</td>'
      +'<td>'+(active?'<button class="btn btn-sm" style="background:#1a3a1a;" onclick="removeBan(\''+b.id+'\',\''+escapeAttr(b.playerName||'')+'\')">提前解封</button>':'<span style="color:#555;font-size:11px;">已失效</span>')+'</td>'
      +'</tr>';
  });
  document.getElementById('bansBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无禁令</td></tr>';
}
async function addBan(){
  var uuid=document.getElementById('banPlayerUuid').value.trim();
  var name=document.getElementById('banPlayerName').value.trim();
  var type=document.getElementById('banType').value;
  var dur=parseInt(document.getElementById('banDuration').value)||0;
  var reason=document.getElementById('banReason').value.trim();
  if(!uuid){toast('请输入或选择玩家 UUID','err');return;}
  var d=await api('POST','/api/admin/bans/add',{playerUuid:uuid,playerName:name||uuid,banType:type,reason:reason,durationHours:dur});
  if(d.message){toast(d.message,'ok');loadBans();document.getElementById('banReason').value='';}
  else toast(d.error||'添加禁令失败','err');
}
async function removeBan(banId,playerName){
  if(!confirm('确定要解除 '+playerName+' 的禁令？'))return;
  var d=await api('POST','/api/admin/bans/remove',{banId:banId});
  if(d.message){toast(d.message,'ok');loadBans();}
  else toast(d.error||'解禁失败','err');
}

// ============ PRICE VOLATILITY ============
var lastVolatilitySnapshot=null;
var trendLabel={'UP':'<span style="color:#81c784;">↑ 涨</span>','DOWN':'<span style="color:#e57373;">↓ 跌</span>','FLAT':'<span style="color:#888;">→ 平</span>'};
async function loadPriceVolatility(){
  var d=await api('GET','/api/admin/price-volatility');
  lastVolatilitySnapshot=d;
  document.getElementById('volEnabled').value=String(!!d.enabled);
  document.getElementById('volMaxFluctuation').value=Math.round((d.maxFluctuation||0)*100);
  document.getElementById('volRefreshMinutes').value=d.priceRefreshMinutes||60;
  document.getElementById('volTestMode').value=String(!!d.testModeEnabled);
  var cards=(d.items||[]).map(function(it){
    var pressure=it.supplyPressure||0;
    var pressureText=pressure>0.01?(pressure*100).toFixed(1)+'% 供过于求':pressure<-0.01?(-pressure*100).toFixed(1)+'% 供不应求':'正常';
    return ksCard({
      title:it.material,badge:String(trendLabel[it.trend]||it.trend||''),badgeCls:'cyan',
      fields:[['基础价',fmt(it.basePrice)],['当前收购价',fmt(it.buyPrice)],['随机漂移',(it.driftValue*100).toFixed(1)+'%'],['大手导向',it.trendBias!==0?(it.trendBias*100).toFixed(0)+'%':'无'],['供需压力',pressureText]],
      onclick:"openMaterialDrawer('"+escapeAttr(it.material)+"')",
      actions:[{label:'📂 材料档案',onclick:"openMaterialDrawer('"+escapeAttr(it.material)+"')"}]
    });
  });
  ksGrid('volGrid',cards,'暂无物品价格数据');
  if(typeof ksCardSearch==='function')ksCardSearch('volSearch','volGrid');
}
function MaterialNamesFallback(mat){
  return mat; // 物品中文名已在游戏内/玩家端展示，管理端表格直接用材质名，避免额外请求
}
function ensureVolMatOptions(){
  if(!lastVolatilitySnapshot){loadPriceVolatility().then(fillVolMatOptions);}
  else fillVolMatOptions();
}
function fillVolMatOptions(){
  var h='';(lastVolatilitySnapshot.items||[]).forEach(function(it){h+='<option value="'+it.material+'">';});
  document.getElementById('volMatOptions').innerHTML=h;
}
async function saveVolatilitySettings(){
  var enabled=document.getElementById('volEnabled').value==='true';
  var maxFluctuation=(parseFloat(document.getElementById('volMaxFluctuation').value)||0)/100;
  var refreshMinutes=parseInt(document.getElementById('volRefreshMinutes').value)||60;
  var testMode=document.getElementById('volTestMode').value==='true';
  var d=await api('POST','/api/admin/price-volatility/settings',
    {enabled:enabled,maxFluctuation:maxFluctuation,refreshMinutes:refreshMinutes,testMode:testMode});
  if(d.message){toast(d.message,'ok');loadPriceVolatility();}
  else toast(d.error||'保存失败','err');
}
async function applyVolatilityBias(){
  var material=document.getElementById('volMaterial').value.trim().toUpperCase();
  var bias=(parseFloat(document.getElementById('volBias').value)||0)/100;
  if(!material){toast('请输入物品材质','err');return;}
  var d=await api('POST','/api/admin/price-volatility/bias',{material:material,trendBias:bias});
  if(d.message){toast(d.message,'ok');loadPriceVolatility();}
  else toast(d.error||'设置失败','err');
}
async function clearVolatilityBias(){
  var material=document.getElementById('volMaterial').value.trim().toUpperCase();
  if(!material){toast('请输入物品材质','err');return;}
  document.getElementById('volBias').value=0;
  var d=await api('POST','/api/admin/price-volatility/bias',{material:material,trendBias:0});
  if(d.message){toast('导向已清除','ok');loadPriceVolatility();}
  else toast(d.error||'清除失败','err');
}

// ============ BLINDBOX POOLS ============
async function loadBbPoolList(){
  var d=await api('GET','/api/blindbox/pools');
  var maxLevelInput=document.getElementById('bbPoolMinEnterpriseLevel');if(maxLevelInput)maxLevelInput.max=d.maxEnterpriseLevel||10;
  var t='';(d.pools||[]).forEach(function(p){
    t+='<tr>';
    t+='<td>'+p.id+'</td><td>'+p.name+'</td><td>'+p.poolType+'</td>';
    t+='<td>'+fmt(p.price)+'</td><td>'+(p.pityRulesText||p.pityMax)+'</td><td>'+fmt(p.minEnterpriseLevel||1)+'</td>';
    t+='<td>'+(p.enabled?'<span class="badge badge-active">启用</span>':'<span class="badge badge-closed">停用</span>')+'</td>';
    t+='<td>'+p.lootCount+'</td><td>'+p.pullCount+'</td>';
    t+='<td><button class="btn btn-sm" onclick="editBbPool(\''+p.id+'\')">编辑</button> ';
    t+='<button class="btn btn-sm btn-danger" onclick="deleteBbPool(\''+p.id+'\')">删除</button></td>';
    t+='</tr>';
  });
  document.getElementById('bbPoolListBody').innerHTML=t||'<tr><td colspan="10" style="color:#666;">暂无卡池</td></tr>';
}
function editBbPool(id){
  api('GET','/api/blindbox/pools').then(function(d){
    var p=(d.pools||[]).find(function(x){return x.id===id;});
    if(!p)return;
    document.getElementById('bbPoolId').value=p.id;
    document.getElementById('bbPoolName').value=p.name;
    document.getElementById('bbPoolType').value=p.poolType;
    document.getElementById('bbPoolPrice').value=p.price;
    document.getElementById('bbPoolPity').value=p.pityMax;
    document.getElementById('bbPoolPityRules').value=p.pityRules||'';
    document.getElementById('bbPoolEnabled').value=p.enabled?'true':'false';
    document.getElementById('bbPoolLimitedOnly').checked=!!p.limitedOnly;
    document.getElementById('bbPoolOwnerType').value=p.ownerType||'PUBLIC';
    document.getElementById('bbPoolMinEnterpriseLevel').value=p.minEnterpriseLevel||1;
    document.getElementById('bbPoolAllowedCat').value=p.allowedCategories||'';
    document.getElementById('bbPoolAllowedInd').value=p.allowedIndustries||'';
    document.getElementById('bbPoolRequiredLand').value=p.requiredLandZoneTypes||'';
    document.getElementById('bbPoolDesc').value=p.description||'';
  });
}
async function saveBbPool(){
  var id=document.getElementById('bbPoolId').value.trim();
  var name=document.getElementById('bbPoolName').value.trim();
  if(!id||!name){toast('ID 和名称必填','err');return;}
  var body={
    id:id,name:name,
    poolType:document.getElementById('bbPoolType').value,
    price:Number(document.getElementById('bbPoolPrice').value),
    pityMax:Number(document.getElementById('bbPoolPity').value),
    pityRules:document.getElementById('bbPoolPityRules').value,
    enabled:document.getElementById('bbPoolEnabled').value==='true',
    limitedOnly:document.getElementById('bbPoolLimitedOnly').checked,
    ownerType:document.getElementById('bbPoolOwnerType').value,
    minEnterpriseLevel:Number(document.getElementById('bbPoolMinEnterpriseLevel').value)||1,
    allowedCategories:document.getElementById('bbPoolAllowedCat').value,
    allowedIndustries:document.getElementById('bbPoolAllowedInd').value,
    requiredLandZoneTypes:document.getElementById('bbPoolRequiredLand').value,
    description:document.getElementById('bbPoolDesc').value
  };
  var d=await api('POST','/api/admin/blindbox/pool',body);
  if(d.message){toast(d.message,'ok');loadBbPoolList();}
  else toast(d.error||'保存失败','err');
}
async function deleteBbPool(id){
  if(!confirm('确认删除卡池 '+id+' 及其所有战利品？'))return;
  var d=await api('POST','/api/admin/blindbox/pool/delete',{id:id});
  if(d.message){toast(d.message,'ok');loadBbPoolList();}
  else toast(d.error||'删除失败','err');
}

// ============ BLINDBOX LOOT ============
async function loadBbPoolOptions(){
  var d=await api('GET','/api/blindbox/pools');
  var el=document.getElementById('bbLootPoolId');
  var cur=el.value;
  var opts='<option value="">--选择池--</option>';
  (d.pools||[]).forEach(function(p){
    opts+='<option value="'+p.id+'"'+(p.id===cur?' selected':'')+'>'+p.name+' ('+p.id+')</option>';
  });
  el.outerHTML='<select id="bbLootPoolId">'+opts+'</select>';
}
async function loadBbLoot(){
  var el=document.getElementById('bbLootPoolId');
  var poolId=el.value;
  if(!poolId){toast('请先选池','err');return;}
  var d=await api('GET','/api/blindbox/loot?poolId='+encodeURIComponent(poolId));
  var t='';(d.loot||[]).forEach(function(l){
    t+='<tr>';
    t+='<td style="font-size:10px;color:#888;">'+l.id.substr(0,8)+'</td>';
    t+='<td>'+l.itemMaterial+'</td>';
    t+='<td>'+(l.displayName||'')+'</td>';
    t+='<td>'+l.weight+'</td>';
    t+='<td style="color:'+bbRarityColorToCss(l.rarity)+';">'+l.rarity+'</td>';
    t+='<td>'+l.quantity+'</td>';
    t+='<td><button class="btn btn-sm btn-danger" onclick="deleteBbLoot(\''+l.id+'\')">删除</button></td>';
    t+='</tr>';
  });
  document.getElementById('bbLootListBody').innerHTML=t||'<tr><td colspan="7" style="color:#666;">该池无战利品</td></tr>';
}
async function addBbLoot(){
  var el=document.getElementById('bbLootPoolId');
  var poolId=el.value;
  if(!poolId){toast('请先选池','err');return;}
  var mat=document.getElementById('bbLootMat').value.trim();
  if(!mat){toast('请填材质','err');return;}
  var body={
    poolId:poolId,itemMaterial:mat,
    displayName:document.getElementById('bbLootName').value,
    weight:Number(document.getElementById('bbLootWeight').value),
    rarity:document.getElementById('bbLootRarity').value,
    quantity:Number(document.getElementById('bbLootQty').value)
  };
  var d=await api('POST','/api/admin/blindbox/loot',body);
  if(d.message){toast(d.message,'ok');loadBbLoot();}
  else toast(d.error||'添加失败','err');
}
async function deleteBbLoot(id){
  if(!confirm('确认删除战利品？'))return;
  var d=await api('POST','/api/admin/blindbox/loot/delete',{id:id});
  if(d.message){toast(d.message,'ok');loadBbLoot();}
  else toast(d.error||'删除失败','err');
}

// ============ BLINDBOX STATS ============
async function loadBbStats(){
  var d=await api('GET','/api/blindbox/pools');
  var t='';(d.pools||[]).forEach(function(p){
    t+='<tr><td>'+p.name+' ('+p.id+')</td><td>'+p.poolType+'</td><td>'+fmt(p.price)+'</td>';
    t+='<td>'+p.pullCount+'</td><td>'+p.lootCount+'</td><td>'+(p.pityRulesText||p.pityMax)+'</td></tr>';
  });
  document.getElementById('bbStatsBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无数据</td></tr>';
}

// ============ 原生地产地图组件（只拉 ksHWP 瓦片图层，区域/地块自绘） ============
// 不再嵌 ksHWP 整页（避免带入标注/玩家追踪/渲染工具等无关 UI）。
// 仅 GET /kSHWP/api/tile 拿地形瓦片；ksHWP 不在线则回退纯网格。
function KsReMap(canvasId, opts){
  opts=opts||{};
  var canvas=document.getElementById(canvasId);
  if(!canvas) return null;
  var ctx=canvas.getContext('2d');
  var TS=256; // 瓦片像素尺寸，与 ksHWP 一致
  var st={world:opts.world||'world',zoom:4,panX:0,panZ:0,
          dragging:false,selecting:false,lastX:0,lastY:0,
          sX1:0,sZ1:0,sX2:0,sZ2:0,zones:[],plots:[],sel:null,tilesOk:true};
  var tiles={}; // 'zoom:tx:tz' -> Image | 'loading' | 'fail'
  function wpp(){return TS/(16*st.zoom);}            // pixels per world-block
  function w2p(wx,wz){var b=wpp();return {px:wx*b+st.panX, pz:wz*b+st.panZ};}
  function p2w(px,pz){var b=16*st.zoom/TS;return {wx:(px-st.panX)*b, wz:(pz-st.panZ)*b};}
  function mouse(e){var r=canvas.getBoundingClientRect();return {x:e.clientX-r.left,y:e.clientY-r.top};}

  function resize(){canvas.width=canvas.clientWidth;canvas.height=canvas.clientHeight;draw();}
  function setStatus(t){var el=document.getElementById(opts.statusId);if(el)el.textContent=t;}

  function draw(){
    ctx.fillStyle='#0a0a1a';ctx.fillRect(0,0,canvas.width,canvas.height);
    // 地形瓦片
    var minTX=Math.floor(-st.panX/TS)-1,minTZ=Math.floor(-st.panZ/TS)-1;
    var maxTX=Math.floor((canvas.width-st.panX)/TS)+1,maxTZ=Math.floor((canvas.height-st.panZ)/TS)+1;
    for(var tz=minTZ;tz<=maxTZ;tz++)for(var tx=minTX;tx<=maxTX;tx++){
      var k=st.zoom+':'+tx+':'+tz, px=tx*TS+st.panX, pz=tz*TS+st.panZ;
      var c=tiles[k];
      if(c&&c!=='loading'&&c!=='fail'){ctx.drawImage(c,px,pz,TS,TS);}
      else{ctx.fillStyle='#10101e';ctx.fillRect(px,pz,TS-1,TS-1);}
      if(!c&&st.tilesOk)loadTile(k,tx,tz);
    }
    // 16-block 网格（瓦片回退时也有参照）
    ctx.strokeStyle='#ffffff10';ctx.lineWidth=0.5;
    var b=wpp(), step=16*b;
    if(step>=8){
      var ox=((st.panX%step)+step)%step, oz=((st.panZ%step)+step)%step;
      for(var x=ox;x<canvas.width;x+=step){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,canvas.height);ctx.stroke();}
      for(var y=oz;y<canvas.height;y+=step){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(canvas.width,y);ctx.stroke();}
    }
    // 已建区域
    st.zones.forEach(function(z){
      var p1=w2p(Math.min(z.x1,z.x2),Math.min(z.z1,z.z2));
      var p2=w2p(Math.max(z.x1,z.x2),Math.max(z.z1,z.z2));
      var col=z.status==='FOR_SALE'?'#4caf50':z.status==='SOLD'?'#f44336':'#ff9800';
      ctx.fillStyle=col+'22';ctx.fillRect(p1.px,p1.pz,p2.px-p1.px,p2.pz-p1.pz);
      ctx.strokeStyle=col;ctx.lineWidth=1.5;ctx.strokeRect(p1.px,p1.pz,p2.px-p1.px,p2.pz-p1.pz);
      ctx.fillStyle=col;ctx.font='bold 11px sans-serif';
      ctx.fillText((z.name||z.id)+' ('+z.status+')',p1.px+4,p1.pz+14);
    });
    // 已售地块
    st.plots.forEach(function(p){
      var p1=w2p(Math.min(p.x1,p.x2),Math.min(p.z1,p.z2));
      var p2=w2p(Math.max(p.x1,p.x2),Math.max(p.z1,p.z2));
      ctx.fillStyle='#FF3DF233';ctx.fillRect(p1.px,p1.pz,p2.px-p1.px,p2.pz-p1.pz);
      ctx.strokeStyle='#FF3DF2';ctx.lineWidth=1;ctx.setLineDash([3,2]);
      ctx.strokeRect(p1.px,p1.pz,p2.px-p1.px,p2.pz-p1.pz);ctx.setLineDash([]);
    });
    // 当前框选
    if(st.sel){
      var q1=w2p(Math.min(st.sel.x1,st.sel.x2),Math.min(st.sel.z1,st.sel.z2));
      var q2=w2p(Math.max(st.sel.x1,st.sel.x2),Math.max(st.sel.z1,st.sel.z2));
      ctx.fillStyle='#ffff0022';ctx.fillRect(q1.px,q1.pz,q2.px-q1.px,q2.pz-q1.pz);
      ctx.strokeStyle='#ff0';ctx.lineWidth=2;ctx.setLineDash([6,3]);
      ctx.strokeRect(q1.px,q1.pz,q2.px-q1.px,q2.pz-q1.pz);ctx.setLineDash([]);
    }
  }
  function loadTile(k,tx,tz){
    tiles[k]='loading';
    fetch('/kSHWP/api/tile?world='+encodeURIComponent(st.world)+'&x='+tx+'&z='+tz+'&zoom='+st.zoom)
      .then(function(r){if(!r.ok)throw 0;return r.json();})
      .then(function(d){
        if(d&&d.tile){var img=new Image();img.onload=function(){tiles[k]=img;draw();};img.src='data:image/png;base64,'+d.tile;}
        else{tiles[k]='fail';}
      })
      .catch(function(){tiles[k]='fail';if(st.tilesOk){st.tilesOk=false;setStatus('⚠ ksHWP 地图未安装，仅显示网格');draw();}});
  }
  // 居中到已有区域（或原点）
  function center(){
    var z=st.zones[0];
    var cx=z?((z.x1+z.x2)/2):0, cz=z?((z.z1+z.z2)/2):0;
    st.panX=canvas.width/2-cx*wpp(); st.panZ=canvas.height/2-cz*wpp(); draw();
  }
  // --- 交互 ---
  canvas.addEventListener('mousedown',function(e){
    var m=mouse(e);
    if(e.shiftKey){var w=p2w(m.x,m.y);st.selecting=true;st.sX1=Math.round(w.wx);st.sZ1=Math.round(w.wz);st.sel={x1:st.sX1,z1:st.sZ1,x2:st.sX1,z2:st.sZ1};}
    else{st.dragging=true;st.lastX=e.clientX;st.lastY=e.clientY;canvas.style.cursor='grabbing';}
  });
  canvas.addEventListener('mousemove',function(e){
    var m=mouse(e),w=p2w(m.x,m.y);
    var cel=document.getElementById(opts.coordId);if(cel)cel.textContent='X '+Math.round(w.wx)+' · Z '+Math.round(w.wz);
    if(st.selecting){st.sel={x1:st.sX1,z1:st.sZ1,x2:Math.round(w.wx),z2:Math.round(w.wz)};draw();}
    else if(st.dragging){st.panX+=e.clientX-st.lastX;st.panZ+=e.clientY-st.lastY;st.lastX=e.clientX;st.lastY=e.clientY;draw();}
  });
  window.addEventListener('mouseup',function(){
    if(st.selecting){st.selecting=false;canvas.style.cursor='crosshair';
      var s=st.sel;
      if(s&&Math.abs(s.x2-s.x1)>1&&Math.abs(s.z2-s.z1)>1&&opts.onSelect){
        opts.onSelect({world:st.world,x1:Math.min(s.x1,s.x2),z1:Math.min(s.z1,s.z2),x2:Math.max(s.x1,s.x2),z2:Math.max(s.z1,s.z2)});
      }
    }
    if(st.dragging){st.dragging=false;canvas.style.cursor='crosshair';}
  });
  canvas.addEventListener('wheel',function(e){
    e.preventDefault();
    var m=mouse(e),before=p2w(m.x,m.y);
    if(e.deltaY<0&&st.zoom>1)st.zoom=Math.max(1,st.zoom-1);
    else if(e.deltaY>0&&st.zoom<16)st.zoom=Math.min(16,st.zoom+1);
    var after=w2p(before.wx,before.wz); // 保持鼠标点不动
    st.panX+=m.x-after.px;st.panZ+=m.y-after.pz;
    tiles={};draw();
  },{passive:false});
  setTimeout(resize,50);
  window.addEventListener('resize',resize);
  return {
    setZones:function(z){st.zones=z||[];draw();},
    setPlots:function(p){st.plots=p||[];draw();},
    setWorld:function(w){if(w&&w!==st.world){st.world=w;tiles={};draw();}},
    zoomIn:function(){if(st.zoom>1){st.zoom--;tiles={};draw();}},
    zoomOut:function(){if(st.zoom<16){st.zoom++;tiles={};draw();}},
    center:center, resize:resize, clearSel:function(){st.sel=null;draw();}
  };
}
// Built-in district map. It deliberately uses only the real-estate API data, so
// zoning remains usable when the optional HWP terrain service is unavailable.
function KsMapEngine(canvasId,opts){
  opts=opts||{};var canvas=document.getElementById(canvasId);if(!canvas)return null;
  var ctx=canvas.getContext('2d'),st={world:opts.world||'world',zones:[],plots:[],scale:1,ox:0,oz:0,drag:false,moved:false,select:false,lastX:0,lastY:0,start:null,sel:null,tiles:{},terrain:opts.terrain!==false,tilesEnabled:opts.terrain!==false};
  function size(){return {w:canvas.clientWidth||800,h:canvas.clientHeight||440};}
  function color(type){return {RESIDENTIAL:'#00D9FF',COMMERCIAL:'#FF4FD8',INDUSTRIAL:'#FFC857',AGRICULTURAL:'#5BE38B'}[type]||'#8FA6C7';}
  function colorAlpha(hex,alpha){var value=parseInt(String(hex).slice(1),16);return 'rgba('+((value>>16)&255)+','+((value>>8)&255)+','+(value&255)+','+alpha+')';}
  function statusText(){var el=document.getElementById(opts.statusId);if(el)el.textContent='INTERNAL VECTOR MAP · '+st.world;}
  function rect(v){return {x:Math.min(Number(v.x1)||0,Number(v.x2)||0),z:Math.min(Number(v.z1)||0,Number(v.z2)||0),w:Math.abs((Number(v.x2)||0)-(Number(v.x1)||0)),h:Math.abs((Number(v.z2)||0)-(Number(v.z1)||0))};}
  function point(x,z){return {x:st.ox+x*st.scale,y:st.oz+z*st.scale};}
  function world(x,y){return {x:(x-st.ox)/st.scale,z:(y-st.oz)/st.scale};}
  function mouse(e){var r=canvas.getBoundingClientRect();return {x:e.clientX-r.left,y:e.clientY-r.top};}
  function setStatus(text){var el=document.getElementById(opts.statusId);if(el)el.textContent=text;}
  function shape(x,y,w,h,r){r=Math.max(0,Math.min(r,w/3,h/3));ctx.beginPath();ctx.moveTo(x+r,y);ctx.lineTo(x+w-r,y);ctx.lineTo(x+w,y+r);ctx.lineTo(x+w,y+h-r);ctx.lineTo(x+w-r,y+h);ctx.lineTo(x+r,y+h);ctx.lineTo(x,y+h-r);ctx.lineTo(x,y+r);ctx.closePath();}
  function fit(){
    var all=st.zones.concat(st.plots).filter(function(v){return !v.world||v.world===st.world;});if(!all.length){st.scale=1;st.ox=size().w/2;st.oz=size().h/2;return;}
    var minX=Infinity,minZ=Infinity,maxX=-Infinity,maxZ=-Infinity;all.forEach(function(v){var q=rect(v);minX=Math.min(minX,q.x);minZ=Math.min(minZ,q.z);maxX=Math.max(maxX,q.x+q.w);maxZ=Math.max(maxZ,q.z+q.h);});
    var s=size(),pad=52,spanX=Math.max(1,maxX-minX),spanZ=Math.max(1,maxZ-minZ);st.scale=Math.max(.035,Math.min((s.w-pad*2)/spanX,(s.h-pad*2)/spanZ));st.ox=(s.w-spanX*st.scale)/2-minX*st.scale;st.oz=(s.h-spanZ*st.scale)/2-minZ*st.scale;
  }
  function drawGrid(s){var step=st.scale<.18?256:(st.scale<.6?64:16);var a=world(0,0),b=world(s.w,s.h),x=Math.floor(a.x/step)*step,z=Math.floor(a.z/step)*step;ctx.strokeStyle='rgba(127,167,204,.10)';ctx.lineWidth=1;for(;x<=b.x+step;x+=step){var p=point(x,0);ctx.beginPath();ctx.moveTo(p.x,0);ctx.lineTo(p.x,s.h);ctx.stroke();}for(;z<=b.z+step;z+=step){var q=point(0,z);ctx.beginPath();ctx.moveTo(0,q.y);ctx.lineTo(s.w,q.y);ctx.stroke();}}
  function tileZoom(){var wanted=16/Math.max(.02,st.scale),levels=[1,2,4,8],best=1,delta=Infinity;levels.forEach(function(level){var d=Math.abs(Math.log(wanted/level));if(d<delta){best=level;delta=d;}});return best;}
  function loadTile(key,zoom,tx,tz){st.tiles[key]='loading';fetch('/kSHWP/api/tile?world='+encodeURIComponent(st.world)+'&x='+tx+'&z='+tz+'&zoom='+zoom).then(function(r){if(!r.ok)throw new Error('tile '+r.status);return r.json();}).then(function(data){if(!data||!data.tile)throw new Error('empty tile');var image=new Image();image.onload=function(){st.tiles[key]=image;draw();};image.onerror=function(){st.tiles[key]='failed';};image.src='data:image/png;base64,'+data.tile;}).catch(function(){st.tiles[key]='failed';if(st.tilesEnabled){st.tilesEnabled=false;st.tiles={};setStatus('ksHWP terrain unavailable; showing district grid');draw();}});}
  function drawTiles(s){if(!st.tilesEnabled)return;var zoom=tileZoom(),span=16*zoom,a=world(0,0),b=world(s.w,s.h),minX=Math.floor(Math.min(a.x,b.x)/span)-1,maxX=Math.floor(Math.max(a.x,b.x)/span)+1,minZ=Math.floor(Math.min(a.z,b.z)/span)-1,maxZ=Math.floor(Math.max(a.z,b.z)/span)+1,size=span*st.scale;for(var tz=minZ;tz<=maxZ;tz++)for(var tx=minX;tx<=maxX;tx++){var key=st.world+':'+zoom+':'+tx+':'+tz,img=st.tiles[key],p=point(tx*span,tz*span);if(img&&img!=='loading'&&img!=='failed'){ctx.save();ctx.filter='saturate(0.55) brightness(0.6) contrast(1.05)';ctx.drawImage(img,p.x,p.y,size,size);ctx.restore();}else{ctx.fillStyle=img==='loading'?'#101827':'#0b1420';ctx.fillRect(p.x,p.y,size,size);}if(!img)loadTile(key,zoom,tx,tz);}ctx.fillStyle='rgba(6,10,22,0.45)';ctx.fillRect(0,0,s.w,s.h);}
  function draw(){var s=size();ctx.clearRect(0,0,s.w,s.h);ctx.fillStyle='#070B12';ctx.fillRect(0,0,s.w,s.h);drawTiles(s);drawGrid(s);
    st.zones.filter(function(z){return !z.world||z.world===st.world;}).forEach(function(z){var q=rect(z),p=point(q.x,q.z),w=q.w*st.scale,h=q.h*st.scale,c=color(z.type),sale=z.status==='FOR_SALE',radius=Math.min(12,Math.max(0,Math.min(w,h)*.12)),fill=ctx.createLinearGradient(p.x,p.y,p.x,p.y+h);ctx.save();fill.addColorStop(0,colorAlpha(c,sale?.42:.16));fill.addColorStop(1,colorAlpha(c,sale?.14:.055));shape(p.x,p.y,w,h,radius);ctx.fillStyle=fill;ctx.fill();shape(p.x+.5,p.y+.5,Math.max(0,w-1),Math.max(0,h-1),radius);ctx.globalAlpha=sale?.98:.48;ctx.strokeStyle=c;ctx.lineWidth=sale?2:1;ctx.shadowColor=c;ctx.shadowBlur=sale?18:8;ctx.setLineDash(sale?[]:[5,4]);ctx.stroke();ctx.setLineDash([]);ctx.globalAlpha=1;if(w>72&&h>30){ctx.fillStyle='#F8FCFF';ctx.font='700 14px Rajdhani,Arial,sans-serif';ctx.shadowColor=c;ctx.shadowBlur=8;ctx.fillText(String(z.name||z.id),p.x+8,p.y+17);ctx.shadowBlur=0;ctx.fillStyle=c;ctx.font='10px Roboto Mono,monospace';ctx.fillText(String(z.type||'ZONE')+' · '+String(z.status||''),p.x+8,p.y+32);}ctx.restore();});
    st.plots.filter(function(p){return !p.world||p.world===st.world;}).forEach(function(p){var q=rect(p),v=point(q.x,q.z),w=Math.max(2,q.w*st.scale),h=Math.max(2,q.h*st.scale);ctx.save();shape(v.x,v.y,w,h,Math.min(7,Math.max(2,st.scale*5)));ctx.fillStyle=p.ownerType==='ENTERPRISE'?'#FF4FD8':'#B18CFF';ctx.globalAlpha=.35;ctx.fill();ctx.globalAlpha=.95;ctx.strokeStyle=p.ownerType==='ENTERPRISE'?'#FF4FD8':'#B18CFF';ctx.lineWidth=1;ctx.setLineDash([4,3]);ctx.stroke();ctx.restore();});
    if(st.sel){var q=rect(st.sel),p=point(q.x,q.z);ctx.fillStyle='rgba(255,200,87,.14)';ctx.fillRect(p.x,p.y,q.w*st.scale,q.h*st.scale);ctx.strokeStyle='#FFC857';ctx.lineWidth=1.5;ctx.setLineDash([6,4]);ctx.strokeRect(p.x,p.y,q.w*st.scale,q.h*st.scale);ctx.setLineDash([]);}
  }
  function resize(){var s=size(),d=Math.min(window.devicePixelRatio||1,2);canvas.width=Math.round(s.w*d);canvas.height=Math.round(s.h*d);ctx.setTransform(d,0,0,d,0,0);draw();}
  function hit(x,y){var w=world(x,y),all=st.plots.slice().reverse().concat(st.zones.slice().reverse());for(var i=0;i<all.length;i++){var v=all[i],q=rect(v);if((!v.world||v.world===st.world)&&w.x>=q.x&&w.x<=q.x+q.w&&w.z>=q.z&&w.z<=q.z+q.h)return {kind:i<st.plots.length?'plot':'zone',value:v};}return null;}
  canvas.addEventListener('mousedown',function(e){var m=mouse(e),w=world(m.x,m.y);st.lastX=m.x;st.lastY=m.y;if(e.shiftKey){st.select=true;st.start={x:Math.round(w.x),z:Math.round(w.z)};st.sel={x1:st.start.x,z1:st.start.z,x2:st.start.x,z2:st.start.z};}else{st.drag=true;st.moved=false;canvas.style.cursor='grabbing';}});
  canvas.addEventListener('mousemove',function(e){var m=mouse(e),w=world(m.x,m.y),el=document.getElementById(opts.coordId);if(el)el.textContent='X '+Math.round(w.x)+' · Z '+Math.round(w.z);if(st.select){st.sel={x1:st.start.x,z1:st.start.z,x2:Math.round(w.x),z2:Math.round(w.z)};draw();}else if(st.drag){st.moved=st.moved||Math.abs(m.x-st.lastX)>2||Math.abs(m.y-st.lastY)>2;st.ox+=m.x-st.lastX;st.oz+=m.y-st.lastY;st.lastX=m.x;st.lastY=m.y;draw();}});
  window.addEventListener('mouseup',function(e){if(st.select){st.select=false;var q=rect(st.sel);if(q.w>1&&q.h>1&&opts.onSelect)opts.onSelect({world:st.world,x1:q.x,z1:q.z,x2:q.x+q.w,z2:q.z+q.h});draw();}else if(st.drag){var m=e&&e.clientX?mouse(e):{x:st.lastX,y:st.lastY};if(!st.moved&&opts.onEntityClick){var found=hit(m.x,m.y);if(found)opts.onEntityClick(found);}st.drag=false;canvas.style.cursor='crosshair';}});
  canvas.addEventListener('wheel',function(e){e.preventDefault();var m=mouse(e),before=world(m.x,m.y),factor=e.deltaY<0?1.18:.85;st.scale=Math.max(.02,Math.min(10,st.scale*factor));st.ox=m.x-before.x*st.scale;st.oz=m.y-before.z*st.scale;draw();},{passive:false});
  if(window.ResizeObserver)new ResizeObserver(resize).observe(canvas);setTimeout(function(){resize();statusText();},40);window.addEventListener('resize',resize);
  return {setZones:function(v){st.zones=v||[];draw();},setPlots:function(v){st.plots=v||[];draw();},setWorld:function(v){if(v&&v!==st.world){st.world=v;st.tiles={};st.tilesEnabled=st.terrain;statusText();}draw();},zoomIn:function(){st.scale=Math.min(10,st.scale*1.18);draw();},zoomOut:function(){st.scale=Math.max(.02,st.scale*.85);draw();},center:function(){fit();draw();},resize:resize,clearSel:function(){st.sel=null;draw();}};
}
function KsDistrictMap(canvasId,opts){opts=opts||{};opts.terrain=false;return KsMapEngine(canvasId,opts);}
function drawDistrictFocus(canvasId,v){
  var c=document.getElementById(canvasId);if(!c)return;var d=Math.min(window.devicePixelRatio||1,2),w=c.clientWidth||520,h=c.clientHeight||220;c.width=w*d;c.height=h*d;var x=c.getContext('2d');x.setTransform(d,0,0,d,0,0);x.fillStyle='#080D16';x.fillRect(0,0,w,h);x.strokeStyle='rgba(112,154,194,.12)';for(var g=16;g<w;g+=16){x.beginPath();x.moveTo(g,0);x.lineTo(g,h);x.stroke();}for(var gy=16;gy<h;gy+=16){x.beginPath();x.moveTo(0,gy);x.lineTo(w,gy);x.stroke();}var x1=Math.min(Number(v.x1)||0,Number(v.x2)||0),z1=Math.min(Number(v.z1)||0,Number(v.z2)||0),ww=Math.max(1,Math.abs((Number(v.x2)||0)-(Number(v.x1)||0))),hh=Math.max(1,Math.abs((Number(v.z2)||0)-(Number(v.z1)||0))),scale=Math.min((w-68)/ww,(h-68)/hh),rw=Math.max(48,ww*scale),rh=Math.max(48,hh*scale),left=(w-rw)/2,top=(h-rh)/2,col={RESIDENTIAL:'#00D9FF',COMMERCIAL:'#FF4FD8',INDUSTRIAL:'#FFC857',AGRICULTURAL:'#5BE38B'}[v.type]||'#B18CFF';x.fillStyle=col+'35';x.fillRect(left,top,rw,rh);x.strokeStyle=col;x.lineWidth=2;x.strokeRect(left,top,rw,rh);x.fillStyle='#F1FAFF';x.font='600 14px Rajdhani,Arial';x.fillText(String(v.name||v.id||'PLOT'),left+12,top+24);x.fillStyle=col;x.font='11px Roboto Mono,monospace';x.fillText('['+x1+', '+z1+']  '+ww+' x '+hh,left+12,top+43);
}
function openAdminDistrictEntity(item){
  var v=item.value,canvas='<canvas id="districtFocusMap" class="district-focus-map"></canvas>';
  if(item.kind==='plot'){showModal('地块详情 // '+escapeHtml(v.id),canvas+'<div class="hub-feed"><div class="hub-feed-item"><span>区域</span><b>'+escapeHtml(v.zoneId||'—')+'</b></div><div class="hub-feed-item"><span>所有者</span><b>'+escapeHtml(v.ownerType||'—')+' · '+escapeHtml(v.ownerId||'—')+'</b></div><div class="hub-feed-item"><span>范围</span><b>['+v.x1+','+v.z1+'] - ['+v.x2+','+v.z2+']</b></div><div class="hub-feed-item"><span>购入价</span><b>'+fmt(v.price||0)+'</b></div></div><p><button class="btn" onclick="closeModal();document.getElementById(\'rePlotFilterZone\').value=\''+escapeAttr(v.zoneId||'')+'\';loadRePlots()">查看区域地块</button></p>');setTimeout(function(){drawDistrictFocus('districtFocusMap',v);},0);return;}
  showModal('区域详情 // '+escapeHtml(v.name||v.id),canvas+'<div class="hub-feed"><div class="hub-feed-item"><span>规划</span><b>'+escapeHtml(ZONE_TYPE_CN(v.type))+'</b></div><div class="hub-feed-item"><span>状态</span><b>'+escapeHtml(v.status||'—')+'</b></div><div class="hub-feed-item"><span>基础价</span><b>'+fmt(v.basePrice||0)+'</b></div><div class="hub-feed-item"><span>范围</span><b>['+v.x1+','+v.z1+'] - ['+v.x2+','+v.z2+']</b></div></div><p><button class="btn" onclick="closeModal();editReZone(\''+escapeAttr(v.id)+'\')">载入编辑器</button> <button class="btn btn-sm" onclick="closeModal();setReZonePrice(\''+escapeAttr(v.id)+'\','+Number(v.basePrice||0)+')">调整价格</button></p>');setTimeout(function(){drawDistrictFocus('districtFocusMap',v);},0);
}var reZoneMap=null;
function initReZoneMap(){
  if(reZoneMap){reZoneMap.resize();return;}
  reZoneMap=KsMapEngine('reZoneCanvas',{
    world:document.getElementById('reZoneWorld').value||'world',
    statusId:'reZoneMapStatus', coordId:'reZoneCoord',
    onEntityClick:openAdminDistrictEntity,
    onSelect:function(s){
      document.getElementById('reZoneX1').value=s.x1;
      document.getElementById('reZoneZ1').value=s.z1;
      document.getElementById('reZoneX2').value=s.x2;
      document.getElementById('reZoneZ2').value=s.z2;
      toast('已框选: ('+s.x1+','+s.z1+')→('+s.x2+','+s.z2+')','ok');
    }
  });
}
async function loadReZones(){
  initReZoneMap();
  requestAnimationFrame(function(){if(reZoneMap)reZoneMap.resize();});
  var d=await api('GET','/api/realestate/zones');
  if(!d.moduleLoaded){
    document.getElementById('reModuleHint').textContent='⚠ 房地产模块未安装（需将 ks-Eco-RealEstate-1.1.0.jar 放入 plugins/ks-Eco/extra/）';
  }else{
    document.getElementById('reModuleHint').textContent='✅ 房地产模块已加载';
  }
  var zones=d.zones||[];
  var t='';zones.forEach(function(z){
    var range='['+z.x1+','+z.z1+']-['+z.x2+','+z.z2+']';
    var statusBadge=z.status==='FOR_SALE'?'<span class="badge badge-active">可售</span>':
                    z.status==='STATE_OWNED'?'<span class="badge badge-pending">国有</span>':
                    '<span class="badge badge-closed">已售罄</span>';
    var mp=z.maxPlots||0;
    var isResidential=z.type==='RESIDENTIAL';
    var full=isResidential&&mp>0&&z.plotCount>=mp;
    var volCell=isResidential
      ?'<span style="'+(full?'color:#ff5252;font-weight:700;':'')+'">'+z.plotCount+' / '+(mp>0?mp:'∞')+(full?' 满':'')+'</span>'
      :'<span style="color:#666;">不限(非住宅)</span>';
    t+='<tr><td style="font-size:10px;">'+z.id+'</td><td>'+escapeHtml(z.name)+'</td>';
    t+='<td>'+escapeHtml(z.world)+'</td><td style="font-size:11px;">'+range+'</td>';
    t+='<td>'+ZONE_TYPE_CN(z.type)+'</td><td>'+fmt(z.basePrice)+'</td><td>'+pct(z.taxRate)+'</td>';
    t+='<td>'+volCell+'</td>';
    t+='<td style="font-size:11px;">'+(z.dungeonTemplateId?escapeHtml(z.dungeonTemplateId):'<span style="color:#666;">无</span>')+'</td>';
    t+='<td>'+statusBadge+'</td>';
    t+='<td style="white-space:nowrap;"><button class="btn btn-sm" onclick="editReZone(\''+z.id+'\')">编辑</button> ';
    t+='<button class="btn btn-sm" onclick="setReZonePrice(\''+z.id+'\','+z.basePrice+')">改价</button> ';
    t+='<button class="btn btn-sm" onclick="changeReZoneType(\''+z.id+'\',\''+z.type+'\')">改规划</button> ';
    t+='<button class="btn btn-sm" onclick="setReZoneMaxPlots(\''+z.id+'\','+mp+')">容积率</button> ';
    t+='<button class="btn btn-sm" onclick="setReZoneDungeonLink(\''+z.id+'\',\''+escapeAttr(z.dungeonTemplateId||'')+'\')">副本权限</button> ';
    t+='<button class="btn btn-sm btn-warn" onclick="toggleReZoneSale(\''+z.id+'\',\''+z.status+'\')">切换可售</button> ';
    t+='<button class="btn btn-sm btn-danger" onclick="deleteReZone(\''+z.id+'\',\''+escapeAttr(z.name)+'\','+z.plotCount+')">删除</button></td></tr>';
  });
  document.getElementById('reZoneListBody').innerHTML=t||'<tr><td colspan="11" style="color:#666;">暂无区域</td></tr>';
  // 把区域画到地图，并按第一个区域所在世界对齐
  if(reZoneMap){
    reZoneMap.setZones(zones);
    if(zones.length){reZoneMap.setWorld(zones[0].world);}
    var pd=await api('GET','/api/realestate/plots');
    reZoneMap.setPlots(pd.plots||[]);
    reZoneMap.center();
  }
}
function editReZone(id){
  api('GET','/api/realestate/zones').then(function(d){
    var z=(d.zones||[]).find(function(x){return x.id===id;});
    if(!z)return;
    document.getElementById('reZoneName').value=z.name;
    document.getElementById('reZoneWorld').value=z.world;
    document.getElementById('reZoneType').value=z.type;
    document.getElementById('reZoneStatus').value=z.status;
    document.getElementById('reZoneX1').value=z.x1;
    document.getElementById('reZoneZ1').value=z.z1;
    document.getElementById('reZoneX2').value=z.x2;
    document.getElementById('reZoneZ2').value=z.z2;
    document.getElementById('reZonePrice').value=z.basePrice;
    document.getElementById('reZoneTax').value=z.taxRate;
    document.getElementById('reZoneMaxPlots').value=z.maxPlots||0;
    document.getElementById('reZoneDungeonTpl').value=z.dungeonTemplateId||'';
  });
}
function ZONE_TYPE_CN(t){return {RESIDENTIAL:'住宅',COMMERCIAL:'商业',INDUSTRIAL:'工业',AGRICULTURAL:'农业'}[t]||t;}
async function changeReZoneType(id,cur){
  var order=['RESIDENTIAL','COMMERCIAL','INDUSTRIAL','AGRICULTURAL'];
  var v=prompt('设置规划类型 (RESIDENTIAL住宅 / COMMERCIAL商业 / INDUSTRIAL工业 / AGRICULTURAL农业)\n当前: '+cur,cur);
  if(v===null)return;v=v.trim().toUpperCase();
  if(order.indexOf(v)<0){toast('类型无效','err');return;}
  var d=await api('POST','/api/admin/realestate/zone/type',{id:id,type:v});
  if(d.message){toast(d.message,'ok');loadReZones();}else toast(d.error||'失败','err');
}
async function setReZoneMaxPlots(id,cur){
  var v=prompt('设置容积率（最大合法登记地块数，0=不限）\n当前: '+cur,cur);
  if(v===null)return;var n=parseInt(v);
  if(isNaN(n)||n<0){toast('数值无效','err');return;}
  var d=await api('POST','/api/admin/realestate/zone/max-plots',{id:id,maxPlots:n});
  if(d.message){toast(d.message,'ok');loadReZones();}else toast(d.error||'失败','err');
}
async function setReZoneDungeonLink(id,cur){
  var v=prompt('设置该区域的副本权限（绑定的副本模板ID，留空=取消；对住宅/农业/工业用地生效）\n当前: '+(cur||'无'),cur||'');
  if(v===null)return;
  var d=await api('POST','/api/admin/realestate/zone/dungeon-link',{id:id,templateId:v.trim()});
  if(d.message){toast(d.message,'ok');loadReZones();}else toast(d.error||'失败','err');
}
async function deleteReZone(id,name,plotCount){
  if(!confirm('确定删除区域「'+name+'」？\n该区域名下 '+plotCount+' 块地产将一并删除，不可恢复！'))return;
  var d=await api('POST','/api/admin/realestate/zone/delete',{id:id});
  if(d.message){toast(d.message,'ok');loadReZones();}else toast(d.error||'删除失败','err');
}
async function createReZone(){
  var body={
    name:document.getElementById('reZoneName').value.trim(),
    world:document.getElementById('reZoneWorld').value.trim()||'world',
    type:document.getElementById('reZoneType').value,
    status:document.getElementById('reZoneStatus').value,
    x1:Number(document.getElementById('reZoneX1').value),
    z1:Number(document.getElementById('reZoneZ1').value),
    x2:Number(document.getElementById('reZoneX2').value),
    z2:Number(document.getElementById('reZoneZ2').value),
    basePrice:Number(document.getElementById('reZonePrice').value),
    taxRate:parseFloat(document.getElementById('reZoneTax').value),
    maxPlots:parseInt(document.getElementById('reZoneMaxPlots').value)||0,
    dungeonTemplateId:document.getElementById('reZoneDungeonTpl').value.trim()
  };
  if(!body.name){toast('名称必填','err');return;}
  var d=await api('POST','/api/admin/realestate/zone',body);
  if(d.message){toast(d.message,'ok');loadReZones();}
  else toast(d.error||'失败','err');
}
async function toggleReZoneSale(id,cur){
  var next=cur==='FOR_SALE'?'STATE_OWNED':'FOR_SALE';
  var d=await api('POST','/api/admin/realestate/zone/status',{id:id,status:next});
  if(d.message){toast(d.message,'ok');loadReZones();}else toast(d.error,'err');
}
async function setReZonePrice(id,cur){
  var v=prompt('设置区域基础价（当前 '+fmt(cur)+'）:',cur);
  if(v===null)return;
  var price=parseFloat(v);
  if(isNaN(price)||price<0){toast('价格无效','err');return;}
  var d=await api('POST','/api/admin/realestate/zone/price',{id:id,price:price});
  if(d.message){toast(d.message,'ok');loadReZones();}else toast(d.error||'改价失败','err');
}
async function loadRePlots(){
  var zoneId=document.getElementById('rePlotFilterZone').value.trim()||null;
  var ownerType=document.getElementById('rePlotFilterOwnerType').value||null;
  var qs='';
  if(zoneId)qs+='&zoneId='+encodeURIComponent(zoneId);
  if(ownerType)qs+='&ownerType='+ownerType;
  var d=await api('GET','/api/realestate/plots'+(qs?'?'+qs.substring(1):''));
  window.rePlotCache={};
  var t='';(d.plots||[]).forEach(function(p){
    window.rePlotCache[p.id]=p;
    var perk=p.perk||{};
    var funcs=[];
    if(p.agriEffective)funcs.push('农业');
    if(p.industryEffective)funcs.push('工业');
    t+='<tr><td style="font-size:10px;">'+p.id+'</td>';
    t+='<td>'+p.zoneId+'</td><td>'+p.world+'</td>';
    t+='<td style="font-size:11px;">['+p.x1+','+p.z1+']-['+p.x2+','+p.z2+']</td>';
    t+='<td>'+p.ownerType+':'+(p.ownerType==='ENTERPRISE'?p.ownerId:p.ownerId.substr(0,8))+'</td>';
    t+='<td>'+fmt(p.price)+'</td><td>'+pct(p.taxRate)+'</td>';
    t+='<td style="font-size:11px;">'+new Date(p.purchasedAt*1000).toLocaleDateString('zh-CN')+'</td>';
    t+='<td style="font-size:11px;">'+(funcs.join('+')||'-')+'<br><span style="color:#777;">'+(perk.agriGrowthIntervalTicks||100)+'t / +'+(perk.agriGrowthSteps||1)+'</span></td>';
    t+='<td><button class="btn btn-sm" onclick="editPlotPerk(\''+p.id+'\')">福利</button></td></tr>';
  });
  document.getElementById('rePlotListBody').innerHTML=t||'<tr><td colspan="10" style="color:#666;">无地块</td></tr>';
}

// ============ 地块福利配置 ============
function editPlotPerk(plotId){
  var p=(window.rePlotCache||{})[plotId]; if(!p){toast('地块不存在','err');return;}
  var k=p.perk||{};
  function val(v,def){return v!=null?v:def;}
  function sec(ticks){return ((Number(ticks||100))/20).toFixed(1).replace(/\.0$/,'');}
  var body='';
  body+='<div style="color:#aaa;font-size:12px;margin-bottom:10px;">区域类型: '+escapeHtml(p.zoneType||'-')+' | 当前生效: '+(p.agriEffective?'农业 ':'')+(p.industryEffective?'工业':'')+'</div>';
  body+='<div class="form-row">';
  body+='<label><input type="checkbox" id="plotAgriEnabled" '+(k.agriEnabled?'checked':'')+'> 额外启用农业功能</label>';
  body+='<label><input type="checkbox" id="plotIndustryEnabled" '+(k.industryEnabled?'checked':'')+'> 额外启用工业功能</label>';
  body+='</div>';
  body+='<div class="form-row">';
  body+='<label>成熟加速间隔(秒)<br><input id="plotAgriInterval" type="number" min="1" max="60" step="0.5" value="'+sec(k.agriGrowthIntervalTicks)+'"></label>';
  body+='<label>每次推进阶段<br><input id="plotAgriSteps" type="number" min="1" max="8" step="1" value="'+val(k.agriGrowthSteps,1)+'"></label>';
  body+='<label>每次扫描采样<br><input id="plotAgriSamples" type="number" min="1" max="512" step="1" value="'+val(k.agriGrowthSamples,64)+'"></label>';
  body+='</div>';
  body+='<div class="form-row">';
  body+='<label>收获额外产出几率<br><input id="plotHarvestBonus" type="number" min="0" max="1" step="0.01" value="'+val(k.agriHarvestYieldBonusChance,0.2)+'"></label>';
  body+='<label>官方收购溢价<br><input id="plotPremium" type="number" min="0" max="10" step="0.01" value="'+val(k.agriOfficialPremiumPct,0.1)+'"></label>';
  body+='</div>';
  body+='<div class="form-row">';
  body+='<label>熔炉加速比例<br><input id="plotFurnaceSpeed" type="number" min="0" max="0.95" step="0.01" value="'+val(k.industryFurnaceSpeedPct,0.2)+'"></label>';
  body+='<label>熔炉额外产出几率<br><input id="plotFurnaceBonus" type="number" min="0" max="1" step="0.01" value="'+val(k.industryFurnaceBonusOutputChance,0.1)+'"></label>';
  body+='<label>招投标信誉加成<br><input id="plotBidBonus" type="number" min="0" max="10" step="0.01" value="'+val(k.industryBiddingReputationBonusPct,0.05)+'"></label>';
  body+='</div>';
  body+='<button class="btn" onclick="savePlotPerk('+escapeAttr(JSON.stringify(plotId))+')">保存地块福利</button>';
  showModal('地块福利: '+escapeHtml(plotId),body);
}
async function savePlotPerk(plotId){
  var body={
    plotId:plotId,
    agriEnabled:document.getElementById('plotAgriEnabled').checked,
    industryEnabled:document.getElementById('plotIndustryEnabled').checked,
    agriGrowthIntervalSeconds:Number(document.getElementById('plotAgriInterval').value)||5,
    agriGrowthSteps:Number(document.getElementById('plotAgriSteps').value)||1,
    agriGrowthSamples:Number(document.getElementById('plotAgriSamples').value)||64,
    agriHarvestYieldBonusChance:Number(document.getElementById('plotHarvestBonus').value)||0,
    agriOfficialPremiumPct:Number(document.getElementById('plotPremium').value)||0,
    industryFurnaceSpeedPct:Number(document.getElementById('plotFurnaceSpeed').value)||0,
    industryFurnaceBonusOutputChance:Number(document.getElementById('plotFurnaceBonus').value)||0,
    industryBiddingReputationBonusPct:Number(document.getElementById('plotBidBonus').value)||0
  };
  var d=await api('POST','/api/admin/realestate/plot/perk',body);
  if(d.message){toast(d.message,'ok');closeModal();loadRePlots();}
  else toast(d.error||'保存失败','err');
}

async function loadLandPerks(){
  var d=await api('GET','/api/admin/land-perks');
  var c=d.config||{};
  document.getElementById('lpAgriMode').value=String(c.agri_scope_mode!=null?c.agri_scope_mode:0)==='1'?'1':'0';
  document.getElementById('lpIndMode').value=String(c.industry_scope_mode!=null?c.industry_scope_mode:0)==='1'?'1':'0';
  document.getElementById('lpAgriGrowth').value=c.agri_growth_boost_chance!=null?c.agri_growth_boost_chance:0.15;
  document.getElementById('lpAgriHarvest').value=c.agri_harvest_yield_bonus_chance!=null?c.agri_harvest_yield_bonus_chance:0.20;
  document.getElementById('lpAgriPremium').value=c.agri_official_premium_pct!=null?c.agri_official_premium_pct:0.10;
  document.getElementById('lpIndFurnaceSpeed').value=c.industry_furnace_speed_pct!=null?c.industry_furnace_speed_pct:0.20;
  document.getElementById('lpIndFurnaceBonus').value=c.industry_furnace_bonus_output_chance!=null?c.industry_furnace_bonus_output_chance:0.10;
  document.getElementById('lpIndBidBonus').value=c.industry_bidding_reputation_bonus_pct!=null?c.industry_bidding_reputation_bonus_pct:0.05;
  document.getElementById('lpAgriMaxSamples').value=c.agri_growth_max_samples_per_second!=null?c.agri_growth_max_samples_per_second:4096;
  document.getElementById('lpAgriMinTps').value=c.agri_growth_min_tps!=null?c.agri_growth_min_tps:18.5;
  document.getElementById('lpIndustryMaxEvents').value=c.industry_max_events_per_second!=null?c.industry_max_events_per_second:512;
  document.getElementById('lpIndustryMinTps').value=c.industry_min_tps!=null?c.industry_min_tps:18.0;
  var r=d.runtime||{};
  var tps=r.currentTps!=null?Number(r.currentTps).toFixed(2):'--';
  document.getElementById('lpPerfStatus').textContent='运行状态：TPS '+tps+
    ' | 农业上一秒采样 '+(r.lastAgriSamplesPerSecond!=null?r.lastAgriSamplesPerSecond:'--')+
    ' | 工业上一秒事件 '+(r.lastIndustryEventsPerSecond!=null?r.lastIndustryEventsPerSecond:'--')+
    ((r.agriPausedByTps||r.industryPausedByTps)?' | 低 TPS 保护中':'');
  document.getElementById('lpModuleHint').textContent=d.moduleLoaded?'✅ 房地产模块已加载':'⚠ 房地产模块未安装（需将 ks-Eco-RealEstate-1.1.0.jar 放入 plugins/ks-Eco/extra/）';
}
async function saveLandPerks(){
  var body={
    agri_scope_mode:Number(document.getElementById('lpAgriMode').value),
    industry_scope_mode:Number(document.getElementById('lpIndMode').value),
    agri_growth_boost_chance:Number(document.getElementById('lpAgriGrowth').value),
    agri_harvest_yield_bonus_chance:Number(document.getElementById('lpAgriHarvest').value),
    agri_official_premium_pct:Number(document.getElementById('lpAgriPremium').value),
    industry_furnace_speed_pct:Number(document.getElementById('lpIndFurnaceSpeed').value),
    industry_furnace_bonus_output_chance:Number(document.getElementById('lpIndFurnaceBonus').value),
    industry_bidding_reputation_bonus_pct:Number(document.getElementById('lpIndBidBonus').value),
    agri_growth_max_samples_per_second:Number(document.getElementById('lpAgriMaxSamples').value),
    agri_growth_min_tps:Number(document.getElementById('lpAgriMinTps').value),
    industry_max_events_per_second:Number(document.getElementById('lpIndustryMaxEvents').value),
    industry_min_tps:Number(document.getElementById('lpIndustryMinTps').value)
  };
  var d=await api('POST','/api/admin/land-perks/set',body);
  if(d.message){toast(d.message,'ok');loadLandPerks();}
  else toast(d.error||'保存失败','err');
}

// ============ 元老院 / 政治（admin 监管） ============
// 政治路由在 /ks-Eco/politic/api/*；admin 的 api() 前缀 /ks-Eco，故传 '/politic/api/...'
var POL_STATUS_CN={PROPOSED:'待表决',SENATE_VOTING:'元老院表决中',TRIBUNE_REVIEW:'保民官审查中',
  APPROVED:'已批准',VETOED:'已否决',SENATE_OVERRIDE:'覆议中',OVERRIDDEN:'覆议通过',
  ENACTED:'已颁布',REJECTED:'已驳回',ABANDONED:'已放弃'};
var POL_OFFICE_CN={CONSUL:'👑 执政官',SENATOR:'🏛 元老',TRIBUNE:'🛡 保民官',EQUESTRIAN:'🐎 骑士'};
function polStatusBadge(s){
  var cls=s==='ENACTED'||s==='OVERRIDDEN'?'badge-active':
          s==='REJECTED'||s==='VETOED'||s==='ABANDONED'?'badge-closed':'badge-pending';
  return '<span class="badge '+cls+'">'+(POL_STATUS_CN[s]||s)+'</span>';
}
async function loadPoliticOffices(){
  var d=await api('GET','/politic/api/offices?type=all');
  var order={CONSUL:0,SENATOR:1,TRIBUNE:2,EQUESTRIAN:3};
  var list=(d.offices||[]).slice().sort(function(a,b){return (order[a.officeType]||9)-(order[b.officeType]||9);});
  var t='';list.forEach(function(o){
    var term=o.termEndsAt>0?new Date(o.termEndsAt*1000).toLocaleDateString('zh-CN'):'—';
    t+='<tr><td>'+(POL_OFFICE_CN[o.officeType]||o.officeType)+'</td><td>'+escapeHtml(o.playerName||'')+'</td>';
    t+='<td style="font-size:10px;">'+o.playerUuid+'</td><td style="font-size:11px;">'+new Date(o.electedAt*1000).toLocaleDateString('zh-CN')+'</td><td style="font-size:11px;">'+term+'</td></tr>';
  });
  document.getElementById('politicOfficeBody').innerHTML=t||'<tr><td colspan="5" style="color:#666;">暂无在任职务</td></tr>';
  if(typeof ksKpiRow==='function'){
    var cnt={CONSUL:0,SENATOR:0,TRIBUNE:0,EQUESTRIAN:0};
    list.forEach(function(o){if(cnt[o.officeType]!=null)cnt[o.officeType]++;});
    ksKpiRow('polOfficeKpis',[
      {icon:'👑',label:'执政官',value:String(cnt.CONSUL),accent:cnt.CONSUL===0?'var(--magenta)':''},
      {icon:'🏛',label:'元老',value:String(cnt.SENATOR)},
      {icon:'🛡',label:'保民官',value:String(cnt.TRIBUNE)},
      {icon:'🐎',label:'骑士',value:String(cnt.EQUESTRIAN)}
    ]);
  }
  var senators=list.filter(function(o){return o.officeType==='SENATOR';});
  var sel=document.getElementById('consulAssignUuid');
  if(sel){
    sel.innerHTML=senators.length?senators.map(function(o){return '<option value="'+o.playerUuid+'" data-name="'+escapeAttr(o.playerName||'')+'">'+escapeHtml(o.playerName||o.playerUuid)+'</option>';}).join('')
      :'<option value="">暂无元老</option>';
  }
  loadAdminPoliticAnnouncements();
  loadTribuneElectionAdmin();
}
async function assignConsul(){
  var sel=document.getElementById('consulAssignUuid');
  var uuid=sel.value;
  if(!uuid){toast('请先任命元老再选择','err');return;}
  var name=sel.options[sel.selectedIndex].getAttribute('data-name')||'';
  var d=await api('POST','/politic/api/admin/consul/assign',{playerUuid:uuid,playerName:name});
  if(d.success){toast(d.message||'已任命','ok');loadPoliticOffices();}else toast(d.message||d.error||'失败','err');
}
async function loadTribuneElectionAdmin(){
  var d=await api('GET','/politic/api/tribune-election');
  var box=document.getElementById('tribuneElectionAdminInfo');
  if(!box)return;
  var ends=d.endsAt?new Date(d.endsAt*1000).toLocaleString('zh-CN'):'—';
  var tally=(d.tally||[]).map(function(r,i){return '<div>'+(i+1)+'. '+escapeHtml(r.candidateName)+' — '+r.votes+' 票'+(i<d.seats?' <span style="color:#4caf50;">(当前领先)</span>':'')+'</div>';}).join('')||'<div style="color:#666;">暂无投票</div>';
  box.innerHTML='本轮选举ID: '+(d.electionId||'—')+' | 周期: '+d.intervalHours+'小时 | 截止: '+ends+' | 席位: '+d.seats+'<br>'+tally;
}
async function forceTallyTribune(){
  if(!confirm('确认立即结束本轮保民官投票并计票就任？'))return;
  var d=await api('POST','/politic/api/admin/election/trigger',{type:'TRIBUNE'});
  toast(d.message||JSON.stringify(d).substring(0,80),'ok');loadPoliticOffices();
}
async function loadAdminPoliticAnnouncements(){
  try{
    var d=await api('GET','/politic/api/proposals');
    var anns=(d.proposals||[]).map(function(p){
      return {title:p.title,author:p.proposerName||'',category:p.status==='ENACTED'?'LAW':
        (['SENATE_VOTING','SENATE_OVERRIDE','TRIBUNE_REVIEW'].includes(p.status)?'VOTING':'GENERAL')};
    });
    var voting=anns.filter(function(a){return a.category==='VOTING';}).slice(0,3);
    var laws=anns.filter(function(a){return a.category==='LAW';}).slice(0,3);
    var h='';
    if(voting.length) h+='<div style="margin-bottom:4px;"><b style="color:#ff9800;">🏛 表决中</b>'+voting.map(function(a){return '<div style="padding:2px 6px;margin:2px 0;border-left:2px solid #ff9800;padding-left:6px;"><span style="color:#ffd08a;">'+escapeHtml(a.title)+'</span></div>';}).join('')+'</div>';
    if(laws.length) h+='<div><b style="color:#4caf50;">📜 已颁布</b>'+laws.map(function(a){return '<div style="padding:2px 6px;margin:2px 0;border-left:2px solid #4caf50;padding-left:6px;"><span style="color:#7fe08a;">'+escapeHtml(a.title)+'</span></div>';}).join('')+'</div>';
    if(!h) h='📭 暂无立法动态';
    document.getElementById('adminPoliticAnnouncements').innerHTML=h;
  }catch(e){ document.getElementById('adminPoliticAnnouncements').innerHTML='⚠ 加载公告失败'; }
}
async function addSenator(){
  var uuid=document.getElementById('senatorUuid').value.trim();
  if(!uuid){toast('请填写 UUID','err');return;}
  var d=await api('POST','/politic/api/admin/senator/add',{playerUuid:uuid,playerName:document.getElementById('senatorName').value.trim()});
  if(d.success){toast(d.message||'已任命','ok');loadPoliticOffices();}else toast(d.message||d.error||'失败','err');
}
async function removeSenator(){
  var uuid=document.getElementById('senatorUuid').value.trim();
  if(!uuid){toast('请填写 UUID','err');return;}
  var d=await api('POST','/politic/api/admin/senator/remove',{playerUuid:uuid});
  if(d.success){toast(d.message||'已移除','ok');loadPoliticOffices();}else toast(d.message||d.error||'失败','err');
}
async function triggerElection(){
  var type=document.getElementById('electionType').value;
  if(!confirm('确认触发 '+type+' 选举/重算？'))return;
  var d=await api('POST','/politic/api/admin/election/trigger',{type:type});
  toast(d.message||JSON.stringify(d).substring(0,80),'ok');loadPoliticOffices();
}
async function loadAdminProposals(){
  var status=document.getElementById('apropFilter').value;
  var url='/politic/api/proposals'+(status?'?status='+status:'');
  var d=await api('GET',url);
  var t='';window._propsCache={};(d.proposals||[]).forEach(function(p){window._propsCache[String(p.id)]=p;
    t+='<tr data-pid="'+escapeAttr(String(p.id))+'" style="cursor:pointer;"><td style="font-size:10px;">'+p.id+'</td><td>'+escapeHtml(p.title)+'</td>';
    t+='<td style="font-size:10px;">'+p.proposalType+'</td><td>'+escapeHtml(p.proposerName||'')+'</td>';
    t+='<td>'+polStatusBadge(p.status)+'</td><td style="font-size:11px;color:#aaa;">'+escapeHtml(p.resultSummary||'')+'</td></tr>';
  });
  document.getElementById('apropBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无提案</td></tr>';
}
var ECO_FEATURE_LABELS={
  market:'💰 市场',bank:'🏦 银行',transfer:'💸 玩家转账',enterprise:'🏢 企业',bidding:'📋 招投标',
  trade:'💱 玩家交易',exchange:'🔄 官方兑换',realestate:'🏠 房地产（含/house /land）',
  dungeon:'🏰 副本（/dungeon）',politic:'🏛 元老院（/politic）',
  blindbox:'🎁 盲盒（个人）',ent_blindbox:'🎪 企业盲盒',limited_sale:'⏳ 限时直售',compensation:'✦ 服务器补偿',invites:'🤝 合资邀请'
};
async function loadEcoFeatures(){
  var d=await api('GET','/api/admin/eco/features');
  var f=d.features||{};
  var t='<table><tr><th>功能</th><th>状态</th><th>操作</th></tr>';
  Object.keys(ECO_FEATURE_LABELS).forEach(function(key){
    var open=!!f[key];
    t+='<tr><td>'+ECO_FEATURE_LABELS[key]+'</td>';
    t+='<td><span class="badge '+(open?'badge-active':'badge-closed')+'">'+(open?'已开放':'未开放')+'</span></td>';
    t+='<td><label style="cursor:pointer;"><input type="checkbox" '+(open?'checked':'')+' onchange="setEcoFeature(\''+key+'\',this.checked)"/> 开放</label></td></tr>';
  });
  t+='</table>';
  document.getElementById('ecoFeaturesForm').innerHTML=t;
}
async function setEcoFeature(key,open){
  var d=await api('POST','/api/admin/eco/features/set',{key:key,value:open});
  if(d.features){toast((ECO_FEATURE_LABELS[key]||key)+(open?' 已开放':' 已关闭'),'ok');loadEcoFeatures();}
  else toast(d.error||'失败','err');
}
async function loadTransportConfig(){
  var d=await api('GET','/api/admin/transport/config');
  if(d.error){toast(d.error,'err');return;}
  document.getElementById('transportEnabled').checked=!!d.enabled;
  document.getElementById('transportFreeDistance').value=d.freeDistance;
  document.getElementById('transportBaseFee').value=d.baseFee;
  document.getElementById('transportPerBlockFee').value=d.perBlockFee;
  document.getElementById('transportCrossWorldSurcharge').value=d.crossWorldSurcharge;
  document.getElementById('transportMinimumFee').value=d.minimumFee;
  document.getElementById('transportMaximumFee').value=d.maximumFee;
}
async function saveTransportConfig(){
  var body={
    enabled:document.getElementById('transportEnabled').checked,
    freeDistance:parseFloat(document.getElementById('transportFreeDistance').value),
    baseFee:parseFloat(document.getElementById('transportBaseFee').value),
    perBlockFee:parseFloat(document.getElementById('transportPerBlockFee').value),
    crossWorldSurcharge:parseFloat(document.getElementById('transportCrossWorldSurcharge').value),
    minimumFee:parseFloat(document.getElementById('transportMinimumFee').value),
    maximumFee:parseFloat(document.getElementById('transportMaximumFee').value)
  };
  var d=await api('POST','/api/admin/transport/config',body);
  if(d.message)toast(d.message,'ok');else toast(d.error||'保存失败','err');
}
async function loadPoliticConfig(){
  var d=await api('GET','/politic/api/config');
  var cfg=d.config||{};
  var keys=[['senate_seats','元老席位'],['tribune_seats','保民官席位'],['equestrian_seats','骑士席位'],['term_duration_hours','任期(小时)'],['election_check_interval_min','选举检查间隔(分)'],['tribune_election_interval_hours','保民官选举周期(现实小时)']];
  var t='<table><tr><th>配置项</th><th>当前值</th><th>新值</th><th>操作</th></tr>';
  keys.forEach(function(k){
    t+='<tr><td>'+k[1]+' ('+k[0]+')</td><td>'+(cfg[k[0]]||'—')+'</td>';
    t+='<td><input id="cfg_'+k[0]+'" style="width:90px;" placeholder="'+(cfg[k[0]]||'')+'"/></td>';
    t+='<td><button class="btn btn-sm" onclick="setPoliticConfig(\''+k[0]+'\')">更新</button></td></tr>';
  });
  t+='</table>';
  document.getElementById('politicConfigForm').innerHTML=t;
  var lm=document.getElementById('legislativeMode');
  if(lm)lm.value=(cfg.legislative_mode==='true'||cfg.legislative_mode===true)?'true':'false';
}
async function setPoliticConfig(key){
  var v=document.getElementById('cfg_'+key).value.trim();
  if(!v){toast('请填写新值','err');return;}
  var d=await api('POST','/politic/api/config',{key:key,value:v});
  if(d.success){toast('已更新 '+key,'ok');loadPoliticConfig();}else toast(d.error||'失败','err');
}
async function setLegislativeMode(){
  var v=document.getElementById('legislativeMode').value;
  var d=await api('POST','/politic/api/config',{key:'legislative_mode',value:v});
  if(d.success){toast('立法模式已'+(v==='true'?'开启':'关闭'),'ok');}else toast(d.error||'失败','err');
}

// ============ 副本系统 (Dungeon, admin) ============
var DG='/api/realestate-dungeon';
var DG_NEXT={'grid.world_name':1,'grid.spacing':1,'grid.max_grids':1,'map.base_y':1,'map.arena_radius':1};
var dgTplCache=[];
function dgTime(t){return t?new Date(t*1000).toLocaleString('zh-CN'):'—';}

async function loadDgTemplates(){
  var d=await api('GET',DG+'/templates');
  dgTplCache=(d&&d.templates)||[];
  var t='';
  dgTplCache.forEach(function(x){
    t+='<tr data-dgid="'+escapeAttr(x.id)+'" style="cursor:pointer;"><td>'+escapeHtml(x.id)+'</td><td>'+escapeHtml(x.name)+'</td><td>'+escapeHtml(x.difficulty)+'</td><td>'+fmt(x.ticketPrice)+'</td><td>'+x.minPlayers+'-'+x.maxPlayers+'</td><td>'+x.timeLimitMinutes+'分</td><td>'+x.monsterLevel+'</td><td>'+(x.schematic?escapeHtml(x.schematic):'<span style="color:#666;">纯虚空</span>')+'</td><td>'+(x.requirePropertyKey?'🔑':'')+'</td><td>'+(x.rewardConfig?'<span class="badge badge-active">已配置</span>':'<span style="color:#666;">无</span>')+'</td><td style="font-size:11px;color:#aaa;">'+escapeHtml(x.description||'')+'</td>'
      +'<td><button class="btn btn-sm" onclick="editDgTemplate(\''+escapeAttr(x.id)+'\')">编辑</button> '
      +'<button class="btn btn-sm btn-danger" onclick="deleteDgTemplate(\''+escapeAttr(x.id)+'\')">删除</button></td></tr>';
  });
  document.getElementById('dgTplBody').innerHTML=t||'<tr><td colspan="12" style="color:#666;">暂无模板</td></tr>';
}
function editDgTemplate(id){
  var x=dgTplCache.find(function(e){return e.id===id;});if(!x)return;
  document.getElementById('dgTplId').value=x.id;
  document.getElementById('dgTplName').value=x.name;
  document.getElementById('dgTplDiff').value=x.difficulty;
  document.getElementById('dgTplPrice').value=x.ticketPrice;
  document.getElementById('dgTplMinP').value=x.minPlayers;
  document.getElementById('dgTplMaxP').value=x.maxPlayers;
  document.getElementById('dgTplTime').value=x.timeLimitMinutes;
  document.getElementById('dgTplLevel').value=x.monsterLevel;
  document.getElementById('dgTplSchem').value=x.schematic||'';
  document.getElementById('dgTplRequireKey').checked=!!x.requirePropertyKey;
  document.getElementById('dgTplRewardConfig').value=x.rewardConfig||'';
  document.getElementById('dgTplDesc').value=x.description||'';
  toast('已载入模板 '+id+'，修改后点保存','info');
}
async function saveDgTemplate(){
  var body={
    id:document.getElementById('dgTplId').value.trim()||undefined,
    name:document.getElementById('dgTplName').value.trim(),
    difficulty:document.getElementById('dgTplDiff').value.trim()||'NORMAL',
    ticketPrice:parseFloat(document.getElementById('dgTplPrice').value)||0,
    minPlayers:parseInt(document.getElementById('dgTplMinP').value)||1,
    maxPlayers:parseInt(document.getElementById('dgTplMaxP').value)||4,
    timeLimitMinutes:parseInt(document.getElementById('dgTplTime').value)||60,
    monsterLevel:parseInt(document.getElementById('dgTplLevel').value)||10,
    schematic:document.getElementById('dgTplSchem').value.trim(),
    requirePropertyKey:document.getElementById('dgTplRequireKey').checked,
    rewardConfig:document.getElementById('dgTplRewardConfig').value.trim(),
    description:document.getElementById('dgTplDesc').value.trim()
  };
  if(!body.name){toast('请填写名称','err');return;}
  var d=await api('POST',DG+'/templates',body);
  if(d.id){toast('模板已保存: '+d.id,'ok');document.getElementById('dgTplId').value='';loadDgTemplates();}
  else toast(d.error||'保存失败','err');
}
async function deleteDgTemplate(id){
  if(!confirm('确认删除副本模板 '+id+'？此操作不可撤销。'))return;
  var d=await api('POST',DG+'/templates/'+encodeURIComponent(id)+'/delete',{});
  if(d.message){toast(d.message,'ok');loadDgTemplates();}
  else toast(d.error||'删除失败','err');
}

async function loadDgInstances(){
  var st=document.getElementById('dgInstStatus').value;
  var d=await api('GET',DG+'/instances'+(st?'?status='+encodeURIComponent(st):''));
  var xs=(d&&d.instances)||[];
  var t='';
  xs.forEach(function(x){
    var badge=x.status==='ACTIVE'?'badge-active':x.status==='WAITING'?'badge-pending':'badge-closed';
    var canEnd=(x.status==='ACTIVE'||x.status==='WAITING');
    t+='<tr><td>'+escapeHtml(x.id)+'</td><td>'+escapeHtml(x.templateId||'')+'</td><td>'+escapeHtml(x.gridId||'')+'</td>'
      +'<td><span class="badge '+badge+'">'+x.status+'</span></td><td style="font-size:11px;">'+dgTime(x.startedAt)+'</td><td style="font-size:11px;">'+dgTime(x.expiresAt)+'</td>'
      +'<td style="font-size:10px;">'+escapeHtml(x.ownerUuid||'')+'</td>'
      +'<td><button class="btn btn-sm" onclick="dgInstDetail(\''+escapeAttr(x.id)+'\')">详情</button> '
      +(canEnd?'<button class="btn btn-sm btn-danger" onclick="dgForceEnd(\''+escapeAttr(x.id)+'\')">强制结束</button>':'')+'</td></tr>';
  });
  document.getElementById('dgInstBody').innerHTML=t||'<tr><td colspan="8" style="color:#666;">暂无实例</td></tr>';
}
async function dgInstDetail(id){
  var d=await api('GET',DG+'/instances/'+id+'?withLogs=true&logLimit=100');
  if(d.error){toast(d.error,'err');return;}
  var logs=(d.logs||[]).map(function(l){return '<tr><td style="font-size:11px;">'+dgTime(l.createdAt)+'</td><td>'+escapeHtml(l.eventType)+'</td><td style="font-size:10px;">'+escapeHtml(l.playerUuid||'')+'</td><td style="font-size:11px;color:#aaa;">'+escapeHtml(l.detail||'')+'</td></tr>';}).join('');
  var html='<div style="font-size:12px;line-height:1.8;">'
    +'<b>实例:</b> '+escapeHtml(d.id)+'<br><b>模板:</b> '+escapeHtml(d.templateId||'')+'<br><b>网格:</b> '+escapeHtml(d.gridId||'')+'<br>'
    +'<b>状态:</b> '+escapeHtml(d.status)+'<br><b>开始:</b> '+dgTime(d.startedAt)+'<br><b>到期:</b> '+dgTime(d.expiresAt)+'<br><b>所有者:</b> '+escapeHtml(d.ownerUuid||'')+'</div>'
    +'<h4 style="margin-top:12px;">事件日志</h4><div class="table-wrap"><table><thead><tr><th>时间</th><th>事件</th><th>玩家</th><th>详情</th></tr></thead><tbody>'+(logs||'<tr><td colspan="4" style="color:#666;">无日志</td></tr>')+'</tbody></table></div>';
  showModal('副本详情 '+escapeHtml(d.id),html);
}
async function dgForceEnd(id){
  if(!confirm('确定强制结束副本 '+id+'？玩家会被请出，副本房产将被清理。'))return;
  var d=await api('POST',DG+'/instances/'+id+'/force-end',{});
  if(d.ok){toast('已强制结束 '+id,'ok');loadDgInstances();}
  else toast(d.error||'操作失败','err');
}

async function loadDgGrids(){
  var d=await api('GET',DG+'/grids');
  document.getElementById('dgGridStats').innerHTML=
    '<div class="stat-card"><div class="stat-label">空闲网格</div><div class="stat-val">'+(d.freeCount!=null?d.freeCount:'—')+'</div></div>'
    +'<div class="stat-card"><div class="stat-label">最大网格</div><div class="stat-val">'+(d.maxGrids!=null?d.maxGrids:'—')+'</div></div>'
    +'<div class="stat-card"><div class="stat-label">已分配</div><div class="stat-val">'+((d.grids||[]).length)+'</div></div>';
  var t='';
  (d.grids||[]).forEach(function(g){
    var badge=g.status==='FREE'?'badge-active':g.status==='OCCUPIED'?'badge-pending':'badge-closed';
    t+='<tr><td>'+escapeHtml(g.id)+'</td><td>'+escapeHtml(g.world)+'</td><td>'+g.gridX+'</td><td>'+g.gridZ+'</td><td><span class="badge '+badge+'">'+g.status+'</span></td><td style="font-size:11px;">'+dgTime(g.occupiedSince)+'</td><td style="font-size:11px;">'+dgTime(g.lastUsedAt)+'</td></tr>';
  });
  document.getElementById('dgGridBody').innerHTML=t||'<tr><td colspan="7" style="color:#666;">暂无网格（开第一个副本时自动创建）</td></tr>';
}

async function loadDgConfig(){
  var d=await api('GET',DG+'/config');
  if(d.error){toast(d.error,'err');return;}
  var t='';
  Object.keys(d).forEach(function(k){
    var modeLabel=DG_NEXT[k]?'<span class="badge badge-pending">下次副本</span>':'<span class="badge badge-active">即时</span>';
    var inputId='dgCfg_'+k.replace(/[^a-zA-Z0-9]/g,'_');
    t+='<tr><td style="font-family:monospace;font-size:11px;">'+escapeHtml(k)+'</td>'
      +'<td><input id="'+inputId+'" value="'+escapeAttr(String(d[k]))+'" style="width:140px;"/></td>'
      +'<td>'+modeLabel+'</td>'
      +'<td><button class="btn btn-sm" onclick="saveDgConfig(\''+escapeAttr(k)+'\',\''+inputId+'\')">保存</button></td></tr>';
  });
  document.getElementById('dgConfigBody').innerHTML=t||'<tr><td colspan="4" style="color:#666;">无配置</td></tr>';
}
async function saveDgConfig(key,inputId){
  var val=document.getElementById(inputId).value.trim();
  var d=await api('POST',DG+'/config',{key:key,value:val});
  if(d.ok){toast(key+' 已更新（'+(d.mode==='IMMEDIATE'?'即时生效':'下次副本生效')+'）','ok');}
  else toast(d.error||'保存失败','err');
}

function hubRows(target, html, colspan, empty){
  var el=document.getElementById(target);if(el)el.innerHTML=html||'<tr><td colspan="'+colspan+'" style="color:var(--faint);">'+(empty||'暂无数据')+'</td></tr>';
}
function hubStats(target, values){
  var el=document.getElementById(target);if(!el)return;
  el.innerHTML=values.map(function(v){return '<div class="stat-card"><div class="stat-val">'+v.value+'</div><div class="stat-label">'+v.label+'</div></div>';}).join('');
}
function hubBadge(status){
  var ok=['ACTIVE','FOR_SALE','FREE','OPEN','ENABLED'].indexOf(String(status||'').toUpperCase())>=0;
  var pending=['WAITING','PENDING','STATE_OWNED','OCCUPIED'].indexOf(String(status||'').toUpperCase())>=0;
  return '<span class="badge '+(ok?'badge-active':(pending?'badge-pending':'badge-closed'))+'">'+escapeHtml(status||'—')+'</span>';
}
async function loadCorpsHub(){
  var data=await Promise.all([api('GET','/api/enterprise/list'),api('GET','/api/bank/list'),api('GET','/api/realestate/zones')]);
  var ents=data[0].enterprises||[],banks=data[1].banks||[],zones=data[2].zones||[];
  var corpBalance=ents.reduce(function(n,e){return n+Number(e.corporate_balance||0);},0);
  var bankAssets=banks.reduce(function(n,b){return n+Number(b.total_assets||b.capital||0);},0);
  hubStats('corpsHubStats',[{value:fmt(ents.length),label:'ACTIVE CORPORATIONS'},{value:fmt(corpBalance),label:'CORP LIQUIDITY'},{value:fmt(banks.length),label:'BANK NETWORKS'},{value:fmt(bankAssets),label:'BANK ASSETS'},{value:fmt(zones.length),label:'PLANNED ZONES'}]);
  hubRows('corpsHubEntBody',ents.slice(0,8).map(function(e){return '<tr><td>'+escapeHtml(e.name||e.id)+'</td><td>'+escapeHtml(e.industry||e.type||'—')+'</td><td>'+fmt(e.corporate_balance||0)+'</td><td>'+fmt(e.employee_count||0)+'</td><td>'+hubBadge(e.status)+'</td><td><button class="btn btn-sm" onclick="openEntMgr(\''+escapeAttr(e.id)+'\')">下钻</button></td></tr>';}).join(''),6,'暂无企业');
  hubRows('corpsHubBankBody',banks.slice(0,8).map(function(b){return '<tr><td>'+escapeHtml(b.name||b.id)+'</td><td>'+escapeHtml(b.type||'—')+'</td><td>'+fmt(b.total_assets||b.capital||0)+'</td><td>'+pct(b.loan_rate)+'</td><td>'+hubBadge(b.status)+'</td><td><button class="btn btn-sm" onclick="openBankMgr(\''+escapeAttr(b.id)+'\')">下钻</button></td></tr>';}).join(''),6,'暂无银行');
  hubRows('corpsHubZoneBody',zones.slice(0,8).map(function(z){return '<tr><td>'+escapeHtml(z.name||z.id)+'</td><td>'+escapeHtml(ZONE_TYPE_CN(z.type))+'</td><td>'+escapeHtml(z.world||'—')+'</td><td>'+fmt(z.basePrice||0)+'</td><td>'+hubBadge(z.status)+'</td><td><button class="btn btn-sm" onclick="switchTab(\'re-zones\')">查看</button></td></tr>';}).join(''),6,'房地产模块未加载或暂无区域');
}
async function loadProtocolsHub(){
  var data=await Promise.all([api('GET','/api/admin/mo/list'),api('GET',DG+'/templates'),api('GET',DG+'/instances'),api('GET','/politic/api/offices')]);
  var mos=data[0].orders||[],templates=data[1].templates||[],instances=data[2].instances||[],offices=data[3].offices||[];
  var activeInstances=instances.filter(function(x){return x.status==='ACTIVE'||x.status==='WAITING';}).length;
  hubStats('protocolsHubStats',[{value:fmt(mos.filter(function(x){return x.status==='ACTIVE';}).length),label:'ACTIVE MAJOR ORDERS'},{value:fmt(templates.length),label:'DUNGEON TEMPLATES'},{value:fmt(activeInstances),label:'LIVE INSTANCES'},{value:fmt(offices.length),label:'ACTIVE OFFICES'}]);
  var moHtml=mos.slice(0,6).map(function(o){var p=Math.max(0,Math.min(100,Number(o.progressPct||0)*100));return '<div class="hub-feed-item"><span><b>'+escapeHtml(o.title||o.id)+'</b><small>'+fmt(o.currentValue||0)+' / '+fmt(o.targetValue||0)+' · '+p.toFixed(0)+'%</small></span>'+hubBadge(o.status)+'</div>';}).join('');
  document.getElementById('protocolsHubMo').innerHTML=moHtml||'<div style="color:var(--faint);">暂无主任务</div>';
  hubRows('protocolsHubInstanceBody',instances.slice(0,10).map(function(x){return '<tr><td>'+escapeHtml(x.id)+'</td><td>'+escapeHtml(x.templateId||'—')+'</td><td>'+hubBadge(x.status)+'</td><td>'+escapeHtml(dgTime(x.expiresAt)||'—')+'</td><td><button class="btn btn-sm" onclick="dgInstDetail(\''+escapeAttr(x.id)+'\')">详情</button></td></tr>';}).join(''),5,'暂无副本实例');
}
async function loadMarketHub(){
  var data=await Promise.all([api('GET','/api/market/stats'),api('GET','/api/eco/public-info'),api('GET','/api/listings'),api('GET','/api/blindbox/pools')]);
  var stats=data[0],prices=data[1].prices||[],listings=data[2].listings||[],pools=data[3].pools||[];
  hubStats('marketHubStats',[{value:fmt(stats.activeListings||listings.length),label:'ACTIVE LISTINGS'},{value:fmt(stats.storedItems||0),label:'WAREHOUSE ITEMS'},{value:fmt(pools.filter(function(x){return x.enabled;}).length),label:'LIVE GACHA POOLS'},{value:stats.officialBuyEnabled?'ONLINE':'OFFLINE',label:'OFFICIAL BUY'}]);
  hubRows('marketHubListingBody',listings.slice(0,10).map(function(l){return '<tr><td>'+escapeHtml(l.material||'—')+'</td><td>'+escapeHtml(l.sellerName||'—')+'</td><td>'+fmt(l.quantity||0)+'</td><td>'+fmt(l.unitPrice||0)+'</td><td>'+fmt(l.totalPrice||0)+'</td></tr>';}).join(''),5,'暂无活跃挂单');
  hubRows('marketHubPriceBody',prices.slice(0,10).map(function(p){return '<tr><td>'+escapeHtml(p.chineseName||p.material||'—')+'</td><td>'+fmt(p.buyPrice||0)+'</td><td>'+fmt(p.marketAvg||0)+'</td><td>'+escapeHtml(p.trend||'—')+'</td></tr>';}).join(''),4,'暂无官方材料价格');
}
async function loadSecurityHub(){
  var data=await Promise.all([api('GET','/api/audit/log?limit=20'),api('GET','/api/admin/bans'),api('GET','/api/admin/eco/features')]);
  var logs=data[0].logs||[],bans=data[1].bans||[],features=data[2].features||data[2]||{};
  var openFeatures=Array.isArray(features)?features.length:Object.keys(features).filter(function(k){return features[k];}).length;
  hubStats('securityHubStats',[{value:fmt(logs.length),label:'RECENT AUDIT EVENTS'},{value:fmt(bans.length),label:'ACTIVE MARKET BANS'},{value:fmt(openFeatures),label:'OPEN PLAYER FEATURES'}]);
  hubRows('securityHubAuditBody',logs.slice(0,12).map(function(x){var at=x.createdAt||x.created_at;return '<tr><td>'+escapeHtml(at?new Date(Number(at)*1000).toLocaleString('zh-CN'):'—')+'</td><td>'+escapeHtml(x.action||'—')+'</td><td>'+escapeHtml(x.playerName||x.player_name||x.playerUuid||'SYSTEM')+'</td><td>'+escapeHtml(x.targetId||x.target_id||'—')+'</td><td>'+escapeHtml(x.details||'')+'</td></tr>';}).join(''),5,'暂无审计记录');
}
