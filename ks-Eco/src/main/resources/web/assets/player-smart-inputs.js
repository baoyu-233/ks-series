(function(){
  var BASE='/ks-Eco', timers=new WeakMap(), cache={bank:null,enterprise:null};
  function headers(){ return typeof H==='function'?H():{}; }
  function kind(input){
    var key=((input.id||'')+' '+(input.name||'')).toLowerCase();
    if(/bankid|bank_id|bank.*id/.test(key)&&!/name/.test(key)) return 'bank';
    if(/enterpriseid|enterprise_id|entid|ent_id|ent.*id/.test(key)) return 'enterprise';
    if(/uuid|player|owner|invitee|member|borrower|candidate|requester|publisher|bidder/.test(key)) return 'player';
    return '';
  }
  function part(input){
    var raw=input.value||'', comma=raw.lastIndexOf(','), prefix=comma<0?'':raw.slice(0,comma+1), token=raw.slice(comma+1).trim();
    var colon=token.lastIndexOf(':');
    if(colon>=0&&kind(input)==='player'){ prefix+=token.slice(0,colon+1); token=token.slice(colon+1).trim(); }
    return {prefix:prefix, token:token};
  }
  function list(kindName){ return document.getElementById('ksSmart'+kindName[0].toUpperCase()+kindName.slice(1)+'Options'); }
  function setOptions(kindName, rows){
    var dl=list(kindName); if(!dl)return;
    dl.replaceChildren();
    rows.forEach(function(row){
      var option=document.createElement('option');
      if(kindName==='player'){ option.value=row.name||row.uuid; option.label=row.uuid||''; option.dataset.value=row.uuid||''; }
      else { var id=String(row.id||''); option.value=(row.name||id)+' ['+id+']'; option.label=id; option.dataset.value=id; }
      dl.appendChild(option);
    });
  }
  function fetchRows(kindName, query){
    if(kindName==='player') return fetch(BASE+'/api/players/search?q='+encodeURIComponent(query||''),{headers:headers()})
      .then(function(r){return r.json();}).then(function(d){return d.players||[];});
    var endpoint=kindName==='bank'?'/api/bank/list':'/api/enterprise/list';
    if(cache[kindName]) return Promise.resolve(cache[kindName]);
    return fetch(BASE+endpoint,{headers:headers()}).then(function(r){return r.json();}).then(function(d){
      cache[kindName]=kindName==='bank'?(d.banks||[]):(d.enterprises||[]); return cache[kindName];
    });
  }
  function refresh(input){
    var type=kind(input); if(!type)return;
    var current=part(input);
    fetchRows(type,current.token).then(function(rows){
      if(type==='player'&&current.token){ var q=current.token.toLowerCase(); rows=rows.filter(function(row){return String(row.name||'').toLowerCase().includes(q)||String(row.uuid||'').includes(q);}); }
      else if(type!=='player'&&current.token){ var q=current.token.toLowerCase(); rows=rows.filter(function(row){return String(row.name||'').toLowerCase().includes(q)||String(row.id||'').toLowerCase().includes(q);}); }
      setOptions(type,rows.slice(0,50));
    }).catch(function(){});
  }
  function resolve(input){
    var type=kind(input); if(!type)return;
    var dl=list(type), current=part(input); if(!dl||!current.token)return;
    Array.prototype.some.call(dl.options,function(option){
      if(option.value!==current.token||!option.dataset.value)return false;
      input.value=current.prefix+option.dataset.value; return true;
    });
  }
  function wire(input){
    if(input.dataset.ksSmartFilled||input.type==='hidden'||input.type==='number')return;
    var type=kind(input); if(!type)return;
    var dataList=list(type);if(!dataList)return;
    input.dataset.ksSmartFilled='1'; input.setAttribute('list',dataList.id); input.setAttribute('autocomplete','off');
    input.addEventListener('focus',function(){ cache.bank=null; cache.enterprise=null; refresh(input); });
    input.addEventListener('input',function(){ clearTimeout(timers.get(input)); timers.set(input,setTimeout(function(){refresh(input);},180)); });
    input.addEventListener('change',function(){resolve(input);}); input.addEventListener('blur',function(){resolve(input);});
  }
  function wireAll(root){ (root||document).querySelectorAll('input').forEach(wire); }
  wireAll(); new MutationObserver(function(records){records.forEach(function(record){record.addedNodes.forEach(function(node){if(node.nodeType===1)wireAll(node);});});}).observe(document.body,{childList:true,subtree:true});
})();
