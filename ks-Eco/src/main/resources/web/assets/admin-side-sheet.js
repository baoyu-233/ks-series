/* Side-sheet adapter: legacy dialog calls and non-action table rows now drill down without changing API handlers. */
(function(){
  var originalModalClose=closeModal,entityFocus=null;
  function closeEntity(){var overlay=document.getElementById('ksEntityOverlay');if(overlay)overlay.remove();if(entityFocus&&typeof entityFocus.focus==='function')entityFocus.focus();entityFocus=null;}
  function openEntity(title,bodyHTML){
    closeEntity();if(typeof ksDrillClose==='function')ksDrillClose();entityFocus=document.activeElement;
    var overlay=document.createElement('div');overlay.id='ksEntityOverlay';overlay.addEventListener('click',function(e){if(e.target===overlay)closeEntity();});
    overlay.innerHTML='<section class="entity-sheet" role="dialog" aria-modal="true"><button class="entity-close" type="button">CLOSE ESC</button><div class="entity-kicker">ENTITY DETAIL // LIVE CONTROL</div><h2 class="entity-title"></h2><div class="entity-body"></div></section>';
    overlay.querySelector('.entity-title').textContent=String(title||'DETAIL');overlay.querySelector('.entity-body').innerHTML=bodyHTML||'';
    overlay.querySelector('.entity-close').addEventListener('click',closeEntity);document.body.appendChild(overlay);overlay.querySelector('.entity-close').focus();
  }
  window.ksEntityOpen=openEntity;window.ksEntityClose=closeEntity;
  showModal=function(title,bodyHTML){openEntity(title,bodyHTML)};
  closeModal=function(){if(document.getElementById('ksEntityOverlay'))closeEntity();else originalModalClose()};
  document.addEventListener('keydown',function(e){if(e.key==='Escape'&&document.getElementById('ksEntityOverlay'))closeEntity()});
  document.addEventListener('click',function(e){
    if(e.target.closest('#ksEntityOverlay,#ksDrillOverlay,button,a,input,select,textarea,label'))return;
    var row=e.target.closest('tbody tr');if(!row||!row.closest('table'))return;
    var table=row.closest('table'),head=table.tHead&&table.tHead.rows[0],cells=row.cells;if(!head||!cells||!cells.length)return;
    var fields='';for(var i=0;i<cells.length;i++){var label=head.cells[i]?head.cells[i].textContent.trim():'FIELD '+(i+1),value=cells[i].textContent.trim();if(!value||/^(edit|delete|manage|details?)$/i.test(value))continue;fields+='<div class="entity-field"><span>'+escapeHtml(label)+'</span><b>'+escapeHtml(value)+'</b></div>';}
    if(!fields)return;var section=document.querySelector('.tab-section.active'),heading=section&&section.querySelector('h2,h3');openEntity((heading?heading.textContent.trim():'RECORD')+' // DETAIL','<div class="entity-record">'+fields+'</div>');
  });
})();
