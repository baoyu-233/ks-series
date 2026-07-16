// ====== 第62轮：副本协议抽屉 + 补充搜索 ======
function openDgTplDrawer(id){
  var x=(typeof dgTplCache!=='undefined'?dgTplCache:[]).find(function(e){return e.id===id;});if(!x)return;
  var feed='<div class="hub-feed">'
   +'<div class="hub-feed-item"><span>名称</span><b>'+escapeHtml(x.name||'')+'</b></div>'
   +'<div class="hub-feed-item"><span>难度</span><b>'+escapeHtml(x.difficulty||'NORMAL')+'</b></div>'
   +'<div class="hub-feed-item"><span>门票价</span><b style="color:#00E5FF;">'+fmt(x.ticketPrice||0)+'</b></div>'
   +'<div class="hub-feed-item"><span>人数要求</span><b>'+x.minPlayers+' - '+x.maxPlayers+' 人</b></div>'
   +'<div class="hub-feed-item"><span>时限</span><b>'+x.timeLimitMinutes+' 分钟</b></div>'
   +'<div class="hub-feed-item"><span>怪物等级</span><b>'+x.monsterLevel+'</b></div>'
   +'<div class="hub-feed-item"><span>地图 Schematic</span><b>'+(x.schematic?escapeHtml(x.schematic):'纯虚空')+'</b></div>'
   +'<div class="hub-feed-item"><span>进入要求</span><b>'+(x.requirePropertyKey?'🔑 需资产钥匙':'购票即可')+'</b></div>'
   +'</div>';
  if(x.description)feed+='<p style="font-size:12px;color:#8FB4CC;line-height:1.7;margin-top:8px;">'+escapeHtml(x.description)+'</p>';
  feed+='<h4 style="margin-top:14px;color:#FF3DF2;letter-spacing:2px;font-size:10px;">通关奖励 // REWARDS</h4>';
  if(x.rewardConfig){
    var rc=x.rewardConfig;try{rc=JSON.stringify(JSON.parse(x.rewardConfig),null,2);}catch(e){}
    feed+='<pre style="font-size:10.5px;color:#8FB4CC;background:rgba(0,229,255,.04);border:1px solid rgba(0,229,255,.12);padding:10px;white-space:pre-wrap;word-break:break-all;">'+escapeHtml(rc)+'</pre>';
  }else feed+='<p style="color:#4E6B85;font-size:11px;">未配置奖励。</p>';
  if(x.requirePropertyKey)feed+='<p style="color:#4E6B85;font-size:11px;margin-top:8px;">关联领地：在「企业与领地 → 领地地块」划区时将副本权限绑定为本模板 ID（'+escapeHtml(x.id)+'），该区域地块所有者即持有钥匙。</p>';
  feed+='<div class="form-row" style="margin-top:12px;">'
   +'<button class="btn btn-sm" onclick="closeModal();editDgTemplate(\''+escapeAttr(x.id)+'\')">载入编辑器</button>'
   +'<button class="btn btn-sm btn-danger" onclick="closeModal();deleteDgTemplate(\''+escapeAttr(x.id)+'\')">删除模板</button>'
   +'</div>';
  showModal('副本协议 // '+escapeHtml(x.id),feed);
}
(function(){
  var el=document.getElementById('dgTplBody');
  if(el)el.addEventListener('click',function(e){
    if(e.target.closest('button')||e.target.closest('a')||e.target.closest('input'))return;
    var tr=e.target.closest('tr');if(!tr||!tr.dataset.dgid)return;
    openDgTplDrawer(tr.dataset.dgid);
  });
  if(typeof ksAddSearch==='function'){
    ksAddSearch('dgTplBody','⌕ 搜索模板 ID / 名称 / 难度…');
    ksAddSearch('dgInstBody','⌕ 搜索实例 ID / 模板 / 状态…');
  }
})();
