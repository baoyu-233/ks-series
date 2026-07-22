var TOKEN=(new URL(location)).searchParams.get('token')||'';
if(TOKEN&&history.replaceState){var cleanUrl=new URL(location.href);cleanUrl.searchParams.delete('token');history.replaceState(null,'',cleanUrl.pathname+cleanUrl.search+cleanUrl.hash);}
var API='/ks-Eco';
var myPlayerUuid='';
function H(){return TOKEN?{'Authorization':'Bearer '+TOKEN}:{};}
async function resolvePlayerRef(value){
  var ref=(value||'').trim();
  if(ref==='__self__'||ref.toLowerCase()==='self'){
    if(!myPlayerUuid) throw new Error('未获取到当前玩家身份，请刷新页面后重试');
    return myPlayerUuid;
  }
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
    else if(typeof out[key]==='number'&&/(count|total|balance|assets|amount)/i.test(key))out[key]=0;
  });
  return out;
}
function ksPreviewApi(path){
  /* Static-preview fixtures only; gated on preview=1 + localhost exactly like admin.html. */
  if(path==='/api/eco/bootstrap')return {myUuid:'afab46b2-0000-4000-8000-000000000001',myName:'CyberKitsune',myBalance:1330090,enabledFeatures:['market','storage','tax','bank','enterprise','bidding','blindbox','ent_blindbox','realestate','politic','invites','exchange']};
  if(path==='/api/my-banks')return {banks:[{id:'B-AURORA',name:'曙光发展银行',type:'COMMERCIAL',myBalance:482000,deposit_rate:.012,loan_rate:.02,isOwner:true,status:'ACTIVE'},{id:'B-NOVA',name:'新星储备银行',type:'COMMERCIAL',myBalance:126500,deposit_rate:.015,loan_rate:.026,isOwner:false,status:'ACTIVE'}]};
  if(path==='/api/my-loans')return {loans:[{id:'LN-31',bank_id:'B-NOVA',principal:200000,remaining:84200,interest_rate:.026,status:'ACTIVE',dueAt:1784620800}]};
  if(path==='/api/bank/loan/my-requests')return {requests:[{id:'RQ-7',bank_id:'B-AURORA',principal:50000,term_days:30,status:'PENDING',requested_at:1783940000}]};
  if(path.indexOf('/api/bank/rates/get')===0)return {loanRate:.02,depositRate:.012};
  if(path==='/api/bank/guidance')return {available:true,starterAmount:20000,interestRate:.005,termDays:30,totalCap:2000000,claimedTotal:400000,dailyCap:100000,claimedToday:20000,assets:96000000,reserve:12000000,eligible:true};
  if(path==='/api/bank/collateral-auctions')return {auctions:[]};
  if(path==='/api/bank/list')return {banks:[{id:'B-AURORA',name:'曙光发展银行',type:'COMMERCIAL',total_assets:330182382,loan_rate:.02,interest_rate:.012,status:'ACTIVE'},{id:'B-NOVA',name:'新星储备银行',type:'COMMERCIAL',total_assets:210093000,loan_rate:.026,interest_rate:.015,status:'ACTIVE'}]};
  if(path==='/api/bank/gameplay/dashboard')return {available:true,demandDeposits:608500,termDeposits:50000,totalDeposits:658500,loanOutstanding:84200,netBankPosition:574300,nextPayment:18000,credit:{score:742,tier:'B',tierLabel:'B级',factors:[{label:'按期结清记录',points:35},{label:'当前在贷笔数',points:-8}],nextSteps:['继续按期还款']},termDepositsList:[{id:'TD-DEMO',bank_id:'B-AURORA',bank_name:'曙光发展银行',product_code:'TERM_30',product_name:'三十日定期',principal:50000,fixed_rate:.012,matures_at:1787000000,status:'ACTIVE'}]};
  if(path.indexOf('/api/bank/deposit-products')===0)return {products:[{product_code:'TERM_7',name:'七日稳盈',term_days:7,fixed_rate:.004,min_amount:100},{product_code:'TERM_30',name:'三十日定期',term_days:30,fixed_rate:.012,min_amount:500},{product_code:'TERM_90',name:'九十日增益',term_days:90,fixed_rate:.025,min_amount:1000}]};
  if(path==='/api/bank/loan/products')return {products:[{code:'CONSUMER',name:'消费信用贷',minAmount:100,maxAmount:20000,minTermDays:1,maxTermDays:30},{code:'HOME',name:'住房抵押贷',minAmount:5000,maxAmount:50000,minTermDays:30,maxTermDays:90},{code:'BUSINESS',name:'经营周转贷',minAmount:1000,maxAmount:50000,minTermDays:7,maxTermDays:90},{code:'PROJECT',name:'项目履约贷',minAmount:1000,maxAmount:50000,minTermDays:7,maxTermDays:90}]};
  if(path.indexOf('/api/bank/loan/collateral')===0)return {assets:[{assetType:'HOUSE',assetRef:'H-DEMO',appraisedValue:80000,loanToValue:.75,maxLoan:60000}]};
  if(path==='/api/bank/equity/portfolio')return {portfolio:[{bank_id:'B-AURORA',name:'曙光发展银行',shares:620000,ownershipPercent:62}]};
  if(path.indexOf('/api/bank/equity/cap-table')===0)return {success:true,totalIssued:1000000,authorizedShares:10000000,paidInCapital:330000,shareholders:[{shareholder_uuid:'afab46b2-0000-4000-8000-000000000001',shares:620000,ownershipPercent:62,controlling:true},{shareholder_uuid:'afab46b2-0000-4000-8000-000000000002',shares:380000,ownershipPercent:38,controlling:false}]};
  if(path.indexOf('/api/bank/equity/offerings')===0)return {offerings:[{id:'SO-DEMO',bank_id:'B-AURORA',offer_type:'PRIMARY',seller_uuid:null,shares:50000,price_per_share:1.2,created_by:'afab46b2-0000-4000-8000-000000000002',status:'OPEN'}]};
  if(path.indexOf('/api/bank/operations')===0)return {success:true,liquidity:300000,totalDeposits:200000,loanAssets:180000,equity:280000,liquidityRatio:1.5,capitalRatio:.58,badDebtRatio:.02,rating:'A',operatingStatus:'NORMAL',realizedLoanInterest:18000,interestExpense:5000,loanLossProvision:1800,dividendsPaid:3000,retainedEarnings:8200};
  if(path.indexOf('/api/bank/dividends')===0)return {dividends:[{id:'BD-DEMO',amount:3000,recipient_count:2,status:'FINALIZED',declared_at:1784550000}]};
  if(path.indexOf('/api/bank/loan/restructure/requests')===0)return {requests:[]};
  if(path==='/api/bank/loan/quote')return {success:true,allowed:true,productName:'消费信用贷',effectiveRate:.036,totalDue:10360,installments:4,tierLabel:'B级',validUntil:Math.floor(Date.now()/1000)+120};
  if(path==='/api/my-enterprises')return {enterprises:[{id:'ENT-NOVA',name:'新星联合工业',level:6,industry:'INDUSTRY',type:'PRIVATE',current_assets:4820000,registered_capital:2500000,corporate_balance:4820000,employee_count:12,myRole:'OWNER',status:'ACTIVE'},{id:'ENT-ORBIT',name:'轨道物流集团',level:3,industry:'OTHER',type:'PRIVATE',current_assets:2360000,registered_capital:1400000,corporate_balance:2360000,employee_count:7,myRole:'MANAGER',status:'ACTIVE'}]};
  if(path==='/api/enterprise/list')return {enterprises:[{id:'ENT-NOVA',name:'新星联合工业',level:6,industry:'INDUSTRY',current_assets:4820000,corporate_balance:4820000,employee_count:12,status:'ACTIVE'},{id:'ENT-AGRI',name:'曙光农业社',level:4,industry:'AGRICULTURE',current_assets:1560000,corporate_balance:1560000,employee_count:18,status:'ACTIVE'}]};
  if(path.indexOf('/api/enterprise/dividends')===0)return {dividends:[{enterprise_id:'ENT-NOVA',amount:120000,tax:12000,declared_at:1783500000,status:'PAID'},{enterprise_id:'ENT-NOVA',amount:98000,tax:9800,declared_at:1783100000,status:'PAID'},{enterprise_id:'ENT-NOVA',amount:150000,tax:15000,declared_at:1783900000,status:'PAID'}]};
  if(path==='/api/blindbox/pools')return {pools:[{id:'tech-cache',name:'科技补给箱',enabled:true,price:680,pityMax:50,poolType:'PUBLIC',pullCount:1240,lootCount:14,description:'高级红石与机械部件',allowedIndustries:'',requiredLandZoneTypes:''},{id:'agri-crate',name:'丰收种子箱',enabled:true,price:320,pityMax:30,poolType:'PUBLIC',pullCount:860,lootCount:9,description:'稀有作物与农业增益',allowedIndustries:'AGRICULTURE',requiredLandZoneTypes:'AGRICULTURAL'}]};
  if(path.indexOf('/api/blindbox/loot')===0)return {loot:[{material:'DIAMOND',displayName:'钻石',rarity:'SSR',weight:2},{material:'IRON_INGOT',displayName:'铁锭',rarity:'R',weight:40},{material:'GOLD_INGOT',displayName:'金锭',rarity:'SR',weight:12},{material:'EMERALD',displayName:'绿宝石',rarity:'SR',weight:8}]};
  if(path==='/api/blindbox/my-pulls')return {pulls:[{poolId:'tech-cache',material:'IRON_INGOT',rarity:'R',pulledAt:1783950000}]};
  if(path.indexOf('/api/blindbox/pity')===0)return {pity:{'tech-cache':{count:23,max:50}}};
  if(path.indexOf('/api/enterprise/blindbox/pools')===0)return {enterpriseLevel:6,pools:[{id:'ind-supply',name:'工业物资池',enabled:true,price:1500,poolType:'ENTERPRISE',pullCount:210,lootCount:11,minEnterpriseLevel:4,description:'企业专属工业物资',allowedIndustries:'INDUSTRY',requiredLandZoneTypes:'INDUSTRIAL'},{id:'executive-cache',name:'高阶企业战略箱',enabled:true,price:4800,poolType:'ENTERPRISE',pullCount:38,lootCount:6,minEnterpriseLevel:8,description:'高等级企业专属池',allowedIndustries:'INDUSTRY',requiredLandZoneTypes:''}]};
  if(path==='/api/enterprise/projects')return {projects:[{id:'PJ-701',title:'跨河大桥建设',budget:900000,deadline:1784800000,bidCount:3,status:'OPEN',publisherName:'元老院'},{id:'PJ-688',title:'城郊铁路延伸',budget:1500000,deadline:1784200000,bidCount:5,status:'AWARDED',publisherName:'新星联合工业'}]};
  if(path==='/api/enterprise/procurements')return {procurements:[{id:'PC-95',title:'采购圆石 30000',quantity:30000,budget:120000,deadline:1784700000,bidCount:2,status:'OPEN',enterprise_id:'ENT-NOVA'}]};
  if(path==='/api/enterprise/my-bids')return {bids:[{id:'BD-51',projectId:'PJ-701',totalPrice:860000,status:'PENDING',submitted_at:1783940000}]};
  if(path==='/politic/api/my-office')return {office:'SENATOR',officeName:'元老',since:1783000000};
  if(path==='/politic/api/offices?type=all')return {offices:[{officeType:'CONSUL',playerName:'baoyu_233',playerUuid:'afab46b2-0000-4000-8000-000000000000'},{officeType:'SENATOR',playerName:'CyberKitsune',playerUuid:'afab46b2-0000-4000-8000-000000000001'},{officeType:'SENATOR',playerName:'NovaMiner',playerUuid:'afab46b2-0000-4000-8000-000000000002'},{officeType:'TRIBUNE',playerName:'RailWorks',playerUuid:'afab46b2-0000-4000-8000-000000000003'}]};
  if(path==='/politic/api/proposals')return {proposals:[{id:'PR-12',title:'下调市场交易税至 4%',proposalType:'SET_TAX_RATE',status:'VOTING',yea:3,nay:1,proposerName:'CyberKitsune',createdAt:1783900000}]};
  if(path==='/politic/api/tribune-election')return {status:'OPEN',candidates:[{name:'RailWorks',votes:6},{name:'NovaMiner',votes:4}],endsAt:1784500000};
  if(path==='/api/mo/list')return {orders:[{id:'MO-3',title:'城市电网扩容',metricType:'ITEM_SUBMIT',currentValue:6400,targetValue:10000,status:'ACTIVE'}]};
  if(path==='/api/eco/public-info')return {prices:[{material:'DIAMOND',chineseName:'钻石',buyPrice:130,marketAvg:124,trend:'UP'},{material:'IRON_INGOT',chineseName:'铁锭',buyPrice:7.01,marketAvg:7.65,trend:'DOWN'}],taxes:{}};
  if(path==='/api/my-tax-records')return {records:[{id:31,category:'MARKET_TRADE',base_amount:4600,tax_rate:.05,tax_amount:230,collected_at:1783880000},{id:32,category:'MARKET_TRADE',base_amount:8200,tax_rate:.05,tax_amount:410,collected_at:1783920000},{id:33,category:'DIVIDEND_TAX',base_amount:12000,tax_rate:.10,tax_amount:1200,collected_at:1783960000}]};
  if(path==='/api/player/invites')return {invites:[]};
  if(path==='/api/player/bans')return {bans:[]};
  if(path==='/api/realestate/my-plots')return {plots:[{id:'P58d49c39',zoneId:'Z459f0700',world:'world',x1:-376,z1:-104,x2:-232,z2:-28,ownerType:'ENTERPRISE',ownerId:'05e9d6b9',price:10000,taxRate:.05,status:'PURCHASED',purchasedAt:1783528747}]};
  if(path==='/api/realestate/zones')return {moduleLoaded:true,zones:[{id:'Z459f0700',name:'TEST-AG',world:'world',x1:-376,z1:-104,x2:-232,z2:-28,type:'AGRICULTURAL',basePrice:10000,taxRate:.05,status:'FOR_SALE',plotCount:1,houseCount:0,maxPlots:0,dungeonTemplateId:null}]};
  if(path==='/api/realestate/plots')return {plots:[{id:'P58d49c39',zoneId:'Z459f0700',world:'world',x1:-376,z1:-104,x2:-232,z2:-28,ownerType:'ENTERPRISE',ownerId:'05e9d6b9',price:10000,taxRate:.05,status:'PURCHASED',purchasedAt:1783528747,zoneType:'AGRICULTURAL'}]};
  if(path.indexOf('/api/realestate/region/voxels?')===0){
    var u=new URL(path,'http://preview.local'),x1=Number(u.searchParams.get('x1')),z1=Number(u.searchParams.get('z1'));
    var x2=Number(u.searchParams.get('x2')),z2=Number(u.searchParams.get('z2')),w=x2-x1+1,d=z2-z1+1,blocks=[];
    for(var x=0;x<w;x+=2)for(var z=0;z<d;z+=2)blocks.push({x:x,y:0,z:z,color:0x4e8b58,mat:'GRASS_BLOCK'});
    for(var bx=6;bx<w-5;bx+=16)for(var bz=6;bz<d-5;bz+=16){
      var h=5+Math.abs((x1+bx+z1+bz)%11);
      for(var y=1;y<=h;y++)for(var edge=0;edge<5;edge++){
        blocks.push({x:bx+edge,y:y,z:bz,color:0x75838a,mat:'STONE_BRICKS'});
        blocks.push({x:bx+edge,y:y,z:bz+4,color:0x75838a,mat:'STONE_BRICKS'});
        if(edge>0&&edge<4){blocks.push({x:bx,y:y,z:bz+edge,color:0x8ca6b0,mat:'LIGHT_BLUE_STAINED_GLASS'});blocks.push({x:bx+4,y:y,z:bz+edge,color:0x8ca6b0,mat:'LIGHT_BLUE_STAINED_GLASS'});}
      }
    }
    return {world:'world',x1:x1,y1:64,z1:z1,x2:x2,y2:82,z2:z2,truncated:false,blocks:blocks};
  }
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
    var r=await fetch(API+path,o),contentType=(r.headers.get('content-type')||'').toLowerCase(),text=await r.text(),data;
    try{data=text?JSON.parse(text):{};}
    catch(parseError){
      throw new Error('服务器返回了非 JSON 响应（HTTP '+r.status+(contentType?' · '+contentType:'')+'），请刷新页面后重试');
    }
    if(!r.ok&&!data.error)data.error='请求失败（HTTP '+r.status+'）';
    return data;
  }catch(e){return {error:e.message}}}
function toast(msg,type){
  var b=document.getElementById('toast-box');
  var d=document.createElement('div');d.className='toast toast-'+(type||'info');d.textContent=msg;
  b.appendChild(d);setTimeout(function(){d.remove();},4000);
}
function fmt(n){return n!=null?Number(n).toLocaleString():'0';}
function pct(n){return n!=null?(Number(n)*100).toFixed(2)+'%':'—';}
function bidderLabel(b){return (b.bidderName||b.enterprise_id||b.bidder_uuid||'').toString();}
function bidTypeLabel(t){return t==='ENTERPRISE'?'企业':(t==='PLAYER'?'个人':(t||''));}
function bidStatusLabel(s){return {PENDING:'待评标',AWARDED:'已中标',REJECTED:'未中标',PENDING_DEPOSIT:'待缴保证金'}[s]||s;}
function projectStatusLabel(s){return {OPEN:'招标中',AWARDED:'已中标',PENDING_DEPOSIT:'待缴保证金'}[s]||s;}
function projectStatusBadge(s){return s==='OPEN'?'badge-active':(s==='PENDING_DEPOSIT'?'badge-pending':(s==='AWARDED'?'badge-owner':'badge-closed'));}
function showModal(title,bodyHTML){
  document.getElementById('modalContent').innerHTML='<button class="close" onclick="closeModal()">✕</button><h3>'+title+'</h3>'+bodyHTML;
  document.getElementById('modalOverlay').classList.add('show');
}
function closeModal(){document.getElementById('modalOverlay').classList.remove('show');}

// ====== NAVIGATION ======
var TAB_FEATURE={'my-banks':'bank','blindbox':'blindbox','ent-blindbox':'ent_blindbox','realestate':'realestate','politic':'politic','my-enterprises':'enterprise','bidding':'bidding','my-invites':'invites'};
var enabledFeatureSet=new Set();
function applyFeatureGates(list){
  enabledFeatureSet=new Set(list||[]);
  document.querySelectorAll('#sidebar a[data-feature]').forEach(function(a){
    var f=a.getAttribute('data-feature');
    a.style.display=enabledFeatureSet.has(f)?'':'none';
  });
  document.querySelectorAll('#sidebar .nav-hub').forEach(function(h){
    var any=false;h.querySelectorAll('a').forEach(function(a){if(a.style.display!=='none')any=true;});
    h.style.display=any?'':'none';
  });
}
function switchTab(tabId){
  var feat=TAB_FEATURE[tabId];
  if(feat && !enabledFeatureSet.has(feat))tabId='terminal';
  document.querySelectorAll('.tab-section').forEach(function(t){t.classList.remove('active');});
  document.querySelectorAll('#sidebar .sb-nav a,#sidebar .hub-label').forEach(function(a){a.classList.remove('active');});
  var tab=document.getElementById('tab-'+tabId);if(tab)tab.classList.add('active');
  document.querySelectorAll('#sidebar .nav-hub').forEach(function(h){h.classList.remove('active');});
  var nav=document.querySelector('#sidebar [data-nav="'+tabId+'"]')||document.querySelector('#sidebar [data-hub-nav="'+tabId+'"]');
  if(nav){nav.classList.add('active');var hub=nav.closest('.nav-hub');if(hub){hub.classList.add('active');var eb=document.getElementById('pageEyebrow');if(eb)eb.textContent=hub.getAttribute('data-label')||'KSECO NET';}}
  var titles={'terminal':'干员总览 // MY TERMINAL','grid-hub':'交易网络 // THE GRID','empires-hub':'实业控制 // MY EMPIRES','drops-hub':'极客娱乐 // QUESTS & DROPS','my-banks':'资金网络 // BANKING GRID','blindbox':'盲盒卡池 // GACHA','ent-blindbox':'企业卡池 // CORP GACHA','realestate':'实业地块 // MY EMPIRES','politic':'元老院 // SENATE','my-enterprises':'我的企业 // MY CORPS','bidding':'招投标 // TENDERS','my-invites':'合资邀请 // INVITES','my-tax':'税收记录 // TAX LOG','public-info':'市场行情 // THE GRID'};
  titles.mo='主任务 // MAJOR ORDERS';
  document.getElementById('pageTitle').textContent=titles[tabId]||tabId;
  if(tabId==='terminal')loadTerminal();
  if(tabId==='grid-hub')loadGridHub();
  if(tabId==='empires-hub')loadEmpiresHub();
  if(tabId==='drops-hub')loadDropsHub();
  if(tabId==='mo')loadMo();
  if(tabId==='my-banks')loadMyBanks();
  if(tabId==='blindbox')loadBbPools();
  if(tabId==='ent-blindbox')loadEntBbEnts();
  if(tabId==='realestate')loadMyRe();
  if(tabId==='politic')loadPolitic();
  if(tabId==='my-enterprises')loadMyEnterprises();
  if(tabId==='bidding')loadProjectsBrowse();
  if(tabId==='my-invites')loadMyInvites();
  if(tabId==='my-tax')loadMyTax();
  if(tabId==='public-info')loadPublicInfo();
}
function switchHub(tabId){
  switchTab(tabId);
  document.querySelectorAll('#sidebar .hub-label').forEach(function(el){el.classList.remove('active');});
  var label=document.querySelector('#sidebar [data-hub-nav="'+tabId+'"]');
  if(label){label.classList.add('active');var hub=label.closest('.nav-hub');if(hub)hub.classList.add('active');}
}
async function loadMo(){
  var d=await api('GET','/api/mo/list');
  var orders=d.orders||[];
  var html='';
  orders.forEach(function(o){
    var pct=Math.max(0,Math.min(1,o.progressPct||0));
    html+='<div style="padding:10px 0;border-bottom:1px solid rgba(255,255,255,.08);">';
    html+='<div style="display:flex;justify-content:space-between;gap:10px;"><b>'+escapeHtml(o.title||'MO')+'</b><span class="badge">'+(o.status||'ACTIVE')+'</span></div>';
    if(o.description)html+='<div style="color:#aaa;font-size:12px;margin:4px 0;">'+escapeHtml(o.description)+'</div>';
    html+='<div style="height:8px;background:rgba(255,255,255,.06);border-radius:4px;overflow:hidden;margin:8px 0;"><div style="height:100%;width:'+(pct*100).toFixed(1)+'%;background:linear-gradient(90deg,#F3DFA8,#C09A55);transition:width .6s cubic-bezier(.22,.9,.32,1);"></div></div>';
    html+='<div style="font-size:12px;color:#bbb;">'+fmt(o.currentValue||0)+' / '+fmt(o.targetValue||0)+' ('+Math.round(pct*100)+'%)</div>';
    html+='</div>';
  });
  document.getElementById('moList').innerHTML=html||'<div style="color:#777;">暂无主任务</div>';
}

function switchBankSub(sub,ev){
  document.querySelectorAll('.bank-sub').forEach(function(s){s.classList.remove('active');});
  document.querySelectorAll('#tab-my-banks .inline-tab').forEach(function(t){t.classList.remove('active');});
  document.getElementById('bank-sub-'+sub).classList.add('active');
  var bankButton=ev&&ev.currentTarget?ev.currentTarget:document.querySelector('#tab-my-banks .inline-tab[onclick*="'+sub+'"]');if(bankButton)bankButton.classList.add('active');
  if(sub==='browse')loadAllBanks();
}
function switchEntSub(sub,ev){
  document.querySelectorAll('.ent-sub').forEach(function(s){s.classList.remove('active');});
  document.querySelectorAll('#tab-my-enterprises .inline-tab').forEach(function(t){t.classList.remove('active');});
  document.getElementById('ent-sub-'+sub).classList.add('active');
  var entButton=ev&&ev.currentTarget?ev.currentTarget:document.querySelector('#tab-my-enterprises .inline-tab[onclick*="'+sub+'"]');if(entButton)entButton.classList.add('active');
  if(sub==='browse')loadAllEnterprises();
}
function switchBidSub(sub,ev){
  document.querySelectorAll('.bid-sub').forEach(function(s){s.classList.remove('active');});
  document.querySelectorAll('#tab-bidding .inline-tab').forEach(function(t){t.classList.remove('active');});
  document.getElementById('bid-sub-'+sub).classList.add('active');
  var bidButton=ev&&ev.currentTarget?ev.currentTarget:document.querySelector('#tab-bidding .inline-tab[onclick*="'+sub+'"]');if(bidButton)bidButton.classList.add('active');
  if(sub==='projects')loadProjectsBrowse();else if(sub==='procurement')loadProcurementsBrowse();else if(sub==='mybids')loadMyBids();else loadMyPublished();
}

