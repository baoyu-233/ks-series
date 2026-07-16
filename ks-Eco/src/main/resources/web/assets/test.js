(function(){
  var frame=document.getElementById('appFrame');
  var status=document.getElementById('frameStatus');
  var state={view:'admin',scenario:'normal'};

  function frameUrl(){
    return state.view+'.html?preview=1&previewScenario='+encodeURIComponent(state.scenario);
  }

  function setActive(selector,key,value){
    document.querySelectorAll(selector).forEach(function(button){
      button.classList.toggle('active',button.dataset[key]===value);
    });
  }

  function loadFrame(){
    status.textContent='正在载入';
    status.className='status';
    frame.src=frameUrl();
  }

  document.querySelectorAll('[data-view]').forEach(function(button){
    button.addEventListener('click',function(){
      state.view=button.dataset.view;
      setActive('[data-view]','view',state.view);
      loadFrame();
    });
  });

  document.querySelectorAll('[data-scenario]').forEach(function(button){
    button.addEventListener('click',function(){
      state.scenario=button.dataset.scenario;
      setActive('[data-scenario]','scenario',state.scenario);
      loadFrame();
    });
  });

  document.getElementById('reloadFrame').addEventListener('click',loadFrame);
  document.getElementById('openFrame').addEventListener('click',function(){
    window.open(frameUrl(),'_blank','noopener');
  });
  frame.addEventListener('load',function(){
    status.textContent=state.scenario==='slow'?'慢接口就绪':'页面就绪';
    status.className='status ready';
  });
  frame.addEventListener('error',function(){
    status.textContent='页面载入失败';
    status.className='status error';
  });

  loadFrame();
})();
