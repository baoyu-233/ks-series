/* ===== ks-eco admin: consolidated pages + drill-down glue (additive; overrides list renderers) ===== */
var curBankId=null,curEntId=null,_banksCache=[],_entsCache=[],_bankChart=null;

// ---- Banks page: KPI + enhanced list ----
async function loadBankList(){
  var d=await api('GET','/api/bank/list');
  _banksCache=d.banks||[];
  var totalAssets=0,commercial=0;
  _banksCache.forEach(function(b){totalAssets+=Number(b.total_assets||b.capital||0);if(b.type!=='CENTRAL')commercial++;});
  document.getElementById('bankKpis').innerHTML=
    '<div class="stat-card"><div class="stat-val">'+fmt(_banksCache.length)+'</div><div class="stat-label">银行总数</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(commercial)+'</div><div class="stat-label">商业银行</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(totalAssets)+'</div><div class="stat-label">银行总资产</div></div>'
    +'<div class="stat-card" id="bankLoanKpi"><div class="stat-val">…</div><div class="stat-label">未偿贷款总额</div></div>';
  var cards=_banksCache.map(function(b){
    return ksCard({
      title:b.name,badge:b.type||'',badgeCls:b.type==='CENTRAL'?'':'cyan',
      fields:[['银行ID',b.id],['总资产',fmt(b.total_assets||b.capital)],['贷款利率',pct(b.loan_rate)],['存款利率/周期',pct(b.interest_rate||b.deposit_rate)],['状态',b.status||'']],
      onclick:"openBankMgr('"+escapeAttr(b.id)+"')",
      actions:[
        {label:'🛠 管理',cls:'btn-primary',onclick:"openBankMgr('"+escapeAttr(b.id)+"')"},
        {label:'📈 利率',onclick:"setBankRate('"+escapeAttr(b.id)+"')"},
        {label:'📨 邀请',onclick:"quickBankInvite('"+escapeAttr(b.id)+"')"}
      ]
    });
  });
  ksGrid('bankListGrid',cards,'暂无银行');
  if(typeof ksCardSearch==='function')ksCardSearch('bankListSearch','bankListGrid');
  var ld=await api('GET','/api/bank/loans');window._loansCache=ld.loans||[];
  var out=0;window._loansCache.forEach(function(l){if(l.status==='ACTIVE')out+=Number(l.remaining||0);});
  var el=document.getElementById('bankLoanKpi');if(el)el.innerHTML='<div class="stat-val">'+fmt(out)+'</div><div class="stat-label">未偿贷款总额</div>';
  if(curBankId)renderBankLoans(curBankId);
}
function renderBankLoans(bankId){
  var loans=(window._loansCache||[]).filter(function(l){return String(l.bank_id)===String(bankId);});
  var t='';loans.forEach(function(l){
    t+='<tr><td>'+escapeHtml(l.id)+'</td><td>'+escapeHtml(l.bank_id)+'</td><td>'+escapeHtml(l.borrower_uuid)+'</td><td>'+fmt(l.principal)+'</td><td>'+fmt(l.remaining)+'</td><td>'+pct(l.interest_rate)+'</td><td><span class="badge '+(l.status==='ACTIVE'?'badge-active':'badge-closed')+'">'+escapeHtml(l.status)+'</span></td><td>'+(l.status==='ACTIVE'?'<span style="color:#888;">由借款人还款</span>':'')+'</td></tr>';
  });
  document.getElementById('loanListBody').innerHTML=t||'<tr><td colspan="8" style="color:#666;">本行暂无贷款</td></tr>';
}
async function openBankMgr(bankId){
  curBankId=bankId;
  var b=_banksCache.find(function(x){return String(x.id)===String(bankId);})||{};
  document.getElementById('bankDetail').style.display='block';
  document.getElementById('bankDetailTitle').textContent=(b.name||bankId)+' · '+(b.type||'')+' · '+(b.status||'');
  ['loanBankId','entLoanRequestBankId','bankPermBankId','inviteBankId2'].forEach(function(id){var e=document.getElementById(id);if(e)e.value=bankId;});
  if(!window._loansCache){var ld=await api('GET','/api/bank/loans');window._loansCache=ld.loans||[];}
  renderBankLoans(bankId);
  if(typeof loadEnterpriseLoanRequests==='function')loadEnterpriseLoanRequests();
  if(typeof loadBankPermList==='function')loadBankPermList();
  var assets=Number(b.total_assets||b.capital||0),out=0,cnt=0;
  (window._loansCache||[]).forEach(function(l){if(String(l.bank_id)===String(bankId)&&l.status==='ACTIVE'){out+=Number(l.remaining||0);cnt++;}});
  document.getElementById('bankDetailInfo').innerHTML=
    '总资产 <b style="color:#fff;">'+fmt(assets)+'</b><br>未偿贷款 <b style="color:#F5D083;">'+fmt(out)+'</b>（'+cnt+' 笔）<br>贷款利率 <b>'+pct(b.loan_rate)+'</b> · 存款利率/周期 <b>'+pct(b.interest_rate||b.deposit_rate)+'</b><br>银行ID <span style="font-family:monospace;color:#888;">'+bankId+'</span>';
  try{
    if(_bankChart)_bankChart.destroy();
    _bankChart=new Chart(document.getElementById('bankDetailChart').getContext('2d'),{type:'doughnut',
      data:{labels:['可用资产','未偿贷款'],datasets:[{data:[Math.max(assets,0),Math.max(out,0)],
        backgroundColor:['rgba(0,229,255,.78)','rgba(130,171,242,.65)'],borderWidth:0}]},
      options:{responsive:true,maintainAspectRatio:false,cutout:'68%',plugins:{legend:{position:'bottom',labels:{color:'#C9CDD6',boxWidth:10,font:{size:10}}}}}});
  }catch(e){}
  document.getElementById('bankDetail').scrollIntoView({behavior:'smooth',block:'start'});
}
function closeBankMgr(){curBankId=null;document.getElementById('bankDetail').style.display='none';}
function viewBankPerms(bankId){openBankMgr(bankId);}

