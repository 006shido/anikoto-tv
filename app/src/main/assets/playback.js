(function(){
  if(window.__tvCommand)return;
  var token='__TOKEN__';
  var playbackRate=__SPEED__;
  function skin(){
    if(document.head&&!document.getElementById('tv-native-controls')){
      var css=document.createElement('style');css.id='tv-native-controls';
      css.textContent='.jw-controlbar,.jw-display,.jw-title,.jw-settings-menu,.jw-controls-backdrop,.jw-nextup,.jw-tooltip,.jw-intro,.jw-outro,.plyr__controls,.plyr__control--overlaid,.vjs-control-bar,.vjs-big-play-button{display:none!important;visibility:hidden!important;pointer-events:none!important} [id^="skip-intro"],[id^="skip-outro"]{opacity:0!important;pointer-events:none!important} video::-webkit-media-controls{display:none!important} .tv-dock-visible .jw-captions{bottom:27vh!important} video{object-fit:contain}';
      document.head.appendChild(css);
    }
    document.querySelectorAll('video').forEach(function(v){v.controls=false;});
  }
  document.addEventListener('DOMContentLoaded',skin);
  function captions(video){
    try{
      if(typeof jwplayer==='function'&&typeof jwplayer().getCaptionsList==='function'){
        var p=jwplayer(),list=p.getCaptionsList();
        if(list.length)return {list:list.map(function(t){return String(t.label||'Subtitle');}),selected:p.getCurrentCaptions()};
      }
    }catch(e){}
    var tracks=Array.from(video.textTracks).filter(function(t){return t.kind==='subtitles'||t.kind==='captions';});
    return {list:['Off'].concat(tracks.map(function(t,i){return t.label||t.language||'Track '+(i+1);})),selected:tracks.findIndex(function(t){return t.mode==='showing';})+1};
  }
  function command(action){
    if(action==='skip:intro'||action==='skip:outro'){
      var skip=document.querySelector('[id^="skip-'+action.slice(5)+'"]');if(skip)skip.click();
    }
    if(action.indexOf('overlay:')===0&&document.documentElement)document.documentElement.classList.toggle('tv-dock-visible',action==='overlay:on');
    if(action.indexOf('speed:')===0){
      var rate=Number(action.slice(6));
      if(rate>=0.5&&rate<=2)playbackRate=rate;
    }
    if(action==='play'||action==='toggle'){
      var start=document.querySelector('#player > .play');
      if(start&&getComputedStyle(start).display!=='none')start.click();
    }
    document.querySelectorAll('video').forEach(function(v){
      try {
        v.playbackRate=playbackRate;
        if(action==='toggle') {if(v.paused)v.play().catch(function(){});else v.pause();}
        if(action==='play')v.play().catch(function(){});
        if(action==='pause')v.pause();
        if(action==='forward')v.currentTime=Math.min(isFinite(v.duration)?v.duration:Infinity,v.currentTime+10);
        if(action==='rewind')v.currentTime=Math.max(0,v.currentTime-10);
        if(action.indexOf('seek:')===0){var position=Number(action.slice(5));if(isFinite(position))v.currentTime=Math.max(0,Math.min(isFinite(v.duration)?v.duration:position,position));}
        if(action.indexOf('captions:')===0){
          var index=Number(action.slice(9));
          if(typeof jwplayer==='function'&&jwplayer().getCaptionsList().length)jwplayer().setCurrentCaptions(index);
          else Array.from(v.textTracks).filter(function(t){return t.kind==='subtitles'||t.kind==='captions';}).forEach(function(t,i){t.mode=i===index-1?'showing':'disabled';});
        }
      } catch(e){}
    });
    document.querySelectorAll('iframe').forEach(function(f){f.contentWindow.postMessage({tvToken:token,action:action},'*');});
  }
  window.__tvCommand=command;
  addEventListener('message',function(e){if(e.source===parent&&e.data&&e.data.tvToken===token)command(e.data.action);});
  setInterval(function(){
    skin();
    document.querySelectorAll('video').forEach(function(v){if(v.playbackRate!==playbackRate)v.playbackRate=playbackRate;});
    var video=Array.from(document.querySelectorAll('video')).find(function(v){return v.readyState>0;});
    var skips={intro:false,outro:false};
    ['intro','outro'].forEach(function(type){var e=document.querySelector('[id^="skip-'+type+'"]');if(e){var s=getComputedStyle(e);skips[type]=s.display!=='none'&&s.visibility!=='hidden'&&e.getBoundingClientRect().width>0;}});
    if(video&&window.tvPlayback)tvPlayback.postMessage(JSON.stringify({time:video.currentTime,duration:isFinite(video.duration)?video.duration:0,paused:video.paused,captions:captions(video),skips:skips}));
  },1000);
})();