// ====== MY BANKS ======
// ====== MY TERMINAL (个人终端聚合) ======
async function loadTerminal(){
  var boot=await api('GET','/api/eco/bootstrap');
  if(boot&&boot.myUuid)myPlayerUuid=boot.myUuid;
  var name=boot&&boot.myName?boot.myName:'';
  if(name){
    document.getElementById('termName').textContent=name;
    document.getElementById('termAvatar').src='https://mc-heads.net/body/'+encodeURIComponent(name)+'/100';
  }
  document.getElementById('termUuid').textContent=myPlayerUuid||'UNLINKED';
  var wallet=(boot&&boot.myBalance!=null)?Number(boot.myBalance):null;
  var bankTotal=0,bankCnt=0,entAssets=0,plotCnt=0,plotVal=0,taxTotal=0,taxCnt=0;
  var ents=[],plots=[];
  var jobs=[];
  if(enabledFeatureSet.has('bank'))jobs.push(api('GET','/api/my-banks').then(function(d){(d.banks||[]).forEach(function(b){bankTotal+=Number(b.myBalance||0);bankCnt++;});}).catch(function(){}));
  if(enabledFeatureSet.has('enterprise'))jobs.push(api('GET','/api/my-enterprises').then(function(d){ents=d.enterprises||[];ents.forEach(function(e){entAssets+=Number(e.current_assets||0);});}).catch(function(){}));
  if(enabledFeatureSet.has('realestate'))jobs.push(api('GET','/api/realestate/my-plots').then(function(d){plots=d.plots||[];plots.forEach(function(p){plotCnt++;plotVal+=Number(p.price||0);});}).catch(function(){}));
  jobs.push(api('GET','/api/my-tax-records').then(function(d){var recs=d.records||[];taxCnt=recs.length;recs.forEach(function(r){taxTotal+=Number(r.tax_amount||0);});
    var t='';recs.slice(0,6).forEach(function(r){t+='<tr><td>'+r.category+'</td><td>'+fmt(r.base_amount)+'</td><td style="color:#FF3DF2;">'+fmt(r.tax_amount)+'</td><td style="font-size:11px;color:#4E6B85;">'+new Date(r.collected_at*1000).toLocaleString('zh-CN')+'</td></tr>';});
    document.getElementById('termTaxBody').innerHTML=t||'<tr><td colspan="4" style="color:#4E6B85;">暂无税单记录</td></tr>';
  }).catch(function(){}));
  try{await Promise.all(jobs);}catch(e){}
  var net=(wallet||0)+bankTotal+entAssets+plotVal;
  document.getElementById('termNet').textContent=fmt(net);
  var st='';
  st+='<div class="stat-card"><div class="stat-val">'+(wallet!=null?fmt(wallet):'—')+'</div><div class="stat-label">WALLET · 钱包资金</div></div>';
  st+='<div class="stat-card" onclick="switchTab(\'my-banks\')"><div class="stat-val">'+fmt(bankTotal)+'</div><div class="stat-label">DEPOSITS · 银行存款 ('+bankCnt+')</div></div>';
  st+='<div class="stat-card" onclick="switchTab(\'my-enterprises\')"><div class="stat-val">'+fmt(entAssets)+'</div><div class="stat-label">CORP ASSETS · 企业资产 ('+ents.length+')</div></div>';
  st+='<div class="stat-card" onclick="switchTab(\'realestate\')"><div class="stat-val">'+fmt(plotVal)+'</div><div class="stat-label">LAND · 地块估值 ('+plotCnt+')</div></div>';
  st+='<div class="stat-card" onclick="switchTab(\'my-tax\')"><div class="stat-val">'+fmt(taxTotal)+'</div><div class="stat-label">TAX PAID · 累计纳税 ('+taxCnt+')</div></div>';
  document.getElementById('termStats').innerHTML=st;
  var h='';
  ents.forEach(function(e){h+='<span class="term-chip" onclick="ksEntDrill(\''+e.id+'\')">🏢 '+escapeHtml(e.name)+' <b>'+fmt(e.current_assets)+'</b></span>';});
  window._termPlots=plots;
  plots.slice(0,12).forEach(function(p,i){h+='<span class="term-chip" onclick="if(window.openEmpirePlotData)openEmpirePlotData(window._termPlots['+i+'])">⬢ '+escapeHtml(p.zoneId||'')+' ['+p.x1+','+p.z1+'] <b>'+fmt(p.price)+'</b></span>';});
  document.getElementById('termHoldings').innerHTML=h||'<span style="color:#4E6B85;font-size:12px;">暂无产业 — 前往「实业控制」开始建设你的赛博帝国</span>';
}
function playerHubRows(target,html,colspan,empty){var el=document.getElementById(target);if(el)el.innerHTML=html||'<tr><td colspan="'+colspan+'" style="color:var(--faint);">'+(empty||'暂无数据')+'</td></tr>';}
function playerHubStats(target,values){var el=document.getElementById(target);if(el)el.innerHTML=values.map(function(v){return '<div class="stat-card"><div class="stat-val">'+v.value+'</div><div class="stat-label">'+v.label+'</div></div>';}).join('');}
function playerHubBadge(status){var s=String(status||'').toUpperCase(),ok=['ACTIVE','FOR_SALE','OPEN','ENABLED'].indexOf(s)>=0,pending=['WAITING','PENDING','STATE_OWNED'].indexOf(s)>=0;return '<span class="badge '+(ok?'badge-active':(pending?'badge-pending':'badge-closed'))+'">'+escapeHtml(status||'—')+'</span>';}
var gridListingCache={},gridPriceCache={},empirePlotCache={};
async function loadGridHub(){
  var data=await Promise.all([api('GET','/api/market/stats'),api('GET','/api/eco/public-info'),api('GET','/api/listings'),enabledFeatureSet.has('bank')?api('GET','/api/my-banks'):Promise.resolve({banks:[]})]);
  var stats=data[0],prices=data[1].prices||[],listings=data[2].listings||[],banks=data[3].banks||[];
  var deposits=banks.reduce(function(n,b){return n+Number(b.myBalance||0);},0);
  playerHubStats('gridHubStats',[{value:fmt(stats.activeListings||listings.length),label:'ACTIVE LISTINGS'},{value:fmt(prices.length),label:'OFFICIAL MATERIALS'},{value:fmt(deposits),label:'BANK DEPOSITS'},{value:stats.officialBuyEnabled?'ONLINE':'OFFLINE',label:'OFFICIAL BUY'}]);
  gridListingCache={};listings.forEach(function(l){gridListingCache[l.id]=l;});
  playerHubRows('gridHubListings',listings.slice(0,12).map(function(l){return '<tr><td>'+escapeHtml(l.material||'—')+'</td><td>'+escapeHtml(l.sellerName||'—')+'</td><td>'+fmt(l.quantity||0)+'</td><td>'+fmt(l.unitPrice||0)+'</td><td>'+fmt(l.totalPrice||0)+'</td><td><button class="btn btn-sm" onclick="openGridListing(\''+escapeAttr(l.id)+'\')">详情</button></td></tr>';}).join(''),6,'暂无在售挂单');
  gridPriceCache={};prices.forEach(function(p){gridPriceCache[p.material]=p;});
  playerHubRows('gridHubPrices',prices.slice(0,12).map(function(p){return '<tr><td>'+escapeHtml(p.chineseName||p.material||'—')+'</td><td>'+fmt(p.basePrice||0)+'</td><td>'+fmt(p.buyPrice||0)+'</td><td>'+fmt(p.marketAvg||0)+'</td><td>'+escapeHtml(p.trend||'—')+'</td><td><button class="btn btn-sm" onclick="openGridPrice(\''+escapeAttr(p.material)+'\')">详情</button></td></tr>';}).join(''),6,'暂无官方材料');
}
function openGridListing(id){var l=gridListingCache[id];if(!l)return;showModal('挂单详情 // LISTING '+escapeHtml(id),'<div class="hub-feed"><div class="hub-feed-item"><span>物品</span><b>'+escapeHtml(l.material||'—')+'</b></div><div class="hub-feed-item"><span>卖家</span><b>'+escapeHtml(l.sellerName||'—')+'</b></div><div class="hub-feed-item"><span>数量</span><b>'+fmt(l.quantity||0)+'</b></div><div class="hub-feed-item"><span>单价</span><b>'+fmt(l.unitPrice||0)+'</b></div><div class="hub-feed-item"><span>总价</span><b>'+fmt(l.totalPrice||0)+'</b></div></div>');}
function openGridPrice(material){var p=gridPriceCache[material];if(!p)return;showModal('材料行情 // '+escapeHtml(p.chineseName||material),'<div class="hub-feed"><div class="hub-feed-item"><span>材料</span><b>'+escapeHtml(material)+'</b></div><div class="hub-feed-item"><span>基础价</span><b>'+fmt(p.basePrice||0)+'</b></div><div class="hub-feed-item"><span>当前官方收购</span><b>'+fmt(p.buyPrice||0)+'</b></div><div class="hub-feed-item"><span>七日市场均价</span><b>'+fmt(p.marketAvg||0)+'</b></div><div class="hub-feed-item"><span>趋势</span><b>'+escapeHtml(p.trend||'—')+'</b></div></div>');}
async function loadEmpiresHub(){
  var data=await Promise.all([enabledFeatureSet.has('enterprise')?api('GET','/api/my-enterprises'):Promise.resolve({enterprises:[]}),enabledFeatureSet.has('realestate')?api('GET','/api/realestate/my-plots'):Promise.resolve({plots:[]})]);
  var ents=data[0].enterprises||[],plots=data[1].plots||[],assets=ents.reduce(function(n,e){return n+Number(e.current_assets||e.corporate_balance||0);},0),landValue=plots.reduce(function(n,p){return n+Number(p.price||0);},0);
  playerHubStats('empiresHubStats',[{value:fmt(ents.length),label:'MY CORPORATIONS'},{value:fmt(assets),label:'CORP ASSETS'},{value:fmt(plots.length),label:'OWNED PLOTS'},{value:fmt(landValue),label:'LAND VALUE'}]);
  playerHubRows('empiresHubEnts',ents.slice(0,10).map(function(e){return '<tr><td>'+escapeHtml(e.name||e.id)+'</td><td>'+escapeHtml(e.industry||e.type||'—')+'</td><td>'+fmt(e.current_assets||e.corporate_balance||0)+'</td><td>'+fmt(e.employee_count||0)+'</td><td>'+playerHubBadge(e.status)+'</td><td><button class="btn btn-sm" onclick="ksEntDrill(\''+escapeAttr(e.id)+'\')">管理</button></td></tr>';}).join(''),6,'暂无企业');
  empirePlotCache={};plots.forEach(function(p){empirePlotCache[p.id||p.plotId]=p;});
  playerHubRows('empiresHubPlots',plots.slice(0,10).map(function(p){var id=p.id||p.plotId;return '<tr><td>'+escapeHtml(p.zoneId||'—')+'</td><td>'+escapeHtml(p.world||'—')+'</td><td>['+escapeHtml(String(p.x1))+','+escapeHtml(String(p.z1))+'] - ['+escapeHtml(String(p.x2))+','+escapeHtml(String(p.z2))+']</td><td>'+fmt(p.price||0)+'</td><td>'+escapeHtml(p.dungeonTemplateId||'—')+'</td><td><button class="btn btn-sm" onclick="openEmpirePlot(\''+escapeAttr(id)+'\')">详情</button></td></tr>';}).join(''),6,'暂无地块');
}
function openEmpirePlot(id){var p=empirePlotCache[id];if(!p)return;showModal('地块详情 // '+escapeHtml(id),'<div class="hub-feed"><div class="hub-feed-item"><span>区域</span><b>'+escapeHtml(p.zoneId||'—')+'</b></div><div class="hub-feed-item"><span>世界</span><b>'+escapeHtml(p.world||'—')+'</b></div><div class="hub-feed-item"><span>范围</span><b>['+escapeHtml(String(p.x1))+','+escapeHtml(String(p.z1))+'] - ['+escapeHtml(String(p.x2))+','+escapeHtml(String(p.z2))+']</b></div><div class="hub-feed-item"><span>购入价</span><b>'+fmt(p.price||0)+'</b></div><div class="hub-feed-item"><span>副本权限</span><b>'+escapeHtml(p.dungeonTemplateId||'—')+'</b></div></div>');}
async function loadDropsHub(){
  var data=await Promise.all([api('GET','/api/mo/list'),enabledFeatureSet.has('blindbox')?api('GET','/api/blindbox/pools'):Promise.resolve({pools:[]}),enabledFeatureSet.has('blindbox')?api('GET','/api/blindbox/my-pulls'):Promise.resolve({pulls:[]})]);
  var mos=data[0].orders||[],pools=data[1].pools||[],pulls=data[2].pulls||[];
  playerHubStats('dropsHubStats',[{value:fmt(mos.filter(function(m){return m.status==='ACTIVE';}).length),label:'ACTIVE MAJOR ORDERS'},{value:fmt(pools.filter(function(p){return p.enabled;}).length),label:'OPEN GACHA POOLS'},{value:fmt(pulls.length),label:'MY RECENT PULLS'}]);
  document.getElementById('dropsHubMo').innerHTML=mos.slice(0,6).map(function(m){var p=Math.max(0,Math.min(100,Number(m.progressPct||0)*100));return '<div class="hub-feed-item"><span><b>'+escapeHtml(m.title||m.id)+'</b><small>'+fmt(m.currentValue||0)+' / '+fmt(m.targetValue||0)+' · '+p.toFixed(0)+'%</small></span>'+playerHubBadge(m.status)+'</div>';}).join('')||'<div style="color:var(--faint);">暂无主任务</div>';
  document.getElementById('dropsHubPools').innerHTML=pools.filter(function(p){return p.enabled;}).slice(0,8).map(function(p){return '<button class="hub-pool" type="button" onclick="switchTab(\'blindbox\')"><b>'+escapeHtml(p.name||p.id)+'</b><small>'+fmt(p.price||0)+' / 保底 '+escapeHtml(String(p.pityRulesText||p.pityMax||'—'))+'</small></button>';}).join('')||'<div style="color:var(--faint);">暂无开放卡池</div>';
}
// ====== GACHA FX (全屏开箱动效) ======
function ksGachaFx(rarity,done){
  if(window.matchMedia&&matchMedia('(prefers-reduced-motion: reduce)').matches){if(done)done();return;}
  var col=bbRarityColorToCss(rarity)||'#00E5FF';
  var ov=document.createElement('div');ov.className='gacha-overlay';
  var cols='';
  for(var i=0;i<24;i++){
    var chars='';for(var j=0;j<20;j++){chars+=String.fromCharCode(0x30A0+Math.floor(Math.random()*96))+'<br>';}
    cols+='<span class="gr" style="left:'+(i*4.2)+'%;animation-delay:'+(Math.random()*.9).toFixed(2)+'s;animation-duration:'+(0.9+Math.random()*0.9).toFixed(2)+'s;">'+chars+'</span>';
  }
  ov.innerHTML='<div class="gacha-rain">'+cols+'</div><div class="gacha-core" style="--gc:'+col+'"><div class="gacha-ring"></div><div class="gacha-txt">DECRYPTING<br>LOOT.PKG</div></div>';
  document.body.appendChild(ov);
  setTimeout(function(){ov.classList.add('reveal');},1050);
  setTimeout(function(){ov.remove();if(done)done();},1500);
}
async function loadMyBanks(){
  var d=await api('GET','/api/my-banks');
  myPlayerUuid = d.myUuid || '';
  var totalDeposit=0,cards=[];
  (d.banks||[]).forEach(function(b){
    totalDeposit+=Number(b.myBalance||0);
    var owned=b.isOwner||b.owner;
    cards.push(ksCard({
      title:b.name,badge:owned?'我经营':escapeHtml(b.type||''),badgeCls:owned?'':'cyan',
      fields:[['我的存款',fmt(b.myBalance||0)+' 金币'],['银行ID',b.id],['总资产',fmt(b.total_assets||b.capital)],['存款利率/周期',pct(b.interest_rate||b.deposit_rate)]],
      onclick:"ksBankDrill('"+escapeAttr(b.id)+"')",
      actions:[
        {label:'💳 存/取款',onclick:"document.getElementById('depBankId').value='"+escapeAttr(b.id)+"';ksDrill('存取款 // '+'"+escapeAttr(b.name)+"',['ks-card-depwd'])"},
        {label:'⚙ 详细管理',onclick:"ksBankDrill('"+escapeAttr(b.id)+"')"}
      ]
    }));
  });
  ksGrid('bankCards',cards,'暂无银行账户 — 请先在"创建银行"中创建');
  var loans=await api('GET','/api/my-loans');
  var activeLoans=0,loanOutstanding=0;
  var t='';(loans.loans||[]).forEach(function(l){
    if(l.status==='ACTIVE'){activeLoans++;loanOutstanding+=Number(l.remaining||0);}
    var serviceable=l.status==='ACTIVE'||l.status==='OVERDUE';
    t+='<tr><td>'+l.id+'</td><td>'+l.bank_id+'</td><td>'+fmt(l.principal)+'</td><td>'+fmt(l.remaining)+'</td><td>'+pct(l.interest_rate)+'</td><td><span class="badge '+(l.status==='ACTIVE'?'badge-active':'badge-closed')+'">'+l.status+'</span></td><td>'+(serviceable?'<button class="btn btn-sm btn-danger" onclick="repayLoan(\''+l.id+'\','+l.remaining+')">还款</button> <button class="btn btn-sm" onclick="requestLoanRestructure(\''+l.id+'\')">申请展期</button>':'')+'</td></tr>';
  });
  document.getElementById('loanBody').innerHTML=t||'<tr><td colspan="7" style="color:#666;">暂无贷款</td></tr>';
  var reqs=await api('GET','/api/bank/loan/my-requests');
  var pendingReqs=0;
  var t2='';(reqs.requests||[]).forEach(function(r){
    if(r.status==='PENDING')pendingReqs++;
    var badge=r.status==='PENDING'?'badge-active':(r.status==='APPROVED'?'badge-owner':'badge-closed');
    t2+='<tr><td>'+r.id+'</td><td>'+r.bank_id+'</td><td>'+fmt(r.principal)+'</td><td>'+r.term_days+'</td><td><span class="badge '+badge+'">'+r.status+'</span></td><td>'+new Date(r.requested_at*1000).toLocaleString('zh-CN')+'</td><td>'+(r.status==='PENDING'?'<button class="btn btn-sm btn-danger" onclick="cancelMyLoanRequest(\''+escapeAttr(r.id)+'\')">撤销</button>':'')+'</td></tr>';
  });
  document.getElementById('myLoanReqBody').innerHTML=t2||'<tr><td colspan="7" style="color:#666;">暂无申请</td></tr>';
  ksKpiRow('myBankKpis',[
    {icon:'🏦',label:'银行账户',value:String((d.banks||[]).length)},
    {icon:'💰',label:'存款总额',value:fmt(totalDeposit)},
    {icon:'📄',label:'活跃贷款',value:String(activeLoans),sub:activeLoans?'待还 '+fmt(loanOutstanding):''},
    {icon:'⏳',label:'待审批申请',value:String(pendingReqs),accent:pendingReqs?'var(--magenta)':''}
  ]);
  // Auto-fill depBankId if empty
  if(!document.getElementById('depBankId').value&&d.banks&&d.banks.length>0){
    document.getElementById('depBankId').value=d.banks[0].id;
  }
  document.getElementById('playerInfo').textContent='已登录';
  await populateBankSelectors(d.banks||[]);
  loadBankGameplay();
  loadGuidanceBank();
  loadCollateralAuctions();
  loadBankEquityPortfolio();
}
async function populateBankSelectors(myBanks){
  var all=await api('GET','/api/bank/list');
  var active=(all.banks||[]).filter(function(b){return b.status==='ACTIVE'&&b.type!=='CENTRAL';});
  var owned=(myBanks||[]).filter(function(b){return b.isOwner||b.owner;});
  function fill(id,items,label){
    var el=document.getElementById(id);if(!el)return;
    var current=el.value;
    el.innerHTML='<option value="">'+label+'</option>'+items.map(function(b){return '<option value="'+escapeAttr(b.id)+'">'+escapeHtml(b.name)+' · '+escapeHtml(b.id)+'</option>';}).join('');
    if(items.some(function(b){return b.id===current;}))el.value=current;else if(items.length)el.value=items[0].id;
  }
  fill('depBankId',active,'选择存取款银行');
  fill('termBankId',active,'选择存单银行');
  fill('equityBankId',active,'选择股份市场');
  ['mgmtBankId','inviteBankId2','setRateBankId','loanIssueBankId','loanReqBankId','bankOpsBankId'].forEach(function(id){fill(id,owned,'选择我经营的银行');});
  if(active.length)loadDepositProducts();
  if(owned.length)loadBankOperations();
  if(active.length)loadBankEquityMarket();
}
async function loadBankGameplay(){
  var box=document.getElementById('bankGameplayOverview');if(!box)return;
  var d=await api('GET','/api/bank/gameplay/dashboard');
  if(d.error||d.available===false){box.innerHTML='<span style="color:#d88;">'+escapeHtml(d.error||d.reason||'读取失败')+'</span>';return;}
  var c=d.credit||{},factors=(c.factors||[]),steps=(c.nextSteps||[]);
  var factorHtml=factors.map(function(f){return '<span class="badge '+(Number(f.points)>=0?'badge-owner':'badge-closed')+'" style="margin:2px;">'+escapeHtml(f.label)+' '+(Number(f.points)>0?'+':'')+fmt(f.points)+'</span>';}).join('');
  box.innerHTML='<div class="stats-row" style="margin:0 0 9px 0;">'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.totalDeposits)+'</div><div class="stat-label">存款资产</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.termDeposits)+'</div><div class="stat-label">定期存单</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.loanOutstanding)+'</div><div class="stat-label">贷款负债</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.insuredCoverage)+'</div><div class="stat-label">存款保险覆盖</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.netBankPosition)+'</div><div class="stat-label">银行净头寸</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+escapeHtml(c.tierLabel||c.tier||'—')+' · '+fmt(c.score||0)+'</div><div class="stat-label">信用等级</div></div>'
    +'</div><div><b>征信影响：</b>'+((factorHtml)||'<span style="color:#777;">暂无记录</span>')+'</div>'
    +(steps.length?'<div style="margin-top:7px;color:#d9a441;"><b>改善建议：</b>'+steps.map(escapeHtml).join('；')+'</div>':'');
  renderTermDeposits(d.termDepositsList||[]);
}
function renderTermDeposits(list){
  var body=document.getElementById('termDepositBody');if(!body)return;
  body.innerHTML=list.map(function(d){
    var active=d.status==='ACTIVE',matured=Number(d.matures_at||0)<=Date.now()/1000;
    return '<tr><td>'+escapeHtml(d.id)+'</td><td>'+escapeHtml(d.bank_name||d.bank_id)+'<br><small>'+escapeHtml(d.product_name||d.product_code)+'</small></td><td>'+fmt(d.principal)+'</td><td>'+pct(d.fixed_rate)+'</td><td>'+new Date(Number(d.matures_at)*1000).toLocaleString('zh-CN')+'</td><td><span class="badge '+(active?'badge-active':'badge-closed')+'">'+escapeHtml(d.status)+'</span></td><td>'+(active?'<button class="btn btn-sm '+(matured?'btn-success':'btn-danger')+'" onclick="redeemTermDeposit(\''+escapeAttr(d.id)+'\','+matured+')">'+(matured?'到期结清':'提前支取')+'</button>':'')+'</td></tr>';
  }).join('')||'<tr><td colspan="7" style="color:#666;">暂无定期存单</td></tr>';
}
async function loadDepositProducts(){
  var bankId=document.getElementById('termBankId').value,select=document.getElementById('termProductCode');
  if(!bankId){select.innerHTML='<option value="">请先选择银行</option>';return;}
  var d=await api('GET','/api/bank/deposit-products?bankId='+encodeURIComponent(bankId));
  select.innerHTML=(d.products||[]).map(function(p){return '<option value="'+escapeAttr(p.product_code)+'">'+escapeHtml(p.name)+' · '+p.term_days+'天 · '+pct(p.fixed_rate)+' · 起存 '+fmt(p.min_amount)+'</option>';}).join('')||'<option value="">暂无在售产品</option>';
}
async function openTermDeposit(){
  var bankId=document.getElementById('termBankId').value,productCode=document.getElementById('termProductCode').value,amount=Number(document.getElementById('termDepositAmount').value||0),autoRenew=document.getElementById('termAutoRenew').checked;
  if(!bankId||!productCode||amount<=0){toast('请选择银行、产品并填写金额','err');return;}
  if(!confirm('确认从该银行活期账户转入 '+fmt(amount)+'？提前支取可能损失利息。'))return;
  var d=await api('POST','/api/bank/term/open',{bankId:bankId,productCode:productCode,amount:amount,autoRenew:autoRenew});
  if(d.success){toast(d.message||'存单已开立','ok');loadMyBanks();}else toast(d.error||'开立失败','err');
}
async function redeemTermDeposit(id,matured){
  if(!confirm(matured?'确认结清到期存单？':'该存单尚未到期，提前支取会损失部分利息。确认继续？'))return;
  var d=await api('POST','/api/bank/term/redeem',{depositId:id});
  if(d.success){toast((d.message||'存单已结清')+'，到账 '+fmt(d.payout),'ok');loadMyBanks();}else toast(d.error||'支取失败','err');
}
async function loadBankOperations(){
  var bankId=(document.getElementById('bankOpsBankId')||{}).value,panel=document.getElementById('bankOperationsPanel');if(!panel||!bankId)return;
  var d=await api('GET','/api/bank/operations?bankId='+encodeURIComponent(bankId));
  if(d.error){panel.innerHTML='<span style="color:#d88;">'+escapeHtml(d.error)+'</span>';return;}
  var risk=d.operatingStatus==='NORMAL'?'badge-owner':(d.operatingStatus==='WATCH'?'badge-active':'badge-closed');
  panel.innerHTML='<div class="stats-row" style="margin:0 0 8px 0;">'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.liquidity)+'</div><div class="stat-label">可用流动性</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.totalDeposits)+'</div><div class="stat-label">客户存款</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.loanAssets)+'</div><div class="stat-label">贷款资产</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.equity)+'</div><div class="stat-label">净资产</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+escapeHtml(d.rating)+'</div><div class="stat-label">银行评级</div></div></div>'
    +'<div>流动性覆盖率 <b>'+pct(d.liquidityRatio)+'</b> · 资本充足率 <b>'+pct(d.capitalRatio)+'</b> · 坏账率 <b>'+pct(d.badDebtRatio)+'</b> · 状态 <span class="badge '+risk+'">'+escapeHtml(d.operatingStatus)+'</span></div>'
    +'<div style="margin-top:7px;">已实现贷款利息 <b>'+fmt(d.realizedLoanInterest)+'</b> · 存款利息成本 <b>'+fmt(d.interestExpense)+'</b> · 坏账拨备 <b>'+fmt(d.loanLossProvision)+'</b> · 已分红 <b>'+fmt(d.dividendsPaid)+'</b> · 可分配利润 <b>'+fmt(d.retainedEarnings)+'</b></div>';
  loadBankDividends(bankId);
}
async function loadBankDividends(bankId){
  var d=await api('GET','/api/bank/dividends?bankId='+encodeURIComponent(bankId)),body=document.getElementById('bankDividendBody');if(!body)return;
  body.innerHTML=(d.dividends||[]).map(function(x){return '<tr><td>'+escapeHtml(x.id)+'</td><td>'+fmt(x.amount)+'</td><td>'+fmt(x.recipient_count)+'</td><td>'+escapeHtml(x.status)+'</td><td>'+new Date(Number(x.declared_at)*1000).toLocaleString('zh-CN')+'</td></tr>';}).join('')||'<tr><td colspan="5" style="color:#666;">暂无分红记录</td></tr>';
}
async function declareBankDividend(){
  var bankId=document.getElementById('bankOpsBankId').value,amount=Number(document.getElementById('bankDividendAmount').value||0);if(!bankId||amount<=0){toast('请选择银行并填写分红金额','err');return;}
  if(!confirm('确认从已实现利润中分配 '+fmt(amount)+'？资金将计入各股东在本行的活期账户。'))return;
  var d=await api('POST','/api/bank/dividend/declare',{bankId:bankId,amount:amount});
  if(d.success){toast(d.message||'分红已完成','ok');loadBankOperations();loadMyBanks();}else toast(d.error||'分红失败','err');
}
async function loadBankEquityPortfolio(){
  var box=document.getElementById('bankEquityPortfolio');if(!box)return;
  var d=await api('GET','/api/bank/equity/portfolio');
  box.innerHTML=(d.portfolio||[]).map(function(x){return '<span class="badge badge-owner" style="margin:2px;">'+escapeHtml(x.name||x.bank_id)+' '+fmt(x.shares)+' 股 · '+fmt(x.ownershipPercent)+'%</span>';}).join('')||'<span style="color:#666;">暂无银行持股</span>';
}
async function loadBankEquityMarket(){
  var bankId=(document.getElementById('equityBankId')||{}).value,cap=document.getElementById('bankEquityCapTable'),body=document.getElementById('bankEquityOfferBody');if(!bankId||!cap||!body)return;
  var d=await api('GET','/api/bank/equity/cap-table?bankId='+encodeURIComponent(bankId));
  if(d.error){cap.innerHTML='<span style="color:#d88;">'+escapeHtml(d.error)+'</span>';return;}
  cap.innerHTML='<b>已发行 '+fmt(d.totalIssued)+' 股</b> · 授权上限 '+fmt(d.authorizedShares)+' · 实缴资本 '+fmt(d.paidInCapital)+'<br>'+(d.shareholders||[]).map(function(x){return '<span class="badge '+(x.controlling?'badge-owner':'badge-active')+'" style="margin:2px;">'+escapeHtml(x.shareholder_uuid)+' '+fmt(x.ownershipPercent)+'%</span>';}).join('');
  var market=await api('GET','/api/bank/equity/offerings?bankId='+encodeURIComponent(bankId));
  body.innerHTML=(market.offerings||[]).map(function(x){var mine=x.created_by===myPlayerUuid;return '<tr><td>'+escapeHtml(x.id)+'</td><td>'+escapeHtml(x.offer_type)+'</td><td>'+escapeHtml(x.seller_uuid||'银行增资')+'</td><td>'+fmt(x.shares)+'</td><td>'+fmt(x.price_per_share)+'</td><td>'+fmt(Number(x.shares)*Number(x.price_per_share))+'</td><td>'+(mine?'<button class="btn btn-sm btn-danger" onclick="cancelBankShareOffering(\''+escapeAttr(x.id)+'\')">撤单</button>':'<button class="btn btn-sm btn-success" onclick="acceptBankShareOffering(\''+escapeAttr(x.id)+'\')">认购</button>')+'</td></tr>';}).join('')||'<tr><td colspan="7" style="color:#666;">暂无股份挂牌</td></tr>';
}
async function createBankShareOffering(){
  var bankId=document.getElementById('bankOpsBankId').value,offerType=document.getElementById('bankShareOfferType').value,shares=Number(document.getElementById('bankShareOfferShares').value||0),pricePerShare=Number(document.getElementById('bankShareOfferPrice').value||0);if(!bankId||shares<=0||pricePerShare<=0){toast('请选择银行并填写股份与价格','err');return;}
  var d=await api('POST','/api/bank/equity/offerings',{bankId:bankId,offerType:offerType,shares:shares,pricePerShare:pricePerShare});
  if(d.success){toast(d.message||'股份挂牌已发布','ok');document.getElementById('equityBankId').value=bankId;loadBankEquityMarket();}else toast(d.error||'发布失败','err');
}
async function acceptBankShareOffering(id){
  if(!confirm('确认使用该银行活期账户认购此挂牌？'))return;
  var d=await api('POST','/api/bank/equity/accept',{offeringId:id});
  if(d.success){toast(d.message||'认购完成','ok');loadMyBanks();}else toast(d.error||'认购失败','err');
}
async function cancelBankShareOffering(id){
  var d=await api('POST','/api/bank/equity/cancel',{offeringId:id});
  if(d.success){toast(d.message||'撤单完成','ok');loadBankEquityMarket();}else toast(d.error||'撤单失败','err');
}
async function loadGuidanceBank(){
  var box=document.getElementById('guidanceBankCard');if(!box)return;
  var d=await api('GET','/api/bank/guidance');
  if(d.error||d.available===false){box.innerHTML='<span style="color:#d88;">'+escapeHtml(d.error||d.reason||'引导银行暂不可用')+'</span>';return;}
  var leftTotal=Math.max(0,Number(d.totalCap||0)-Number(d.claimedTotal||0));
  var leftDaily=Math.max(0,Number(d.dailyCap||0)-Number(d.claimedToday||0));
  var html='<div class="stats-row" style="margin:0 0 8px 0;">'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.starterAmount)+'</div><div class="stat-label">一次性启动贷款</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+pct(d.interestRate)+'</div><div class="stat-label">固定利率</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(d.termDays)+' 天</div><div class="stat-label">还款期限</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(leftDaily)+'</div><div class="stat-label">今日剩余额度</div></div>'
    +'</div><div style="color:#aaa;">政策总额度剩余 '+fmt(leftTotal)+' | 银行可用资产 '+fmt(d.assets)+' | 准备金 '+fmt(d.reserve)+'</div>';
  if(d.eligible){html+='<div style="margin-top:9px;"><button class="btn btn-success" onclick="claimGuidanceLoan()">领取启动贷款</button></div>';}
  else html+='<div style="margin-top:9px;color:#d9a441;">'+escapeHtml(d.reason||'当前不可领取')+'</div>';
  box.innerHTML=html;
}
async function claimGuidanceLoan(){
  if(!confirm('领取后将生成一笔需要偿还的贷款，确认继续？'))return;
  var d=await api('POST','/api/bank/guidance/claim',{});
  if(d.success||d.message){toast(d.message||'启动贷款已到账','ok');loadMyBanks();}
  else toast(d.reason||d.error||'领取失败','err');
}
async function loadCollateralAuctions(){
  var d=await api('GET','/api/bank/collateral-auctions');
  var rows='';(d.auctions||[]).forEach(function(a){
    var closes=a.closes_at?new Date(a.closes_at*1000).toLocaleString('zh-CN'):'-';
    rows+='<tr><td>'+escapeHtml(a.id)+'</td><td>'+escapeHtml(a.asset_type)+': '+escapeHtml(a.asset_ref)+'</td><td>'+fmt(a.current_price)+'</td><td>'+closes+'</td><td><button class="btn btn-sm btn-success" onclick="bidCollateralAuction(\''+escapeAttr(a.id)+'\','+Number(a.current_price||0)+')">出价</button></td></tr>';
  });
  var body=document.getElementById('auctionBody');if(body)body.innerHTML=rows||'<tr><td colspan="5" style="color:#666;">暂无进行中的拍卖</td></tr>';
}
async function bidCollateralAuction(id,current){
  var amount=Number(prompt('出价金额（当前价 '+fmt(current)+'）',String(Math.ceil(current+1))));if(!amount)return;
  var d=await api('POST','/api/bank/collateral-auctions/bid',{auctionId:id,amount:amount});
  if(d.success){toast(d.message,'ok');loadCollateralAuctions();}else toast(d.error||d.message||'竞价失败','err');
}
async function doDeposit(){
  var d=await api('POST','/api/bank/deposit',{bankId:document.getElementById('depBankId').value,amount:parseFloat(document.getElementById('depAmount').value)||0});
  if(d.message){toast(d.message,'ok');loadMyBanks();}else toast(d.error||'存款失败','err');
}
async function doWithdraw(){
  var d=await api('POST','/api/bank/withdraw',{bankId:document.getElementById('depBankId').value,amount:parseFloat(document.getElementById('depAmount').value)||0});
  if(d.message){toast(d.message,'ok');loadMyBanks();}else toast(d.error||'取款失败','err');
}
async function repayLoan(loanId,amount){
  var d=await api('POST','/api/bank/loan/repay',{loanId:loanId,amount:amount});
  if(d.message){toast(d.message,'ok');loadMyBanks();}else toast(d.error,'err');
}
async function requestLoanRestructure(loanId){
  var days=Number(prompt('申请展期天数：仅支持 7、14 或 30 天。每 7 天费用为当前剩余应还的 1%。','7'));if([7,14,30].indexOf(days)<0){if(days)toast('只支持 7、14 或 30 天','err');return;}
  var d=await api('POST','/api/bank/loan/restructure',{loanId:loanId,requestedDays:days});
  if(d.success){toast((d.message||'展期申请已提交')+'，预计费用 '+fmt(d.fee),'ok');loadMyBanks();}else toast(d.error||'申请失败','err');
}
async function cancelMyLoanRequest(requestId){
  if(!confirm('确认撤销贷款申请？已占用的抵押物会立即释放。'))return;
  var d=await api('POST','/api/bank/loan/cancel',{requestId:requestId});
  if(d.success){toast(d.message||'申请已撤销','ok');loadMyBanks();}else toast(d.error||'撤销失败','err');
}
async function createMyBank(){
  var coOwners=document.getElementById('bkCoOwners').value;
  var owners=['__self__']; // placeholder - server will use session UUID
  if(coOwners.trim()){owners=owners.concat(coOwners.split(',').map(function(s){return s.trim();}));}
  var d=await api('POST','/api/bank/create',{
    name:document.getElementById('bkName').value,
    ownerUuids:owners,
    initialCapital:parseFloat(document.getElementById('bkCapital').value)||0,
    type:document.getElementById('bkType').value
  });
  if(d.pendingId){toast(d.message||'Joint venture confirmation created.','ok');switchTab('my-invites');return;}
  if(d.id){toast('银行创建成功: '+d.id,'ok');loadMyBanks();switchBankSub('overview');}else toast(d.error||'创建失败','err');
}
async function loadAllBanks(){
  var d=await api('GET','/api/bank/list');
  var cards=[];
  for(var i=0;i<(d.banks||[]).length;i++){
    var b=d.banks[i];
    var r=await api('GET','/api/bank/rates/get?bankId='+b.id);
    cards.push(ksCard({
      title:b.name,badge:b.type||'COMMERCIAL',badgeCls:b.type==='CENTRAL'?'':'cyan',
      fields:[['银行ID',b.id],['总资产',fmt(b.total_assets)],['贷款利率',pct(r.loanRate)],['存款利率/周期',pct(r.depositRate)]],
      actions:[
        {label:'💰 存款/开户',onclick:"quickDeposit('"+escapeAttr(b.id)+"')"},
        {label:'💸 申请贷款',cls:'btn-success',onclick:"openLoanApplyModal('"+escapeAttr(b.id)+"')"}
      ]
    }));
  }
  ksGrid('allBanksGrid',cards,'暂无银行');
}
// 原"申请就业"按钮调 members/add 给自己加员工，自所有权校验上线后对普通玩家永远 403（死按钮）。
// 改为快捷存款：deposit 首次存款自动开户。成员管理归银行所有者在成员管理页操作。
async function quickDeposit(bankId){
  var amt=prompt('存款金额（首次存款自动开户）：','1000');
  if(amt===null)return;
  var d=await api('POST','/api/bank/deposit',{bankId:bankId,amount:parseFloat(amt)||0});
  if(d.message){toast(d.message,'ok');loadMyBanks();}else toast(d.error||'存款失败','err');
}
async function openLoanApplyModal(bankId){
  var catalog=await api('GET','/api/bank/loan/products');
  var options=(catalog.products||[]).map(function(p){return '<option value="'+escapeAttr(p.code)+'">'+escapeHtml(p.name)+' · '+fmt(p.minAmount)+'-'+fmt(p.maxAmount)+' · '+p.minTermDays+'-'+p.maxTermDays+'天</option>';}).join('');
  var html='<div class="form-row"><span style="font-size:12px;color:#aaa;">向银行 '+escapeHtml(bankId)+' 获取正式报价。报价会结合产品、期限、信用等级和银行流动性，有效期内可锁定提交。</span></div>'
    +'<div class="form-row"><label>贷款产品<br><select id="mLoanProduct" onchange="loadLoanCollateral()">'+options+'</select></label>'
    +'<label>还款方式<br><select id="mLoanRepayment"><option value="EQUAL_PAYMENT">等额还款</option><option value="EQUAL_PRINCIPAL">等额本金</option><option value="BULLET">到期还本</option></select></label></div>'
    +'<div class="form-row" id="mLoanCollateralRow"><label>真实抵押物<br><select id="mLoanCollateral"><option value="">消费贷无需抵押</option></select></label><span style="font-size:11px;color:#888;">住房/经营贷支持本人地块或房屋；项目贷绑定本人已中标合同。</span></div>'
    +'<div class="form-row"><label>本金<br><input id="mLoanAmount" type="number" step="1" value="10000"/></label>'
    +'<label>期限(天)<br><input id="mLoanTerm" type="number" value="30"/></label>'
    +'<label>用途说明<br><input id="mLoanPurpose" maxlength="256" placeholder="消费、购房、经营或项目用途"/></label></div>'
    +'<div id="mLoanQuote" style="color:#888;font-size:12px;margin:8px 0;">请选择参数后获取报价。</div>'
    +'<div class="form-row"><button class="btn btn-primary" onclick="previewLoanQuote(\''+escapeAttr(bankId)+'\')">计算正式报价</button></div>';
  showModal('💸 贷款产品与报价',html);
  loadLoanCollateral();
}
var currentLoanQuote=null;
async function loadLoanCollateral(){
  var product=(document.getElementById('mLoanProduct')||{}).value,select=document.getElementById('mLoanCollateral');if(!select)return;
  if(product==='CONSUMER'){select.innerHTML='<option value="">消费贷无需抵押</option>';select.disabled=true;return;}
  select.disabled=false;select.innerHTML='<option value="">正在读取可抵押资产...</option>';
  var d=await api('GET','/api/bank/loan/collateral?productType='+encodeURIComponent(product));
  select.innerHTML=(d.assets||[]).map(function(x){return '<option value="'+escapeAttr(x.assetType+'|'+x.assetRef)+'">'+escapeHtml(x.assetType+' · '+x.assetRef)+' · 估值 '+fmt(x.appraisedValue)+' · 可贷 '+fmt(x.maxLoan)+'</option>';}).join('')||'<option value="">没有符合条件的未抵押资产</option>';
}
async function previewLoanQuote(bankId){
  var principal=Number(document.getElementById('mLoanAmount').value||0),termDays=Number(document.getElementById('mLoanTerm').value||0),productType=document.getElementById('mLoanProduct').value,repaymentType=document.getElementById('mLoanRepayment').value;
  var collateral=(document.getElementById('mLoanCollateral').value||'').split('|'),collateralType=collateral[0]||'',collateralRef=collateral.slice(1).join('|')||'';
  var box=document.getElementById('mLoanQuote');box.textContent='正在计算信用与风险报价...';
  var d=await api('POST','/api/bank/loan/quote',{bankId:bankId,principal:principal,termDays:termDays,productType:productType,repaymentType:repaymentType,collateralType:collateralType,collateralRef:collateralRef});
  if(d.error||d.allowed===false){currentLoanQuote=null;box.innerHTML='<span style="color:#d88;">'+escapeHtml(d.error||d.reason||'当前无法提供报价')+'</span>';return;}
  currentLoanQuote={bankId:bankId,principal:principal,termDays:termDays,productType:productType,repaymentType:repaymentType,effectiveRate:Number(d.effectiveRate),validUntil:Number(d.validUntil),collateralType:collateralType,collateralRef:collateralRef};
  box.innerHTML='<div class="stats-row" style="margin:0 0 7px 0;"><div class="stat-card"><div class="stat-val">'+escapeHtml(d.productName||productType)+'</div><div class="stat-label">贷款产品</div></div><div class="stat-card"><div class="stat-val">'+pct(d.effectiveRate)+'</div><div class="stat-label">整期固定费率</div></div><div class="stat-card"><div class="stat-val">'+fmt(d.totalDue)+'</div><div class="stat-label">预计总应还</div></div><div class="stat-card"><div class="stat-val">'+fmt(d.installments||1)+'</div><div class="stat-label">计划期数</div></div><div class="stat-card"><div class="stat-val">'+escapeHtml(d.tierLabel||d.tier||'—')+'</div><div class="stat-label">信用等级</div></div></div><div>报价有效至 '+new Date(Number(d.validUntil)*1000).toLocaleTimeString('zh-CN')+'。提交后由银行审批，批准后自动放款。</div><button class="btn btn-success" style="margin-top:8px;" onclick="submitQuotedLoan()">确认报价并提交申请</button>';
}
async function submitQuotedLoan(){
  if(!currentLoanQuote){toast('请先获取有效报价','err');return;}
  if(Date.now()/1000>currentLoanQuote.validUntil){toast('报价已过期，请重新计算','err');return;}
  var body={bankId:currentLoanQuote.bankId,principal:currentLoanQuote.principal,termDays:currentLoanQuote.termDays,productType:currentLoanQuote.productType,repaymentType:currentLoanQuote.repaymentType,acceptedRate:currentLoanQuote.effectiveRate,validUntil:currentLoanQuote.validUntil,purpose:document.getElementById('mLoanPurpose').value,collateralType:currentLoanQuote.collateralType,collateralRef:currentLoanQuote.collateralRef};
  if(!confirm('确认申请 '+fmt(body.principal)+'，整期费率 '+pct(body.acceptedRate)+'？'))return;
  var d=await api('POST','/api/bank/loan/apply-quoted',body);
  if(d.success||d.id){toast(d.message||'贷款申请已提交','ok');currentLoanQuote=null;closeModal();loadMyBanks();}else toast(d.error||'申请失败','err');
}
async function loadLoanRequests(){
  var bankId=document.getElementById('loanReqBankId').value;
  if(!bankId){toast('请先输入银行ID','err');return;}
  var d=await api('GET','/api/bank/loan/requests?bankId='+bankId+'&status=PENDING');
  var t='';(d.requests||[]).forEach(function(r){
    t+='<tr><td>'+r.id+'</td><td>'+(r.borrower_name||r.borrower_uuid)+'</td><td>'+fmt(r.principal)+'</td><td>'+r.term_days+'</td><td>'+new Date(r.requested_at*1000).toLocaleString('zh-CN')+'</td>'
      +'<td><button class="btn btn-sm btn-success" onclick="decideLoanRequest(\''+bankId+'\',\''+r.id+'\',true)">✅ 批准</button> <button class="btn btn-sm btn-danger" onclick="decideLoanRequest(\''+bankId+'\',\''+r.id+'\',false)">❌ 拒绝</button></td></tr>';
  });
  document.getElementById('loanReqBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无待审批申请</td></tr>';
  loadRestructureRequests();
}
async function loadRestructureRequests(){
  var bankId=document.getElementById('loanReqBankId').value,body=document.getElementById('restructureRequestBody');if(!body||!bankId)return;
  var d=await api('GET','/api/bank/loan/restructure/requests?bankId='+encodeURIComponent(bankId)+'&status=PENDING');
  body.innerHTML=(d.requests||[]).map(function(r){return '<tr><td>'+escapeHtml(r.id)+'</td><td>'+escapeHtml(r.loan_id)+'</td><td>'+escapeHtml(r.borrower_uuid)+'</td><td>'+fmt(r.requested_days)+' 天</td><td>'+fmt(r.quoted_fee)+'</td><td>'+new Date(Number(r.requested_at)*1000).toLocaleString('zh-CN')+'</td><td><button class="btn btn-sm btn-success" onclick="decideLoanRestructure(\''+escapeAttr(r.id)+'\',true)">批准</button> <button class="btn btn-sm btn-danger" onclick="decideLoanRestructure(\''+escapeAttr(r.id)+'\',false)">拒绝</button></td></tr>';}).join('')||'<tr><td colspan="7" style="color:#666;">暂无待审批展期申请</td></tr>';
}
async function decideLoanRestructure(requestId,approve){
  var d=await api('POST','/api/bank/loan/restructure/decide',{requestId:requestId,approve:approve});
  if(d.success){toast(d.message||'展期申请已处理','ok');loadRestructureRequests();loadLoanRequests();}else toast(d.error||'处理失败','err');
}
async function decideLoanRequest(bankId,requestId,approve){
  var d=await api('POST','/api/bank/loan/'+(approve?'approve':'reject'),{bankId:bankId,requestId:requestId});
  if(d.message){toast(d.message,'ok');loadLoanRequests();}else toast(d.error||'操作失败','err');
}
async function setMyBankRate(){
  var d=await api('POST','/api/bank/rates/set',{
    bankId:document.getElementById('setRateBankId').value,
    loanRate:parseFloat(document.getElementById('setRateLoan').value),
    depositRate:parseFloat(document.getElementById('setRateDeposit').value),
    reserveRatio:parseFloat(document.getElementById('setReserveRatio').value)
  });
  if(d.message){toast(d.message,'ok');}else toast(d.error||'失败','err');
}
async function issueMyLoan(){
  var d=await api('POST','/api/bank/loan/issue',{
    bankId:document.getElementById('loanIssueBankId').value,
    borrowerUuid:document.getElementById('loanBorrower').value,
    principal:parseFloat(document.getElementById('loanPrincipal').value)||0,
    termDays:parseInt(document.getElementById('loanTermDays').value)||30
  });
  if(d.message){toast(d.message,'ok');}else toast(d.error||'失败','err');
}
async function sendBankInvite(){
  var d=await api('POST','/api/enterprise/invite/send',{
    bankId:document.getElementById('inviteBankId2').value,
    inviteeUuid:document.getElementById('inviteeBankUuid').value
  });
  if(d.message){toast(d.message,'ok');}else toast(d.error,'err');
}

// Bank member management
async function loadBankMembers(){
  var bankId=document.getElementById('mgmtBankId').value;
  if(!bankId){toast('请先输入银行ID','err');return;}
  var d=await api('GET','/api/bank/members?bankId='+bankId);
  var t='';(d.members||[]).forEach(function(m){
    t+='<tr><td>'+m.uuid+'</td><td>'+m.name+'</td><td><span class="badge '+(m.role==='OWNER'?'badge-owner':'badge-active')+'">'+m.role+'</span></td><td>'+new Date(m.joinedAt*1000).toLocaleString('zh-CN')+'</td><td><button class="btn btn-sm btn-danger" onclick="removeBankMember(\''+bankId+'\',\''+m.uuid+'\')">移除</button></td></tr>';
  });
  document.getElementById('bankMemberBody').innerHTML=t||'<tr><td colspan="5" style="color:#666;">暂无成员</td></tr>';
  renderBankAccessControls(d.members||[],bankId);
}
async function renderBankAccessControls(members,bankId){
  var card=document.getElementById('bankMemberBody').closest('.card');
  var panel=document.getElementById('bankManagementAccess');
  if(!panel){panel=document.createElement('div');panel.id='bankManagementAccess';panel.style.marginTop='14px';card.appendChild(panel);}
  var roles=await api('GET','/api/bank/access/templates?bankId='+encodeURIComponent(bankId));
  var grants=await api('GET','/api/bank/access/permissions?bankId='+encodeURIComponent(bankId));
  if(roles.error||grants.error){panel.innerHTML='<p style="color:#c66;">'+escapeHtml(roles.error||grants.error||'权限加载失败')+'</p>';return;}
  var labels={MANAGE_MEMBERS:'管理成员',MANAGE_PERMISSIONS:'管理权限',VIEW_FINANCE:'查看财务',SET_RATES:'设置利率',ISSUE_LOAN:'直接放贷',APPROVE_LOAN:'审批贷款'};
  var perms=roles.permissions||[];
  var memberOptions=members.filter(function(m){return m.role!=='OWNER';}).map(function(m){return '<option value="'+escapeAttr(m.uuid)+'">'+escapeHtml(m.name||m.uuid)+' ('+escapeHtml(m.role||'TELLER')+')</option>';}).join('');
  var templates=['DIRECTOR','MANAGER','TELLER'].map(function(role){var selected=(roles.roles||{})[role]||[];var boxes=perms.map(function(p){return '<label style="display:inline-block;margin:2px 8px 2px 0;"><input type="checkbox" data-bank-role="'+role+'" value="'+p+'" '+(selected.indexOf(p)>=0?'checked':'')+'/> '+(labels[p]||p)+'</label>';}).join('');return '<tr><td>'+role+'</td><td>'+boxes+'</td><td><button class="btn btn-sm" onclick="saveBankRoleTemplate(\''+role+'\')">保存</button></td></tr>';}).join('');
  var grantRows=(grants.permissions||[]).map(function(p){return '<tr><td>'+escapeHtml(p.player_uuid)+'</td><td>'+escapeHtml(labels[p.permission]||p.permission)+'</td><td><button class="btn btn-sm btn-danger" onclick="setBankPermission(false,\''+escapeAttr(p.player_uuid)+'\',\''+escapeAttr(p.permission)+'\')">撤销</button></td></tr>';}).join('');
  panel.innerHTML='<h4>银行岗位与权限</h4><div class="form-row"><label>成员<br><select id="bankRoleMember">'+(memberOptions||'<option value="">没有可管理成员</option>')+'</select></label><label>岗位<br><select id="bankRoleValue"><option value="DIRECTOR">业务主管</option><option value="MANAGER">经理</option><option value="TELLER">柜员</option></select></label><button class="btn btn-sm" onclick="setBankMemberRole()">设置岗位</button></div><div class="table-wrap"><table><thead><tr><th>岗位</th><th>该银行默认权限</th><th>操作</th></tr></thead><tbody>'+templates+'</tbody></table></div><h4 style="margin-top:14px;">成员额外权限</h4><div class="form-row"><label>成员<br><select id="bankPermissionMember">'+(memberOptions||'<option value="">没有可授权成员</option>')+'</select></label><label>权限<br><select id="bankPermissionValue">'+perms.map(function(p){return '<option value="'+p+'">'+(labels[p]||p)+'</option>';}).join('')+'</select></label><button class="btn btn-sm btn-success" onclick="setBankPermission(true)">授予</button></div><div class="table-wrap"><table><thead><tr><th>成员</th><th>额外权限</th><th>操作</th></tr></thead><tbody>'+(grantRows||'<tr><td colspan="3" style="color:#666;">暂无额外权限</td></tr>')+'</tbody></table></div>';
}
async function setBankMemberRole(){
  var bankId=document.getElementById('mgmtBankId').value,uuid=document.getElementById('bankRoleMember').value,role=document.getElementById('bankRoleValue').value;
  if(!uuid){toast('请选择成员','err');return;}var d=await api('POST','/api/bank/access/member-role',{bankId:bankId,playerUuid:uuid,role:role});if(d.message){toast(d.message,'ok');loadBankMembers();}else toast(d.error||'岗位更新失败','err');
}
async function saveBankRoleTemplate(role){
  var bankId=document.getElementById('mgmtBankId').value,perms=Array.from(document.querySelectorAll('input[data-bank-role="'+role+'"]:checked')).map(function(e){return e.value;});
  var d=await api('POST','/api/bank/access/templates/set',{bankId:bankId,role:role,permissions:perms});if(d.message){toast(d.message,'ok');loadBankMembers();}else toast(d.error||'模板保存失败','err');
}
async function setBankPermission(enabled,uuid,permission){
  var bankId=document.getElementById('mgmtBankId').value,target=uuid||document.getElementById('bankPermissionMember').value,perm=permission||document.getElementById('bankPermissionValue').value;
  if(!target){toast('请选择成员','err');return;}var d=await api('POST','/api/bank/access/permissions/set',{bankId:bankId,playerUuid:target,permission:perm,enabled:enabled});if(d.message){toast(d.message,'ok');loadBankMembers();}else toast(d.error||'权限更新失败','err');
}
async function addBankMember(){
  var d=await api('POST','/api/bank/members/add',{
    bankId:document.getElementById('mgmtBankId').value,
    playerUuid:document.getElementById('newBankMemberUuid').value,
    playerName:'',
    role:'MEMBER'
  });
  if(d.message){toast(d.message,'ok');loadBankMembers();}else toast(d.error,'err');
}
async function removeBankMember(bankId,uuid){
  if(!confirm('确定移除该成员？'))return;
  var d=await api('POST','/api/bank/members/remove',{bankId:bankId,playerUuid:uuid});
  if(d.message){toast(d.message,'ok');loadBankMembers();}else toast(d.error,'err');
}

// ====== MY ENTERPRISES ======
async function selectCorporateBank(){
  var enterpriseId=document.getElementById('efEnterpriseId').value.trim(),bankId=document.getElementById('efBankId').value.trim();
  if(!enterpriseId||!bankId){toast('请填写企业ID和银行ID','err');return;}
  var d=await api('POST','/api/enterprise/corporate-bank',{enterpriseId:enterpriseId,bankId:bankId});
  if(d.message){toast(d.message,'ok');loadMyEnterprises();}else toast(d.error||'切换开户行失败','err');
}
async function requestEnterpriseFinance(){
  var body={enterpriseId:document.getElementById('efEnterpriseId').value.trim(),bankId:document.getElementById('efBankId').value.trim(),purpose:document.getElementById('efPurpose').value,principal:Number(document.getElementById('efAmount').value),termDays:Number(document.getElementById('efTerm').value),collateralType:document.getElementById('efCollateralType').value,collateralRef:document.getElementById('efCollateralRef').value.trim(),loanToValue:Number(document.getElementById('efLtv').value)};
  if(!body.enterpriseId||!body.bankId||!body.collateralRef){toast('请填写企业、开户行和抵押物ID','err');return;}
  var d=await api('POST','/api/enterprise/finance/request',body);
  if(d.success){toast(d.message,'ok');loadEnterpriseFinance();}else toast(d.error||'融资申请失败','err');
}
async function loadEnterpriseFinance(){
  var enterpriseId=document.getElementById('efEnterpriseId').value.trim();if(!enterpriseId)return;
  var d=await api('GET','/api/enterprise/finance/loans?enterpriseId='+encodeURIComponent(enterpriseId));
  var rows='';(d.loans||[]).forEach(function(l){rows+='<tr><td>'+escapeHtml(l.id)+'</td><td>'+escapeHtml(l.bank_id)+'</td><td>'+escapeHtml(l.purpose)+'</td><td>'+fmt(l.remaining)+'</td><td>'+pct(l.interest_rate)+'</td><td>'+escapeHtml(l.status)+'</td><td>'+((l.status==='ACTIVE'||l.status==='OVERDUE')?'<button class="btn btn-sm" onclick="repayEnterpriseFinance(\''+escapeAttr(l.id)+'\')">还款</button>':'')+'</td></tr>';});
  document.getElementById('efLoanBody').innerHTML=rows||'<tr><td colspan="7" style="color:#666;">暂无企业贷款</td></tr>';
  loadEnterpriseInventory();
}
async function loadEnterpriseInventory(){
  var enterpriseId=document.getElementById('efEnterpriseId').value.trim();if(!enterpriseId)return;
  var d=await api('GET','/api/enterprise/finance/inventory?enterpriseId='+encodeURIComponent(enterpriseId));
  var rows='';(d.lots||[]).forEach(function(l){rows+='<tr><td>'+escapeHtml(l.id)+'</td><td>'+escapeHtml(l.description)+'</td><td>'+fmt(l.quantity)+'</td><td>'+fmt(l.appraised_value)+'</td><td>'+escapeHtml(l.status)+'</td></tr>';});
  document.getElementById('efInventoryBody').innerHTML=rows||'<tr><td colspan="5" style="color:#666;">暂无管理员登记的估值库存</td></tr>';
}
async function repayEnterpriseFinance(loanId){
  var enterpriseId=document.getElementById('efEnterpriseId').value.trim(),amount=Number(prompt('从企业公户还款金额','1000'));if(!enterpriseId||!amount)return;
  var d=await api('POST','/api/enterprise/finance/repay',{enterpriseId:enterpriseId,loanId:loanId,amount:amount});
  if(d.success){toast(d.message,'ok');loadEnterpriseFinance();loadMyEnterprises();}else toast(d.error||'还款失败','err');
}
async function payEnterpriseSalary(){
  var enterpriseId=document.getElementById('salaryEnterpriseId').value.trim(),employee=document.getElementById('salaryEmployee').value.trim();
  if(!enterpriseId||!employee){toast('请填写企业和员工','err');return;}
  var d=await api('POST','/api/enterprise/salary/pay',{enterpriseId:enterpriseId,employee:employee});
  if(d.success){toast(d.message+' '+fmt(d.amount||0),'ok');loadMyEnterprises();}else toast(d.error||d.message||'发薪失败','err');
}
var myEnterprises=[];
async function loadMyEnterprises(){
  var d=await api('GET','/api/my-enterprises');
  myEnterprises=d.enterprises||[];
  var totalAssets=0,totalEmp=0,cards=[];
  myEnterprises.forEach(function(e){
    totalAssets+=Number(e.current_assets||0);totalEmp+=Number(e.employee_count||0);
    cards.push(ksCard({
      title:e.name,badge:e.industry||e.type||'',badgeCls:'cyan',
      fields:[['企业等级','Lv.'+fmt(e.level||1)],['当前资产',fmt(e.current_assets)],['注册资本',fmt(e.registered_capital)],['企业ID',e.id],['员工数',fmt(e.employee_count||0)]],
      onclick:"ksEntDrill('"+escapeAttr(e.id)+"')",
      actions:[{label:'⚙ 详细管理',onclick:"ksEntDrill('"+escapeAttr(e.id)+"')"}]
    }));
  });
  ksGrid('entCards',cards,'暂无企业 — 请先在"创建企业"中注册');
  ksKpiRow('myEntKpis',[
    {icon:'🏢',label:'名下企业',value:String(myEnterprises.length)},
    {icon:'📊',label:'总资产',value:fmt(totalAssets)},
    {icon:'👥',label:'雇员总数',value:String(totalEmp)},
    {icon:'🏭',label:'涉足行业',value:String(new Set(myEnterprises.map(function(e){return e.industry||'OTHER'})).size)}
  ]);
  document.getElementById('myDivBody').innerHTML='<tr><td colspan="5" style="color:#666;">分红记录请在管理面板查看</td></tr>';
  var opts=myEnterprises.map(function(e){return '<option value="'+e.id+'">'+escapeHtml(e.name)+' ('+e.id+')</option>';}).join('');
  ['pjPubEntId','pcPubEntId'].forEach(function(sel){
    var el=document.getElementById(sel);
    if(el)el.innerHTML=opts||'<option value="">暂无企业</option>';
  });
  loadEnterpriseTop50();
  loadMyDividendPayouts();
}
async function loadMyDividendPayouts(){
  var d=await api('GET','/api/enterprise/dividend/payouts');
  var rows='';(d.payouts||[]).forEach(function(p){
    rows+='<tr><td>'+escapeHtml(p.dividend_id||'')+'</td><td>'+escapeHtml(p.enterprise_name||p.enterprise_id||'')+'</td><td>'+fmt(p.gross_amount)+'</td><td>'+fmt(p.tax_amount)+'</td><td>'+(p.paid_at?new Date(p.paid_at*1000).toLocaleString('zh-CN'):'')+'</td></tr>';
  });
  document.getElementById('myDivBody').innerHTML=rows||'<tr><td colspan="5" style="color:#666;">暂无分红记录</td></tr>';
}
async function loadEnterpriseTop50(){
  var d=await api('GET','/api/rankings/enterprises');
  var rows='';(d.rankings||[]).forEach(function(e,i){
    rows+='<tr><td>'+(i+1)+'</td><td>'+escapeHtml(e.name||'')+(e.description?'<div style="font-size:10px;color:#888;">'+escapeHtml(e.description)+'</div>':'')+'</td><td>'+fmt(e.player_count||0)+'</td><td>'+fmt(e.current_assets||0)+'</td></tr>';
  });
  document.getElementById('enterpriseTop50Body').innerHTML=rows||'<tr><td colspan="4" style="color:#666;">暂无企业</td></tr>';
}
async function createMyEnterprise(){
  var coOwners=document.getElementById('entCoOwners').value;
  var owners=['__self__'];
  if(coOwners.trim()){owners=owners.concat(coOwners.split(',').map(function(s){return s.trim();}));}
  var d=await api('POST','/api/enterprise/register',{
    name:document.getElementById('entName').value,
    ownerUuids:owners,
    registeredCapital:parseFloat(document.getElementById('entCapital').value)||0,
    type:'PRIVATE',
    region:document.getElementById('entRegion').value
  });
  if(d.id){toast('企业注册成功: '+d.id,'ok');loadMyEnterprises();switchEntSub('overview');}
  else if(d.pendingId){toast(d.message||'合资确认已发起','ok');switchTab('my-invites');}
  else toast(d.error||'注册失败（资金不足？）','err');
}
async function loadAllEnterprises(){
  var d=await api('GET','/api/enterprise/list');
  var cards=(d.enterprises||[]).map(function(e){
    return ksCard({
      title:e.name,badge:e.industry||'OTHER',badgeCls:'cyan',
      fields:[['企业ID',e.id],['类型',e.type||''],['注册资本',fmt(e.registered_capital)],['员工数',fmt(e.employee_count)],['状态',e.status||''],['加入方式','仅接受邀请']]
    });
  });
  ksGrid('allEntsGrid',cards,'暂无企业');
}
async function sendEntInvite(){
  var d=await api('POST','/api/enterprise/invite/send',{
    enterpriseId:document.getElementById('inviteEntId2').value,
    inviteeUuid:document.getElementById('inviteeEntUuid').value
  });
  if(d.message){toast(d.message,'ok');}else toast(d.error,'err');
}
async function injectEnterpriseCapital(){
  var enterpriseId=document.getElementById('entFinanceId').value.trim();
  var amount=parseFloat(document.getElementById('entInjectAmount').value)||0;
  if(!enterpriseId||amount<=0){toast('请输入企业ID和有效金额','err');return;}
  var d=await api('POST','/api/enterprise/capital/inject',{enterpriseId:enterpriseId,amount:amount});
  if(d.success){toast(d.message||'企业注资成功','ok');loadMyEnterprises();}else toast(d.error||d.message||'企业注资失败','err');
}
async function saveEnterpriseDividendRate(){
  var enterpriseId=document.getElementById('entFinanceId').value.trim();
  var rate=parseFloat(document.getElementById('entDividendRate').value);
  if(!enterpriseId||!Number.isFinite(rate)){toast('请输入企业ID和分红比例','err');return;}
  var d=await api('POST','/api/enterprise/dividend/rate',{enterpriseId:enterpriseId,rate:rate});
  if(d.success){toast(d.message||'分红比例已保存','ok');loadMyEnterprises();}else toast(d.error||d.message||'保存失败','err');
}
async function saveEnterpriseDividendShares(){
  var enterpriseId=document.getElementById('entFinanceId').value.trim();
  var raw=document.getElementById('entDividendShares').value.trim();
  var shares={};
  try{
    raw.split(',').forEach(function(part){var pair=part.trim().split(':');if(pair.length!==2)throw new Error();shares[pair[0].trim()]=Number(pair[1].trim());});
  }catch(e){toast('格式应为 UUID:60,UUID:40','err');return;}
  if(!enterpriseId||Object.keys(shares).length===0||Object.values(shares).some(function(v){return !Number.isFinite(v);})){toast('请输入企业ID和有效的所有者占比','err');return;}
  var d=await api('POST','/api/enterprise/dividend/shares',{enterpriseId:enterpriseId,shares:shares});
  if(d.success){toast(d.message||'所有者分红占比已保存','ok');}else toast(d.error||d.message||'保存失败','err');
}
async function distributeEnterpriseDividend(){
  var enterpriseId=document.getElementById('entFinanceId').value.trim();
  if(!enterpriseId){toast('请输入企业ID','err');return;}
  if(!confirm('确认按当前比例从企业公户发放一次分红？'))return;
  var d=await api('POST','/api/enterprise/dividend/distribute',{enterpriseId:enterpriseId});
  if(d.success){toast(d.message||'分红已发放','ok');loadMyEnterprises();}else toast(d.error||d.message||'分红失败','err');
}
async function distributeCustomEnterpriseDividend(){
  var enterpriseId=document.getElementById('entFinanceId').value.trim();
  var amount=parseFloat(document.getElementById('entCustomDividendAmount').value)||0;
  var raw=document.getElementById('entCustomDividendShares').value.trim();
  var shares={};
  try{raw.split(',').forEach(function(part){var pair=part.trim().split(':');if(pair.length!==2||!pair[0].trim())throw new Error();shares[pair[0].trim()]=Number(pair[1].trim());});}
  catch(e){toast('成员格式应为 玩家ID:60,玩家ID:40','err');return;}
  if(!enterpriseId||amount<=0||Object.keys(shares).length===0||Object.values(shares).some(function(v){return !Number.isFinite(v);})){toast('请填写企业、总额和成员比例','err');return;}
  if(!confirm('确认从企业公户发放 '+fmt(amount)+' 的税前分红？'))return;
  var d=await api('POST','/api/enterprise/dividend/custom',{enterpriseId:enterpriseId,amount:amount,shares:shares});
  if(d.success){toast(d.message||'分红已发放','ok');loadMyEnterprises();}else toast(d.error||d.message||'分红失败','err');
}
async function previewCustomEnterpriseDividend(){
  var enterpriseId=document.getElementById('entFinanceId').value.trim();
  var amount=parseFloat(document.getElementById('entCustomDividendAmount').value)||0;
  var raw=document.getElementById('entCustomDividendShares').value.trim(); var shares={};
  try{raw.split(',').forEach(function(part){var pair=part.trim().split(':');if(pair.length!==2||!pair[0].trim())throw new Error();shares[pair[0].trim()]=Number(pair[1].trim());});}
  catch(e){toast('成员格式应为 玩家ID:60,玩家ID:40','err');return;}
  var d=await api('POST','/api/enterprise/dividend/preview',{enterpriseId:enterpriseId,amount:amount,shares:shares});
  if(d.error){toast(d.error,'err');return;}
  var rows=(d.payouts||[]).map(function(p){return '<tr><td>'+p.playerUuid+'</td><td>'+Number(p.sharePercent).toFixed(2)+'%</td><td>'+fmt(p.gross)+'</td><td>'+fmt(p.tax)+'</td><td>'+fmt(p.net)+'</td></tr>';}).join('');
  showModal('分红方案预览','<p>税前总额: '+fmt(d.amount)+' | 税额: '+fmt(d.tax)+' | 税后总额: '+fmt(d.net)+'</p><div class="table-wrap"><table><tr><th>成员</th><th>比例</th><th>税前</th><th>税额</th><th>到手</th></tr>'+rows+'</table></div>');
}

// ====== 招投标（工程招标 + 企业采购） ======
var _bidKpi={proj:null,proc:null};
function ksRenderBidKpis(){
  ksKpiRow('bidKpis',[
    {icon:'🎯',label:'招标中项目',value:_bidKpi.proj==null?'—':String(_bidKpi.proj.open),sub:_bidKpi.proj?'共 '+_bidKpi.proj.total+' 个':''},
    {icon:'🛒',label:'开放采购需求',value:_bidKpi.proc==null?'—':String(_bidKpi.proc.open),sub:_bidKpi.proc?'共 '+_bidKpi.proc.total+' 个':''},
    {icon:'💰',label:'项目预算合计',value:_bidKpi.proj==null?'—':fmt(_bidKpi.proj.budget)},
    {icon:'📦',label:'采购预算合计',value:_bidKpi.proc==null?'—':fmt(_bidKpi.proc.budget)}
  ]);
}
async function loadProjectsBrowse(){
  var d=await api('GET','/api/enterprise/projects');
  var open=0,budget=0;
  var cards=(d.projects||[]).map(function(p){
    if(p.status==='OPEN'){open++;budget+=Number(p.budget)||0;}
    var dl=p.deadline?new Date(p.deadline*1000).toLocaleDateString('zh-CN'):'—';
    var actions=[{label:'🔎 详情',onclick:"openProjectDetail('"+escapeAttr(p.id)+"')"}];
    if(p.status==='OPEN')actions.unshift({label:'📝 投标',cls:'btn-primary',onclick:"openBidModal('"+escapeAttr(p.id)+"')"});
    return ksCard({
      title:p.title,badge:projectStatusLabel(p.status),badgeCls:p.status==='OPEN'?'cyan':'',
      fields:[['项目ID',p.id],['预算',fmt(p.budget)],['投标数',fmt(p.bidCount||0)],['截止',dl]],
      onclick:"openProjectDetail('"+escapeAttr(p.id)+"')",actions:actions
    });
  });
  ksGrid('bidProjGrid',cards,'暂无招标项目');
  _bidKpi.proj={open:open,total:(d.projects||[]).length,budget:budget};
  ksRenderBidKpis();
}
async function openProjectDetail(projectId){
  var pd=await api('GET','/api/enterprise/projects');
  var p=(pd.projects||[]).find(function(x){return x.id===projectId;})||{};
  var bd=await api('GET','/api/enterprise/project/bids?projectId='+projectId);
  var dl=p.deadline?new Date(p.deadline*1000).toLocaleString('zh-CN'):'—';
  var html='<div class="form-row"><table style="width:100%;font-size:12px;"><tr><td>发布方</td><td>'+(p.publisher_type||'')+' ('+(p.publisher_uuid||'')+')</td></tr>'
    +'<tr><td>预算</td><td>'+fmt(p.budget)+'</td></tr>'
    +'<tr><td>位置</td><td>'+escapeHtml(p.location||'未指定')+'</td></tr>'
    +'<tr><td>截止时间</td><td>'+dl+'</td></tr>'
    +'<tr><td>预付款比例</td><td>'+pct(p.prepayment_ratio)+'</td></tr>'
    +'<tr><td>违约金比例</td><td>'+pct(p.penalty_ratio)+'</td></tr>'
    +'<tr><td>保证金要求</td><td>'+(p.deposit_ratio>0?pct(p.deposit_ratio)+'（中标金额，需在 '+(p.deposit_deadline_hours||24)+' 小时内缴纳）':'无')+'</td></tr>'
    +'<tr><td>允许分包</td><td>'+(p.allow_subcontract?'是':'否')+'</td></tr>'
    +'<tr><td>允许联合体投标</td><td>'+(p.allow_consortium?'是':'否')+'</td></tr>'
    +'<tr><td>状态</td><td>'+(p.status||'')+'</td></tr></table></div>'
    +'<div class="form-row"><h4 style="margin:8px 0 4px;">投标记录（'+(bd.count||0)+'）</h4></div>'
    +'<div class="table-wrap"><table><thead><tr><th>投标方</th><th>类型</th><th>金额</th><th>状态</th><th>时间</th><th>操作</th></tr></thead><tbody>'
    +(bd.bids||[]).map(function(b){
      var canPay=b.status==='PENDING_DEPOSIT'&&((b.bidder_type==='PLAYER'&&b.bidder_uuid===myPlayerUuid)||(b.bidder_type==='ENTERPRISE'&&myEnterprises.map(function(e){return e.id;}).indexOf(b.enterprise_id)>=0));
      var act=canPay?'<button class="btn btn-sm btn-primary" onclick="payBidDeposit(\''+b.id+'\')">💰 缴纳保证金 '+fmt(b.deposit_amount)+'</button>':'';
      return '<tr><td style="font-size:10px;">'+escapeHtml(bidderLabel(b))+'</td><td>'+bidTypeLabel(b.bidder_type)+'</td><td>'+fmt(b.bid_amount)+'</td><td><span class="badge '+(b.status==='AWARDED'?'badge-owner':b.status==='REJECTED'?'badge-closed':'badge-active')+'">'+bidStatusLabel(b.status)+'</span></td><td>'+new Date(b.submitted_at*1000).toLocaleString('zh-CN')+'</td><td>'+act+'</td></tr>';
    }).join('')
    +'</tbody></table></div>'
    +(((bd.bids||[]).length===0)?'<p style="color:#666;font-size:11px;">暂无投标</p>':'');
  showModal('🔎 招标详情 '+projectId,html);
}
async function payBidDeposit(bidId){
  var d=await api('POST','/api/enterprise/bid/deposit/pay',{bidId:bidId});
  if(d.status==='AWARDED'){toast(d.message||'保证金已缴纳，中标确认','ok');closeModal();loadProjectsBrowse();loadMyBids();}else toast(d.error||'失败','err');
}
function openBidModal(projectId){
  var html='<div class="form-row">'
    +'<label>投标方类型<br><select id="mBidType" onchange="document.getElementById(\'mBidEntWrap\').style.display=this.value===\'ENTERPRISE\'?\'\':\'none\';document.getElementById(\'mBidPlayerWrap\').style.display=this.value===\'PLAYER\'?\'\':\'none\';"><option value="PLAYER">以个人名义</option><option value="ENTERPRISE">以企业名义</option></select></label>'
    +'</div><div class="form-row" id="mBidPlayerWrap"><span style="font-size:11px;color:#888;">将以你当前账号（'+escapeHtml(myPlayerUuid)+'）投标</span></div>'
    +'<div class="form-row" id="mBidEntWrap" style="display:none;"><label>企业ID<br><input id="mBidEntId" placeholder="你所属企业的ID"/></label></div>'
    +'<div class="form-row"><label>投标金额<br><input id="mBidAmount" type="number" step="1"/></label>'
    +'<button class="btn btn-primary" onclick="submitMyBid(\''+projectId+'\')">提交投标</button></div>';
  showModal('📝 投标项目 '+projectId,html);
}
async function submitMyBid(projectId){
  var type=document.getElementById('mBidType').value;
  var body={projectId:projectId,bidderType:type,bidAmount:parseFloat(document.getElementById('mBidAmount').value)||0};
  if(type==='ENTERPRISE')body.enterpriseId=document.getElementById('mBidEntId').value;
  else body.bidderUuid=myPlayerUuid;
  var d=await api('POST','/api/enterprise/bid/submit',body);
  if(d.id){toast('投标成功: '+d.id,'ok');closeModal();loadProjectsBrowse();}else toast(d.error||'失败','err');
}
async function loadProcurementsBrowse(){
  var d=await api('GET','/api/enterprise/procurements');
  var open=0,budget=0;
  var cards=(d.procurements||[]).map(function(p){
    if(p.status==='OPEN'){open++;budget+=Number(p.budget)||0;}
    var actions=[{label:'🔎 详情',onclick:"openProcurementDetail('"+escapeAttr(p.id)+"')"}];
    if(p.status==='OPEN')actions.unshift({label:'📝 投标',cls:'btn-primary',onclick:"openSupplyBidModal('"+escapeAttr(p.id)+"')"});
    return ksCard({
      title:p.title,badge:p.status||'',badgeCls:p.status==='OPEN'?'cyan':'',
      fields:[['采购ID',p.id],['发布企业',p.enterprise_id||''],['预算',fmt(p.budget)],['数量',fmt(p.quantity||1)],['投标数',fmt(p.bidCount||0)]],
      onclick:"openProcurementDetail('"+escapeAttr(p.id)+"')",actions:actions
    });
  });
  ksGrid('bidProcGrid',cards,'暂无采购需求');
  _bidKpi.proc={open:open,total:(d.procurements||[]).length,budget:budget};
  ksRenderBidKpis();
}
async function openProcurementDetail(procId){
  var pd=await api('GET','/api/enterprise/procurements');
  var p=(pd.procurements||[]).find(function(x){return x.id===procId;})||{};
  var bd=await api('GET','/api/enterprise/procurement/bids?procurementId='+procId);
  var html='<div class="form-row"><table style="width:100%;font-size:12px;"><tr><td>发布企业</td><td>'+(p.enterprise_id||'')+'</td></tr>'
    +'<tr><td>需求说明</td><td>'+escapeHtml(p.item_desc||'未填写')+'</td></tr>'
    +'<tr><td>数量</td><td>'+(p.quantity||1)+'</td></tr>'
    +'<tr><td>预算</td><td>'+fmt(p.budget)+'</td></tr>'
    +'<tr><td>状态</td><td>'+(p.status||'')+'</td></tr></table></div>'
    +'<div class="form-row"><h4 style="margin:8px 0 4px;">投标记录（'+(bd.count||0)+'）</h4></div>'
    +'<div class="table-wrap"><table><thead><tr><th>投标方</th><th>类型</th><th>单价</th><th>总价</th><th>状态</th><th>时间</th></tr></thead><tbody>'
    +(bd.bids||[]).map(function(b){
      return '<tr><td style="font-size:10px;">'+escapeHtml(bidderLabel(b))+'</td><td>'+bidTypeLabel(b.bidder_type)+'</td><td>'+fmt(b.unit_price)+'</td><td>'+fmt(b.total_price)+'</td><td><span class="badge '+(b.status==='AWARDED'?'badge-owner':b.status==='REJECTED'?'badge-closed':'badge-active')+'">'+bidStatusLabel(b.status)+'</span></td><td>'+new Date(b.submitted_at*1000).toLocaleString('zh-CN')+'</td></tr>';
    }).join('')
    +'</tbody></table></div>'
    +(((bd.bids||[]).length===0)?'<p style="color:#666;font-size:11px;">暂无投标</p>':'');
  showModal('🔎 采购详情 '+procId,html);
}
async function loadMyBids(){
  var d=await api('GET','/api/enterprise/my-bids');
  var t1='';(d.projectBids||[]).forEach(function(b){
    var act=b.status==='PENDING_DEPOSIT'?'<button class="btn btn-sm btn-primary" onclick="payBidDeposit(\''+b.id+'\')">💰 缴纳保证金 '+fmt(b.deposit_amount)+'</button>':'';
    t1+='<tr><td>'+escapeHtml(b.project_title||b.project_id||'')+'</td><td>'+bidTypeLabel(b.bidder_type)+(b.enterprise_id?' ('+b.enterprise_id+')':'')+'</td><td>'+fmt(b.bid_amount)+'</td><td><span class="badge '+(b.status==='AWARDED'?'badge-owner':b.status==='REJECTED'?'badge-closed':'badge-active')+'">'+bidStatusLabel(b.status)+'</span></td><td>'+(b.project_status||'')+'</td><td>'+new Date(b.submitted_at*1000).toLocaleString('zh-CN')+'</td><td>'+act+'</td></tr>';
  });
  document.getElementById('myProjBidBody').innerHTML=t1||'<tr><td colspan="7" style="color:#666;">暂无投标记录</td></tr>';
  var t2='';(d.procurementBids||[]).forEach(function(b){
    t2+='<tr><td>'+escapeHtml(b.procurement_title||b.procurement_id||'')+'</td><td>'+bidTypeLabel(b.bidder_type)+(b.enterprise_id?' ('+b.enterprise_id+')':'')+'</td><td>'+fmt(b.unit_price)+' / '+fmt(b.total_price)+'</td><td><span class="badge '+(b.status==='AWARDED'?'badge-owner':b.status==='REJECTED'?'badge-closed':'badge-active')+'">'+bidStatusLabel(b.status)+'</span></td><td>'+(b.procurement_status||'')+'</td><td>'+new Date(b.submitted_at*1000).toLocaleString('zh-CN')+'</td></tr>';
  });
  document.getElementById('myProcBidBody').innerHTML=t2||'<tr><td colspan="6" style="color:#666;">暂无投标记录</td></tr>';
}
function openSupplyBidModal(procId){
  var html='<div class="form-row">'
    +'<label>投标方类型<br><select id="mSupType" onchange="document.getElementById(\'mSupEntWrap\').style.display=this.value===\'ENTERPRISE\'?\'\':\'none\';document.getElementById(\'mSupPlayerWrap\').style.display=this.value===\'PLAYER\'?\'\':\'none\';"><option value="PLAYER">以个人名义</option><option value="ENTERPRISE">以企业名义</option></select></label>'
    +'</div><div class="form-row" id="mSupPlayerWrap"><span style="font-size:11px;color:#888;">将以你当前账号（'+escapeHtml(myPlayerUuid)+'）投标</span></div>'
    +'<div class="form-row" id="mSupEntWrap" style="display:none;"><label>企业ID<br><input id="mSupEntId" placeholder="你所属企业的ID"/></label></div>'
    +'<div class="form-row"><label>单价<br><input id="mSupUnitPrice" type="number" step="1"/></label>'
    +'<span style="font-size:11px;color:#888;">总价由采购数量自动计算</span>'
    +'<button class="btn btn-primary" onclick="submitMySupplyBid(\''+procId+'\')">提交投标</button></div>';
  showModal('📝 供应投标 '+procId,html);
}
async function submitMySupplyBid(procId){
  var type=document.getElementById('mSupType').value;
  var body={
    procurementId:procId,bidderType:type,
    unitPrice:parseFloat(document.getElementById('mSupUnitPrice').value)||0
  };
  if(type==='ENTERPRISE')body.enterpriseId=document.getElementById('mSupEntId').value;
  else body.bidderUuid=myPlayerUuid;
  var d=await api('POST','/api/enterprise/procurement/bid',body);
  if(d.id){toast('供应投标成功: '+d.id,'ok');closeModal();loadProcurementsBrowse();}else toast(d.error||'失败','err');
}

// ====== 我的发布/评标 ======
async function publishMyProject(){
  var type=document.getElementById('pjPubType').value;
  var publisherRef=type==='ENTERPRISE'?document.getElementById('pjPubEntId').value:myPlayerUuid;
  if(type==='ENTERPRISE'&&!publisherRef){toast('你还没有可用的企业','err');return;}
  var d=await api('POST','/api/enterprise/project/publish',{
    title:document.getElementById('pjTitle').value,
    publisherRef:publisherRef,
    publisherType:type,
    budget:parseFloat(document.getElementById('pjBudget').value)||0,
    prepaymentRatio:parseFloat(document.getElementById('pjPrepay').value)||0.3,
    penaltyRatio:parseFloat(document.getElementById('pjPenalty').value)||0.1,
    depositRatio:parseFloat(document.getElementById('pjDepositRatio').value)||0,
    depositDeadlineHours:parseInt(document.getElementById('pjDepositHours').value)||24,
    deadline:Math.floor(Date.now()/1000)+(parseInt(document.getElementById('pjDeadlineDays').value)||7)*86400,
    location:document.getElementById('pjLocation').value,
    allowSubcontract:document.getElementById('pjSubcontract').checked,
    allowConsortium:document.getElementById('pjConsortium').checked
  });
  if(d.id){toast('招标发布成功: '+d.id,'ok');loadMyPublished();}else toast(d.error||'失败','err');
}
async function publishMyProcurement(){
  var enterpriseId=document.getElementById('pcPubEntId').value;
  if(!enterpriseId){toast('你还没有可用的企业','err');return;}
  var d=await api('POST','/api/enterprise/procurement/publish',{
    enterpriseId:enterpriseId,
    title:document.getElementById('pcTitle').value,
    itemDesc:document.getElementById('pcItemDesc').value,
    quantity:parseInt(document.getElementById('pcQuantity').value)||1,
    budget:parseFloat(document.getElementById('pcBudget').value)||0
  });
  if(d.id){toast('采购发布成功: '+d.id,'ok');loadMyPublished();}else toast(d.error||'失败','err');
}
async function loadMyPublished(){
  if(!myEnterprises.length)await loadMyEnterprises();
  var myEntIds=myEnterprises.map(function(e){return e.id;});
  var pd=await api('GET','/api/enterprise/projects');
  var myProjs=(pd.projects||[]).filter(function(p){
    return (p.publisher_type==='PLAYER'&&p.publisher_uuid===myPlayerUuid)||(p.publisher_type==='ENTERPRISE'&&myEntIds.indexOf(p.publisher_uuid)>=0);
  });
  var t1='';myProjs.forEach(function(p){
    var act=(p.status==='OPEN'?'<button class="btn btn-sm btn-primary" onclick="openProjectAwardModal(\''+p.id+'\')">⚖ 评标</button>':'<span class="badge badge-closed">已结束</span>');
    t1+='<tr><td style="font-size:10px;">'+p.id+'</td><td>'+escapeHtml(p.title)+'</td><td>'+fmt(p.budget)+'</td><td>'+fmt(p.bidCount||0)+'</td><td><span class="badge '+projectStatusBadge(p.status)+'">'+projectStatusLabel(p.status)+'</span></td><td>'+act+'</td></tr>';
  });
  document.getElementById('myPubProjBody').innerHTML=t1||'<tr><td colspan="6" style="color:#666;">暂无发布的招标</td></tr>';
  var qd=await api('GET','/api/enterprise/procurements');
  var myProcs=(qd.procurements||[]).filter(function(p){return myEntIds.indexOf(p.enterprise_id)>=0;});
  var t2='';myProcs.forEach(function(p){
    var act=(p.status==='OPEN'?'<button class="btn btn-sm btn-primary" onclick="openProcurementAwardModal(\''+p.id+'\')">⚖ 评标</button>':'<span class="badge badge-closed">已结束</span>');
    t2+='<tr><td style="font-size:10px;">'+p.id+'</td><td>'+escapeHtml(p.title)+'</td><td>'+fmt(p.budget)+'</td><td>'+fmt(p.bidCount||0)+'</td><td><span class="badge '+(p.status==='OPEN'?'badge-active':p.status==='AWARDED'?'badge-pending':'badge-closed')+'">'+(p.status||'')+'</span></td><td>'+act+'</td></tr>';
  });
  document.getElementById('myPubProcBody').innerHTML=t2||'<tr><td colspan="6" style="color:#666;">暂无发布的采购</td></tr>';
}
async function openProjectAwardModal(projectId){
  var pd=await api('GET','/api/enterprise/projects');
  var p=(pd.projects||[]).find(function(x){return x.id===projectId;})||{};
  var bd=await api('GET','/api/enterprise/project/bids?projectId='+projectId);
  var isStrict=p.publisher_type==='OFFICIAL'||p.publisher_type==='STATE_OWNED';
  var rows=(bd.bids||[]).filter(function(b){return b.status==='PENDING';}).map(function(b){
    var pick=isStrict?'':'<button class="btn btn-sm" onclick="awardMyProject(\''+projectId+'\',\''+b.id+'\')">指定中标</button>';
    return '<tr><td style="font-size:10px;">'+b.id+'</td><td>'+escapeHtml(bidderLabel(b))+'</td><td>'+bidTypeLabel(b.bidder_type)+'</td><td>'+fmt(b.bid_amount)+'</td><td>'+pick+'</td></tr>';
  }).join('');
  var html='<p style="color:#888;font-size:11px;">'+(isStrict?'官方/国企招标强制按综合评分（价格50%+资质30%+时效20%）定标，不可自主指定':'你可以自主指定中标方，或使用综合评分自动评标')+'</p>'
    +'<div class="table-wrap"><table><thead><tr><th>投标ID</th><th>投标方</th><th>类型</th><th>金额</th><th>操作</th></tr></thead><tbody>'+(rows||'<tr><td colspan="5" style="color:#666;">暂无待处理投标</td></tr>')+'</tbody></table></div>'
    +'<div class="form-row" style="margin-top:8px;"><button class="btn btn-primary" onclick="awardMyProject(\''+projectId+'\')">⚖ 综合评分自动评标</button></div>';
  showModal('⚖ 评标 '+projectId,html);
}
async function awardMyProject(projectId,bidId){
  var body={projectId:projectId};if(bidId)body.bidId=bidId;
  var d=await api('POST','/api/enterprise/project/award',body);
  if(d.id){toast(d.message||('评标完成 | 中标方: '+bidTypeLabel(d.bidder_type)+' | 模式: '+d.award_mode),'ok');closeModal();loadMyPublished();loadProjectsBrowse();}else toast(d.error||'失败','err');
}
async function openProcurementAwardModal(procId){
  var bd=await api('GET','/api/enterprise/procurement/bids?procurementId='+procId);
  var rows=(bd.bids||[]).filter(function(b){return b.status==='PENDING';}).map(function(b){
    return '<tr><td style="font-size:10px;">'+b.id+'</td><td>'+escapeHtml(bidderLabel(b))+'</td><td>'+bidTypeLabel(b.bidder_type)+'</td><td>'+fmt(b.unit_price)+'</td><td>'+fmt(b.total_price)+'</td><td><button class="btn btn-sm" onclick="awardMyProcurement(\''+procId+'\',\''+b.id+'\')">指定中标</button></td></tr>';
  }).join('');
  var html='<div class="table-wrap"><table><thead><tr><th>投标ID</th><th>投标方</th><th>类型</th><th>单价</th><th>总价</th><th>操作</th></tr></thead><tbody>'+(rows||'<tr><td colspan="6" style="color:#666;">暂无待处理投标</td></tr>')+'</tbody></table></div>'
    +'<div class="form-row" style="margin-top:8px;"><button class="btn btn-primary" onclick="awardMyProcurement(\''+procId+'\')">⚖ 最低价自动评标</button></div>';
  showModal('⚖ 评标 '+procId,html);
}
async function awardMyProcurement(procId,bidId){
  var body={procurementId:procId};if(bidId)body.bidId=bidId;
  var d=await api('POST','/api/enterprise/procurement/award',body);
  if(d.message||d.id){toast(d.message||'评标完成','ok');closeModal();loadMyPublished();loadProcurementsBrowse();}else toast(d.error||'失败','err');
}

// Enterprise member management
async function loadEntMembers(){
  var entId=document.getElementById('mgmtEntId').value;
  if(!entId){toast('请先输入企业ID','err');return;}
  document.getElementById('entFinanceId').value=entId;
  var mine=myEnterprises.find(function(e){return e.id===entId;});
  if(mine&&Number.isFinite(Number(mine.dividend_rate)))document.getElementById('entDividendRate').value=Number(mine.dividend_rate);
  var d=await api('GET','/api/enterprise/members?enterpriseId='+entId);
  var t='';(d.members||[]).forEach(function(m){
    t+='<tr><td>'+m.uuid+'</td><td>'+m.name+'</td><td><span class="badge '+(m.role==='OWNER'?'badge-owner':m.role==='MANAGER'?'badge-pending':'badge-active')+'">'+m.role+'</span></td><td>'+fmt(m.salary)+'</td><td>'+new Date(m.joinedAt*1000).toLocaleString('zh-CN')+'</td><td><button class="btn btn-sm btn-danger" onclick="removeEntMember(\''+entId+'\',\''+m.uuid+'\')">移除</button></td></tr>';
  });
  document.getElementById('entMemberBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无成员</td></tr>';
  // Also load permissions
  var pd=await api('GET','/api/enterprise/permissions?enterpriseId='+entId);
  if(pd.permissions&&pd.permissions.length>0){
    var pt='<div style="margin-top:10px;"><h4>权限设置</h4><table><tr><th>玩家</th><th>权限</th><th>授予者</th></tr>';
    pd.permissions.forEach(function(p){pt+='<tr><td>'+p.player_uuid+'</td><td>'+p.permission+'</td><td>'+p.granted_by+'</td></tr>';});
    pt+='</table></div>';
    document.getElementById('entMemberBody').insertAdjacentHTML('afterend',pt);
  }
  renderEnterprisePermissionAndAudit(d.members||[],pd.permissions||[],entId);
}
function renderEnterprisePermissionAndAudit(members,permissions,entId){
  var card=document.getElementById('entMemberBody').closest('.card');
  var panel=document.getElementById('enterpriseManagementExtras');
  if(!panel){panel=document.createElement('div');panel.id='enterpriseManagementExtras';panel.style.marginTop='14px';card.appendChild(panel);}
  var grants={};permissions.forEach(function(p){if(p.permission==='BLINDBOX_DRAW')grants[p.player_uuid]=true;});
  var options=members.filter(function(m){return m.role!=='OWNER'&&m.role!=='MANAGER';}).map(function(m){return '<option value="'+escapeAttr(m.uuid)+'">'+escapeHtml(m.name||m.uuid)+' ('+escapeHtml(m.uuid)+')</option>';}).join('');
  var rows='';Object.keys(grants).forEach(function(uuid){rows+='<tr><td>'+escapeHtml(uuid)+'</td><td>BLINDBOX_DRAW</td><td><button class="btn btn-sm btn-danger" onclick="setEntBlindBoxPermission(false,\''+escapeAttr(uuid)+'\')">撤销</button></td></tr>';});
  panel.innerHTML='<h4>企业盲盒权限</h4><div class="form-row"><label>成员<br><select id="entBlindBoxPermissionMember">'+(options||'<option value="">没有可授权成员</option>')+'</select></label><button class="btn btn-sm btn-success" onclick="setEntBlindBoxPermission(true)">授予抽取</button></div><div class="table-wrap"><table><thead><tr><th>成员</th><th>权限</th><th>操作</th></tr></thead><tbody>'+ (rows||'<tr><td colspan="3" style="color:#666;">暂无额外盲盒权限</td></tr>') +'</tbody></table></div><div id="enterpriseAuditPanel" style="margin-top:14px;"></div>';
  renderEnterpriseRoleControls(members,permissions,entId);
  loadEnterpriseAudit();
}
async function renderEnterpriseRoleControls(members,permissions,entId){
  var panel=document.getElementById('enterpriseManagementExtras');if(!panel)return;
  var roles=await api('GET','/api/enterprise/access/templates?enterpriseId='+encodeURIComponent(entId));
  var accessPermissions=await api('GET','/api/enterprise/access/permissions?enterpriseId='+encodeURIComponent(entId));
  permissions=accessPermissions.permissions||[];
  if(roles.error){panel.innerHTML='<p style="color:#c66;">'+escapeHtml(roles.error)+'</p>';return;}
  var allPerms=roles.permissions||[];
  var labels={MANAGE_MEMBERS:'管理成员',MANAGE_PERMISSIONS:'管理权限',MANAGE_BIDDING:'管理招标',DECLARE_DIVIDEND:'宣布分红',VIEW_FINANCE:'查看财务',MANAGE_FUNDS:'管理公户',BLINDBOX_DRAW:'盲盒抽取'};
  var memberOptions=members.filter(function(m){return m.role!=='OWNER';}).map(function(m){return '<option value="'+escapeAttr(m.uuid)+'">'+escapeHtml(m.name||m.uuid)+' ('+escapeHtml(m.role||'EMPLOYEE')+')</option>';}).join('');
  var roleOptions='<option value="CEO">CEO</option><option value="MANAGER">经理</option><option value="EMPLOYEE">员工</option>';
  var templateRows=['CEO','MANAGER','EMPLOYEE'].map(function(role){var mine=(roles.roles||{})[role]||[];var boxes=allPerms.map(function(p){return '<label style="display:inline-block;margin:2px 8px 2px 0;"><input type="checkbox" data-ent-role="'+role+'" value="'+p+'" '+(mine.indexOf(p)>=0?'checked':'')+'/> '+(labels[p]||p)+'</label>';}).join('');return '<tr><td>'+role+'</td><td>'+boxes+'</td><td><button class="btn btn-sm" onclick="saveEntRoleTemplate(\''+role+'\')">保存</button></td></tr>';}).join('');
  var grantRows=permissions.map(function(p){return '<tr><td>'+escapeHtml(p.player_uuid)+'</td><td>'+escapeHtml(labels[p.permission]||p.permission)+'</td><td><button class="btn btn-sm btn-danger" onclick="setEntPermission(false,\''+escapeAttr(p.player_uuid)+'\',\''+escapeAttr(p.permission)+'\')">撤销</button></td></tr>';}).join('');
  panel.innerHTML='<h4>企业岗位与权限</h4><div class="form-row"><label>成员<br><select id="entRoleMember">'+(memberOptions||'<option value="">没有可管理成员</option>')+'</select></label><label>岗位<br><select id="entRoleValue">'+roleOptions+'</select></label><button class="btn btn-sm" onclick="setEntMemberRole()">设置岗位</button></div><div class="table-wrap"><table><thead><tr><th>岗位</th><th>该企业默认权限</th><th>操作</th></tr></thead><tbody>'+templateRows+'</tbody></table></div><h4 style="margin-top:14px;">成员额外权限</h4><div class="form-row"><label>成员<br><select id="entPermissionMember">'+(memberOptions||'<option value="">没有可授权成员</option>')+'</select></label><label>权限<br><select id="entPermissionValue">'+allPerms.map(function(p){return '<option value="'+p+'">'+(labels[p]||p)+'</option>';}).join('')+'</select></label><button class="btn btn-sm btn-success" onclick="setEntPermission(true)">授予</button></div><div class="table-wrap"><table><thead><tr><th>成员</th><th>额外权限</th><th>操作</th></tr></thead><tbody>'+(grantRows||'<tr><td colspan="3" style="color:#666;">暂无额外权限</td></tr>')+'</tbody></table></div><div id="enterpriseAuditPanel" style="margin-top:14px;"></div>';
  var addRole=document.getElementById('newEntMemberRole');if(addRole&&!addRole.querySelector('option[value="CEO"]'))addRole.insertAdjacentHTML('afterbegin','<option value="CEO">CEO</option>');
  loadEnterpriseAudit();
}
async function setEntMemberRole(){
  var entId=document.getElementById('mgmtEntId').value,uuid=document.getElementById('entRoleMember').value,role=document.getElementById('entRoleValue').value;
  if(!uuid){toast('请选择成员','err');return;}var d=await api('POST','/api/enterprise/access/member-role',{enterpriseId:entId,playerUuid:uuid,role:role});if(d.message){toast(d.message,'ok');loadEntMembers();}else toast(d.error||'岗位更新失败','err');
}
async function saveEntRoleTemplate(role){
  var entId=document.getElementById('mgmtEntId').value,perms=Array.from(document.querySelectorAll('input[data-ent-role="'+role+'"]:checked')).map(function(e){return e.value;});
  var d=await api('POST','/api/enterprise/access/templates/set',{enterpriseId:entId,role:role,permissions:perms});if(d.message){toast(d.message,'ok');loadEntMembers();}else toast(d.error||'模板保存失败','err');
}
async function setEntPermission(enabled,uuid,permission){
  var entId=document.getElementById('mgmtEntId').value,target=uuid||document.getElementById('entPermissionMember').value,perm=permission||document.getElementById('entPermissionValue').value;
  if(!target){toast('请选择成员','err');return;}var d=await api('POST','/api/enterprise/access/permissions/set',{enterpriseId:entId,playerUuid:target,permission:perm,enabled:enabled});if(d.message){toast(d.message,'ok');loadEntMembers();}else toast(d.error||'权限更新失败','err');
}
async function setEntBlindBoxPermission(enabled,uuid){
  var entId=document.getElementById('mgmtEntId').value;
  var target=uuid||document.getElementById('entBlindBoxPermissionMember').value;
  if(!entId||!target){toast('请选择企业成员','err');return;}
  var d=await api('POST','/api/enterprise/permissions/set',{enterpriseId:entId,playerUuid:target,permission:'BLINDBOX_DRAW',enabled:enabled});
  if(d.message){toast(d.message,'ok');loadEntMembers();}else toast(d.error||'权限更新失败','err');
}
async function loadEnterpriseAudit(){
  var entId=document.getElementById('mgmtEntId').value;
  var panel=document.getElementById('enterpriseAuditPanel');if(!entId||!panel)return;
  var d=await api('GET','/api/enterprise/audit?enterpriseId='+encodeURIComponent(entId)+'&limit=80');
  if(d.error){panel.innerHTML='<h4>企业审计日志</h4><p style="color:#888;font-size:11px;">'+escapeHtml(d.error)+'</p>';return;}
  var rows='';(d.logs||[]).forEach(function(log){rows+='<tr><td>'+(log.createdAt?new Date(log.createdAt*1000).toLocaleString('zh-CN'):'')+'</td><td>'+escapeHtml(log.playerName||log.playerUuid||'SYSTEM')+'</td><td>'+escapeHtml(log.action||'')+'</td><td>'+escapeHtml(log.details||'')+'</td></tr>';});
  panel.innerHTML='<h4>企业审计日志</h4><div class="table-wrap"><table><thead><tr><th>时间</th><th>操作者</th><th>操作</th><th>详情</th></tr></thead><tbody>'+ (rows||'<tr><td colspan="4" style="color:#666;">暂无操作记录</td></tr>') +'</tbody></table></div>';
}
async function addEntMember(){
  var d=await api('POST','/api/enterprise/members/add',{
    enterpriseId:document.getElementById('mgmtEntId').value,
    playerUuid:document.getElementById('newEntMemberUuid').value,
    playerName:'',
    role:document.getElementById('newEntMemberRole').value,
    salary:parseFloat(document.getElementById('newEntMemberSalary').value)||0
  });
  if(d.message){toast(d.message,'ok');loadEntMembers();}else toast(d.error,'err');
}
async function removeEntMember(entId,uuid){
  if(!confirm('确定移除该成员？'))return;
  var d=await api('POST','/api/enterprise/members/remove',{enterpriseId:entId,playerUuid:uuid});
  if(d.message){toast(d.message,'ok');loadEntMembers();}else toast(d.error,'err');
}

// ====== MY INVITES ======
async function loadMyInvites(){
  var d=await api('GET','/api/player/invites');
  var t='';(d.invites||[]).forEach(function(i){
    t+='<tr><td>'+i.id+'</td><td>'+(i.enterprise_id||i.bank_id)+'</td><td>'+(i.enterprise_id?'企业':'银行')+'</td><td>'+i.inviter_uuid+'</td><td>'+new Date(i.created_at*1000).toLocaleString('zh-CN')+'</td><td><button class="btn btn-sm btn-success" onclick="respondInvite(\''+i.id+'\',\'ACCEPT\')">接受</button> <button class="btn btn-sm btn-danger" onclick="respondInvite(\''+i.id+'\',\'DECLINE\')">拒绝</button></td></tr>';
  });
  (d.creationConfirmations||[]).forEach(function(i){
    t+='<tr><td>'+i.id+'</td><td>'+escapeHtml(i.name||'')+'</td><td>合资成立确认</td><td>'+i.creator_uuid+'</td><td>'+new Date(i.expires_at*1000).toLocaleString('zh-CN')+'</td><td><button class="btn btn-sm btn-success" onclick="respondCreationConfirmation(\''+i.id+'\',\'ACCEPT\')">确认成立</button> <button class="btn btn-sm btn-danger" onclick="respondCreationConfirmation(\''+i.id+'\',\'DECLINE\')">拒绝</button></td></tr>';
  });
  document.getElementById('inviteBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无邀请</td></tr>';
  var badge=document.getElementById('inviteBadge');
  var cnt=(d.invites||[]).length+(d.creationConfirmations||[]).length;
  badge.textContent=cnt;badge.style.display=cnt>0?'inline':'none';
}
async function respondInvite(inviteId,action){
  var d=await api('POST','/api/player/invites/respond',{inviteId:inviteId,action:action});
  if(d.message){toast(d.message,'ok');loadMyInvites();loadMyBanks();loadMyEnterprises();}else toast(d.error,'err');
}
async function respondCreationConfirmation(pendingId,action){
  var d=await api('POST','/api/enterprise/creation/pending/respond',{pendingId:pendingId,action:action});
  if(d.message){toast(d.message,'ok');loadMyInvites();loadMyEnterprises();}else toast(d.error||'操作失败','err');
}

// ====== MY TAX ======
async function loadMyTax(){
  var d=await api('GET','/api/my-tax-records');
  var t='';(d.records||[]).forEach(function(r){
    t+='<tr><td>'+r.id+'</td><td>'+r.category+'</td><td>'+fmt(r.base_amount)+'</td><td>'+pct(r.tax_rate)+'</td><td>'+fmt(r.tax_amount)+'</td><td>'+new Date(r.collected_at*1000).toLocaleString('zh-CN')+'</td></tr>';
  });
  document.getElementById('taxBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无记录</td></tr>';
}

// ====== PUBLIC INFO: PRICES & TAX RATES ======
async function loadPublicInfo(){
  var d=await api('GET','/api/eco/public-info');
  // 价格表
  var tp='';
  var trendIcon={'UP':'<span style="color:#81c784;">▲ 涨</span>','DOWN':'<span style="color:#e57373;">▼ 跌</span>','FLAT':'<span style="color:#888;">— 平</span>'};
  (d.prices||[]).forEach(function(p){
    var hasMarket=p.marketAvg&&p.marketAvg>0;
    tp+='<tr>'
      +'<td><b>'+p.chineseName+'</b></td>'
      +'<td style="color:#888;font-size:11px;">'+p.material.toLowerCase()+'</td>'
      +'<td style="color:#aaa;">'+fmt(p.basePrice)+'</td>'
      +'<td style="color:#4fc3f7;font-weight:bold;">'+fmt(p.buyPrice)+'</td>'
      +'<td>'+(trendIcon[p.trend]||'')+'</td>'
      +'<td style="color:'+(hasMarket?'#81c784':'#666')+'">'+(hasMarket?fmt(p.marketAvg):'暂无数据')+'</td>'
      +'</tr>';
  });
  document.getElementById('pubPriceBody').innerHTML=tp||'<tr><td colspan="6" style="color:#666;">暂无配置</td></tr>';
  // 交易税率表
  var tt='';
  var taxDesc={
    'MARKET_TRADE':'玩家之间在市场挂单成交时收取',
    'PROPERTY_TRADE':'商品房转让时按成交价收取（契税，一次性）',
    'OFFICIAL_TRADE':'向官方出售物品时收取',
    'ENTERPRISE_SMALL':'年收入较低企业适用',
    'ENTERPRISE_MEDIUM':'年收入中等企业适用',
    'ENTERPRISE_LARGE':'年收入较高企业适用',
    'DIVIDEND_TAX':'企业向股东分红时收取',
    'BANK_INTEREST':'银行存款利息收入时收取',
    'PLAYER_TRANSFER':'玩家转账时仅对超过单笔免税额的部分收取',
    'TAX_PENALTY':'逾期未缴税款每日加收'
  };
  (d.taxRates||[]).forEach(function(t){
    tt+='<tr>'
      +'<td><b>'+t.chineseName+'</b></td>'
      +'<td style="color:#ffb74d;font-weight:bold;font-size:13px;">'+t.ratePercent+'</td>'
      +'<td style="color:#888;font-size:11px;">'+(taxDesc[t.category]||'')+'</td>'
      +'</tr>';
  });
  document.getElementById('pubTaxBody').innerHTML=tt||'<tr><td colspan="3" style="color:#666;">暂无配置</td></tr>';
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
  document.getElementById('pubIndustryBody').innerHTML=im||'<tr><td colspan="4" style="color:#666;">暂无配置</td></tr>';
}

// Init
var TAB_ORDER=['my-banks','blindbox','ent-blindbox','realestate','politic','my-enterprises','bidding','my-invites','my-tax'];
async function loadBootstrap(){
  var d=await api('GET','/api/eco/bootstrap');
  myPlayerUuid=d.myUuid||'';
  document.getElementById('playerInfo').textContent='已登录';
  var pn=d.myName||'';
  if(pn){
    document.getElementById('profileName').textContent=pn;
    var av=document.getElementById('profileAvatar');
    av.src='https://mc-heads.net/avatar/'+encodeURIComponent(pn)+'/64';
  }
  if(myPlayerUuid){
    var pid=document.getElementById('profileId');
    pid.textContent=(pn?'@'+pn+' · ':'')+myPlayerUuid.substr(0,13)+'…';
    pid.title=myPlayerUuid;
  }else if(!pn){
    document.getElementById('profileId').textContent='未登录';
  }
  applyFeatureGates(d.enabledFeatures||['market','storage','tax']);
  switchTab('terminal');
  if(enabledFeatureSet.has('bank')){
    setInterval(function(){
      if(document.getElementById('tab-my-banks').classList.contains('active'))loadMyBanks();
    },15000);
  }
  if(enabledFeatureSet.has('invites')){
    setInterval(function(){loadMyInvites();},30000);
    loadMyInvites();
  }
  loadBanNotice();
}
loadBootstrap();

async function loadBanNotice(){
  var d=await api('GET','/api/player/bans');
  var bans=(d.bans||[]);
  var el=document.getElementById('banNotice');
  if(!el)return;
  if(bans.length===0){el.style.display='none';return;}
  var typeLabel={'LISTING':'禁止市场上架','SELL_TO_OFFICIAL':'禁止官方兑换','ALL_MARKET':'禁止市场上架及官方兑换'};
  var html='🚫 <b>你当前有以下上架禁令：</b><ul style="margin:6px 0 0 16px;padding:0;">';
  bans.forEach(function(b){
    var exp=b.expires_at&&b.expires_at>0?'（到期：'+new Date(b.expires_at*1000).toLocaleString('zh-CN')+'）':'（永久）';
    html+='<li>'+(typeLabel[b.ban_type]||b.ban_type)+exp+(b.reason?' — '+escapeHtml(b.reason):'')+'</li>';
  });
  html+='</ul>';
  el.innerHTML=html;
  el.style.display='block';
}

// ====== BLINDBOX ======
function switchBbSub(sub,ev){
  document.querySelectorAll('.bb-sub').forEach(function(s){s.classList.remove('active');});
  document.querySelectorAll('#tab-blindbox .inline-tab').forEach(function(t){t.classList.remove('active');});
  document.getElementById('bb-sub-'+sub).classList.add('active');
  var bbButton=ev&&ev.currentTarget?ev.currentTarget:document.querySelector('#tab-blindbox .inline-tab[onclick*="'+sub+'"]');if(bbButton)bbButton.classList.add('active');
  if(sub==='history')loadBbMyPulls();
}
function bbRarityColor(r){return {COMMON:'§f',UNCOMMON:'§a',RARE:'§b',EPIC:'§d',LEGENDARY:'§6'}[r]||'§f';}
async function loadBbPools(){
  var d=await api('GET','/api/blindbox/pools');
  var el=document.getElementById('bbPoolCards');if(!el)return;
  var cards=[];
  (d.pools||[]).forEach(function(p){
    if(!p.enabled||p.limitedOnly)return;
    var pityText=p.pityRulesText&&p.pityRulesText!=='none'?p.pityRulesText:(p.pityMax>0?p.pityMax+' 抽必出 RARE+':'—');
    var openCall="openPullModal('"+escapeAttr(p.id)+"','"+escapeAttr(p.name)+"',"+Number(p.price)+","+Number(p.pityMax||0)+",'"+escapeAttr(p.pityRulesText||'')+"')";
    var fields=[['单抽价格',fmt(p.price)+' 金币'],['累计抽取',fmt(p.pullCount)+' 次 · '+p.lootCount+' 款'],['保底',pityText]];
    if(p.allowedIndustries)fields.push(['行业限定',p.allowedIndustries]);
    if(p.requiredLandZoneTypes)fields.push(['需地块类型',p.requiredLandZoneTypes]);
    if(p.description)fields.push(['说明',p.description]);
    cards.push(ksCard({
      title:p.name,badge:p.poolType||'PUBLIC',badgeCls:'cyan',fields:fields,onclick:openCall,
      actions:[
        {label:'🎲 单抽',onclick:openCall},
        {label:'🎰 10 连 ('+fmt(p.price*10)+')',cls:'btn-warn',onclick:"confirmPullTen('"+escapeAttr(p.id)+"','"+escapeAttr(p.name)+"',"+Number(p.price*10)+")"}
      ]
    }));
  });
  ksGrid('bbPoolCards',cards,'暂无可用盲盒池');
}
function escapeHtml(s){return s==null?'':String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}
function escapeAttr(s){return escapeHtml(s).replace(/'/g,'&#39;');}
async function openPullModal(poolId,name,price,pityMax,pityRulesText){
  var d=await api('GET','/api/blindbox/loot?poolId='+encodeURIComponent(poolId));
  var lootHtml='';
  if(d.loot&&d.loot.length>0){
    var totalWeight=0;
    d.loot.forEach(function(l){if((l.bundleSlot||0)===0)totalWeight+=l.weight||0;});
    var bundles={};
    d.loot.forEach(function(l){
      if(l.bundleId&&(l.bundleSlot||0)>0){
        if(!bundles[l.bundleId])bundles[l.bundleId]=[];
        bundles[l.bundleId].push(l);
      }
    });
    d.loot.forEach(function(l){
      if((l.bundleSlot||0)>0)return;
      var prob=totalWeight>0?Math.round(l.weight/totalWeight*1000)/10:0;
      var col=bbRarityColorToCss(l.rarity);
      var dispName=escapeHtml(l.displayName&&l.displayName.length>0?l.displayName:l.itemMaterial);
      var uid='lore_'+l.id.replace(/[^a-zA-Z0-9]/g,'_');
      lootHtml+='<div style="background:#07080C;border-radius:10px;padding:8px 10px;margin-bottom:6px;border-left:3px solid '+col+';">';
      lootHtml+='<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;">';
      lootHtml+='<span style="color:'+col+';font-size:10px;font-weight:bold;min-width:72px;">'+l.rarity+'</span>';
      lootHtml+='<span style="font-size:12px;flex:1;">'+dispName+'</span>';
      lootHtml+='<span style="color:#888;font-size:11px;">×'+l.quantity+'</span>';
      lootHtml+='<span style="color:#00E5FF;font-size:12px;font-weight:bold;min-width:52px;text-align:right;">'+prob+'%</span>';
      if(l.lore&&l.lore.length>0)lootHtml+='<button onclick="toggleLore(\''+uid+'\')" style="background:none;border:1px solid #444;color:#888;border-radius:3px;padding:1px 5px;font-size:10px;cursor:pointer;">lore▾</button>';
      lootHtml+='</div>';
      if(l.lore&&l.lore.length>0){
        lootHtml+='<div id="'+uid+'" style="display:none;margin-top:5px;padding:4px 8px;background:#060611;border-radius:4px;font-size:10px;color:#aaa;line-height:1.6;">';
        l.lore.forEach(function(line){lootHtml+=escapeHtml(line)+'<br>';});
        lootHtml+='</div>';
      }
      if(l.bundleId&&bundles[l.bundleId]&&bundles[l.bundleId].length>0){
        lootHtml+='<div style="margin-top:5px;padding-left:8px;border-left:2px solid #333;font-size:10px;color:#888;">同时获得：';
        bundles[l.bundleId].forEach(function(b){
          var bn=escapeHtml(b.displayName&&b.displayName.length>0?b.displayName:b.itemMaterial);
          lootHtml+='<span style="color:#ccc;margin-right:4px;">'+bn+'×'+b.quantity+'</span>';
        });
        lootHtml+='</div>';
      }
      lootHtml+='</div>';
    });
  }
  var body='<div style="margin-bottom:8px;">价格: <b style="color:#00E5FF;">'+fmt(price)+'</b> 金币 / 抽</div>';
  var ruleText=pityRulesText&&pityRulesText!=='none'?pityRulesText:(pityMax>0?('连续 '+pityMax+' 抽未出 RARE+ 必出'):'');
  if(ruleText)body+='<div style="margin-bottom:8px;font-size:11px;color:#888;">保底: '+escapeHtml(ruleText)+'</div>';
  body+='<div style="margin-bottom:10px;max-height:340px;overflow-y:auto;"><h4 style="font-size:12px;color:#00E5FF;margin:8px 0 6px;">奖池预览</h4>'+lootHtml+'</div>';
  body+='<div style="display:flex;gap:8px;"><button class="btn" onclick="confirmPull(\''+poolId+'\')">🎲 确认抽取</button><button class="btn btn-sm" onclick="closeModal()">取消</button></div>';
  body+='<div id="pullResult" style="margin-top:12px;"></div>';
  showModal('🎁 '+name,body);
}
function toggleLore(uid){var el=document.getElementById(uid);if(el)el.style.display=el.style.display==='none'?'block':'none';}
function bbRarityColorToCss(r){return {COMMON:'#aaa',UNCOMMON:'#4caf50',RARE:'#00E5FF',EPIC:'#ce93d8',LEGENDARY:'#ff9800'}[r]||'#aaa';}
async function confirmPull(poolId){
  var d=await api('POST','/api/blindbox/pull',{poolId:poolId});
  var box=document.getElementById('pullResult');
  if(d.success){
    await new Promise(function(res){ksGachaFx(d.rarity,res);});
    var rarColor=bbRarityColorToCss(d.rarity);
    box.innerHTML='<div style="padding:12px;background:#07080C;border-radius:10px;border:1px solid '+rarColor+';">'+
      '<div style="font-size:13px;color:#4caf50;">✓ 抽取成功</div>'+
      '<div style="margin-top:6px;font-size:14px;">物品: <b>'+escapeHtml(d.itemDisplayName&&d.itemDisplayName.length>0?d.itemDisplayName:d.itemMaterial)+'</b></div>'+
      '<div style="font-size:12px;color:'+rarColor+';">稀有度: '+escapeHtml(d.rarity)+'</div>'+
      '<div style="font-size:11px;color:#888;">数量: '+d.quantity+'</div>'+
      (d.pityTriggered>0?'<div style="font-size:11px;color:#ff9800;margin-top:4px;">🎯 触发保底（连续 '+d.pityTriggered+' 抽）</div>':'')+
      (d.storedToBox?'<div style="font-size:11px;color:#f44;margin-top:4px;">背包已满，已存入暂存箱</div>':'')+
      '</div>';
    toast('抽取成功: '+(d.itemDisplayName&&d.itemDisplayName.length>0?d.itemDisplayName:d.itemMaterial),'ok');
    loadBbPools();
    if(document.getElementById('bb-sub-history').classList.contains('active'))loadBbMyPulls();
  }else{
    box.innerHTML='<div style="padding:12px;background:#4a1111;border-radius:6px;color:#ffc9c9;">✗ '+escapeHtml(d.error||'抽取失败')+'</div>';
    toast(d.error||'抽取失败','err');
  }
}
async function loadBbMyPulls(){
  var d=await api('GET','/api/blindbox/my-pulls');
  var el=document.getElementById('bbHistoryBody');if(!el)return;
  if(!d.pulls||d.pulls.length===0){el.innerHTML='<tr><td colspan="4" style="color:#666;">暂无记录</td></tr>';return;}
  var t='';
  d.pulls.forEach(function(r){
    t+='<tr><td>'+new Date(r.pulledAt*1000).toLocaleString('zh-CN')+'</td><td>'+escapeHtml(r.poolName||r.poolId)+'</td><td>'+escapeHtml(r.itemMaterial)+'</td>';
    t+='<td style="color:'+bbRarityColorToCss(r.rarity)+';">'+r.rarity+'</td></tr>';
  });
  el.innerHTML=t;
}
async function confirmPullTen(poolId,name,totalPrice){
  if(!confirm('确认 10 连抽 '+name+'？\n共需 '+totalPrice+' 金币。'))return;
  var d=await api('POST','/api/blindbox/pull-ten',{poolId:poolId});
  if(!d.allSuccess){toast(d.results&&d.results[0]&&d.results[0].error||'10连抽失败','err');return;}
  var _ord=['COMMON','UNCOMMON','RARE','EPIC','LEGENDARY'],_best='COMMON';
  (d.results||[]).forEach(function(r){if(_ord.indexOf(r.rarity)>_ord.indexOf(_best))_best=r.rarity;});
  await new Promise(function(res){ksGachaFx(_best,res);});
  var rarCnt=d.rareCount||0;
  var body='<div style="margin-bottom:8px;font-size:13px;color:#4caf50;">✓ 10 连抽完成，获得 RARE+ 共 '+rarCnt+' 件</div>';
  body+='<div class="table-wrap" style="max-height:340px;"><table><thead><tr><th>#</th><th>物品</th><th>稀有度</th><th>是否触发保底</th></tr></thead><tbody>';
  d.results.forEach(function(r,i){
    var pityStr=r.pityTriggered>0?'<span style="color:#ff9800;">是 ('+r.pityTriggered+' 抽)</span>':'—';
    body+='<tr><td>'+(i+1)+'</td><td>'+escapeHtml(r.itemDisplayName||r.itemMaterial)+'</td>';
    body+='<td style="color:'+bbRarityColorToCss(r.rarity)+';">'+r.rarity+'</td><td>'+pityStr+'</td></tr>';
  });
  body+='</tbody></table></div>';
  body+='<div style="margin-top:10px;"><button class="btn btn-sm" onclick="closeModal()">关闭</button></div>';
  showModal('🎰 '+name+' 10 连抽结果',body);
  toast('10 连抽完成','ok');
  loadBbPools();
}

// ====== ENTERPRISE BLINDBOX ======
async function loadEntBbEnts(){
  var d=await api('GET','/api/my-enterprises');
  myEnterprises=d.enterprises||[];
  var opts='<option value="">--选择企业--</option>';
  (d.enterprises||[]).forEach(function(e){
    opts+='<option value="'+e.id+'">'+escapeHtml(e.name)+' ('+e.id+')</option>';
  });
  var sel1=document.getElementById('entBbPoolEntId');
  if(sel1)sel1.innerHTML=opts;
  loadEntBbPools();
}
async function loadEntBbPools(){
  var entId=document.getElementById('entBbPoolEntId').value;
  document.getElementById('entBbIndustry').textContent='—';
  document.getElementById('entBbCorpBal').textContent='—';
  if(!entId){document.getElementById('entBbPoolCards').innerHTML='<div style="color:#888;padding:16px;">请先选企业</div>';return;}
  var d=await api('GET','/api/enterprise/blindbox/pools?enterpriseId='+encodeURIComponent(entId));
  document.getElementById('entBbIndustry').textContent=d.industry||'—';
  document.getElementById('entBbCorpBal').textContent=fmt(0); // 不直接读，admin 列表里有
  if(d.error){toast(d.error,'err');return;}
  var cards=[];
  (d.pools||[]).forEach(function(p){
    if(!p.enabled||p.limitedOnly)return;
    var enterprise=(myEnterprises||[]).find(function(e){return String(e.id)===String(entId);})||{};
    var currentLevel=Number(d.enterpriseLevel||enterprise.level||1),requiredLevel=Number(p.minEnterpriseLevel||1),locked=currentLevel<requiredLevel;
    var fields=[['企业等级','Lv.'+currentLevel+' / 要求 Lv.'+requiredLevel],['单抽价格',fmt(p.price)+' 金币（公户）'],['累计抽取',fmt(p.pullCount)+' 次 · '+p.lootCount+' 款'],['白名单',p.allowedCategories||'不限']];
    if(p.allowedIndustries)fields.push(['行业限定',p.allowedIndustries]);
    if(p.requiredLandZoneTypes)fields.push(['需地块类型',p.requiredLandZoneTypes]);
    if(p.description)fields.push(['说明',p.description]);
    cards.push(ksCard({
      title:p.name,badge:(p.poolType||'ENTERPRISE')+(p.ownerType?' · '+p.ownerType:''),fields:fields,
      onclick:"openEntBbDetail('"+escapeAttr(entId)+"','"+escapeAttr(p.id)+"','"+escapeAttr(p.name)+"',"+Number(p.price)+")",
      actions:[{label:locked?'等级不足':'🎲 公户抽 1 次',cls:locked?'btn-disabled':'',onclick:locked?'':"confirmEntBbPull('"+escapeAttr(entId)+"','"+escapeAttr(p.id)+"')"}]
    }));
  });
  ksGrid('entBbPoolCards',cards,'该企业无适用盲盒池');
}
async function openEntBbDetail(entId,poolId,name,price){
  var d=await api('GET','/api/blindbox/loot?poolId='+encodeURIComponent(poolId));
  var rows=(d.loot||[]).filter(function(l){return (l.bundleSlot||0)===0;}).map(function(l){
    return {name:(l.displayName&&l.displayName.length>0?l.displayName:l.itemMaterial)+' ×'+l.quantity+' ['+l.rarity+']',weight:l.weight||0};
  });
  var body='<div class="entity-record"><div class="entity-field"><span>单抽价格</span><b>'+fmt(price)+' 金币（公户扣款）</b></div></div>';
  body+='<div class="card" style="margin-top:12px;"><h3>掉落概率</h3>'+ksOddsBars(rows)+'</div>';
  body+='<div style="margin-top:12px;"><button class="btn" onclick="confirmEntBbPull(\''+escapeAttr(entId)+'\',\''+escapeAttr(poolId)+'\')">🎲 公户抽 1 次</button></div>';
  ksEntityOpen('🎪 '+name,body);
}
async function confirmEntBbPull(entId,poolId){
  if(!confirm('确认从企业公户扣款抽 1 次？'))return;
  var d=await api('POST','/api/enterprise/blindbox/pull',{enterpriseId:entId,poolId:poolId});
  if(d.success){
    await new Promise(function(res){ksGachaFx(d.rarity,res);});
    toast('抽到: '+(d.itemDisplayName||d.itemMaterial)+' ('+d.rarity+')','ok');
    document.getElementById('entBbCorpBal').textContent=fmt(d.corporateBalance);
  }else{
    toast(d.error||'抽取失败','err');
  }
}
// ====== REAL ESTATE ======
function switchReSub(sub,ev){
  document.querySelectorAll('.re-sub').forEach(function(s){s.classList.remove('active');});
  document.querySelectorAll('#tab-realestate .inline-tab').forEach(function(t){t.classList.remove('active');});
  document.getElementById('re-sub-'+sub).classList.add('active');
  var reButton=ev&&ev.currentTarget?ev.currentTarget:document.querySelector('#tab-realestate .inline-tab[onclick*="'+sub+'"]');if(reButton)reButton.classList.add('active');
  if(sub==='plots')loadMyRe();
  if(sub==='browse'){loadReBrowse();requestAnimationFrame(function(){if(reBrowseMap)reBrowseMap.resize();});}
  if(sub==='houses')loadHouseMarket();
  if(sub==='perks')loadLandPerks();
}
async function loadLandPerks(){
  var d=await api('GET','/api/realestate/my-land-perks');
  var c=d.config||{};
  var agriMode=String(c.agri_scope_mode!=null?c.agri_scope_mode:0)==='1';
  var indMode=String(c.industry_scope_mode!=null?c.industry_scope_mode:0)==='1';
  document.getElementById('landPerkSummary').innerHTML=
    '你名下（含所属企业）农业地块 <b>'+(d.agriculturalPlotCount||0)+'</b> 块，工业地块 <b>'+(d.industrialPlotCount||0)+'</b> 块。';
  var lines=[];
  lines.push('🌾 <b>农业地块福利</b>（当前'+(agriMode?'<span style="color:#7c7">拥有即永久生效</span>：只要拥有农业地，随时随地享受加成':'<span style="color:#e94">区域内生效</span>：需站在自己的农业地块范围内才能享受')+'）：');
  lines.push('　· 作物成长加速强度 '+(c.agri_growth_boost_chance||0)+'　· 收获额外产出几率 '+pct(c.agri_harvest_yield_bonus_chance)+'　· 官方收购溢价 '+pct(c.agri_official_premium_pct));
  lines.push('🏭 <b>工业地块福利</b>（当前'+(indMode?'<span style="color:#7c7">拥有即永久生效</span>：只要拥有工业地，随时随地享受加成':'<span style="color:#e94">区域内生效</span>：需站在自己的工业地块范围内才能享受')+'）：');
  lines.push('　· 熔炼加速 '+pct(c.industry_furnace_speed_pct)+'　· 熔炼额外产出几率 '+pct(c.industry_furnace_bonus_output_chance)+'　· 招投标信誉加成 '+pct(c.industry_bidding_reputation_bonus_pct)+'（企业拥有工业地即生效，与是否站在地里无关）');
  lines.push('还有专属农资/原料商店或盲盒池，需要拥有对应地块才能访问，具体以游戏内 /eco gui 或盲盒界面为准。');
  document.getElementById('landPerkDetails').innerHTML=lines.join('<br>');
}
async function loadHouseMarket(){
  var d=await api('GET','/api/realestate/houses-for-sale');
  var t='';(d.houses||[]).forEach(function(h){
    var range=(h.x1!=null)?('['+h.x1+','+h.y1+','+h.z1+']-['+h.x2+','+h.y2+','+h.z2+']'):'-';
    t+='<tr><td>'+escapeHtml(h.name||'-')+'</td><td style="font-size:10px;">'+escapeHtml(h.houseId)+'</td><td>'+escapeHtml(h.sellerName)+'</td>';
    t+='<td>'+escapeHtml(h.world||'-')+'</td><td style="font-size:11px;">'+range+'</td>';
    t+='<td>'+fmt(h.price)+'</td>';
    t+='<td><button class="btn btn-sm" onclick="openHouseVoxelViewer(\''+escapeAttr(h.houseId)+'\')">👀 查看3D渲染图</button></td></tr>';
  });
  document.getElementById('houseMarketBody').innerHTML=t||'<tr><td colspan="7" style="color:#666;">目前没有在售的商品房</td></tr>';
}

// ====== 商品房 3D 体素查看器（three.js，按需从 CDN 加载，方块贴图从 Minecraft 资源 CDN 加载） ======
var ksVoxelThreeLoading=null, ksVoxelRenderer=null, ksVoxelAnimHandle=null, ksZoneSceneState=null;
var ksVoxelAmbientLight=null, ksVoxelDirLight=null, ksVoxelTextureCache={};
var KS_VOXEL_TEX_BASE='https://cdn.jsdelivr.net/gh/InventivetalentDev/minecraft-assets@1.21/assets/minecraft/textures/block/';
// 光照预设：环境光 + 平行光（颜色/强度/方向），用于让玩家按喜好切换观感（默认日光，避免过暗显得像"鬼屋"）
var KS_VOXEL_LIGHT_PRESETS={
  day:{bg:0x1c2230,ambient:0xffffff,ambientI:0.85,dir:0xfff6e6,dirI:0.95,dirPos:[1,1.6,0.9]},
  sunset:{bg:0x241a1c,ambient:0xffd9b0,ambientI:0.55,dir:0xff9a4d,dirI:0.95,dirPos:[1,0.6,0.6]},
  night:{bg:0x0a0c16,ambient:0x44557a,ambientI:0.45,dir:0xaabbff,dirI:0.45,dirPos:[0.6,1.4,0.4]},
  studio:{bg:0x202020,ambient:0xffffff,ambientI:1.1,dir:0xffffff,dirI:0.35,dirPos:[1,1.2,1]}
};
function loadThreeJs(){
  if(window.THREE)return Promise.resolve();
  if(ksVoxelThreeLoading)return ksVoxelThreeLoading;
  ksVoxelThreeLoading=import('https://cdn.jsdelivr.net/npm/three@0.158.0/build/three.module.js')
    .then(function(module){window.THREE=module;})
    .catch(function(){throw new Error('three.js 加载失败（CDN 不可达）');});
  return ksVoxelThreeLoading;
}
function closeHouseVoxelViewer(){
  var modal=document.getElementById('houseVoxelModal');
  modal.style.display='none';modal.classList.remove('zone-mode');document.body.style.overflow='';
  if(ksZoneSceneState&&ksZoneSceneState.cleanup)ksZoneSceneState.cleanup();
  if(ksVoxelAnimHandle){cancelAnimationFrame(ksVoxelAnimHandle);ksVoxelAnimHandle=null;}
  if(ksVoxelRenderer){ksVoxelRenderer.dispose();ksVoxelRenderer=null;}
  ksVoxelAmbientLight=null; ksVoxelDirLight=null;
  document.getElementById('houseVoxelCanvasWrap').innerHTML='';
  ksZoneSceneState=null;
}
function applyVoxelLightPreset(name){
  var p=KS_VOXEL_LIGHT_PRESETS[name]||KS_VOXEL_LIGHT_PRESETS.day;
  var THREE=window.THREE;
  if(!THREE||!ksVoxelAmbientLight||!ksVoxelDirLight)return;
  ksVoxelAmbientLight.color.setHex(p.ambient); ksVoxelAmbientLight.intensity=p.ambientI;
  ksVoxelDirLight.color.setHex(p.dir); ksVoxelDirLight.intensity=p.dirI;
  ksVoxelDirLight.position.set(p.dirPos[0],p.dirPos[1],p.dirPos[2]);
  if(ksVoxelDirLight.parent&&ksVoxelDirLight.parent.background)ksVoxelDirLight.parent.background.setHex(p.bg);
}
async function openHouseVoxelViewer(houseId){
  document.getElementById('houseVoxelModal').classList.remove('zone-mode');
  document.getElementById('houseVoxelCloseBtn').textContent='✖ 关闭';
  var modalTitle=document.querySelector('#houseVoxelModal h3');if(modalTitle)modalTitle.textContent='House 3D preview';
  document.getElementById('houseVoxelModal').style.display='flex';
  document.getElementById('houseVoxelStatus').textContent='加载体素数据中...';
  document.getElementById('houseVoxelSize').textContent='';
  document.getElementById('houseVoxelCanvasWrap').innerHTML='';
  try{
    await loadThreeJs();
    var d=await fetchHouseVoxelModel('/api/realestate/house/voxels?houseId='+encodeURIComponent(houseId));
    if(!d||!d.blocks){document.getElementById('houseVoxelStatus').textContent='加载失败：房屋不存在或数据异常。';return;}
    if(d.truncated){document.getElementById('houseVoxelStatus').textContent='⚠ 房屋体积过大（超过渲染上限），无法生成3D预览。';return;}
    if(d.blocks.length===0){document.getElementById('houseVoxelStatus').textContent='该房屋范围内没有方块（可能是空地）。';return;}
    var sizeX=d.x2-d.x1+1, sizeY=d.y2-d.y1+1, sizeZ=d.z2-d.z1+1;
    document.getElementById('houseVoxelStatus').textContent='方块数: '+d.blocks.length+' ｜ 贴图加载中...（拖拽旋转 / 滚轮缩放）';
    document.getElementById('houseVoxelSize').textContent='横坐标(X)跨度: '+sizeX+' ｜ 纵坐标(Z)跨度: '+sizeZ+' ｜ 高度(Y): '+sizeY;
    await renderVoxelScene(d);
    document.getElementById('houseVoxelStatus').textContent='方块数: '+d.blocks.length+'（拖拽旋转 / 滚轮缩放）';
  }catch(e){
    document.getElementById('houseVoxelStatus').textContent='加载出错: '+e.message;
  }
}
// 已知的多面贴图方块：top/side/bottom 文件名不同于材质名本身（其余方块默认用 材质名小写.png 当全部六面）
function prepareRegionVoxelSurface(data){
  var verticalSize=data.y2-data.y1+1;
  var floor=Math.max(0,verticalSize-30);
  var blocks=(data.blocks||[]).filter(function(b){return b.y>=floor;}).map(function(b){return Object.assign({},b,{y:b.y-floor});});
  return Object.assign({},data,{y1:data.y1+floor,blocks:blocks});
}
var KS_REGION_3D_MAX_SPAN=384;
async function openRegionVoxelViewer(selectionOverride){
  var selection=selectionOverride||lastMap3dSelect;
  if(!selection){toast('请先在地图上框选一个区域','err');return;}
  var minX=Math.min(Number(selection.x1),Number(selection.x2)),maxX=Math.max(Number(selection.x1),Number(selection.x2));
  var minZ=Math.min(Number(selection.z1),Number(selection.z2)),maxZ=Math.max(Number(selection.z1),Number(selection.z2));
  var width=maxX-minX+1,depth=maxZ-minZ+1;
  if(width>KS_REGION_3D_MAX_SPAN||depth>KS_REGION_3D_MAX_SPAN){toast('当前 '+width+' × '+depth+'，请缩小到 '+KS_REGION_3D_MAX_SPAN+' × '+KS_REGION_3D_MAX_SPAN+' 内','err');return;}
  closeHouseVoxelViewer();
  var modal=document.getElementById('houseVoxelModal');
  modal.classList.add('zone-mode');modal.style.display='flex';document.body.style.overflow='hidden';
  document.getElementById('houseVoxelCloseBtn').textContent='← 返回 2D 地图';
  var modalTitle=document.querySelector('#houseVoxelModal h3');if(modalTitle)modalTitle.textContent='所选区域 3D 预览';
  var tileSize=24,tiles=[];
  for(var x=minX;x<=maxX;x+=tileSize)for(var z=minZ;z<=maxZ;z+=tileSize){
    tiles.push({x1:x,z1:z,x2:Math.min(maxX,x+tileSize-1),z2:Math.min(maxZ,z+tileSize-1)});
  }
  var centerX=(minX+maxX)/2,centerZ=(minZ+maxZ)/2;
  tiles.sort(function(a,b){
    var ad=Math.pow((a.x1+a.x2)/2-centerX,2)+Math.pow((a.z1+a.z2)/2-centerZ,2);
    var bd=Math.pow((b.x1+b.x2)/2-centerX,2)+Math.pow((b.z1+b.z2)/2-centerZ,2);
    return ad-bd;
  });
  document.getElementById('houseVoxelStatus').textContent='正在建立所选区域场景...';
  document.getElementById('houseVoxelSize').textContent='区域 '+width+' × '+depth+' · '+tiles.length+' 个渐进分片';
  document.getElementById('houseVoxelCanvasWrap').innerHTML='';
  try{
    await loadThreeJs();
    var state=initZoneVoxelScene({zone:selection,minX:minX,minZ:minZ,maxX:maxX,maxZ:maxZ});
    var loaded=0,blocks=0,failed=0,reasons={};
    for(var i=0;i<tiles.length;i++){
      if(ksZoneSceneState!==state)break;
      var tile=tiles[i];
      document.getElementById('houseVoxelStatus').textContent='区域加载 '+(i+1)+' / '+tiles.length+' · 已渲染 '+blocks+' 方块';
      try{
        var path='/api/realestate/region/voxels?world='+encodeURIComponent(selection.world||reMapState.world)
          +'&x1='+tile.x1+'&z1='+tile.z1+'&x2='+tile.x2+'&z2='+tile.z2;
        var data=await api('GET',path);
        if(data&&data.blocks&&data.blocks.length&&!data.truncated){
          data=prepareRegionVoxelSurface(data);
          await addZoneVoxelChunk(state,data,tile);
          blocks+=data.blocks.length;loaded++;
        }else{
          failed++;
          var reason=(data&&data.reason)||((data&&data.blocks&&data.blocks.length===0)?'empty':'unknown');
          reasons[reason]=(reasons[reason]||0)+1;
        }
      }catch(tileError){failed++;reasons.request_failed=(reasons.request_failed||0)+1;}
      await new Promise(function(resolve){requestAnimationFrame(resolve);});
    }
    if(ksZoneSceneState===state){
      var reasonLabels={volume_limit:'扫描体积超限',block_limit:'方块密度超限',height_limit:'高度跨度超限',world_unavailable:'世界不可用',read_failed:'读取失败',request_failed:'请求失败',empty:'空白分片',unknown:'未知错误'};
      var detail=Object.keys(reasons).map(function(key){return (reasonLabels[key]||key)+' '+reasons[key];}).join('、');
      document.getElementById('houseVoxelStatus').textContent=loaded
        ? '区域已加载 · '+loaded+'/'+tiles.length+' 分片 · '+blocks+' 方块'+(failed?' · 未加载：'+detail:'')
        : '区域没有可显示内容'+(detail?' · '+detail:'');
    }
  }catch(error){document.getElementById('houseVoxelStatus').textContent='区域 3D 加载失败: '+error.message;}
}

function openZoneVoxelViewerById(zoneId){
  var zone=reMapState.zones.find(function(item){return String(item.id)===String(zoneId);});
  if(zone)openZoneVoxelViewer(zone);
}

async function openZoneVoxelViewer(zone){
  if(!zone)return;
  closeHouseVoxelViewer();
  var modal=document.getElementById('houseVoxelModal');
  modal.classList.add('zone-mode');modal.style.display='flex';document.body.style.overflow='hidden';
  document.getElementById('houseVoxelCloseBtn').textContent='← 返回 2D 地图';
  var title=document.querySelector('#houseVoxelModal h3');
  if(title)title.textContent=(zone.name||zone.id)+' · 城区 3D 沙盘';
  var minX=Math.min(Number(zone.x1),Number(zone.x2)),maxX=Math.max(Number(zone.x1),Number(zone.x2));
  var minZ=Math.min(Number(zone.z1),Number(zone.z2)),maxZ=Math.max(Number(zone.z1),Number(zone.z2));
  document.getElementById('houseVoxelStatus').textContent='正在读取城区楼盘清单...';
  document.getElementById('houseVoxelSize').textContent='区域 '+(maxX-minX+1)+' × '+(maxZ-minZ+1)+' · 道路与地块先行装配';
  try{
    var manifest=await api('GET','/api/realestate/city/manifest?zoneId='+encodeURIComponent(zone.id));
    await loadThreeJs();
    var state=initZoneVoxelScene({zone:zone,minX:minX,minZ:minZ,maxX:maxX,maxZ:maxZ});
    state.yBase=ksCityBaseY(manifest.buildings||[]);
    addCitySandboxSkeleton(state,manifest);
    var buildings=manifest.buildings||[],loaded=0,blocks=0,failed=0,next=0;
    document.getElementById('houseVoxelSize').textContent='城区 '+(maxX-minX+1)+' × '+(maxZ-minZ+1)+' · '+(manifest.plots||[]).length+' 地块 · '+buildings.length+' 栋楼';
    async function worker(){
      while(next<buildings.length&&ksZoneSceneState===state){
        var building=buildings[next++];
        document.getElementById('houseVoxelStatus').textContent='楼栋异步装配 '+(loaded+failed)+' / '+buildings.length+' · 已渲染 '+blocks+' 外观方块';
        try{
          var data=await fetchHouseVoxelModel(building.modelUrl||('/api/realestate/house/voxels?houseId='+encodeURIComponent(building.id)));
          if(data&&data.status==='READY'&&data.blocks&&data.blocks.length&&!data.truncated){
            await addCityBuildingModel(state,data,building);blocks+=data.blocks.length;loaded++;
          }else failed++;
        }catch(buildingError){failed++;}
        await new Promise(function(resolve){requestAnimationFrame(resolve);});
      }
    }
    await Promise.all(Array.from({length:Math.min(3,Math.max(1,buildings.length))},worker));
    if(ksZoneSceneState===state){
      document.getElementById('houseVoxelStatus').textContent=buildings.length
        ? '售楼沙盘已就绪 · '+loaded+'/'+buildings.length+' 栋 · '+blocks+' 外观方块'+(failed?' · '+failed+' 栋暂不可用':'')+' · 点击楼栋查看房源'
        : '城区骨架已就绪 · 暂无已登记楼栋';
    }
  }catch(error){
    document.getElementById('houseVoxelStatus').textContent='城区 3D 加载失败: '+error.message;
  }
}

function ksCityBaseY(buildings){
  if(!buildings.length)return 0;
  return buildings.reduce(function(v,b){return Math.min(v,Number(b.y1)||0);},Number(buildings[0].y1)||0);
}
async function fetchHouseVoxelModel(url){
  for(var attempt=0;attempt<100;attempt++){
    var data=await api('GET',url);
    if(!data||data.status!=='PREPARING')return data;
    await new Promise(function(resolve){setTimeout(resolve,Math.max(100,Number(data.retryAfterMs)||250));});
  }
  throw new Error('楼栋预渲染超时');
}

function addCitySandboxSkeleton(state,manifest){
  var THREE=window.THREE,b=state.bounds,sizeX=b.maxX-b.minX+1,sizeZ=b.maxZ-b.minZ+1;
  var base=new THREE.Mesh(new THREE.BoxGeometry(sizeX+12,1.2,sizeZ+12),new THREE.MeshStandardMaterial({color:0x26313A,roughness:.88,metalness:.05}));
  base.position.set(sizeX/2,-.7,sizeZ/2);state.scene.add(base);
  var lawn=new THREE.Mesh(new THREE.BoxGeometry(sizeX,0.35,sizeZ),new THREE.MeshStandardMaterial({color:0x466B42,roughness:1}));
  lawn.position.set(sizeX/2,.02,sizeZ/2);state.scene.add(lawn);
  var roadMat=new THREE.MeshStandardMaterial({color:0x24272B,roughness:.92}),lineMat=new THREE.MeshBasicMaterial({color:0xE8D89A});
  [[sizeX+8,5,sizeX/2,-2.7],[sizeX+8,5,sizeX/2,sizeZ+2.7],[5,sizeZ+8,-2.7,sizeZ/2],[5,sizeZ+8,sizeX+2.7,sizeZ/2]].forEach(function(r){
    var road=new THREE.Mesh(new THREE.BoxGeometry(r[0],.3,r[1]),roadMat);road.position.set(r[2],.28,r[3]);state.scene.add(road);
  });
  [[sizeX+8,.15,sizeX/2,-2.7],[sizeX+8,.15,sizeX/2,sizeZ+2.7],[.15,sizeZ+8,-2.7,sizeZ/2],[.15,sizeZ+8,sizeX+2.7,sizeZ/2]].forEach(function(r){
    var line=new THREE.Mesh(new THREE.BoxGeometry(r[0],.03,r[1]),lineMat);line.position.set(r[2],.47,r[3]);state.scene.add(line);
  });
  if((manifest.buildings||[]).length>=3){
    [[5,sizeZ,sizeX/2,sizeZ/2],[sizeX,5,sizeX/2,sizeZ/2]].forEach(function(r){var road=new THREE.Mesh(new THREE.BoxGeometry(r[0],.32,r[1]),roadMat);road.position.set(r[2],.29,r[3]);state.scene.add(road);});
    [[.15,sizeZ,sizeX/2,sizeZ/2],[sizeX,.15,sizeX/2,sizeZ/2]].forEach(function(r){var line=new THREE.Mesh(new THREE.BoxGeometry(r[0],.035,r[1]),lineMat);line.position.set(r[2],.48,r[3]);state.scene.add(line);});
  }
  (manifest.plots||[]).forEach(function(plot){
    var x1=Math.min(Number(plot.x1),Number(plot.x2))-b.minX,z1=Math.min(Number(plot.z1),Number(plot.z2))-b.minZ;
    var w=Math.abs(Number(plot.x2)-Number(plot.x1))+1,d=Math.abs(Number(plot.z2)-Number(plot.z1))+1;
    var color=plot.ownerId?0xD3C69A:0x8AA5AD;
    var pad=new THREE.Mesh(new THREE.BoxGeometry(Math.max(1,w-.8),.18,Math.max(1,d-.8)),new THREE.MeshStandardMaterial({color:color,transparent:true,opacity:.34,roughness:.9}));
    pad.position.set(x1+w/2,.31,z1+d/2);state.scene.add(pad);
    var edges=new THREE.LineSegments(new THREE.EdgesGeometry(pad.geometry),new THREE.LineBasicMaterial({color:plot.ownerId?0xF4D675:0x91DAE8,transparent:true,opacity:.8}));
    edges.position.copy(pad.position);state.scene.add(edges);
  });
  (manifest.buildings||[]).forEach(function(building){
    var w=Number(building.x2)-Number(building.x1)+1,h=Number(building.y2)-Number(building.y1)+1,d=Number(building.z2)-Number(building.z1)+1;
    var marker=ksCityMarkerStyle(building.showcaseMarker),color=marker.color;
    var holder=new THREE.Group();holder.userData.building=building;
    var mass=new THREE.Mesh(new THREE.BoxGeometry(w,Math.max(2,h),d),new THREE.MeshStandardMaterial({color:color,transparent:true,opacity:.16,wireframe:true}));
    mass.position.set(w/2,Math.max(2,h)/2,d/2);mass.userData.building=building;holder.add(mass);
    holder.position.set(Number(building.x1)-b.minX,Number(building.y1)-state.yBase,Number(building.z1)-b.minZ);
    state.scene.add(holder);state.placeholders[String(building.id)]=holder;state.interactive.push(mass);
    var pin=new THREE.Group(),pole=new THREE.Mesh(new THREE.CylinderGeometry(.11,.11,Math.max(2,h*.28),8),new THREE.MeshBasicMaterial({color:color}));
    pole.position.y=Math.max(2,h*.28)/2;pin.add(pole);var diamond=new THREE.Mesh(new THREE.OctahedronGeometry(.65),new THREE.MeshStandardMaterial({color:color,emissive:color,emissiveIntensity:.45}));diamond.position.y=Math.max(2,h*.28)+.4;pin.add(diamond);
    pin.position.set(Number(building.x1)-b.minX+w/2,h+1,Number(building.z1)-b.minZ+d/2);state.scene.add(pin);
  });
  if((manifest.buildings||[]).length){
    var index=document.createElement('div');index.className='ks-city-building-index';
    index.style.cssText='position:absolute;left:18px;bottom:18px;display:grid;gap:6px;max-width:min(330px,calc(100% - 36px));padding:10px;border:1px solid rgba(100,216,255,.3);border-radius:9px;background:rgba(5,15,24,.88);z-index:3;backdrop-filter:blur(8px)';
    var label=document.createElement('div');label.textContent='楼栋目录 · BUILDINGS';label.style.cssText='color:#7895a9;font:9px var(--mono,monospace);letter-spacing:.12em';index.appendChild(label);
    (manifest.buildings||[]).forEach(function(building){var marker=ksCityMarkerStyle(building.showcaseMarker),button=document.createElement('button');button.type='button';button.className='btn btn-sm';button.style.cssText='text-align:left;justify-content:flex-start;border-left:3px solid #'+marker.hex;button.innerHTML='<span style="color:#'+marker.hex+'">◆</span>&nbsp; '+escapeHtml(building.name||building.id);button.onclick=function(){if(state.focusBuilding)state.focusBuilding(building);showCityBuildingCard(document.getElementById('houseVoxelCanvasWrap'),building);};index.appendChild(button);});
    document.getElementById('houseVoxelCanvasWrap').appendChild(index);
  }
}

function ksCityMarkerStyle(name){
  var styles={CYAN:{color:0x64D8FF,hex:'64D8FF',label:'青色'},MAGENTA:{color:0xFF5FC8,hex:'FF5FC8',label:'洋红'},AMBER:{color:0xF1C75B,hex:'F1C75B',label:'金色'},GREEN:{color:0x67D99A,hex:'67D99A',label:'绿色'},BLUE:{color:0x6F8FFF,hex:'6F8FFF',label:'蓝色'},RED:{color:0xFF6B72,hex:'FF6B72',label:'红色'}};
  return styles[String(name||'CYAN').toUpperCase()]||styles.CYAN;
}

async function addCityBuildingModel(state,data,building){
  if(ksZoneSceneState!==state)return;
  var old=state.placeholders[String(building.id)];if(old){state.interactive=state.interactive.filter(function(object){return object.parent!==old&&object!==old;});state.scene.remove(old);delete state.placeholders[String(building.id)];}
  var group=new window.THREE.Group();group.userData.building=building;state.scene.add(group);state.buildingGroups[String(building.id)]=group;
  await addZoneVoxelChunk(state,data,{x1:Number(building.x1),z1:Number(building.z1)},group,building);
}

function initZoneVoxelScene(bounds){
  var THREE=window.THREE,wrap=document.getElementById('houseVoxelCanvasWrap');
  wrap.style.position='relative';
  var width=wrap.clientWidth||1280,height=wrap.clientHeight||720;
  var sizeX=bounds.maxX-bounds.minX+1,sizeZ=bounds.maxZ-bounds.minZ+1,maxDim=Math.max(sizeX,sizeZ,36);
  var scene=new THREE.Scene();scene.background=new THREE.Color(0x050A10);scene.fog=new THREE.Fog(0x050A10,maxDim*1.15,maxDim*4);
  // 售楼沙盘使用正交等轴视角，避免近处楼栋被广角透视夸大、远处楼栋被压扁。
  var viewSize=maxDim*1.35,aspect=width/height;
  var camera=new THREE.OrthographicCamera(-viewSize*aspect/2,viewSize*aspect/2,viewSize/2,-viewSize/2,0.1,maxDim*10);
  var renderer=new THREE.WebGLRenderer({antialias:true,powerPreference:'high-performance'});
  renderer.setPixelRatio(Math.min(window.devicePixelRatio||1,2));renderer.outputColorSpace=THREE.SRGBColorSpace;
  renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1.05;renderer.setSize(width,height);
  wrap.innerHTML='';wrap.appendChild(renderer.domElement);ksVoxelRenderer=renderer;
  var preset=KS_VOXEL_LIGHT_PRESETS[document.getElementById('houseVoxelLightPreset').value||'day']||KS_VOXEL_LIGHT_PRESETS.day;
  ksVoxelAmbientLight=new THREE.AmbientLight(preset.ambient,preset.ambientI);scene.add(ksVoxelAmbientLight);
  ksVoxelDirLight=new THREE.DirectionalLight(preset.dir,preset.dirI);ksVoxelDirLight.position.set(maxDim*.4,maxDim*.8,maxDim*.35);scene.add(ksVoxelDirLight);
  scene.add(new THREE.HemisphereLight(0xbfe6ff,0x263426,.55));
  var grid=new THREE.GridHelper(maxDim*1.5,Math.max(16,Math.ceil(maxDim/16)),0x2C7F9E,0x17313D);
  grid.position.set(sizeX/2,-.05,sizeZ/2);if(grid.material){grid.material.transparent=true;grid.material.opacity=.32;}scene.add(grid);
  var homeCenter=new THREE.Vector3(sizeX/2,Math.min(4,maxDim*.06),sizeZ/2),center=homeCenter.clone(),radius=maxDim*1.18,theta=Math.PI*.75,phi=Math.PI*.25,zoom=1;
  var dragging=false,dragMode='rotate',lastX=0,lastY=0;
  function updateProjection(){var w=wrap.clientWidth||1280,h=wrap.clientHeight||720,a=w/h;camera.left=-viewSize*a/2;camera.right=viewSize*a/2;camera.top=viewSize/2;camera.bottom=-viewSize/2;camera.zoom=zoom;camera.updateProjectionMatrix();}
  function updateCamera(){camera.position.set(center.x+radius*Math.sin(phi)*Math.cos(theta),center.y+radius*Math.cos(phi),center.z+radius*Math.sin(phi)*Math.sin(theta));camera.lookAt(center);camera.updateMatrixWorld();}
  function resetView(){center.copy(homeCenter);theta=Math.PI*.75;phi=Math.PI*.25;zoom=1;updateProjection();updateCamera();}
  function panBy(dx,dy){
    var units=viewSize/((wrap.clientHeight||720)*zoom),right=new THREE.Vector3(1,0,0).applyQuaternion(camera.quaternion);right.y=0;right.normalize();
    var forward=new THREE.Vector3(center.x-camera.position.x,0,center.z-camera.position.z);if(forward.lengthSq()<.001)forward.set(0,0,1);else forward.normalize();
    center.addScaledVector(right,-dx*units);center.addScaledVector(forward,dy*units);updateCamera();
  }
  updateCamera();
  updateProjection();
  function down(e){dragging=true;dragMode=(e.button===1||e.button===2||e.shiftKey)?'pan':'rotate';lastX=e.clientX;lastY=e.clientY;renderer.domElement.focus();}
  function up(){dragging=false;}
  function move(e){if(!dragging)return;var dx=e.clientX-lastX,dy=e.clientY-lastY;if(dragMode==='pan')panBy(dx,dy);else{theta-=dx*.008;phi=Math.max(Math.PI*.12,Math.min(Math.PI*.46,phi-dy*.007));updateCamera();}lastX=e.clientX;lastY=e.clientY;}
  function wheel(e){e.preventDefault();zoom=Math.max(.45,Math.min(3.2,zoom*Math.exp(-e.deltaY*.0012)));updateProjection();}
  function keydown(e){var step=maxDim*.035/zoom;if(e.key==='ArrowLeft'||e.key==='a'||e.key==='A')panBy(step/Math.max(.001,viewSize/((wrap.clientHeight||720)*zoom)),0);else if(e.key==='ArrowRight'||e.key==='d'||e.key==='D')panBy(-step/Math.max(.001,viewSize/((wrap.clientHeight||720)*zoom)),0);else if(e.key==='ArrowUp'||e.key==='w'||e.key==='W')panBy(0,-step/Math.max(.001,viewSize/((wrap.clientHeight||720)*zoom)));else if(e.key==='ArrowDown'||e.key==='s'||e.key==='S')panBy(0,step/Math.max(.001,viewSize/((wrap.clientHeight||720)*zoom)));else return;e.preventDefault();}
  function resize(){if(!ksZoneSceneState||ksZoneSceneState.renderer!==renderer)return;updateProjection();renderer.setSize(wrap.clientWidth||1280,wrap.clientHeight||720);}
  renderer.domElement.tabIndex=0;renderer.domElement.style.outline='none';
  renderer.domElement.addEventListener('mousedown',down);renderer.domElement.addEventListener('mousemove',move);renderer.domElement.addEventListener('wheel',wheel,{passive:false});renderer.domElement.addEventListener('contextmenu',function(e){e.preventDefault();});renderer.domElement.addEventListener('dblclick',resetView);renderer.domElement.addEventListener('keydown',keydown);window.addEventListener('mouseup',up);window.addEventListener('resize',resize);
  var controls=document.createElement('div');controls.className='ks-city-camera-help';controls.style.cssText='position:absolute;left:18px;top:18px;display:flex;align-items:center;gap:8px;padding:7px 9px;border:1px solid rgba(100,216,255,.28);border-radius:8px;background:rgba(5,15,24,.82);color:#7895a9;font:10px var(--mono,monospace);z-index:2;backdrop-filter:blur(7px);pointer-events:none';controls.innerHTML='<button type="button" class="btn btn-sm" style="pointer-events:auto;padding:3px 8px">⌂ 视角复位</button><span>左键旋转 · 右键/Shift+左键平移 · 滚轮缩放 · WASD移动</span>';controls.querySelector('button').onclick=resetView;wrap.appendChild(controls);
  var raycaster=new THREE.Raycaster(),pointer=new THREE.Vector2(),downAt=null;
  function rememberDown(e){downAt={x:e.clientX,y:e.clientY};}
  function selectBuilding(e){
    if(!downAt||Math.hypot(e.clientX-downAt.x,e.clientY-downAt.y)>5)return;
    var rect=renderer.domElement.getBoundingClientRect();pointer.x=((e.clientX-rect.left)/rect.width)*2-1;pointer.y=-((e.clientY-rect.top)/rect.height)*2+1;
    raycaster.setFromCamera(pointer,camera);var hits=raycaster.intersectObjects(state.interactive,true);
    if(!hits.length)return;var object=hits[0].object,building=object.userData.building;
    while(!building&&object.parent){object=object.parent;building=object.userData&&object.userData.building;}
    if(building)showCityBuildingCard(wrap,building);
  }
  renderer.domElement.addEventListener('pointerdown',rememberDown);renderer.domElement.addEventListener('pointerup',selectBuilding);
  function animate(){ksVoxelAnimHandle=requestAnimationFrame(animate);renderer.render(scene,camera);}animate();
  var state={scene:scene,camera:camera,renderer:renderer,loader:new THREE.TextureLoader(),bounds:bounds,yBase:null,materials:{},interactive:[],placeholders:{},buildingGroups:{},resetView:resetView,focusBuilding:function(building){var w=Number(building.x2)-Number(building.x1)+1,d=Number(building.z2)-Number(building.z1)+1,h=Number(building.y2)-Number(building.y1)+1;center.set(Number(building.x1)-bounds.minX+w/2,Math.max(3,h*.28),Number(building.z1)-bounds.minZ+d/2);zoom=Math.max(1.15,Math.min(2.1,maxDim/Math.max(w,d,18)*.72));updateProjection();updateCamera();},cleanup:function(){window.removeEventListener('mouseup',up);window.removeEventListener('resize',resize);renderer.domElement.removeEventListener('pointerdown',rememberDown);renderer.domElement.removeEventListener('pointerup',selectBuilding);renderer.domElement.removeEventListener('mousedown',down);renderer.domElement.removeEventListener('mousemove',move);renderer.domElement.removeEventListener('wheel',wheel);renderer.domElement.removeEventListener('dblclick',resetView);renderer.domElement.removeEventListener('keydown',keydown);}};
  state.loader.crossOrigin='anonymous';ksZoneSceneState=state;return state;
}

function showCityBuildingCard(wrap,building){
  var old=wrap.querySelector('.ks-city-building-card');if(old)old.remove();
  var card=document.createElement('div');card.className='ks-city-building-card';
  var market=building.market||{},sale=building.saleStatus==='FOR_SALE',showcase=building.saleStatus==='SHOWCASE',marker=ksCityMarkerStyle(building.showcaseMarker);
  var footprint=(Number(building.x2)-Number(building.x1)+1)*(Number(building.z2)-Number(building.z1)+1),height=Number(building.y2)-Number(building.y1)+1;
  card.style.cssText='position:absolute;right:18px;top:18px;width:min(300px,calc(100% - 36px));padding:14px 16px;border:1px solid rgba(100,216,255,.45);border-radius:10px;background:rgba(5,15,24,.94);box-shadow:0 16px 50px #0009;color:#ddecf4;font:12px/1.7 system-ui;z-index:3;backdrop-filter:blur(10px)';
  card.innerHTML='<button style="float:right;border:0;background:transparent;color:#9bb6c5;cursor:pointer;font-size:16px" onclick="this.parentNode.remove()">×</button>'+
    '<div style="color:#'+marker.hex+';font-weight:700;font-size:15px;margin-bottom:5px">◆ '+escapeHtml(building.name||building.id||'未命名楼栋')+'</div>'+
    '<div>专属标识：<b style="color:#'+marker.hex+'">'+marker.label+'识别柱</b></div>'+
    '<div>状态：<b style="color:'+(sale||showcase?'#f1c75b':'#8ed6b5')+'">'+(sale?'正式在售':(showcase?'沙盘展示':'已登记'))+'</b></div>'+
    '<div>地块：'+escapeHtml(building.plotId||'-')+'</div>'+
    '<div>大小：'+(Number(building.x2)-Number(building.x1)+1)+' × '+(Number(building.z2)-Number(building.z1)+1)+' · '+height+' 高</div>'+
    '<div>占地：'+fmt(footprint)+' ㎡ · 建筑包围体 '+fmt(footprint*height)+' m³</div>'+
    (sale?'<div>正式挂牌价：<b style="color:#f1c75b">'+fmt(market.price||0)+'</b></div><div>卖方：'+escapeHtml(market.sellerName||'-')+'</div>':(showcase?'<div>展示售价：<b style="color:#f1c75b">'+fmt(building.showcasePrice||0)+'</b></div><div style="color:#7895a9;font-size:10px">展示价不代表已提交正式市场挂单</div>':''));
  wrap.appendChild(card);
}

async function addZoneVoxelChunk(state,data,tile,parent,building){
  if(ksZoneSceneState!==state)return;
  var THREE=window.THREE;
  if(state.yBase==null)state.yBase=data.y1;
  var offsetX=tile.x1-state.bounds.minX,offsetZ=tile.z1-state.bounds.minZ,offsetY=data.y1-state.yBase;
  var target=parent||state.scene;
  var groups={},shaped=[];
  for(var i=0;i<data.blocks.length;i++){
    var block=data.blocks[i],category=ksVoxelShapeCategory(block.mat||'');
    if(category){shaped.push({b:block,category:category});continue;}
    var key=block.mat||('#'+block.color);if(!groups[key])groups[key]=[];groups[key].push(block);
  }
  var geometry=new THREE.BoxGeometry(1,1,1),dummy=new THREE.Object3D(),keys=Object.keys(groups);
  for(var k=0;k<keys.length;k++){
    var key=keys[k],list=groups[key],material=state.materials[key];
    if(!material){material=await ksVoxelBuildMaterial(THREE,state.loader,list[0].mat||'',list[0].color);state.materials[key]=material;}
    if(ksZoneSceneState!==state)return;
    var mesh=new THREE.InstancedMesh(geometry,material,list.length);
    for(var j=0;j<list.length;j++){
      dummy.position.set(offsetX+list[j].x+.5,offsetY+list[j].y+.5,offsetZ+list[j].z+.5);dummy.updateMatrix();mesh.setMatrixAt(j,dummy.matrix);
    }
    mesh.instanceMatrix.needsUpdate=true;
    if(building){mesh.userData.building=building;state.interactive.push(mesh);}target.add(mesh);
  }
  for(var s=0;s<shaped.length;s++){
    var item=shaped[s],group=await ksVoxelBuildShapeGroup(THREE,state.loader,item.category,item.b.mat||'',item.b.data,item.b.color);
    group.position.set(offsetX+item.b.x+.5,offsetY+item.b.y,offsetZ+item.b.z+.5);if(building){group.userData.building=building;state.interactive.push(group);}target.add(group);
  }
}

var KS_VOXEL_MULTI_FACE={
  GRASS_BLOCK:{top:'grass_block_top',side:'grass_block_side',bottom:'dirt',topTint:0x79c05a},
  DIRT_PATH:{top:'dirt_path_top',side:'dirt_path_side',bottom:'dirt'},
  SANDSTONE:{top:'sandstone_top',side:'sandstone',bottom:'sandstone_bottom'},
  RED_SANDSTONE:{top:'red_sandstone_top',side:'red_sandstone',bottom:'red_sandstone_bottom'},
  BOOKSHELF:{top:'oak_planks',side:'bookshelf',bottom:'oak_planks'},
  FURNACE:{top:'furnace_top',side:'furnace_front',bottom:'furnace_top'},
  CRAFTING_TABLE:{top:'crafting_table_top',side:'crafting_table_front',bottom:'oak_planks'}
};
// 不渲染贴图、直接用服务端给的近似颜色立方体——楼梯/台阶/栅栏/墙/活板门/门/栅栏门已用专门几何体+贴图渲染（见 ksVoxelShapeCategory），
// 这里只剩形状太琐碎（薄片/不规则）、做精细几何收益很低的装饰方块
var KS_VOXEL_COLOR_ONLY_SUFFIX=['_BED','_BUTTON','_PRESSURE_PLATE','_SIGN','_CARPET','_RAIL'];
function ksVoxelTextureSpec(matName){
  if(matName==='WATER'||matName==='LAVA')return null;
  for(var i=0;i<KS_VOXEL_COLOR_ONLY_SUFFIX.length;i++){if(matName.endsWith(KS_VOXEL_COLOR_ONLY_SUFFIX[i]))return null;}
  if(KS_VOXEL_MULTI_FACE[matName])return KS_VOXEL_MULTI_FACE[matName];
  var m=matName.match(/^(.*)_LOG$/)||matName.match(/^(.*)_STEM$/);
  if(m)return {top:matName.toLowerCase(),side:matName.toLowerCase(),bottom:matName.toLowerCase()};
  if(matName.endsWith('_LEAVES'))return {top:matName.toLowerCase(),side:matName.toLowerCase(),bottom:matName.toLowerCase(),tint:0x59ae30,transparent:true};
  if(matName.includes('GLASS'))return {top:matName.toLowerCase(),side:matName.toLowerCase(),bottom:matName.toLowerCase(),transparent:true,opacity:.72,depthWrite:true};
  return {top:matName.toLowerCase(),side:matName.toLowerCase(),bottom:matName.toLowerCase()};
}
function ksVoxelLoadTexture(loader,name){
  if(ksVoxelTextureCache[name]!==undefined)return ksVoxelTextureCache[name];
  var THREE=window.THREE;
  var p=new Promise(function(resolve){
    loader.load(KS_VOXEL_TEX_BASE+name+'.png',function(tex){
      tex.magFilter=THREE.NearestFilter; tex.minFilter=THREE.NearestFilter; tex.colorSpace=THREE.SRGBColorSpace;
      resolve(tex);
    },undefined,function(){resolve(null);});
  });
  ksVoxelTextureCache[name]=p;
  return p;
}
// 为某材质构建 BoxGeometry 6 面材质数组：[+x,-x,+y,-y,+z,-z] = [右,左,顶,底,前,后]；纹理加载失败/复杂方块时整体回退为纯色立方体
async function ksVoxelBuildMaterial(THREE,loader,matName,fallbackColor){
  // 整玻璃块使用服务端颜色的干净半透明外露面。原版纹理的透明通道叠加到自定义面后会在 WebGL 中
  // 暴露三角形排序边界；玻璃板仍由专用薄片几何和纹理负责。
  if(matName.includes('GLASS'))return new THREE.MeshStandardMaterial({color:fallbackColor,roughness:.2,metalness:.06,transparent:false,depthWrite:true,side:THREE.FrontSide});
  var spec=ksVoxelTextureSpec(matName);
  if(!spec){
    return new THREE.MeshLambertMaterial({color:fallbackColor,transparent:matName==='WATER'||matName==='LAVA',opacity:(matName==='WATER')?0.75:1});
  }
  var topTex=await ksVoxelLoadTexture(loader,spec.top);
  var sideTex=(spec.side===spec.top)?topTex:await ksVoxelLoadTexture(loader,spec.side);
  var botTex=(spec.bottom===spec.top)?topTex:((spec.bottom===spec.side)?sideTex:await ksVoxelLoadTexture(loader,spec.bottom));
  if(!topTex&&!sideTex&&!botTex){
    return new THREE.MeshLambertMaterial({color:fallbackColor});
  }
  function mk(tex,tint){
    var opt={map:tex||null,color:tex?(tint!=null?tint:0xffffff):fallbackColor};
    if(spec.transparent){opt.transparent=true;opt.alphaTest=spec.opacity?0.05:0.4;opt.opacity=spec.opacity||1;opt.depthWrite=spec.depthWrite!==false;opt.side=THREE.DoubleSide;}
    return new THREE.MeshLambertMaterial(opt);
  }
  var sideMat=mk(sideTex,spec.tint&&spec.side===spec.top?spec.tint:(spec.tint&&!spec.topTint?spec.tint:null));
  var topMat=mk(topTex,spec.topTint||spec.tint);
  var botMat=mk(botTex,spec.tint);
  return [sideMat,sideMat,topMat,botMat,sideMat,sideMat];
}

// ====== 异形方块（楼梯/台阶/栅栏/墙/活板门/门/栅栏门）：简化几何体 + 真实贴图 ======
// 朝向/开合等状态用服务端导出的 BlockData 字符串（如 "minecraft:oak_stairs[facing=east,half=bottom,...]"）正则取值。
function ksVoxelParseProp(dataStr,key){
  if(!dataStr)return null;
  var m=dataStr.match(new RegExp('[\\[,]'+key+'=([a-z_]+)'));
  return m?m[1]:null;
}
function ksVoxelShapeCategory(matName){
  if(matName.endsWith('_GLASS_PANE'))return 'pane';
  if(matName.endsWith('_FENCE_GATE'))return 'fence_gate';
  if(matName.endsWith('_STAIRS'))return 'stairs';
  if(matName.endsWith('_SLAB'))return 'slab';
  if(matName.endsWith('_FENCE'))return 'fence';
  if(matName.endsWith('_WALL'))return 'wall';
  if(matName.endsWith('_TRAPDOOR'))return 'trapdoor';
  if(matName.endsWith('_DOOR'))return 'door';
  return null;
}
// 楼梯/台阶/栅栏/墙/栅栏门复用其"母体方块"的贴图（如 OAK_STAIRS→oak_planks），但材质名到贴图文件名的拼法不规律
// （COBBLESTONE_STAIRS→cobblestone，STONE_BRICK_STAIRS→stone_bricks，OAK_FENCE→oak_planks，PURPUR_STAIRS→purpur_block），
// 因此按常见拼法顺序尝试几个候选名，第一个能加载成功的就用，全部失败再退回纯色。
function ksVoxelBaseCandidates(matName,suffix){
  var base=matName.slice(0,-suffix.length).toLowerCase();
  return [base,base+'s',base+'_planks',base+'_block'];
}
async function ksVoxelLoadFirstAvailable(loader,candidates){
  for(var i=0;i<candidates.length;i++){
    var t=await ksVoxelLoadTexture(loader,candidates[i]);
    if(t)return t;
  }
  return null;
}
var KS_VOXEL_FACING_ANGLE={south:0,west:Math.PI/2,north:Math.PI,east:-Math.PI/2};
// 所有几何体都建在"以方块中心为原点"的局部坐标：x/z∈[-0.5,0.5]（用于绕Y轴旋转朝向），y∈[0,1]为方块内绝对高度。
function ksVoxelBox(THREE,mat,sx,sy,sz,cx,cy,cz){
  var mesh=new THREE.Mesh(new THREE.BoxGeometry(sx,sy,sz),mat);
  mesh.position.set(cx,cy,cz);
  return mesh;
}
// 楼梯：下半层整块踏板 + 上半层半块踢面（背向 facing 一侧）。不区分 inner/outer 转角形状（仅做直梯近似）。
function ksVoxelBuildStairs(THREE,mat,half,facing){
  var g=new THREE.Group();
  var bottomY=0.25, upperY=0.75;
  if(half==='top'){bottomY=0.75; upperY=0.25;}
  g.add(ksVoxelBox(THREE,mat,1,0.5,1, 0,bottomY,0));
  g.add(ksVoxelBox(THREE,mat,1,0.5,0.5, 0,upperY,-0.25));
  g.rotation.y=KS_VOXEL_FACING_ANGLE[facing]||0;
  return g;
}
function ksVoxelBuildSlab(THREE,mat,type){
  var h=0.5, cy=0.25;
  if(type==='top'){cy=0.75;} else if(type==='double'){h=1; cy=0.5;}
  var g=new THREE.Group();
  g.add(ksVoxelBox(THREE,mat,1,h,1, 0,cy,0));
  return g;
}
function ksVoxelBuildFence(THREE,mat){
  var g=new THREE.Group();
  g.add(ksVoxelBox(THREE,mat,0.25,1,0.25, 0,0.5,0));
  return g;
}
function ksVoxelBuildWall(THREE,mat){
  var g=new THREE.Group();
  g.add(ksVoxelBox(THREE,mat,0.75,1,0.75, 0,0.5,0));
  return g;
}
// 活板门：关闭时贴地/贴顶的薄片；打开时翻成贴着 facing 一侧墙面的竖直薄片（不区分具体铰链转向）。
function ksVoxelBuildTrapdoor(THREE,mat,half,facing,open){
  var g=new THREE.Group();
  var th=0.1875;
  if(open!=='true'){
    var cy=(half==='top')?(1-th/2):(th/2);
    g.add(ksVoxelBox(THREE,mat,1,th,1, 0,cy,0));
  }else{
    g.add(ksVoxelBox(THREE,mat,1,1,th, 0,0.5,0.5-th/2));
    g.rotation.y=KS_VOXEL_FACING_ANGLE[facing]||0;
  }
  return g;
}
// 门/栅栏门：简化为固定按"关闭"姿态渲染（贴在 facing 一侧墙边/居中），不模拟开合摆动与铰链方向。
function ksVoxelBuildDoor(THREE,mat,facing){
  var g=new THREE.Group();
  var th=0.1875;
  g.add(ksVoxelBox(THREE,mat,1,1,th, 0,0.5,0.5-th/2));
  g.rotation.y=KS_VOXEL_FACING_ANGLE[facing]||0;
  return g;
}
function ksVoxelBuildFenceGate(THREE,mat,facing){
  var g=new THREE.Group();
  var th=0.1875;
  g.add(ksVoxelBox(THREE,mat,1,1,th, 0,0.5,0));
  g.rotation.y=KS_VOXEL_FACING_ANGLE[facing]||0;
  return g;
}
function ksVoxelBuildPane(THREE,mat,dataStr){
  var g=new THREE.Group(),north=ksVoxelParseProp(dataStr,'north'),south=ksVoxelParseProp(dataStr,'south');
  var east=ksVoxelParseProp(dataStr,'east'),west=ksVoxelParseProp(dataStr,'west'),th=.10;
  if(north==='true'||south==='true'||(!north&&!south&&!east&&!west))g.add(ksVoxelBox(THREE,mat,th,1,1,0,.5,0));
  if(east==='true'||west==='true'||(!north&&!south&&!east&&!west))g.add(ksVoxelBox(THREE,mat,1,1,th,0,.5,0));
  return g;
}
var KS_VOXEL_SHAPE_SUFFIX={stairs:'_STAIRS',slab:'_SLAB',fence:'_FENCE',wall:'_WALL',fence_gate:'_FENCE_GATE'};
async function ksVoxelBuildShapeGroup(THREE,loader,category,matName,dataStr,fallbackColor){
  var facing=ksVoxelParseProp(dataStr,'facing')||'south';
  var half=ksVoxelParseProp(dataStr,'half');
  var open=ksVoxelParseProp(dataStr,'open');
  var type=ksVoxelParseProp(dataStr,'type');
  var tex,mat;
  if(category==='door'){
    tex=await ksVoxelLoadTexture(loader,matName.toLowerCase()+((half==='top')?'_top':'_bottom'));
  }else if(category==='trapdoor'){
    tex=await ksVoxelLoadTexture(loader,matName.toLowerCase());
  }else if(category==='pane'){
    tex=await ksVoxelLoadTexture(loader,matName.slice(0,-5).toLowerCase());
  }else{
    tex=await ksVoxelLoadFirstAvailable(loader,ksVoxelBaseCandidates(matName,KS_VOXEL_SHAPE_SUFFIX[category]));
  }
  mat=tex?new THREE.MeshLambertMaterial({map:tex,transparent:category==='pane',opacity:category==='pane'?0.72:1,alphaTest:category==='pane'?0.08:0,depthWrite:true,side:category==='pane'?THREE.DoubleSide:THREE.FrontSide}):new THREE.MeshLambertMaterial({color:fallbackColor,transparent:category==='pane',opacity:category==='pane'?0.66:1,depthWrite:true});
  if(category==='pane')return ksVoxelBuildPane(THREE,mat,dataStr);
  if(category==='stairs')return ksVoxelBuildStairs(THREE,mat,half,facing);
  if(category==='slab')return ksVoxelBuildSlab(THREE,mat,type);
  if(category==='fence')return ksVoxelBuildFence(THREE,mat);
  if(category==='wall')return ksVoxelBuildWall(THREE,mat);
  if(category==='trapdoor')return ksVoxelBuildTrapdoor(THREE,mat,half,facing,open);
  if(category==='door')return ksVoxelBuildDoor(THREE,mat,facing);
  return ksVoxelBuildFenceGate(THREE,mat,facing);
}

async function renderVoxelScene(d){
  var THREE=window.THREE;
  var wrap=document.getElementById('houseVoxelCanvasWrap');
  var w=wrap.clientWidth||640, h=wrap.clientHeight||480;
  var scene=new THREE.Scene();
  var preset=KS_VOXEL_LIGHT_PRESETS[document.getElementById('houseVoxelLightPreset').value||'day']||KS_VOXEL_LIGHT_PRESETS.day;
  scene.background=new THREE.Color(preset.bg);
  var sizeX=d.x2-d.x1+1, sizeY=d.y2-d.y1+1, sizeZ=d.z2-d.z1+1;
  var maxDim=Math.max(sizeX,sizeY,sizeZ,1);
  var camera=new THREE.PerspectiveCamera(48,w/h,0.1,maxDim*10);
  camera.position.set(maxDim*1.3,maxDim*1.1,maxDim*1.3);
  camera.lookAt(sizeX/2,sizeY/2,sizeZ/2);
  var renderer=new THREE.WebGLRenderer({antialias:true});
  renderer.setPixelRatio(Math.min(window.devicePixelRatio||1,2));
  renderer.outputColorSpace=THREE.SRGBColorSpace;
  renderer.toneMapping=THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure=1.08;
  renderer.setSize(w,h);
  wrap.appendChild(renderer.domElement);
  ksVoxelRenderer=renderer;

  ksVoxelAmbientLight=new THREE.AmbientLight(preset.ambient,preset.ambientI);
  scene.add(ksVoxelAmbientLight);
  ksVoxelDirLight=new THREE.DirectionalLight(preset.dir,preset.dirI);
  ksVoxelDirLight.position.set(preset.dirPos[0],preset.dirPos[1],preset.dirPos[2]);
  scene.add(ksVoxelDirLight);
  scene.add(new THREE.HemisphereLight(0xbfe6ff,0x263426,0.55));

  // 整块（含装饰性薄片方块，仍按立方体近似）按材质分组，一种材质一个 InstancedMesh；
  // 楼梯/台阶/栅栏/墙/活板门/门/栅栏门走专门的异形几何体，逐个生成（数量通常不多，逐个 Mesh 可接受）
  var loader=new THREE.TextureLoader();
  loader.crossOrigin='anonymous';
  var groups={}, shaped=[];
  for(var i=0;i<d.blocks.length;i++){
    var b=d.blocks[i];
    var category=ksVoxelShapeCategory(b.mat||'');
    if(category){ shaped.push({b:b,category:category}); continue; }
    var key=b.mat||('#'+b.color);
    if(!groups[key])groups[key]=[];
    groups[key].push(b);
  }
  var geo=new THREE.BoxGeometry(1,1,1);
  var dummy=new THREE.Object3D();
  var keys=Object.keys(groups);
  for(var k=0;k<keys.length;k++){
    var key=keys[k];
    var list=groups[key];
    var fallbackColor=list[0].color;
    var matArr=await ksVoxelBuildMaterial(THREE,loader,list[0].mat||'',fallbackColor);
    var mesh=new THREE.InstancedMesh(geo,matArr,list.length);
    for(var j=0;j<list.length;j++){
      dummy.position.set(list[j].x+0.5,list[j].y+0.5,list[j].z+0.5);
      dummy.updateMatrix();
      mesh.setMatrixAt(j,dummy.matrix);
    }
    mesh.instanceMatrix.needsUpdate=true;
    scene.add(mesh);
  }
  for(var si=0;si<shaped.length;si++){
    var sb=shaped[si].b;
    var grp=await ksVoxelBuildShapeGroup(THREE,loader,shaped[si].category,sb.mat||'',sb.data,sb.color);
    grp.position.set(sb.x+0.5,sb.y,sb.z+0.5);
    scene.add(grp);
  }

  // 简易鼠标拖拽旋转 + 滚轮缩放（围绕房屋几何中心）
  var center=new THREE.Vector3(sizeX/2,sizeY*0.42,sizeZ/2);
  var radius=camera.position.distanceTo(center);
  var theta=Math.PI*0.78, phi=Math.PI*0.34;
  var dragging=false, lastX=0, lastY=0;
  function updateCamera(){
    camera.position.set(
      center.x+radius*Math.sin(phi)*Math.cos(theta),
      center.y+radius*Math.cos(phi),
      center.z+radius*Math.sin(phi)*Math.sin(theta)
    );
    camera.lookAt(center);
  }
  updateCamera();
  renderer.domElement.addEventListener('mousedown',function(e){dragging=true;lastX=e.clientX;lastY=e.clientY;});
  window.addEventListener('mouseup',function(){dragging=false;});
  renderer.domElement.addEventListener('mousemove',function(e){
    if(!dragging)return;
    theta-=(e.clientX-lastX)*0.01;
    phi=Math.max(0.1,Math.min(Math.PI-0.1,phi-(e.clientY-lastY)*0.01));
    lastX=e.clientX;lastY=e.clientY;
    updateCamera();
  });
  renderer.domElement.addEventListener('wheel',function(e){
    e.preventDefault();
    radius=Math.max(maxDim*0.3,Math.min(maxDim*8,radius*(1+e.deltaY*0.001)));
    updateCamera();
  });

  function animate(){
    ksVoxelAnimHandle=requestAnimationFrame(animate);
    renderer.render(scene,camera);
  }
  animate();
}
function reOwnerLabel(p){
  if(p.ownerType==='ENTERPRISE'){
    var ent=myEnterprises.find(function(e){return e.id===p.ownerId;});
    return '🏢 '+(ent?escapeHtml(ent.name):p.ownerId);
  }
  return '👤 我';
}
async function loadMyRe(){
  if(!myEnterprises.length)await loadMyEnterprises();
  var d=await api('GET','/api/realestate/my-plots');
  window.myRePlotCache=d.plots||[];
  var t='';(d.plots||[]).forEach(function(p,i){
    t+='<tr onclick="openMyRePlotRow(event,'+i+')"><td style="font-size:10px;">'+p.id+'</td>';
    t+='<td style="font-size:11px;">'+reOwnerLabel(p)+'</td>';
    t+='<td>'+p.zoneId+'</td><td>'+p.world+'</td>';
    t+='<td style="font-size:11px;">['+p.x1+','+p.z1+']-['+p.x2+','+p.z2+']</td>';
    t+='<td>'+fmt(p.price)+'</td>';
    t+='<td>'+pct(p.taxRate)+'</td>';
    t+='<td style="font-size:11px;">'+new Date(p.purchasedAt*1000).toLocaleDateString('zh-CN')+'</td>';
    t+='<td style="font-size:11px;">'+(p.dungeonTemplateId?escapeHtml(p.dungeonTemplateId):'<span style="color:#666;">无</span>')+'</td></tr>';
  });
  document.getElementById('myRePlotsBody').innerHTML=t||'<tr><td colspan="9" style="color:#666;">你还没有地块</td></tr>';
}
function openMyRePlotRow(event,index){
  if(event)event.stopPropagation();
  var p=(window.myRePlotCache||[])[index];
  if(p&&typeof openEmpirePlotData==='function')openEmpirePlotData(p);
}
// ====== 原生地产地图（只拉 ksHWP 瓦片，区域/地块自绘；不嵌 ksHWP 整页） ======
function KsReMap(canvasId, opts){
  opts=opts||{};
  var canvas=document.getElementById(canvasId);
  if(!canvas) return null;
  var ctx=canvas.getContext('2d');
  var TS=256;
  var st={world:opts.world||'world',zoom:4,panX:0,panZ:0,
          dragging:false,selecting:false,lastX:0,lastY:0,
          sX1:0,sZ1:0,zones:[],plots:[],sel:null,tilesOk:true};
  var tiles={};
  function wpp(){return TS/(16*st.zoom);}
  function w2p(wx,wz){var b=wpp();return {px:wx*b+st.panX, pz:wz*b+st.panZ};}
  function p2w(px,pz){var b=16*st.zoom/TS;return {wx:(px-st.panX)*b, wz:(pz-st.panZ)*b};}
  function mouse(e){var r=canvas.getBoundingClientRect();return {x:e.clientX-r.left,y:e.clientY-r.top};}
  function resize(){canvas.width=canvas.clientWidth;canvas.height=canvas.clientHeight;draw();}
  function setStatus(t){var el=document.getElementById(opts.statusId);if(el)el.textContent=t;}
  function draw(){
    ctx.fillStyle='#0a0a1a';ctx.fillRect(0,0,canvas.width,canvas.height);
    var minTX=Math.floor(-st.panX/TS)-1,minTZ=Math.floor(-st.panZ/TS)-1;
    var maxTX=Math.floor((canvas.width-st.panX)/TS)+1,maxTZ=Math.floor((canvas.height-st.panZ)/TS)+1;
    for(var tz=minTZ;tz<=maxTZ;tz++)for(var tx=minTX;tx<=maxTX;tx++){
      var k=st.zoom+':'+tx+':'+tz, px=tx*TS+st.panX, pz=tz*TS+st.panZ;
      var c=tiles[k];
      if(c&&c!=='loading'&&c!=='fail'){ctx.drawImage(c,px,pz,TS,TS);}
      else{ctx.fillStyle='#10101e';ctx.fillRect(px,pz,TS-1,TS-1);}
      if(!c&&st.tilesOk)loadTile(k,tx,tz);
    }
    ctx.strokeStyle='#ffffff10';ctx.lineWidth=0.5;
    var b=wpp(), step=16*b;
    if(step>=8){
      var ox=((st.panX%step)+step)%step, oz=((st.panZ%step)+step)%step;
      for(var x=ox;x<canvas.width;x+=step){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,canvas.height);ctx.stroke();}
      for(var y=oz;y<canvas.height;y+=step){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(canvas.width,y);ctx.stroke();}
    }
    st.zones.forEach(function(z){
      if(z.status!=='FOR_SALE')return; // 玩家端只画可售
      var p1=w2p(Math.min(z.x1,z.x2),Math.min(z.z1,z.z2));
      var p2=w2p(Math.max(z.x1,z.x2),Math.max(z.z1,z.z2));
      ctx.fillStyle='#00E5FF22';ctx.fillRect(p1.px,p1.pz,p2.px-p1.px,p2.pz-p1.pz);
      ctx.strokeStyle='#00E5FF';ctx.lineWidth=1.5;ctx.strokeRect(p1.px,p1.pz,p2.px-p1.px,p2.pz-p1.pz);
      ctx.fillStyle='#00E5FF';ctx.font='bold 11px sans-serif';ctx.fillText((z.name||z.id),p1.px+4,p1.pz+14);
    });
    st.plots.forEach(function(p){
      var p1=w2p(Math.min(p.x1,p.x2),Math.min(p.z1,p.z2));
      var p2=w2p(Math.max(p.x1,p.x2),Math.max(p.z1,p.z2));
      ctx.fillStyle='#FF3DF233';ctx.fillRect(p1.px,p1.pz,p2.px-p1.px,p2.pz-p1.pz);
      ctx.strokeStyle='#FF3DF2';ctx.lineWidth=1;ctx.setLineDash([3,2]);
      ctx.strokeRect(p1.px,p1.pz,p2.px-p1.px,p2.pz-p1.pz);ctx.setLineDash([]);
    });
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
      .then(function(d){if(d&&d.tile){var img=new Image();img.onload=function(){tiles[k]=img;draw();};img.src='data:image/png;base64,'+d.tile;}else{tiles[k]='fail';}})
      .catch(function(){tiles[k]='fail';if(st.tilesOk){st.tilesOk=false;setStatus('⚠ ksHWP 地图未安装，仅显示网格');draw();}});
  }
  function center(){
    var z=st.zones.filter(function(x){return x.status==='FOR_SALE';})[0]||st.zones[0];
    var cx=z?((z.x1+z.x2)/2):0, cz=z?((z.z1+z.z2)/2):0;
    st.panX=canvas.width/2-cx*wpp(); st.panZ=canvas.height/2-cz*wpp(); draw();
  }
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
    var after=w2p(before.wx,before.wz);
    st.panX+=m.x-after.px;st.panZ+=m.y-after.pz;tiles={};draw();
  },{passive:false});
  setTimeout(resize,50);
  window.addEventListener('resize',resize);
  return {
    setZones:function(z){st.zones=z||[];draw();},
    setPlots:function(p){st.plots=p||[];draw();},
    setWorld:function(w){if(w&&w!==st.world){st.world=w;tiles={};draw();}},
    zoomIn:function(){if(st.zoom>1){st.zoom--;tiles={};draw();}},
    zoomOut:function(){if(st.zoom<16){st.zoom++;tiles={};draw();}},
    center:center, resize:resize
  };
}
// Internal vector district map: it does not need HWP tiles and keeps parcel
// selection available on every server installation.
function KsMapEngine(canvasId,opts){
  opts=opts||{};
  var canvas=document.getElementById(canvasId);if(!canvas)return null;
  var ctx=canvas.getContext('2d');
  var st={world:opts.world||'world',zones:[],plots:[],scale:1,ox:0,oz:0,mode:'pan',dragging:false,selecting:false,moved:false,last:null,start:null,sel:null,selectedZoneId:null,pointerId:null,tiles:{},terrain:opts.terrain!==false,tilesEnabled:opts.terrain!==false};
  var tileQueue=[],tileActive=0,tileEpoch=0,tileFailures=0,TILE_REQUEST_LIMIT=2;
  function size(){return {w:Math.max(1,canvas.clientWidth||800),h:Math.max(1,canvas.clientHeight||512)};}
  function zoneColor(type){return {RESIDENTIAL:'#00D9FF',COMMERCIAL:'#FF4FD8',INDUSTRIAL:'#FFC857',AGRICULTURAL:'#5BE38B'}[type]||'#8FA6C7';}
  function colorAlpha(hex,alpha){var value=parseInt(String(hex).slice(1),16);return 'rgba('+((value>>16)&255)+','+((value>>8)&255)+','+(value&255)+','+alpha+')';}
  function roundedRect(x,y,w,h,r){r=Math.max(0,Math.min(r,w/3,h/3));ctx.beginPath();ctx.moveTo(x+r,y);ctx.lineTo(x+w-r,y);ctx.quadraticCurveTo(x+w,y,x+w,y+r);ctx.lineTo(x+w,y+h-r);ctx.quadraticCurveTo(x+w,y+h,x+w-r,y+h);ctx.lineTo(x+r,y+h);ctx.quadraticCurveTo(x,y+h,x,y+h-r);ctx.lineTo(x,y+r);ctx.quadraticCurveTo(x,y,x+r,y);ctx.closePath();}
  function bounds(v){var x1=Number(v.x1)||0,x2=Number(v.x2)||0,z1=Number(v.z1)||0,z2=Number(v.z2)||0;return {x:Math.min(x1,x2),z:Math.min(z1,z2),w:Math.abs(x2-x1)+1,h:Math.abs(z2-z1)+1};}
  function point(x,z){return {x:st.ox+x*st.scale,y:st.oz+z*st.scale};}
  function world(x,y){return {x:(x-st.ox)/st.scale,z:(y-st.oz)/st.scale};}
  function mouse(e){var r=canvas.getBoundingClientRect();return {x:e.clientX-r.left,y:e.clientY-r.top};}
  function setStatus(text){var el=document.getElementById(opts.statusId);if(el)el.textContent=text;}
  function inWorld(v){return !v.world||String(v.world)===String(st.world);}
  function visible(){return st.zones.concat(st.plots).filter(inWorld);}
  function fitEntity(v,pad){var q=bounds(v),s=size();pad=pad||64;st.scale=Math.max(.02,Math.min(24,(s.w-pad*2)/Math.max(1,q.w),(s.h-pad*2)/Math.max(1,q.h)));st.ox=(s.w-q.w*st.scale)/2-q.x*st.scale;st.oz=(s.h-q.h*st.scale)/2-q.z*st.scale;}
  function fit(){var all=visible(),s=size();if(!all.length){st.scale=4;st.ox=s.w/2;st.oz=s.h/2;draw();return;}var minX=Infinity,minZ=Infinity,maxX=-Infinity,maxZ=-Infinity;all.forEach(function(v){var q=bounds(v);minX=Math.min(minX,q.x);minZ=Math.min(minZ,q.z);maxX=Math.max(maxX,q.x+q.w);maxZ=Math.max(maxZ,q.z+q.h);});var pad=Math.min(78,Math.max(42,Math.min(s.w,s.h)*.12)),spanX=Math.max(1,maxX-minX),spanZ=Math.max(1,maxZ-minZ);st.scale=Math.max(.02,Math.min(24,(s.w-pad*2)/spanX,(s.h-pad*2)/spanZ));st.ox=(s.w-spanX*st.scale)/2-minX*st.scale;st.oz=(s.h-spanZ*st.scale)/2-minZ*st.scale;draw();}
  function gridStep(){var options=[8,16,32,64,128,256,512,1024,2048];for(var i=0;i<options.length;i++)if(options[i]*st.scale>=48)return options[i];return options[options.length-1];}
  function drawGrid(s){var step=gridStep(),a=world(0,0),b=world(s.w,s.h),x=Math.floor(a.x/step)*step,z=Math.floor(a.z/step)*step;ctx.font='9px Roboto Mono,monospace';ctx.textBaseline='top';for(;x<=b.x+step;x+=step){var p=point(x,0),major=x%(step*4)===0;ctx.strokeStyle=major?'rgba(120,169,204,.16)':'rgba(120,169,204,.075)';ctx.lineWidth=1;ctx.beginPath();ctx.moveTo(Math.round(p.x)+.5,0);ctx.lineTo(Math.round(p.x)+.5,s.h);ctx.stroke();if(major&&p.x>42&&p.x<s.w-42){ctx.fillStyle='rgba(143,176,197,.48)';ctx.fillText(String(x),p.x+4,6);}}for(;z<=b.z+step;z+=step){var q=point(0,z),majorZ=z%(step*4)===0;ctx.strokeStyle=majorZ?'rgba(120,169,204,.16)':'rgba(120,169,204,.075)';ctx.beginPath();ctx.moveTo(0,Math.round(q.y)+.5);ctx.lineTo(s.w,Math.round(q.y)+.5);ctx.stroke();if(majorZ&&q.y>28&&q.y<s.h-28){ctx.fillStyle='rgba(143,176,197,.48)';ctx.fillText(String(z),6,q.y+4);}}}
  function tileZoom(){var wanted=16/Math.max(.02,st.scale),levels=[1,2,4,8],best=1,delta=Infinity;levels.forEach(function(level){var d=Math.abs(Math.log(wanted/level));if(d<delta){best=level;delta=d;}});return best;}
  function resetTileRequests(){tileEpoch++;tileQueue=[];tileFailures=0;}
  function pumpTiles(){
    while(tileActive<TILE_REQUEST_LIMIT&&tileQueue.length){
      var item=tileQueue.shift();
      if(item.epoch!==tileEpoch||item.world!==st.world||st.tiles[item.key]!=='queued')continue;
      tileActive++;st.tiles[item.key]='loading';
      (function(request){
        fetch('/kSHWP/api/tile?world='+encodeURIComponent(request.world)+'&x='+request.tx+'&z='+request.tz+'&zoom='+request.zoom)
          .then(function(r){if(!r.ok)throw new Error('tile '+r.status);return r.text();})
          .then(function(text){var data;try{data=JSON.parse(text);}catch(ignore){throw new Error('tile response is not JSON');}if(!data||!data.tile)throw new Error('empty tile');var image=new Image();image.onload=function(){if(request.epoch===tileEpoch&&request.world===st.world){st.tiles[request.key]=image;draw();}};image.onerror=function(){if(request.epoch===tileEpoch&&request.world===st.world){st.tiles[request.key]='failed';draw();}};image.src='data:image/png;base64,'+data.tile;})
          .catch(function(){if(request.epoch===tileEpoch&&request.world===st.world){st.tiles[request.key]='failed';tileFailures++;if(tileFailures===1)setStatus('部分地形瓦片暂不可用，其他区域继续加载');draw();}})
          .then(function(){tileActive=Math.max(0,tileActive-1);pumpTiles();});
      })(item);
    }
  }
  function loadTile(key,zoom,tx,tz){
    if(st.tiles[key])return;
    st.tiles[key]='queued';
    tileQueue.push({key:key,zoom:zoom,tx:tx,tz:tz,world:st.world,epoch:tileEpoch});
    pumpTiles();
  }
  function drawTiles(s){
    if(!st.tilesEnabled)return;
    var zoom=tileZoom(),span=16*zoom,a=world(0,0),b=world(s.w,s.h),minX=Math.floor(Math.min(a.x,b.x)/span)-1,maxX=Math.floor(Math.max(a.x,b.x)/span)+1,minZ=Math.floor(Math.min(a.z,b.z)/span)-1,maxZ=Math.floor(Math.max(a.z,b.z)/span)+1,tileSize=span*st.scale,needed={},missing=[];
    for(var tz=minZ;tz<=maxZ;tz++)for(var tx=minX;tx<=maxX;tx++){
      var key=st.world+':'+zoom+':'+tx+':'+tz,img=st.tiles[key],p=point(tx*span,tz*span);
      needed[key]=true;
      if(img&&img!=='queued'&&img!=='loading'&&img!=='failed'){ctx.save();ctx.filter='saturate(0.55) brightness(0.6) contrast(1.05)';ctx.drawImage(img,p.x,p.y,tileSize,tileSize);ctx.restore();}
      else{ctx.fillStyle=(img==='queued'||img==='loading')?'#101827':'#0b1420';ctx.fillRect(p.x,p.y,tileSize,tileSize);}
      if(!img)missing.push({key:key,zoom:zoom,tx:tx,tz:tz,distance:Math.abs(p.x-s.w/2)+Math.abs(p.y-s.h/2)});
    }
    tileQueue=tileQueue.filter(function(item){if(item.epoch===tileEpoch&&needed[item.key])return true;if(st.tiles[item.key]==='queued')delete st.tiles[item.key];return false;});
    missing.sort(function(left,right){return left.distance-right.distance;}).forEach(function(item){loadTile(item.key,item.zoom,item.tx,item.tz);});
    ctx.fillStyle='rgba(6,10,22,0.45)';ctx.fillRect(0,0,s.w,s.h);
  }
  function clipped(text,maxWidth){text=String(text||'');if(ctx.measureText(text).width<=maxWidth)return text;while(text.length>1&&ctx.measureText(text+'…').width>maxWidth)text=text.slice(0,-1);return text+'…';}
  function drawZone(z,labels){var q=bounds(z),p=point(q.x,q.z),w=q.w*st.scale,h=q.h*st.scale,c=zoneColor(z.type),sale=z.status==='FOR_SALE',selected=String(z.id)===String(st.selectedZoneId),radius=Math.min(12,Math.max(0,Math.min(w,h)*.12));if(w<1||h<1)return;ctx.save();if(!labels){var fill=ctx.createLinearGradient(p.x,p.y,p.x,p.y+h);fill.addColorStop(0,colorAlpha(c,sale?.42:.16));fill.addColorStop(1,colorAlpha(c,sale?.14:.055));roundedRect(p.x,p.y,w,h,radius);ctx.fillStyle=fill;ctx.fill();roundedRect(p.x+.5,p.y+.5,Math.max(0,w-1),Math.max(0,h-1),radius);ctx.globalAlpha=selected?1:(sale?.98:.48);ctx.strokeStyle=c;ctx.lineWidth=selected?3:(sale?2:1);ctx.setLineDash(sale?[]:[6,5]);ctx.shadowColor=c;ctx.shadowBlur=sale?18:8;ctx.stroke();ctx.setLineDash([]);}else if(w>44&&h>24){ctx.globalAlpha=1;ctx.textAlign='center';ctx.textBaseline='middle';ctx.font='700 15px Rajdhani,Arial,sans-serif';ctx.fillStyle='#F8FCFF';ctx.shadowColor=c;ctx.shadowBlur=8;ctx.fillText(clipped(z.name||z.id,Math.max(28,w-14)),p.x+w/2,p.y+h/2-(h>48?8:0));if(h>48){ctx.shadowBlur=0;ctx.font='10px Roboto Mono,monospace';ctx.fillStyle=c;ctx.fillText(sale?'FOR SALE':String(z.status||z.type||'ZONE'),p.x+w/2,p.y+h/2+12);}}ctx.restore();}
  function drawPlot(v){var q=bounds(v),p=point(q.x,q.z),w=Math.max(1,q.w*st.scale),h=Math.max(1,q.h*st.scale),c=v.ownerType==='ENTERPRISE'?'#F07AD7':'#BFA7FF';ctx.save();ctx.fillStyle=c;ctx.globalAlpha=.14;ctx.fillRect(p.x,p.y,w,h);ctx.globalAlpha=.9;ctx.strokeStyle=c;ctx.lineWidth=1.2;ctx.setLineDash([5,4]);ctx.strokeRect(p.x+.5,p.y+.5,Math.max(0,w-1),Math.max(0,h-1));ctx.restore();}
  function drawSelection(){if(!st.sel)return;var q=bounds(st.sel),p=point(q.x,q.z),w=q.w*st.scale,h=q.h*st.scale;ctx.save();ctx.fillStyle='rgba(241,201,106,.14)';ctx.fillRect(p.x,p.y,w,h);ctx.strokeStyle='#F1C96A';ctx.lineWidth=2;ctx.setLineDash([7,4]);ctx.strokeRect(p.x+.5,p.y+.5,Math.max(0,w-1),Math.max(0,h-1));ctx.restore();}
  function draw(){var s=size();ctx.clearRect(0,0,s.w,s.h);ctx.fillStyle='#050914';ctx.fillRect(0,0,s.w,s.h);drawTiles(s);drawGrid(s);var zones=st.zones.filter(inWorld);zones.forEach(function(z){drawZone(z,false);});st.plots.filter(inWorld).forEach(drawPlot);zones.forEach(function(z){drawZone(z,true);});drawSelection();}
  function resize(){var s=size(),d=Math.min(window.devicePixelRatio||1,2);canvas.width=Math.round(s.w*d);canvas.height=Math.round(s.h*d);ctx.setTransform(d,0,0,d,0,0);draw();}
  function contains(q,w){return w.x>=q.x&&w.x<=q.x+q.w&&w.z>=q.z&&w.z<=q.z+q.h;}
  function hit(x,y){var w=world(x,y),plots=st.plots.filter(inWorld).slice().reverse();for(var i=0;i<plots.length;i++)if(contains(bounds(plots[i]),w))return {kind:'plot',value:plots[i]};var zones=st.zones.filter(inWorld).slice().reverse();for(var j=0;j<zones.length;j++)if(contains(bounds(zones[j]),w))return {kind:'zone',value:zones[j]};return null;}
  function setCursor(found){if(st.selecting||st.mode==='select'){canvas.style.cursor='crosshair';return;}if(st.dragging){canvas.style.cursor='grabbing';return;}canvas.style.cursor=found?'pointer':'grab';}
  function zoomAt(factor,x,y){var s=size();x=x==null?s.w/2:x;y=y==null?s.h/2:y;var before=world(x,y);st.scale=Math.max(.02,Math.min(24,st.scale*factor));st.ox=x-before.x*st.scale;st.oz=y-before.z*st.scale;draw();}
  canvas.addEventListener('pointerdown',function(e){if(e.button!==0)return;var m=mouse(e),w=world(m.x,m.y);st.pointerId=e.pointerId;st.last=m;st.moved=false;try{canvas.setPointerCapture(e.pointerId);}catch(ignore){}if(st.mode==='select'||e.shiftKey){st.selecting=true;st.start={x:Math.round(w.x),z:Math.round(w.z)};st.sel={x1:st.start.x,z1:st.start.z,x2:st.start.x,z2:st.start.z};}else{st.dragging=true;}setCursor();draw();});
  canvas.addEventListener('pointermove',function(e){var m=mouse(e),w=world(m.x,m.y),coord=document.getElementById(opts.coordId);if(coord)coord.textContent='X '+Math.round(w.x)+' · Z '+Math.round(w.z);if(st.selecting){st.moved=st.moved||Math.abs(m.x-st.last.x)>3||Math.abs(m.y-st.last.y)>3;st.sel={x1:st.start.x,z1:st.start.z,x2:Math.round(w.x),z2:Math.round(w.z)};draw();}else if(st.dragging){st.moved=st.moved||Math.abs(m.x-st.last.x)>3||Math.abs(m.y-st.last.y)>3;st.ox+=m.x-st.last.x;st.oz+=m.y-st.last.y;st.last=m;draw();}else setCursor(hit(m.x,m.y));});
  function endPointer(e){if(st.pointerId!=null&&e.pointerId!==st.pointerId)return;var m=mouse(e);if(st.selecting){st.selecting=false;if(st.moved&&st.sel&&opts.onSelect){var x1=Math.min(st.sel.x1,st.sel.x2),x2=Math.max(st.sel.x1,st.sel.x2),z1=Math.min(st.sel.z1,st.sel.z2),z2=Math.max(st.sel.z1,st.sel.z2);opts.onSelect({world:st.world,x1:x1,z1:z1,x2:x2,z2:z2});}else st.sel=null;}else if(st.dragging){st.dragging=false;if(!st.moved&&opts.onEntityClick){var found=hit(m.x,m.y);if(found)opts.onEntityClick(found);}}st.pointerId=null;setCursor(hit(m.x,m.y));draw();}
  canvas.addEventListener('pointerup',endPointer);canvas.addEventListener('pointercancel',endPointer);
  canvas.addEventListener('wheel',function(e){e.preventDefault();var m=mouse(e);zoomAt(e.deltaY<0?1.16:.86,m.x,m.y);},{passive:false});
  if(window.ResizeObserver)new ResizeObserver(resize).observe(canvas);setTimeout(resize,40);window.addEventListener('resize',resize);
  return {setZones:function(v){st.zones=v||[];draw();},setPlots:function(v){st.plots=v||[];draw();},setWorld:function(v){if(v&&v!==st.world){resetTileRequests();st.world=v;st.tiles={};st.tilesEnabled=st.terrain;if(st.terrain)setStatus('正在加载 ksHWP 地形');}draw();},setMode:function(v){st.mode=v==='select'?'select':'pan';canvas.classList.toggle('select-mode',st.mode==='select');setCursor();},setSelected:function(id){st.selectedZoneId=id||null;draw();},setSelection:function(v){st.sel=v||null;draw();},focus:function(v){if(v){fitEntity(v);draw();}},zoomIn:function(){zoomAt(1.16);},zoomOut:function(){zoomAt(.86);},center:fit,resize:resize};
}
function KsDistrictMap(canvasId,opts){opts=opts||{};opts.terrain=false;return KsMapEngine(canvasId,opts);}
function drawDistrictFocus(canvasId,v){
  var c=document.getElementById(canvasId);if(!c)return;var d=Math.min(window.devicePixelRatio||1,2),w=c.clientWidth||520,h=c.clientHeight||220,x=c.getContext('2d');c.width=w*d;c.height=h*d;x.setTransform(d,0,0,d,0,0);x.fillStyle='#080D16';x.fillRect(0,0,w,h);x.strokeStyle='rgba(112,154,194,.12)';for(var g=16;g<w;g+=16){x.beginPath();x.moveTo(g,0);x.lineTo(g,h);x.stroke();}for(var gy=16;gy<h;gy+=16){x.beginPath();x.moveTo(0,gy);x.lineTo(w,gy);x.stroke();}var x1=Math.min(Number(v.x1)||0,Number(v.x2)||0),z1=Math.min(Number(v.z1)||0,Number(v.z2)||0),ww=Math.max(1,Math.abs((Number(v.x2)||0)-(Number(v.x1)||0))),hh=Math.max(1,Math.abs((Number(v.z2)||0)-(Number(v.z1)||0))),scale=Math.min((w-68)/ww,(h-68)/hh),rw=Math.max(48,ww*scale),rh=Math.max(48,hh*scale),left=(w-rw)/2,top=(h-rh)/2,col={RESIDENTIAL:'#00D9FF',COMMERCIAL:'#FF4FD8',INDUSTRIAL:'#FFC857',AGRICULTURAL:'#5BE38B'}[v.type]||'#B18CFF';x.fillStyle=col+'35';x.fillRect(left,top,rw,rh);x.strokeStyle=col;x.lineWidth=2;x.strokeRect(left,top,rw,rh);x.fillStyle='#F1FAFF';x.font='600 14px Rajdhani,Arial';x.fillText(String(v.name||v.id||'PLOT'),left+12,top+24);x.fillStyle=col;x.font='11px Roboto Mono,monospace';x.fillText('['+x1+', '+z1+']  '+ww+' x '+hh,left+12,top+43);
}
var RE_ZONE_META={RESIDENTIAL:{label:'住宅',color:'#55C7FF'},COMMERCIAL:{label:'商业',color:'#EF72D2'},INDUSTRIAL:{label:'工业',color:'#E7BC63'},AGRICULTURAL:{label:'农业',color:'#5BD18B'}};
function reZoneMeta(type){return RE_ZONE_META[type]||{label:type||'未分类',color:'#8FA6C7'};}
function reStatusLabel(status){return {FOR_SALE:'可购',STATE_OWNED:'国有',CLOSED:'关闭'}[status]||status||'未知';}
function reZoneArea(v){return (Math.abs((Number(v.x2)||0)-(Number(v.x1)||0))+1)*(Math.abs((Number(v.z2)||0)-(Number(v.z1)||0))+1);}
function reWorldZones(){return reMapState.zones.filter(function(z){return String(z.world||'world')===String(reMapState.world);});}
function reWorldPlots(){return reMapState.plots.filter(function(p){return String(p.world||'world')===String(reMapState.world);});}
function reSelectedZone(){return reMapState.zones.find(function(z){return String(z.id)===String(reMapState.selectedZoneId);})||null;}
function openBrowseDistrictEntity(item){if(item.kind==='zone'){selectReZone(item.value.id,false);openZoneVoxelViewer(item.value);return;}openRePlotData(item.value);}
function openRePlotData(p){var canvas='<canvas id="districtFocusMap" class="district-focus-map"></canvas>',owner=p.ownerType==='ENTERPRISE'?'企业登记':'个人登记';window.rePreviewPlot=p;showModal('登记地块 // '+escapeHtml(p.id||'—'),canvas+'<div class="hub-feed"><div class="hub-feed-item"><span>所属区域</span><b>'+escapeHtml(p.zoneId||'—')+'</b></div><div class="hub-feed-item"><span>登记类型</span><b>'+owner+'</b></div><div class="hub-feed-item"><span>范围</span><b>['+Number(p.x1)+','+Number(p.z1)+'] - ['+Number(p.x2)+','+Number(p.z2)+']</b></div><div class="hub-feed-item"><span>登记价格</span><b>'+fmt(p.price||0)+'</b></div></div><div style="margin-top:12px;text-align:right;"><button class="btn btn-primary" type="button" onclick="openPlotVoxelViewer()">进入地块 3D</button></div>');setTimeout(function(){drawDistrictFocus('districtFocusMap',Object.assign({},p,{type:p.zoneType}));},0);}
function openPlotVoxelViewer(){var p=window.rePreviewPlot;if(!p){toast('请先选择一个地块','err');return;}openRegionVoxelViewer({world:p.world||reMapState.world,x1:Math.min(Number(p.x1),Number(p.x2)),z1:Math.min(Number(p.z1),Number(p.z2)),x2:Math.max(Number(p.x1),Number(p.x2)),z2:Math.max(Number(p.z1),Number(p.z2))});}
function openEmpirePlotData(p){var canvas='<canvas id="districtFocusMap" class="district-focus-map"></canvas>';window.rePreviewPlot=p;showModal('我的地块 // '+escapeHtml(p.id||p.plotId||'—'),canvas+'<div class="hub-feed"><div class="hub-feed-item"><span>区域</span><b>'+escapeHtml(p.zoneId||'—')+'</b></div><div class="hub-feed-item"><span>范围</span><b>['+p.x1+','+p.z1+'] - ['+p.x2+','+p.z2+']</b></div><div class="hub-feed-item"><span>购入价</span><b>'+fmt(p.price||0)+'</b></div><div class="hub-feed-item"><span>副本权限</span><b>'+escapeHtml(p.dungeonTemplateId||'—')+'</b></div></div><div style="margin-top:12px;text-align:right;"><button class="btn btn-primary" type="button" onclick="openPlotVoxelViewer()">进入地块 3D</button></div>');setTimeout(function(){drawDistrictFocus('districtFocusMap',Object.assign({},p,{type:p.zoneType}));},0);}
var reBrowseMap=null,lastMapSelect=null,lastMap3dSelect=null,reActivePurchaseZone=null;
var reMapState={zones:[],plots:[],world:'world',worldMeta:{},selectedZoneId:null,loaded:false,moduleLoaded:false};
function initBrowseMap(){
  if(reBrowseMap){reBrowseMap.resize();return;}
  reBrowseMap=KsMapEngine('reBrowseCanvas',{
    statusId:'reBrowseStatus', coordId:'reBrowseCoord',
    onEntityClick:openBrowseDistrictEntity,
    onSelect:applyReMapSelection
  });
  reBrowseMap.setMode('pan');
}
function setReMapMode(mode){
  mode=mode==='select'?'select':'pan';
  document.querySelectorAll('[data-re-map-mode]').forEach(function(b){b.classList.toggle('active',b.getAttribute('data-re-map-mode')===mode);});
  if(reBrowseMap)reBrowseMap.setMode(mode);
}
function reSelectionZone(s){
  var matches=reWorldZones().filter(function(z){var x1=Math.min(Number(z.x1),Number(z.x2)),x2=Math.max(Number(z.x1),Number(z.x2)),z1=Math.min(Number(z.z1),Number(z.z2)),z2=Math.max(Number(z.z1),Number(z.z2));return z.status==='FOR_SALE'&&s.x1>=x1&&s.x2<=x2&&s.z1>=z1&&s.z2<=z2;});
  matches.sort(function(a,b){return reZoneArea(a)-reZoneArea(b);});return matches[0]||null;
}
function reSelectionOverlapsPlot(s){return reWorldPlots().some(function(p){var x1=Math.min(Number(p.x1),Number(p.x2)),x2=Math.max(Number(p.x1),Number(p.x2)),z1=Math.min(Number(p.z1),Number(p.z2)),z2=Math.max(Number(p.z1),Number(p.z2));return s.x1<=x2&&s.x2>=x1&&s.z1<=z2&&s.z2>=z1;});}
function reZoneFullyOccupied(z){var zx1=Math.min(Number(z.x1),Number(z.x2)),zx2=Math.max(Number(z.x1),Number(z.x2)),zz1=Math.min(Number(z.z1),Number(z.z2)),zz2=Math.max(Number(z.z1),Number(z.z2));return reWorldPlots().some(function(p){if(String(p.zoneId)!==String(z.id))return false;var x1=Math.min(Number(p.x1),Number(p.x2)),x2=Math.max(Number(p.x1),Number(p.x2)),z1=Math.min(Number(p.z1),Number(p.z2)),z2=Math.max(Number(p.z1),Number(p.z2));return x1<=zx1&&x2>=zx2&&z1<=zz1&&z2>=zz2;});}
function renderReSelection(valid,message){var el=document.getElementById('reMapSelectionText');if(!el)return;el.textContent=message;el.classList.toggle('invalid',valid===false);}
function setReMap3dEnabled(selection){var button=document.getElementById('reMap3dBtn');if(!button)return;var width=selection?selection.x2-selection.x1+1:0,depth=selection?selection.z2-selection.z1+1:0,valid=selection&&width<=KS_REGION_3D_MAX_SPAN&&depth<=KS_REGION_3D_MAX_SPAN;button.disabled=!valid;button.title=valid?'查看所选区域建筑 3D':(selection?'当前 '+width+' × '+depth+'，上限 '+KS_REGION_3D_MAX_SPAN+' × '+KS_REGION_3D_MAX_SPAN:'请框选不超过 '+KS_REGION_3D_MAX_SPAN+' × '+KS_REGION_3D_MAX_SPAN+' 的区域');}
function applyReMapSelection(s){
  var width=s.x2-s.x1+1,depth=s.z2-s.z1+1;
  if(width>KS_REGION_3D_MAX_SPAN||depth>KS_REGION_3D_MAX_SPAN){lastMapSelect=null;lastMap3dSelect=null;setReMap3dEnabled(s);renderReSelection(false,'已框选 '+width+' × '+depth+' 方块，超过 '+KS_REGION_3D_MAX_SPAN+' × '+KS_REGION_3D_MAX_SPAN+' 上限');toast('当前 '+width+' × '+depth+'，请缩小到 '+KS_REGION_3D_MAX_SPAN+' × '+KS_REGION_3D_MAX_SPAN+' 内','err');return;}
  lastMap3dSelect=Object.assign({},s);setReMap3dEnabled(lastMap3dSelect);
  var zone=reSelectionZone(s);
  if(!zone){lastMapSelect=null;renderReSelection(true,'已选 '+width+' × '+depth+' 方块 · 可查看 3D（购地需落在单一可购区域）');return;}
  if(reSelectionOverlapsPlot(s)){lastMapSelect=null;renderReSelection(true,'已选 '+width+' × '+depth+' 方块 · 可查看 3D（范围与已登记地块重叠，不能购地）');return;}
  lastMapSelect=Object.assign({},s,{zoneId:zone.id});
  selectReZone(zone.id,false,true);
  renderReSelection(true,'已选 '+width+' × '+depth+' 方块 · '+(zone.name||zone.id));
  var x1=document.getElementById('bpX1');
  if(x1&&reActivePurchaseZone&&String(reActivePurchaseZone.id)===String(zone.id)){
    x1.value=s.x1;document.getElementById('bpZ1').value=s.z1;document.getElementById('bpX2').value=s.x2;document.getElementById('bpZ2').value=s.z2;
    toast('购买范围已更新','ok');
  }
}
function setReWorld(world){
  if(!world)return;reMapState.world=world;lastMapSelect=null;lastMap3dSelect=null;setReMap3dEnabled(null);
  var zones=reWorldZones(),selected=zones.find(function(z){return z.status==='FOR_SALE';})||zones[0]||null;
  reMapState.selectedZoneId=selected?selected.id:null;
  if(reBrowseMap){reBrowseMap.setWorld(world);reBrowseMap.setSelection(null);reBrowseMap.setSelected(reMapState.selectedZoneId);reBrowseMap.center();}
  renderReSelection(true,'未框选购买范围');renderReWorkspace();updateReMapStatus();
}
function selectReZone(id,focus,preserveSelection){
  var zone=reMapState.zones.find(function(z){return String(z.id)===String(id)&&String(z.world||'world')===String(reMapState.world);});if(!zone)return;
  if(!preserveSelection&&(!lastMapSelect||String(lastMapSelect.zoneId)!==String(id))){lastMapSelect=null;lastMap3dSelect=null;setReMap3dEnabled(null);if(reBrowseMap)reBrowseMap.setSelection(null);renderReSelection(true,'未框选购买范围');}
  reMapState.selectedZoneId=zone.id;if(reBrowseMap){reBrowseMap.setSelected(zone.id);if(focus)reBrowseMap.focus(zone);}renderReWorkspace();
}
function reCapacityText(z){if(z.type!=='RESIDENTIAL')return '不适用';var max=Number(z.maxPlots)||0;if(max<=0)return '不设上限';var count=z.houseCount==null?'—':Number(z.houseCount);return count+' / '+max;}
function renderReKpis(zones,plots){var sale=zones.filter(function(z){return z.status==='FOR_SALE';}),value=sale.reduce(function(n,z){return n+(Number(z.basePrice)||0);},0);document.getElementById('reMapKpis').innerHTML='<div class="re-map-kpi"><span>当前世界区域</span><b class="cyan">'+fmt(zones.length)+'</b></div><div class="re-map-kpi"><span>可购区域</span><b class="green">'+fmt(sale.length)+'</b></div><div class="re-map-kpi"><span>已登记地块</span><b class="magenta">'+fmt(plots.length)+'</b></div><div class="re-map-kpi"><span>在售底价合计</span><b class="amber">'+fmt(value)+'</b></div>';}
function renderReZoneDetail(zone){
  var el=document.getElementById('reZoneDetail');if(!zone){el.innerHTML='<span class="eyebrow">SELECTED DISTRICT</span><h3>当前世界没有区域</h3>';return;}
  var meta=reZoneMeta(zone.type),sale=zone.status==='FOR_SALE',occupied=reZoneFullyOccupied(zone),actionable=sale&&!occupied,area=reZoneArea(zone),status=reStatusLabel(zone.status)+(occupied?' · 已占用':'');
  el.innerHTML='<span class="eyebrow">SELECTED DISTRICT · '+escapeHtml(zone.id)+'</span><h3>'+escapeHtml(zone.name||zone.id)+'</h3><span class="re-map-type-tag"><i class="re-map-swatch" style="color:'+meta.color+'"></i>'+escapeHtml(meta.label)+' · '+escapeHtml(status)+'</span><div class="re-map-detail-grid"><div><small>区域底价</small><b>'+fmt(zone.basePrice||0)+'</b></div><div><small>年税率</small><b>'+pct(zone.taxRate||0)+'</b></div><div><small>坐标面积</small><b>'+fmt(area)+' 方块</b></div><div><small>登记地块</small><b>'+fmt(zone.plotCount||0)+'</b></div><div><small>房屋容量</small><b>'+reCapacityText(zone)+'</b></div><div><small>副本权限</small><b>'+escapeHtml(zone.dungeonTemplateId||'无')+'</b></div></div><button class="btn btn-primary re-map-buy" type="button" data-zone-id="'+escapeAttr(zone.id)+'" onclick="openZoneVoxelViewerById(this.dataset.zoneId)"><i data-lucide="building-2"></i><span>进入城区售楼沙盘</span></button><button class="btn re-map-buy" type="button" style="margin-top:7px" onclick="buyReSelectedZone()"'+(actionable?'':' disabled')+'><i data-lucide="'+(actionable?'scan-line':'lock')+'"></i><span>'+(occupied?'区域已占用':(sale?'选择范围并购入':'当前不可购'))+'</span></button>';
  if(window.lucide&&window.lucide.createIcons)window.lucide.createIcons({attrs:{'stroke-width':1.8}});
}
function renderRePriceBars(zones){var el=document.getElementById('rePriceBars');if(!zones.length){el.innerHTML='<span style="color:#7895A9;font-size:10px;">暂无数据</span>';return;}var max=Math.max.apply(null,zones.map(function(z){return Number(z.basePrice)||0;}));el.innerHTML=zones.map(function(z){var h=max>0?Math.round(12+(Number(z.basePrice)||0)/max*50):12;return '<i class="re-price-bar'+(String(z.id)===String(reMapState.selectedZoneId)?' selected':'')+'" style="height:'+h+'px" title="'+escapeAttr(z.name||z.id)+' · '+escapeAttr(fmt(z.basePrice||0))+'"></i>';}).join('');}
function renderReZoneList(zones){var sorted=zones.slice().sort(function(a,b){if((a.status==='FOR_SALE')!==(b.status==='FOR_SALE'))return a.status==='FOR_SALE'?-1:1;return String(a.name||a.id).localeCompare(String(b.name||b.id),'zh-CN');});document.getElementById('reZoneListCount').textContent=zones.length+' ZONES';document.getElementById('reZoneList').innerHTML=sorted.map(function(z){var m=reZoneMeta(z.type);return '<button class="re-zone-row'+(String(z.id)===String(reMapState.selectedZoneId)?' active':'')+'" type="button" data-zone-id="'+escapeAttr(z.id)+'" onclick="selectReZone(this.dataset.zoneId,true)" style="--zone-color:'+m.color+'"><i class="dot"></i><span><b>'+escapeHtml(z.name||z.id)+'</b><small>'+escapeHtml(m.label)+' · '+escapeHtml(reStatusLabel(z.status))+'</small></span><em>'+fmt(z.basePrice||0)+'</em></button>';}).join('')||'<div style="padding:18px 8px;color:#7895A9;font-size:10px;">当前世界暂无区域</div>';}
function renderReTable(zones){var sale=zones.filter(function(z){return z.status==='FOR_SALE';}),t='';sale.forEach(function(z){var m=reZoneMeta(z.type),range='['+Number(z.x1)+', '+Number(z.z1)+'] – ['+Number(z.x2)+', '+Number(z.z2)+']',occupied=reZoneFullyOccupied(z);t+='<tr><td><b>'+escapeHtml(z.name||z.id)+'</b><br><small style="color:#7895A9">'+escapeHtml(z.id)+'</small></td><td><span style="color:'+m.color+'">'+escapeHtml(m.label)+'</span></td><td>'+fmt(z.basePrice||0)+'</td><td>'+pct(z.taxRate||0)+'</td><td>'+fmt(z.plotCount||0)+'</td><td>'+reCapacityText(z)+'</td><td style="font-size:10px;white-space:nowrap">'+range+'</td><td>'+escapeHtml(z.dungeonTemplateId||'无')+'</td><td><button class="btn btn-sm" type="button" data-zone-id="'+escapeAttr(z.id)+'" onclick="selectReZone(this.dataset.zoneId,true);buyReSelectedZone()"'+(occupied?' disabled':'')+'>'+(occupied?'已占用':'购入')+'</button></td></tr>';});document.getElementById('reBrowseBody').innerHTML=t||'<tr><td colspan="9" style="color:#7895A9;">当前世界暂无可购区域</td></tr>';document.getElementById('reBrowseTableMeta').textContent=sale.length+' 条记录';}
function renderReWorkspace(){var zones=reWorldZones(),plots=reWorldPlots();renderReKpis(zones,plots);renderReZoneDetail(reSelectedZone());renderRePriceBars(zones);renderReZoneList(zones);renderReTable(zones);document.getElementById('reMapEmpty').classList.toggle('show',zones.length===0&&plots.length===0);}
function updateReMapStatus(){var el=document.getElementById('reBrowseStatus'),zones=reWorldZones(),plots=reWorldPlots();if(el)el.textContent=reMapState.world+' · '+zones.length+' 区域 · '+plots.length+' 地块';}
function buyReSelectedZone(){var z=reSelectedZone();if(!z||z.status!=='FOR_SALE')return;if(reZoneFullyOccupied(z)){toast('该区域范围已被登记地块占用','err');return;}var zonePlots=reWorldPlots().filter(function(p){return String(p.zoneId)===String(z.id);});if(zonePlots.length&&(!lastMapSelect||String(lastMapSelect.zoneId)!==String(z.id))){setReMapMode('select');toast('该区域已有地块，请先框选未占用范围','info');return;}openBuyPlot(z.id,z.x1,z.z1,z.x2,z.z2,z.basePrice);}
async function loadReBrowse(forceCenter){
  initBrowseMap();
  document.getElementById('reModuleHint2').textContent='SYNCING REAL DATA';
  document.getElementById('reBrowseStatus').textContent='正在同步区域数据';
  var result=await Promise.all([
    api('GET','/api/realestate/zones'),
    api('GET','/api/realestate/plots'),
    fetch('/kSHWP/api/worlds').then(function(r){return r.ok?r.json():{};}).catch(function(){return {};})
  ]),d=result[0]||{},pd=result[1]||{},wd=result[2]||{};
  if(d.error){reMapState.zones=[];reMapState.plots=[];reMapState.moduleLoaded=false;document.getElementById('reModuleHint2').textContent='SYNC FAILED';reBrowseMap.setZones([]);reBrowseMap.setPlots([]);renderReWorkspace();document.getElementById('reBrowseStatus').textContent='区域数据同步失败';return;}
  if(d.moduleLoaded===false){reMapState.zones=[];reMapState.plots=[];reMapState.moduleLoaded=false;document.getElementById('reModuleHint2').textContent='MODULE OFFLINE';reBrowseMap.setZones([]);reBrowseMap.setPlots([]);renderReWorkspace();document.getElementById('reBrowseStatus').textContent='房地产模块未安装';return;}
  reMapState.moduleLoaded=true;reMapState.zones=Array.isArray(d.zones)?d.zones:[];reMapState.plots=Array.isArray(pd.plots)?pd.plots:[];
  var worlds=[],worldMeta={};
  if(Array.isArray(wd.worlds))wd.worlds.forEach(function(v){
    var w=String(v&&v.name||'').trim();if(!w)return;
    if(worlds.indexOf(w)<0)worlds.push(w);
    worldMeta[w]=v||{};
  });
  reMapState.zones.concat(reMapState.plots).forEach(function(v){
    var w=String(v.world||'world');if(worlds.indexOf(w)<0)worlds.push(w);
  });
  worlds.sort();if(!worlds.length)worlds=['world'];reMapState.worldMeta=worldMeta;
  var previousWorld=reMapState.world;if(worlds.indexOf(reMapState.world)<0)reMapState.world=worlds[0];
  var select=document.getElementById('reWorldSelect');select.innerHTML=worlds.map(function(w){
    var meta=worldMeta[w]||{},dimension=String(meta.dimension||'').trim(),label=dimension?w+' · '+dimension:w;
    return '<option value="'+escapeAttr(w)+'"'+(w===reMapState.world?' selected':'')+'>'+escapeHtml(label)+'</option>';
  }).join('');
  var worldZones=reWorldZones();if(!worldZones.some(function(z){return String(z.id)===String(reMapState.selectedZoneId);})){var first=worldZones.find(function(z){return z.status==='FOR_SALE';})||worldZones[0]||null;reMapState.selectedZoneId=first?first.id:null;}
  reBrowseMap.setZones(reMapState.zones);reBrowseMap.setPlots(reMapState.plots);reBrowseMap.setWorld(reMapState.world);reBrowseMap.setSelected(reMapState.selectedZoneId);reBrowseMap.setSelection(lastMap3dSelect);if(forceCenter||!reMapState.loaded||previousWorld!==reMapState.world)reBrowseMap.center();
  reMapState.loaded=true;document.getElementById('reModuleHint2').textContent='LIVE REGION DATA';updateReMapStatus();renderReWorkspace();
}
async function openBuyPlot(zoneId,x1,z1,x2,z2,price){
  if(!myEnterprises.length)await loadMyEnterprises();
  var zone=reMapState.zones.find(function(z){return String(z.id)===String(zoneId);})||{id:zoneId,world:reMapState.world,x1:Number(x1),z1:Number(z1),x2:Number(x2),z2:Number(z2),basePrice:Number(price)||0};
  reActivePurchaseZone=zone;
  var s=lastMapSelect&&String(lastMapSelect.zoneId)===String(zoneId)&&String(lastMapSelect.world)===String(zone.world||reMapState.world)?lastMapSelect:null;
  var dx1=s?s.x1:x1, dz1=s?s.z1:z1, dx2=s?s.x2:x2, dz2=s?s.z2:z2;
  var body='<p>区域: <b>'+escapeHtml(zone.name||zoneId)+'</b> <span style="color:#7895A9">'+escapeHtml(zoneId)+'</span></p>';
  body+='<p>区域底价: <b style="color:#64D8FF;">'+fmt(price)+'</b> 金币</p>';
  if(s)body+='<p style="font-size:11px;color:#67D99A;">已套用地图框选范围</p>';
  body+='<div class="form-row">';
  body+='<label>买家身份<br><select id="bpBuyerType" onchange="document.getElementById(\'bpEntWrap\').style.display=this.value===\'ENTERPRISE\'?\'\':\'none\';">';
  body+='<option value="PLAYER">👤 个人</option><option value="ENTERPRISE">🏢 企业（公户扣款）</option>';
  body+='</select></label>';
  body+='</div>';
  var entOpts=myEnterprises.map(function(e){return '<option value="'+escapeAttr(e.id)+'">'+escapeHtml(e.name)+' ('+escapeHtml(e.id)+')</option>';}).join('');
  body+='<div class="form-row" id="bpEntWrap" style="display:none;">';
  body+='<label>选择企业<br><select id="bpEntId">'+(entOpts||'<option value="">暂无企业</option>')+'</select></label>';
  body+='</div>';
  body+='<div class="form-row">';
  body+='<label>子区域 X1<br><input id="bpX1" type="number" step="1" value="'+Number(dx1)+'"/></label>';
  body+='<label>子区域 Z1<br><input id="bpZ1" type="number" step="1" value="'+Number(dz1)+'"/></label>';
  body+='<label>子区域 X2<br><input id="bpX2" type="number" step="1" value="'+Number(dx2)+'"/></label>';
  body+='<label>子区域 Z2<br><input id="bpZ2" type="number" step="1" value="'+Number(dz2)+'"/></label>';
  body+='</div>';
  body+='<div style="margin-top:10px;"><button class="btn" id="bpSubmit" data-zone-id="'+escapeAttr(zoneId)+'" onclick="submitBuyPlot(this.dataset.zoneId)">确认购入</button> ';
  body+='<button class="btn btn-sm" onclick="closeModal()">取消</button></div>';
  body+='<div id="bpResult" style="margin-top:8px;"></div>';
  showModal('🏞 购入地块',body);
}
async function submitBuyPlot(zoneId){
  var buyerType=document.getElementById('bpBuyerType').value;
  if(buyerType==='ENTERPRISE'&&!document.getElementById('bpEntId').value){toast('请先创建/选择企业','err');return;}
  var coords=['bpX1','bpZ1','bpX2','bpZ2'].map(function(id){return Number(document.getElementById(id).value);});
  if(coords.some(function(v){return !Number.isFinite(v)||!Number.isInteger(v);})){toast('坐标必须是整数','err');return;}
  var minX=Math.min(coords[0],coords[2]),maxX=Math.max(coords[0],coords[2]),minZ=Math.min(coords[1],coords[3]),maxZ=Math.max(coords[1],coords[3]);
  var zone=reActivePurchaseZone&&String(reActivePurchaseZone.id)===String(zoneId)?reActivePurchaseZone:null;
  if(zone){var zx1=Math.min(Number(zone.x1),Number(zone.x2)),zx2=Math.max(Number(zone.x1),Number(zone.x2)),zz1=Math.min(Number(zone.z1),Number(zone.z2)),zz2=Math.max(Number(zone.z1),Number(zone.z2));if(minX<zx1||maxX>zx2||minZ<zz1||maxZ>zz2){toast('购买范围超出所选区域','err');return;}}
  var body={
    zoneId:zoneId,
    x1:minX,z1:minZ,x2:maxX,z2:maxZ,
    buyerType:buyerType
  };
  if(buyerType==='ENTERPRISE')body.enterpriseId=document.getElementById('bpEntId').value;
  if(!confirm('确认从'+(buyerType==='ENTERPRISE'?'企业公户':'你的 Vault 余额')+'扣款购入？'))return;
  var submit=document.getElementById('bpSubmit');if(submit)submit.disabled=true;
  var d=await api('POST','/api/realestate/plot/purchase',body);
  var box=document.getElementById('bpResult');
  if(d.plotId){box.innerHTML='<span style="color:#67D99A;">✓ 购入成功: '+escapeHtml(d.plotId)+'</span>';toast('购入成功','ok');lastMapSelect=null;lastMap3dSelect=null;setReMap3dEnabled(null);if(reBrowseMap)reBrowseMap.setSelection(null);renderReSelection(true,'未框选购买范围');await Promise.all([loadMyRe(),loadReBrowse(true)]);}
  else{box.innerHTML='<span style="color:#FF7E8A;">✗ '+escapeHtml(d.error||'失败')+'</span>';toast(d.error||'失败','err');if(submit)submit.disabled=false;}
}

// ====== 政治 / 元老院 ======
// 政治路由在 /ks-Eco/politic/api/*；player 的 api() 会前缀 /ks-Eco，故传 '/politic/api/...'
var myPolitic=null;
var STATUS_CN={PROPOSED:'待表决',SENATE_VOTING:'元老院表决中',TRIBUNE_REVIEW:'保民官审查中',
  APPROVED:'已批准',VETOED:'已否决',SENATE_OVERRIDE:'覆议中',OVERRIDDEN:'覆议通过',
  ENACTED:'已颁布',REJECTED:'已驳回',ABANDONED:'已放弃'};
var OFFICE_CN={CONSUL:'执政官',SENATOR:'元老',TRIBUNE:'保民官',EQUESTRIAN:'骑士',NONE:'平民'};
function statusBadge(s){
  var cls=s==='ENACTED'||s==='OVERRIDDEN'?'badge-active':
          s==='REJECTED'||s==='VETOED'||s==='ABANDONED'?'badge-closed':'badge-pending';
  return '<span class="badge '+cls+'">'+(STATUS_CN[s]||s)+'</span>';
}
async function loadPolitic(){
  var d=await api('GET','/politic/api/my-office');
  myPolitic=d;
  var html='身份: <b style="color:#00E5FF;">'+(OFFICE_CN[d.office]||d.office||'平民')+'</b>';
  var tags=[];
  if(d.isConsul)tags.push('执政官');if(d.isSenator)tags.push('元老');
  if(d.isTribune)tags.push('保民官');if(d.isEquestrian)tags.push('骑士');
  if(tags.length)html+=' &nbsp;<span style="color:#888;font-size:11px;">('+tags.join(' / ')+')</span>';
  html+='<div style="margin-top:6px;font-size:11px;color:#888;">';
  html+='提案权: '+(d.canPropose?'✅':'❌')+' &nbsp; 元老院投票: '+(d.canVoteInSenate?'✅':'❌')+' &nbsp; 保民官否决: '+(d.canVeto?'✅':'❌');
  html+='</div>';
  html+='<div style="margin-top:8px;padding:8px;background:#0d1b2a;border-radius:6px;font-size:11px;color:#9fb;line-height:1.7;">'
      +'<b>📜 立法流程：</b> ① 执政官/骑士 提交提案 → ② 提案人「发起表决」→ ③ 元老/执政官 投票（赞成/反对/弃权）→ ④ 保民官 审查放行或否决 → ⑤ 否决可由元老院全票覆议 → 颁布生效。'
      +(d.office==='NONE'||!d.office?'<br><span style="color:#f96;">你目前是平民，没有提案/表决权。需由管理员任命为元老，或通过选举成为执政官/保民官/骑士。</span>':'')
      +(d.isSenator&&!d.isConsul?'<br><span style="color:#fc6;">你是元老：可在提案进入「元老院表决中」后投票；但提案需由执政官/骑士发起。若执政官空缺，请管理员触发选举。</span>':'')
      +'</div>';
  document.getElementById('politicMyOffice').innerHTML=html;
  document.getElementById('politicProposeCard').style.display=d.canPropose?'block':'none';
  updatePropPayloadHint();
  // 元老院构成
  var od=await api('GET','/politic/api/offices?type=all');
  var groups={CONSUL:[],SENATOR:[],TRIBUNE:[],EQUESTRIAN:[]};
  (od.offices||[]).forEach(function(o){if(groups[o.officeType])groups[o.officeType].push(escapeHtml(o.playerName||o.playerUuid.substr(0,8)));});
  var os='';
  os+='<div>👑 执政官: '+(groups.CONSUL.join('、')||'<span style="color:#666;">空缺</span>')+'</div>';
  os+='<div style="margin-top:4px;">🏛 元老 ('+groups.SENATOR.length+'): '+(groups.SENATOR.join('、')||'<span style="color:#666;">无</span>')+'</div>';
  os+='<div style="margin-top:4px;">🛡 保民官 ('+groups.TRIBUNE.length+'): '+(groups.TRIBUNE.join('、')||'<span style="color:#666;">无</span>')+'</div>';
  os+='<div style="margin-top:4px;">🐎 骑士 ('+groups.EQUESTRIAN.length+'): '+(groups.EQUESTRIAN.join('、')||'<span style="color:#666;">无</span>')+'</div>';
  document.getElementById('politicOffices').innerHTML=os;
  _politicKpi.office=OFFICE_CN[d.office]||d.office||'平民';
  _politicKpi.senators=groups.SENATOR.length;
  ksRenderPoliticKpis();
  loadProposals();
  loadPoliticAnnouncements();
  loadTribuneElection();
}
var _politicKpi={office:'—',senators:'—',voting:'—',electionEnds:'—'};
function ksRenderPoliticKpis(){
  ksKpiRow('politicKpis',[
    {icon:'👤',label:'我的身份',value:String(_politicKpi.office)},
    {icon:'🏛',label:'元老席位',value:String(_politicKpi.senators)},
    {icon:'🗳',label:'表决中提案',value:String(_politicKpi.voting),accent:_politicKpi.voting>0?'var(--magenta)':''},
    {icon:'⏰',label:'选举截止',value:String(_politicKpi.electionEnds)}
  ]);
}
function updatePropPayloadHint(){/* 已由表单化提案编辑器替代，保留空函数防止旧调用报错 */}

async function loadTribuneElection(){
  var d=await api('GET','/politic/api/tribune-election');
  var ends=d.endsAt?new Date(d.endsAt*1000).toLocaleString('zh-CN'):'—';
  var html='本轮截止: <b>'+ends+'</b> &nbsp; 周期: '+d.intervalHours+'小时 &nbsp; 席位: '+d.seats;
  html+=d.myVote?' &nbsp; <span style="color:#4caf50;">你已投给: '+escapeHtml(d.myVote)+'</span>':' &nbsp; <span style="color:#f96;">你尚未投票</span>';
  document.getElementById('tribuneElectionInfo').innerHTML=html;
  var seats=d.seats||0;
  var t='';(d.tally||[]).forEach(function(r,i){
    t+='<tr><td>'+(i+1)+'</td><td>'+escapeHtml(r.candidateName)+(i<seats?' <span style="color:#4caf50;font-size:10px;">(当前领先)</span>':'')+'</td><td>'+r.votes+'</td></tr>';
  });
  document.getElementById('tribuneTallyBody').innerHTML=t||'<tr><td colspan="3" style="color:#666;">暂无投票</td></tr>';
  _politicKpi.electionEnds=d.endsAt?new Date(d.endsAt*1000).toLocaleDateString('zh-CN'):'—';
  ksRenderPoliticKpis();
}
async function castTribuneVote(){
  var uuid=document.getElementById('tribuneVoteUuid').value.trim();
  if(!uuid){toast('请填写候选人 UUID','err');return;}
  var d=await api('POST','/politic/api/tribune-election/vote',{candidateUuid:uuid});
  if(d.success){toast(d.message||'投票成功','ok');loadTribuneElection();}else toast(d.message||d.error||'失败','err');
}

async function loadPoliticAnnouncements(){
  try{
    var d=await api('GET','/politic/api/proposals');
    var anns=(d.proposals||[]).map(function(p){
      return {title:p.title,author:p.proposerName||'',category:p.status==='ENACTED'?'LAW':
        (['SENATE_VOTING','SENATE_OVERRIDE','TRIBUNE_REVIEW'].includes(p.status)?'VOTING':'GENERAL')};
    });
    var voting=anns.filter(function(a){return a.category==='VOTING';}).slice(0,5);
    var laws=anns.filter(function(a){return a.category==='LAW';}).slice(0,3);
    var gen=anns.filter(function(a){return a.category!=='VOTING'&&a.category!=='LAW';}).slice(0,3);
    var h='';
    if(voting.length) h+='<div style="margin-bottom:8px;"><b style="color:#ff9800;">🏛 表决中</b>'+voting.map(function(a){return '<div style="padding:4px 8px;margin:4px 0;background:#1a1a2e;border-left:3px solid #ff9800;border-radius:3px;"><span style="color:#ffd08a;">'+escapeHtml(a.title)+'</span> <span style="color:#667;font-size:10px;">'+escapeHtml(a.author||'')+'</span></div>';}).join('')+'</div>';
    if(laws.length) h+='<div style="margin-bottom:8px;"><b style="color:#4caf50;">📜 已颁布</b>'+laws.map(function(a){return '<div style="padding:4px 8px;margin:4px 0;background:#1a1a2e;border-left:3px solid #4caf50;border-radius:3px;"><span style="color:#7fe08a;">'+escapeHtml(a.title)+'</span> <span style="color:#667;font-size:10px;">'+escapeHtml(a.author||'')+'</span></div>';}).join('')+'</div>';
    if(gen.length) h+='<div><b style="color:#00E5FF;">📢 公告</b>'+gen.map(function(a){return '<div style="padding:4px 8px;margin:4px 0;background:#1a1a2e;border-left:3px solid #00E5FF;border-radius:3px;"><span style="color:#7fd6ff;">'+escapeHtml(a.title)+'</span> <span style="color:#667;font-size:10px;">'+escapeHtml(a.author||'')+'</span></div>';}).join('')+'</div>';
    if(!h) h='<div style="color:#667;">暂无公告</div>';
    document.getElementById('politicAnnouncements').innerHTML=h;
    document.getElementById('annRefresh').textContent='实时';
  }catch(e){ document.getElementById('politicAnnouncements').innerHTML='<div style="color:#667;">加载公告失败</div>'; }
}

// ====== 现代化提案编辑器（表单化，无需写 JSON）======
var PROP_CATS=['ENTERPRISE_TAX','PERSONAL_TAX','TRADE_TAX','PROPERTY_TAX'];
var PROP_INDS=['INDUSTRY','AGRICULTURE','REAL_ESTATE','OTHER'];
var PROP_ZONE_TYPES=['RESIDENTIAL','COMMERCIAL','INDUSTRIAL','AGRICULTURAL'];
var propBanks=[], propZones=[];
// 提案类型定义：每类一组字段，type=text/num/pct/select/datalist/bank/zone/pricelist
var PROP_DEFS={
  GENERAL:{label:'📋 一般决议（宣示性，无自动副作用）',fields:[]},
  SET_TAX_RATE:{label:'💰 设置税率',fields:[
    {k:'category',label:'税收类目',type:'datalist',opts:'PROP_CATS',def:'ENTERPRISE_TAX'},
    {k:'industry',label:'行业（留空=通用税率）',type:'datalist',opts:'PROP_INDS',def:''},
    {k:'rate',label:'税率（0.1 = 10%）',type:'pct',def:0.1}
  ]},
  SET_TAX_BRACKET:{label:'📊 设置阶梯税率',fields:[
    {k:'action',label:'操作',type:'select',opts:['upsert','delete'],ctrl:true},
    {k:'id',label:'档位ID（修改/删除时填，新增留空）',type:'text'},
    {k:'industry',label:'行业',type:'datalist',opts:'PROP_INDS',def:'OTHER',cond:['upsert']},
    {k:'scope',label:'范围',type:'select',opts:['ENTERPRISE_TAX','PERSONAL_TAX'],cond:['upsert']},
    {k:'profitMin',label:'利润下限',type:'num',def:0,cond:['upsert']},
    {k:'profitMax',label:'利润上限',type:'num',def:1000000,cond:['upsert']},
    {k:'rate',label:'税率（0.05 = 5%）',type:'pct',def:0.05,cond:['upsert']}
  ]},
  SET_CB_RATES:{label:'🏛 设置央行利率',fields:[
    {k:'baseRate',label:'基准利率（0.03 = 3%）',type:'pct',def:0.03},
    {k:'reserveRequirement',label:'存款准备金率（0.1 = 10%）',type:'pct',def:0.1}
  ]},
  CB_INJECT:{label:'💉 央行注资',fields:[
    {k:'bankId',label:'目标银行',type:'bank'},
    {k:'amount',label:'金额',type:'num',def:100000},
    {k:'mode',label:'方式',type:'select',opts:['GRANT','LOAN'],ctrl:true},
    {k:'interestRate',label:'贷款利率（0.05=5%）',type:'pct',def:0.05,cond:['LOAN']},
    {k:'termDays',label:'贷款期限（天）',type:'num',def:30,cond:['LOAN']}
  ]},
  SET_OFFICIAL_PRICE:{label:'🏷 设置官方定价',fields:[{k:'prices',type:'pricelist'}]},
  RE_ZONE_ADMIN:{label:'🗺 房地产区域管理',fields:[
    {k:'action',label:'操作',type:'select',opts:['create','setPrice','setStatus'],ctrl:true},
    {k:'name',label:'区域名',type:'text',cond:['create']},
    {k:'world',label:'世界',type:'text',def:'world',cond:['create']},
    {k:'x1',label:'X1',type:'num',def:0,cond:['create']},
    {k:'z1',label:'Z1',type:'num',def:0,cond:['create']},
    {k:'x2',label:'X2',type:'num',def:100,cond:['create']},
    {k:'z2',label:'Z2',type:'num',def:100,cond:['create']},
    {k:'type',label:'规划类型',type:'select',opts:'PROP_ZONE_TYPES',cond:['create']},
    {k:'basePrice',label:'基础价',type:'num',def:10000,cond:['create']},
    {k:'zoneId',label:'目标区域',type:'zone',cond:['setPrice','setStatus']},
    {k:'price',label:'新基础价',type:'num',def:10000,cond:['setPrice']},
    {k:'status',label:'状态',type:'select',opts:['FOR_SALE','STATE_OWNED','SOLD'],cond:['setStatus']}
  ]}
};
function propOpts(o){return o==='PROP_CATS'?PROP_CATS:o==='PROP_INDS'?PROP_INDS:o==='PROP_ZONE_TYPES'?PROP_ZONE_TYPES:o;}
async function openProposalComposer(){
  // 预取银行 / 区域用于下拉
  try{var b=await api('GET','/api/bank/list');propBanks=(b&&b.banks)||[];}catch(e){propBanks=[];}
  try{var z=await api('GET','/api/realestate/zones');propZones=(z&&z.zones)||[];}catch(e){propZones=[];}
  var typeSel='<select id="pcType" onchange="renderPropFields()" style="width:100%;">';
  Object.keys(PROP_DEFS).forEach(function(k){typeSel+='<option value="'+k+'">'+PROP_DEFS[k].label+'</option>';});
  typeSel+='</select>';
  var dl='<datalist id="dlCats">'+PROP_CATS.map(function(x){return '<option value="'+x+'">';}).join('')+'</datalist>'
        +'<datalist id="dlInds">'+PROP_INDS.map(function(x){return '<option value="'+x+'">';}).join('')+'</datalist>';
  var body=dl
    +'<label style="display:block;margin-bottom:6px;">提案标题<br><input id="proposalComposerTitle" placeholder="如：上调企业所得税至 12%" style="width:100%;"/></label>'
    +'<label style="display:block;margin-bottom:6px;">提案说明<br><input id="proposalComposerDesc" placeholder="向元老院说明立法理由" style="width:100%;"/></label>'
    +'<label style="display:block;margin-bottom:6px;">提案类型<br>'+typeSel+'</label>'
    +'<div id="pcFields" style="margin-top:8px;padding:8px;background:#0d1b2a;border-radius:6px;"></div>'
    +'<div style="margin-top:12px;text-align:right;"><button class="btn" onclick="closeModal()">取消</button> '
    +'<button class="btn btn-primary" onclick="submitProposalNew()">📜 提交提案</button></div>';
  showModal('📜 新建立法提案',body);
  renderPropFields();
}
function renderPropFields(){
  var type=document.getElementById('pcType').value;
  var def=PROP_DEFS[type];var h='';
  if(!def.fields.length){h='<span style="color:#888;font-size:12px;">该提案为宣示性决议，无需额外参数。</span>';}
  def.fields.forEach(function(f){
    if(f.type==='pricelist'){
      h+='<div class="pf-row"><div style="font-size:12px;margin-bottom:4px;">官方定价条目</div>'
        +'<div id="pcPrices"></div><button class="btn btn-sm" type="button" onclick="addPropPriceRow()">➕ 添加物品</button></div>';
      return;
    }
    var condAttr=f.cond?(' data-cond="'+f.cond.join(',')+'"'):'';
    h+='<div class="pf-row" style="margin-bottom:6px;"'+condAttr+'>';
    h+='<label style="display:block;font-size:12px;">'+(f.label||f.k)+'<br>';
    var id='pf_'+f.k;var onch=f.ctrl?' onchange="pfApplyConds()"':'';
    if(f.type==='select'){
      h+='<select id="'+id+'"'+onch+' style="width:100%;">'+propOpts(f.opts).map(function(o){return '<option value="'+o+'"'+(o===f.def?' selected':'')+'>'+o+'</option>';}).join('')+'</select>';
    }else if(f.type==='bank'){
      if(propBanks.length)h+='<select id="'+id+'" style="width:100%;">'+propBanks.map(function(b){return '<option value="'+b.id+'">'+escapeHtml(b.name||b.id)+' ('+b.id+')</option>';}).join('')+'</select>';
      else h+='<input id="'+id+'" placeholder="银行ID" style="width:100%;"/>';
    }else if(f.type==='zone'){
      if(propZones.length)h+='<select id="'+id+'" style="width:100%;">'+propZones.map(function(z){return '<option value="'+z.id+'">'+escapeHtml(z.name||z.id)+' ('+z.id+')</option>';}).join('')+'</select>';
      else h+='<input id="'+id+'" placeholder="区域ID" style="width:100%;"/>';
    }else if(f.type==='datalist'){
      var dlid=f.opts==='PROP_CATS'?'dlCats':'dlInds';
      h+='<input id="'+id+'" list="'+dlid+'" value="'+(f.def!=null?f.def:'')+'" style="width:100%;"/>';
    }else{// text/num/pct
      var step=(f.type==='num'||f.type==='pct')?' type="number" step="'+(f.type==='pct'?'0.01':'1')+'"':'';
      h+='<input id="'+id+'"'+step+' value="'+(f.def!=null?f.def:'')+'" style="width:100%;"/>';
    }
    h+='</label></div>';
  });
  document.getElementById('pcFields').innerHTML=h;
  if(type==='SET_OFFICIAL_PRICE')addPropPriceRow();
  pfApplyConds();
}
function pfApplyConds(){
  // 找到带 ctrl 的控制字段值（action/mode），按 data-cond 显隐
  var ctrlVal=null;
  var type=document.getElementById('pcType').value;var def=PROP_DEFS[type];
  def.fields.forEach(function(f){if(f.ctrl){var el=document.getElementById('pf_'+f.k);if(el)ctrlVal=el.value;}});
  document.querySelectorAll('#pcFields .pf-row[data-cond]').forEach(function(row){
    var allow=row.getAttribute('data-cond').split(',');
    row.style.display=(ctrlVal&&allow.indexOf(ctrlVal)>=0)?'block':'none';
  });
}
function addPropPriceRow(){
  var box=document.getElementById('pcPrices');if(!box)return;
  var d=document.createElement('div');d.className='pc-price-row';d.style.cssText='display:flex;gap:4px;margin-bottom:4px;';
  d.innerHTML='<input placeholder="物品(如DIAMOND)" class="pcp-mat" style="flex:2;"/>'
    +'<input type="number" placeholder="收购价" class="pcp-buy" style="flex:1;"/>'
    +'<input placeholder="分类" class="pcp-cat" style="flex:1;"/>'
    +'<button class="btn btn-sm btn-danger" type="button" onclick="this.parentNode.remove()">✕</button>';
  box.appendChild(d);
}
async function submitProposalNew(){
  var title=document.getElementById('proposalComposerTitle').value.trim();
  if(!title){toast('请填写提案标题','err');return;}
  var type=document.getElementById('pcType').value;
  var def=PROP_DEFS[type];var payload={};
  var ctrlVal=null;def.fields.forEach(function(f){if(f.ctrl){var el=document.getElementById('pf_'+f.k);if(el)ctrlVal=el.value;}});
  def.fields.forEach(function(f){
    if(f.type==='pricelist'){
      var prices=[];document.querySelectorAll('#pcPrices .pc-price-row').forEach(function(r){
        var mat=r.querySelector('.pcp-mat').value.trim();if(!mat)return;
        prices.push({material:mat.toUpperCase(),buyPrice:Number(r.querySelector('.pcp-buy').value)||0,
          category:r.querySelector('.pcp-cat').value.trim()});
      });
      payload.prices=prices;return;
    }
    // 跳过被条件隐藏的字段
    if(f.cond&&(!ctrlVal||f.cond.indexOf(ctrlVal)<0))return;
    var el=document.getElementById('pf_'+f.k);if(!el)return;
    var v=el.value.trim();
    if(v==='')return; // 空值不提交（如 industry 留空=通用）
    if(f.type==='num'||f.type==='pct')payload[f.k]=Number(v);
    else payload[f.k]=v;
  });
  var d=await api('POST','/politic/api/proposal/create',{
    title:title,proposalType:type,description:document.getElementById('proposalComposerDesc').value.trim(),payload:payload
  });
  if(d.success){toast('提案已提交: '+d.id,'ok');closeModal();loadProposals();}
  else toast(d.error||'提交失败','err');
}
async function loadProposals(){
  var status=document.getElementById('propFilter').value;
  var url='/politic/api/proposals'+(status?'?status='+status:'');
  var d=await api('GET',url);
  var t='';(d.proposals||[]).forEach(function(p){
    t+='<tr><td style="font-size:10px;">'+p.id+'</td><td>'+escapeHtml(p.title)+'</td>';
    t+='<td style="font-size:10px;">'+p.proposalType+'</td><td>'+escapeHtml(p.proposerName||'')+'</td>';
    t+='<td>'+statusBadge(p.status)+'</td>';
    t+='<td><button class="btn btn-sm" onclick="openProposal(\''+p.id+'\')">查看</button></td></tr>';
  });
  document.getElementById('proposalBody').innerHTML=t||'<tr><td colspan="6" style="color:#666;">暂无提案</td></tr>';
  if(!status){
    _politicKpi.voting=(d.proposals||[]).filter(function(p){return p.status==='SENATE_VOTING'||p.status==='TRIBUNE_REVIEW'||p.status==='SENATE_OVERRIDE'||p.status==='VOTING'}).length;
    ksRenderPoliticKpis();
  }
}
async function openProposal(id){
  var p=await api('GET','/politic/api/proposal/'+id);
  if(p.error){toast(p.error,'err');return;}
  var body='<div style="font-size:12px;line-height:1.7;">';
  body+='<div><b>'+escapeHtml(p.title)+'</b> &nbsp;'+statusBadge(p.status)+'</div>';
  body+='<div style="color:#888;">类型: '+p.proposalType+' · 提案人: '+escapeHtml(p.proposerName||'')+' ('+(OFFICE_CN[p.proposerOffice]||p.proposerOffice)+')</div>';
  if(p.description)body+='<div style="margin-top:6px;">'+escapeHtml(p.description)+'</div>';
  if(p.payload&&Object.keys(p.payload).length)body+='<div style="margin-top:6px;font-size:10px;color:#888;font-family:monospace;">'+escapeHtml(JSON.stringify(p.payload))+'</div>';
  if(p.resultSummary)body+='<div style="margin-top:6px;color:#00E5FF;">'+escapeHtml(p.resultSummary)+'</div>';
  // 计票
  if(p.tally&&p.tally.stage){
    var ta=p.tally;
    body+='<div style="margin-top:8px;padding:8px;background:#07080C;border-radius:10px;">';
    body+='计票（'+ta.stage+'）: <b style="color:#4caf50;">'+ta.yesCount+'</b> 赞成 / <b style="color:#f44;">'+ta.noCount+'</b> 反对 / '+ta.abstainCount+' 弃权';
    body+='<br><span style="font-size:11px;color:#888;">'+(ta.totalVoted)+'/'+(ta.totalEligible)+' 已投票 · 法定人数'+(ta.quorumMet?'✅':'未达')+(ta.unanimous?' · 🏛全票一致':'')+'</span>';
    body+='</div>';
  }
  body+='</div>';
  // 操作区（按状态 + 我的权限）
  var acts=[];
  var mp=myPolitic||{};
  var isProposer=mp.playerUuid&&p.proposerUuid===mp.playerUuid;
  if(p.status==='PROPOSED'&&isProposer){
    acts.push('<button class="btn btn-sm btn-success" onclick="politicAct(\''+id+'\',\'start-vote\')">发起元老院表决</button>');
  }
  if(p.status==='SENATE_VOTING'&&mp.canVoteInSenate){
    acts.push('<button class="btn btn-sm btn-success" onclick="politicVote(\''+id+'\',\'YES\')">投赞成</button>');
    acts.push('<button class="btn btn-sm btn-danger" onclick="politicVote(\''+id+'\',\'NO\')">投反对</button>');
    acts.push('<button class="btn btn-sm" onclick="politicVote(\''+id+'\',\'ABSTAIN\')">弃权</button>');
  }
  if(p.status==='TRIBUNE_REVIEW'&&mp.canVeto){
    acts.push('<button class="btn btn-sm btn-success" onclick="politicReview(\''+id+'\',\'APPROVE\')">批准放行</button>');
    acts.push('<button class="btn btn-sm btn-danger" onclick="politicReview(\''+id+'\',\'VETO\')">否决</button>');
  }
  if(p.status==='VETOED'&&mp.canVoteInSenate){
    acts.push('<button class="btn btn-sm btn-warn" onclick="politicAct(\''+id+'\',\'override\')">发起覆议（需全体元老一致）</button>');
  }
  if(p.status==='SENATE_OVERRIDE'&&mp.canVoteInSenate){
    acts.push('<button class="btn btn-sm btn-success" onclick="politicVote(\''+id+'\',\'YES\')">覆议投赞成</button>');
    acts.push('<button class="btn btn-sm btn-danger" onclick="politicVote(\''+id+'\',\'NO\')">覆议投反对</button>');
  }
  var noActHint='当前状态无可执行操作';
  if(acts.length===0){
    if(p.status==='PROPOSED') noActHint=isProposer?'点上方按钮发起表决':'等待提案人（执政官/骑士）发起元老院表决';
    else if(p.status==='SENATE_VOTING') noActHint=mp.canVoteInSenate?'你已完成投票或表决已结束':'只有元老 / 执政官可在元老院表决（你无表决权）';
    else if(p.status==='TRIBUNE_REVIEW') noActHint=mp.canVeto?'你已审查':'等待保民官审查放行 / 否决';
    else if(p.status==='VETOED') noActHint=mp.canVoteInSenate?'可发起全票覆议':'提案已被保民官否决';
    else if(p.status==='SENATE_OVERRIDE') noActHint=mp.canVoteInSenate?'你已投票':'覆议进行中（需全体元老一致）';
  }
  body+='<div style="margin-top:12px;display:flex;gap:6px;flex-wrap:wrap;">'+(acts.join('')||'<span style="color:#888;font-size:11px;">'+noActHint+'</span>')+'</div>';
  body+='<div style="margin-top:8px;"><button class="btn btn-sm" onclick="closeModal()">关闭</button></div>';
  showModal('📜 提案 '+id,body);
}
async function politicVote(id,vote){
  var d=await api('POST','/politic/api/proposal/'+id+'/vote',{vote:vote});
  if(d.success){toast('投票成功'+(d.newStatus?'（状态: '+(STATUS_CN[d.newStatus]||d.newStatus)+'）':''),'ok');closeModal();loadProposals();}
  else toast(d.error||'投票失败','err');
}
async function politicReview(id,action){
  var d=await api('POST','/politic/api/proposal/'+id+'/tribune-review',{action:action});
  if(d.success){toast('审查完成（状态: '+(STATUS_CN[d.newStatus]||d.newStatus)+'）','ok');closeModal();loadProposals();}
  else toast(d.error||'审查失败','err');
}
async function politicAct(id,act){
  var d=await api('POST','/politic/api/proposal/'+id+'/'+act,{});
  if(d.success){toast(d.message||'操作成功','ok');closeModal();loadProposals();}
  else toast(d.error||'操作失败','err');
}
