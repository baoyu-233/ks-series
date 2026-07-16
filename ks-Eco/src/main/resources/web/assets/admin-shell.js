/* Shared shell behavior: keyboard navigation, focus-safe drawers, and mobile navigation. */
(function(){
  var previousFocus=null,baseShowModal=showModal,baseCloseModal=closeModal,baseSwitchTab=switchTab;
  showModal=function(title,body){
    previousFocus=document.activeElement;baseShowModal(title,body);
    var overlay=document.getElementById('modalOverlay'),content=document.getElementById('modalContent');
    overlay.setAttribute('role','presentation');content.setAttribute('role','dialog');content.setAttribute('aria-modal','true');
    var close=content.querySelector('.close');if(close)close.focus();
  };
  closeModal=function(){baseCloseModal();if(previousFocus&&typeof previousFocus.focus==='function')previousFocus.focus();previousFocus=null;};
  function closeNav(){document.body.classList.remove('nav-open');}
  switchTab=function(tabId){baseSwitchTab(tabId);closeNav();document.querySelectorAll('#sidebar [data-nav],#sidebar [data-hub-nav]').forEach(function(el){el.setAttribute('aria-current',el.classList.contains('active')?'page':'false');});};
  function activateHub(tabId){
    switchTab(tabId);
    document.querySelectorAll('#sidebar .hub-label').forEach(function(el){el.classList.remove('active');});
    var label=document.querySelector('#sidebar [data-hub-nav="'+tabId+'"]');
    if(label){label.classList.add('active');var hub=label.closest('.nav-hub');if(hub)hub.classList.add('active');label.setAttribute('aria-current','page');}
  }
  var toggle=document.getElementById('mobileNavToggle'),backdrop=document.getElementById('mobileNavBackdrop');
  if(toggle)toggle.addEventListener('click',function(){document.body.classList.toggle('nav-open');});
  if(backdrop)backdrop.addEventListener('click',closeNav);
  var sidebar=document.getElementById('sidebar');
  if(sidebar)sidebar.addEventListener('click',function(e){
    var hub=e.target.closest('[data-hub-nav]'),nav=e.target.closest('[data-nav]');
    if(!hub&&!nav)return;
    e.preventDefault();e.stopImmediatePropagation();
    if(hub)activateHub(hub.getAttribute('data-hub-nav'));else switchTab(nav.getAttribute('data-nav'));
  },true);
  document.querySelectorAll('#sidebar .sb-nav a').forEach(function(link){
    link.setAttribute('role','button');link.tabIndex=0;
    link.addEventListener('keydown',function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();link.click();}});
    link.addEventListener('click',closeNav);
  });
  document.addEventListener('keydown',function(e){if(e.key==='Escape'){closeNav();if(document.getElementById('modalOverlay').classList.contains('show'))closeModal();}});
  function renderIcons(){if(window.lucide&&window.lucide.createIcons)window.lucide.createIcons({attrs:{'stroke-width':1.8}});}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',renderIcons);else renderIcons();
})();
