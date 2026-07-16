(function(){
  var _t=null, BASE='/ks-Eco';
  function pickerPart(inp){
    var raw=inp.value||'', comma=raw.lastIndexOf(','), prefix=comma>=0?raw.slice(0,comma+1):'', token=raw.slice(comma+1).trim();
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
  // 弹窗式选择器（替代 prompt 手输UUID）
  window.ksPickPlayer=function(title){
    return new Promise(function(resolve){
      var ov=document.createElement('div');
      ov.style.cssText='position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:99999;display:flex;align-items:center;justify-content:center;';
      ov.innerHTML='<div style="background:#1e1e28;color:#eee;padding:20px;border-radius:10px;min-width:320px;box-shadow:0 8px 30px rgba(0,0,0,.5);">'
        +'<div style="font-size:15px;margin-bottom:10px;">'+(title||'选择玩家')+'</div>'
        +'<input class="player-picker" id="ksPickInput" placeholder="输入玩家名搜索…" style="width:100%;padding:8px;box-sizing:border-box;border-radius:6px;border:1px solid #444;background:#2a2a36;color:#eee;"/>'
        +'<div style="margin-top:14px;text-align:right;">'
        +'<button id="ksPickCancel" style="padding:6px 14px;margin-right:8px;border-radius:6px;border:1px solid #555;background:#333;color:#ccc;cursor:pointer;">取消</button>'
        +'<button id="ksPickOk" style="padding:6px 14px;border-radius:6px;border:none;background:#3b82f6;color:#fff;cursor:pointer;">确定</button>'
        +'</div></div>';
      document.body.appendChild(ov);
      var inp=ov.querySelector('#ksPickInput'); wire(inp);
      function done(v){ ov.remove(); resolve(v); }
      ov.querySelector('#ksPickCancel').onclick=function(){ done(null); };
      ov.querySelector('#ksPickOk').onclick=function(){ done(inp.value.trim()||null); };
      ov.addEventListener('click',function(e){ if(e.target===ov) done(null); });
      inp.addEventListener('keydown',function(e){ if(e.key==='Enter'){ done(inp.value.trim()||null);} if(e.key==='Escape'){ done(null);} });
      setTimeout(function(){ inp.focus(); refill(''); },50);
    });
  };
  if(document.readyState!=='loading') window.ksWirePlayerPickers();
  else document.addEventListener('DOMContentLoaded',function(){window.ksWirePlayerPickers();});
})();
