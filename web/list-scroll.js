(function(root){
'use strict';
// One event-driven controller for the whole document, independent of virtual rows.
root.createListScroll=function(){
 const rail=document.getElementById('main-scroll'),track=document.getElementById('scroll-track'),thumb=document.getElementById('scroll-thumb'),mark=thumb.firstElementChild;
 const up=document.getElementById('scroll-start'),down=document.getElementById('scroll-end'),app=document.getElementById('app'),nav=document.querySelector('.bottom-nav');
 const pointers=new Set();let accounts=false,frame=0,hold=null,maximum=0,travel=0,viewport=0,viewportWidth=0,trackHeight=0,hideTimer=0,keyboard=false;
 function hideLater(){clearTimeout(hideTimer);if(!rail.hidden&&!hold&&!(keyboard&&rail.contains(document.activeElement)))hideTimer=setTimeout(()=>rail.classList.add('idle'),440);}
 function wake(){rail.classList.remove('idle');hideLater();}
 const scroller=()=>document.scrollingElement;
 const locked=()=>document.body.classList.contains('overlay-open');
 const clamp=value=>Math.max(0,Math.min(maximum,value));
 function cancel(){
  const previous=hold;hold=null;if(previous)clearTimeout(previous.timer);
  thumb.classList.remove('dragging');hideLater();
  if(previous&&thumb.hasPointerCapture(previous.id))thumb.releasePointerCapture(previous.id);
 }
 function suspend(){pointers.clear();cancel();}
 function refresh(){
  frame=0;
  const page=scroller(),nextViewport=page.clientHeight,nextWidth=page.clientWidth,nextMaximum=Math.max(0,page.scrollHeight-nextViewport);
  rail.style.bottom=Math.max(15,innerHeight-nav.getBoundingClientRect().top+12)+'px';
  const available=accounts&&nextMaximum>0&&nav.getBoundingClientRect().top-12-parseFloat(getComputedStyle(rail).top)>=176;
  rail.hidden=!available||locked();
  const nextHeight=rail.hidden?0:track.clientHeight;
  if(nextMaximum!==maximum||nextViewport!==viewport||nextWidth!==viewportWidth||nextHeight!==trackHeight)cancel();
  maximum=nextMaximum;viewport=nextViewport;viewportWidth=nextWidth;trackHeight=nextHeight;
  if(rail.hidden){cancel();return;}
  const hitHeight=56;
  travel=Math.max(0,trackHeight-hitHeight);
  const top=clamp(page.scrollTop),ratio=top/maximum;
  const markHeight=40;
  thumb.style.height=hitHeight+'px';thumb.style.top=ratio*travel+'px';mark.style.height=markHeight+'px';
  // Keep the visible ball centered in its fixed-size touch target at every position.
  mark.style.top=(hitHeight-markHeight)/2+'px';
  thumb.setAttribute('aria-valuemax',String(maximum));thumb.setAttribute('aria-valuenow',String(Math.round(top)));
  thumb.setAttribute('aria-valuetext',root.InsfavI18n.text('List position: {percent}%',{percent:Math.round(ratio*100)}));
  up.disabled=top<=0;down.disabled=top>=maximum;
 }
 function schedule(){if(!frame)frame=requestAnimationFrame(refresh);}
 function move(value){if(rail.hidden||locked())return;scroller().scrollTo({top:clamp(value),behavior:'instant'});refresh();}
 up.onclick=()=>{cancel();move(0);};down.onclick=()=>{cancel();move(maximum);};
 thumb.onkeydown=event=>{
  let target;const top=scroller().scrollTop,page=Math.max(48,viewport-nav.getBoundingClientRect().height);
  if(event.key==='Home')target=0;else if(event.key==='End')target=maximum;
  else if(event.key==='ArrowUp'||event.key==='ArrowLeft')target=top-65;
  else if(event.key==='ArrowDown'||event.key==='ArrowRight')target=top+65;
  else if(event.key==='PageUp')target=top-page;else if(event.key==='PageDown')target=top+page;
  else if(event.key===' ')target=top+(event.shiftKey?-page:page);else return;
  event.preventDefault();keyboard=true;cancel();wake();move(target);
 };
 document.addEventListener('pointerdown',event=>{
  keyboard=false;if(rail.contains(event.target)&&!locked())wake();pointers.add(event.pointerId);if(pointers.size>1||!event.isPrimary)cancel();
 },true);
 thumb.onpointerdown=event=>{
  if(event.button!==0||!event.isPrimary||pointers.size!==1||rail.hidden||locked()||travel<=0)return;
  event.preventDefault();cancel();thumb.focus({preventScroll:true});
  hold={id:event.pointerId,x:event.clientX,y:event.clientY,lastY:event.clientY,active:false};
  clearTimeout(hideTimer);
  if(event.isTrusted)thumb.setPointerCapture(event.pointerId);
  const pending=hold;
  pending.timer=setTimeout(()=>{
   refresh();if(hold!==pending||document.hidden||locked())return;
   pending.active=true;pending.anchorY=pending.lastY;pending.anchorTop=scroller().scrollTop;
   thumb.classList.add('dragging');
  },250);
 };
 document.addEventListener('pointermove',event=>{
  if(!hold||event.pointerId!==hold.id)return;
  hold.lastY=event.clientY;
  if(!hold.active){if(Math.hypot(event.clientX-hold.x,event.clientY-hold.y)>8)cancel();return;}
  event.preventDefault();move(hold.anchorTop+(event.clientY-hold.anchorY)*maximum/travel);
 },{passive:false});
 function release(event){pointers.delete(event.pointerId);if(hold?.id===event.pointerId)cancel();}
 document.addEventListener('pointerup',release);document.addEventListener('pointercancel',release);
 thumb.addEventListener('lostpointercapture',cancel);thumb.oncontextmenu=event=>event.preventDefault();
 document.addEventListener('keydown',event=>{if(event.key==='Tab')keyboard=true;});
 rail.addEventListener('focusin',wake);rail.addEventListener('focusout',()=>queueMicrotask(hideLater));rail.addEventListener('pointerenter',wake);
 root.addEventListener('blur',suspend);root.addEventListener('pagehide',suspend);
 document.addEventListener('visibilitychange',()=>{if(document.hidden)suspend();});
 root.addEventListener('scroll',schedule,{passive:true});
 root.addEventListener('resize',()=>{cancel();schedule();},{passive:true});
 root.visualViewport?.addEventListener('resize',()=>{cancel();schedule();},{passive:true});
 new ResizeObserver(schedule).observe(app);new ResizeObserver(schedule).observe(nav);
 new MutationObserver(()=>{if(locked())suspend();schedule();}).observe(document.body,{attributes:true,attributeFilter:['class']});
 return {contentChanged(hasAccounts){cancel();accounts=hasAccounts;rail.classList.add('idle');refresh();},cancel:suspend,refresh:schedule};
};
})(window);
