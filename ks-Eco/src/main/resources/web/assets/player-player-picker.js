(function(){
  var _t=null, BASE='/ks-Eco';
  function pickerPart(inp){
    var raw=inp.value||'', mode=inp.dataset.playerList||'';
    var comma=raw.lastIndexOf(','), prefix=comma>=0?raw.slice(0,comma+1):'', token=raw.slice(comma+1).trim();
    if(mode==='shares'){
      var colon=token.lastIndexOf(':');
      if(colon>=0){ prefix+=token.slice(0,colon+1); token=token.slice(colon+1); }
    }
    return {prefix:prefix, query:token};
  }
  function refill(q, inp){
    var dl=document.getElementById('ksPlayerOptions'); if(!dl) return;
    fetch(BASE+'/api/players/search?q='+encodeURIComponent(q||''),{headers:H()})
      .then(function(r){return r.json();})
      .then(function(d){
        var ps=(d&&d.players)||[];
        dl.innerHTML=ps.map(function(p){
          return '<option value="'+(p.name||p.uuid)+'" label="'+p.uuid+'">'+(p.online?' 🟢在线':' ⚫离线')+'</option>';
        }).join('');
        var prefix=inp ? (inp.dataset.ksPickerPrefix||'') : '';
        if(prefix) Array.prototype.forEach.call(dl.options,function(option){ option.value=prefix+option.value; });
      }).catch(function(){});
  }
  function wire(inp){
    if(inp.dataset.ksPicker) return;
    inp.dataset.ksPicker='1';
    inp.setAttribute('list','ksPlayerOptions');
    inp.setAttribute('autocomplete','off');
    if(!inp.title) inp.title='输入玩家名搜索，从下拉选择以自动填入UUID';
    inp.addEventListener('focus',function(){ var part=pickerPart(inp); inp.dataset.ksPickerPrefix=part.prefix; refill(part.query,inp); });
    inp.addEventListener('input',function(){
      clearTimeout(_t); var part=pickerPart(inp); inp.dataset.ksPickerPrefix=part.prefix;
      _t=setTimeout(function(){ refill(part.query,inp); }, 200);
    });
  }
  window.ksWirePlayerPickers=function(root){
    (root||document).querySelectorAll('input.player-picker').forEach(wire);
  };
  if(document.readyState!=='loading') window.ksWirePlayerPickers();
  else document.addEventListener('DOMContentLoaded',function(){window.ksWirePlayerPickers();});
})();
