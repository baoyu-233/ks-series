/* ks-eco ui: additive animation layer (no business logic) */
(function(){
  if(window.matchMedia&&matchMedia('(prefers-reduced-motion: reduce)').matches)return;
  var seen=new WeakSet(),scheduled=false;
  function roll(el){
    if(seen.has(el))return;seen.add(el);
    var txt=(el.textContent||'').trim();
    var m=txt.match(/^(-?[\d,]+(?:\.\d+)?)(.*)$/);if(!m)return;
    var target=parseFloat(m[1].replace(/,/g,''));if(!isFinite(target))return;
    var dec=(m[1].split('.')[1]||'').length,suf=m[2]||'',comma=m[1].indexOf(',')>=0;
    if(Math.abs(target)<0.000001)return;
    var t0=performance.now(),dur=640;
    function fr(t){
      var p=Math.min(1,(t-t0)/dur);p=1-Math.pow(1-p,3);
      var v=target*p;
      var s=comma?v.toLocaleString('en-US',{minimumFractionDigits:dec,maximumFractionDigits:dec}):v.toFixed(dec);
      el.textContent=s+suf;
      if(p<1)requestAnimationFrame(fr);
    }
    requestAnimationFrame(fr);
  }
  function scan(){scheduled=false;document.querySelectorAll('.stat-val').forEach(roll);}
  new MutationObserver(function(){
    if(scheduled)return;scheduled=true;requestAnimationFrame(scan);
  }).observe(document.body,{childList:true,subtree:true});
  scan();
})();