// ---- Enterprises page: KPI + enhanced list ----
async function loadEntList(){
  var d=await api('GET','/api/enterprise/list');
  var levelInput=document.getElementById('editEnterpriseLevel');if(levelInput)levelInput.max=d.maxEnterpriseLevel||10;
  _entsCache=d.enterprises||[];
  var cap=0,corp=0,emp=0,active=0;
  _entsCache.forEach(function(e){cap+=Number(e.registered_capital||0);corp+=Number(e.corporate_balance||0);emp+=Number(e.employee_count||0);if(e.status==='ACTIVE')active++;});
  document.getElementById('entKpis').innerHTML=
    '<div class="stat-card"><div class="stat-val">'+fmt(_entsCache.length)+'</div><div class="stat-label">企业总数</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(active)+'</div><div class="stat-label">正常经营</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(cap)+'</div><div class="stat-label">注册资本合计</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(corp)+'</div><div class="stat-label">公户余额合计</div></div>'
    +'<div class="stat-card"><div class="stat-val">'+fmt(emp)+'</div><div class="stat-label">从业玩家</div></div>';
  var cards=_entsCache.map(function(e){
    return ksCard({
      title:e.name,badge:(e.industry||e.type||''),badgeCls:e.type==='STATE_OWNED'?'':'cyan',
      fields:[['企业ID',e.id],['企业等级',fmt(e.level||1)],['注册资本',fmt(e.registered_capital)],['公户余额',fmt(e.corporate_balance||0)],['员工',fmt(e.employee_count)],['区域',e.region||'—'],['状态',e.status||'']],
      onclick:"openEntMgr('"+escapeAttr(e.id)+"')",
      actions:[
        {label:'🛠 管理',cls:'btn-primary',onclick:"openEntMgr('"+escapeAttr(e.id)+"')"},
        {label:'💳 公户',onclick:"checkCorpBalance('"+escapeAttr(e.id)+"')"}
      ]
    });
  });
  ksGrid('entListGrid',cards,'暂无企业');
  if(typeof ksCardSearch==='function')ksCardSearch('entListSearch','entListGrid');
}
async function openEntMgr(entId){
  curEntId=entId;
  var e=_entsCache.find(function(x){return String(x.id)===String(entId);})||{};
  document.getElementById('entDetail').style.display='block';
  document.getElementById('entDetailTitle').textContent=(e.name||entId)+' · '+(e.type||'')+' · '+(e.status||'');
  ['editEnterpriseId','permEntId','divEntId','inviteEntId','procEntId','inventoryEntId'].forEach(function(id){var el=document.getElementById(id);if(el)el.value=entId;});
  var ne=document.getElementById('editEnterpriseName');if(ne)ne.value=e.name||'';
  var de=document.getElementById('editEnterpriseDescription');if(de)de.value=e.description||'';
  var ty=document.getElementById('editEnterpriseType');if(ty&&e.type)ty.value=e.type;
  var rg=document.getElementById('editEnterpriseRegion');if(rg)rg.value=e.region||'';
  var ind=document.getElementById('editEnterpriseIndustry');if(ind)ind.value=e.industry||'OTHER';
  var ow=document.getElementById('editEnterpriseOwners');if(ow)ow.value=e.owner_uuids||'';
  var cap=document.getElementById('editEnterpriseCapital');if(cap)cap.value=Number(e.registered_capital||0);
  var bal=document.getElementById('editEnterpriseBalance');if(bal)bal.value=Number(e.corporate_balance||0);
  var dr=document.getElementById('editEnterpriseDividendRate');if(dr)dr.value=Number(e.dividend_rate||0);
  var lv=document.getElementById('editEnterpriseLevel');if(lv)lv.value=e.level||1;
  var st=document.getElementById('editEnterpriseStatus');if(st&&e.status)st.value=e.status;
  document.getElementById('entDetailInfo').innerHTML=
    '企业等级 <b style="color:#7ee787;">Lv.'+fmt(e.level||1)+'</b> · 注册资本 <b style="color:#fff;">'+fmt(e.registered_capital)+'</b> · 公户余额 <b style="color:#F5D083;">'+fmt(e.corporate_balance||0)+'</b><br>员工 <b>'+fmt(e.employee_count)+'</b> 人 · 区域 <b>'+escapeHtml(e.region||'—')+'</b> · 类型 <b>'+(e.type||'—')+'</b><br>企业ID <span style="font-family:monospace;color:#888;">'+entId+'</span>';
  var m=await api('GET','/api/enterprise/members?enterpriseId='+encodeURIComponent(entId));
  var rows='';(m.members||[]).forEach(function(mm){
    rows+='<tr><td>'+escapeHtml(mm.name||mm.player_name||'')+'</td><td style="font-family:monospace;font-size:10px;">'+(mm.uuid||mm.player_uuid||'')+'</td><td>'+(mm.role||'')+'</td><td>'+fmt(mm.salary||0)+'</td><td>'+(mm.joined_at||mm.join_time||'')+'</td></tr>';
  });
  document.getElementById('entDetailMembers').innerHTML=rows||'<tr><td colspan="5" style="color:#666;">暂无成员</td></tr>';
  if(typeof loadPermList==='function')loadPermList();
  document.getElementById('entDetail').scrollIntoView({behavior:'smooth',block:'start'});
}
function closeEntMgr(){curEntId=null;document.getElementById('entDetail').style.display='none';}

// ---- Overview: rank sub-tabs ----
function switchRankSub(sub,ev){
  document.querySelectorAll('#tab-macro .rank-sub').forEach(function(s){s.classList.remove('active');});
  document.querySelectorAll('#tab-macro .inline-tab').forEach(function(t){t.classList.remove('active');});
  var el=document.getElementById('rank-sub-'+sub);if(el)el.classList.add('active');
  if(ev&&ev.target)ev.target.classList.add('active');
  var titles={players:'个人财富 Top50 // PLAYERS',enterprises:'企业财富 Top50 // ENTERPRISES',banks:'银行资产 Top50 // BANKS'};
  var title=document.getElementById('pageTitle');if(title)title.textContent=titles[sub]||'财富排行 // RANKINGS';
  if(sub==='players'&&typeof loadPlayerRankings==='function')loadPlayerRankings();
  if(sub==='enterprises'&&typeof loadEntRankings==='function')loadEntRankings();
  if(sub==='banks'&&typeof loadBankRankings==='function')loadBankRankings();
}
