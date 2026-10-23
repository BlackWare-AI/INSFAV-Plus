'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),http=require('node:http'),path=require('node:path');
const {chromium}=require('playwright');

(async()=>{
 const root=path.resolve(__dirname,'../app/src/main/assets');
 const server=http.createServer((req,res)=>{
  if(req.url.startsWith('/thumb/')){res.setHeader('Content-Type','image/svg+xml');res.end('<svg xmlns="http://www.w3.org/2000/svg" width="60" height="60"><rect width="60" height="60" fill="#444"/></svg>');return;}
  const file=path.resolve(root,'.'+(req.url==='/'?'/index.html':req.url.split('?')[0]));
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':file.endsWith('.woff2')?'font/woff2':file.endsWith('.jpg')?'image/jpeg':'text/html');res.end(fs.readFileSync(file));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,args:['--no-sandbox','--disable-gpu']});
 let checks=0;const errors=[];
 const check=(value,message)=>{assert.ok(value,message);checks++;};
 const settled=page=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 const renderedInk=(page,png)=>page.evaluate(async base64=>{
  const image=new Image();image.src='data:image/png;base64,'+base64;await image.decode();
  const canvas=document.createElement('canvas');canvas.width=image.width;canvas.height=image.height;
  const context=canvas.getContext('2d');context.drawImage(image,0,0);const pixels=context.getImageData(0,0,canvas.width,canvas.height).data;
  let ink=0;for(let i=0;i<pixels.length;i+=4)if(Math.max(pixels[i],pixels[i+1],pixels[i+2])>80)ink++;return ink;
 },png.toString('base64'));
 const metrics=page=>page.evaluate(()=>{
  const page=document.scrollingElement,track=document.getElementById('scroll-track'),thumb=document.getElementById('scroll-thumb');
  return {top:page.scrollTop,max:page.scrollHeight-page.clientHeight,viewport:page.clientHeight,track:track.clientHeight,hit:thumb.clientHeight,
   offset:parseFloat(thumb.style.top),mark:parseFloat(thumb.firstElementChild.style.height),markOffset:parseFloat(thumb.firstElementChild.style.top),value:Number(thumb.getAttribute('aria-valuenow')),maximum:Number(thumb.getAttribute('aria-valuemax')),
   active:thumb.classList.contains('dragging'),glow:getComputedStyle(thumb).boxShadow,frames:window.frameRequests,thumb:thumbnailMetrics()};
 });
 async function create(count,viewport={width:393,height:850}){
  const page=await browser.newPage({viewport,hasTouch:true,reducedMotion:'reduce',locale:'en-GB'});
  page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(n=>{
   const items=Array.from({length:n},(_,i)=>({pageId:'account_'+i,name:'A long account name for scrolling '+i}));
   window.fixture=JSON.stringify([{name:'First',items:items.slice(0,n/2)},{name:'Second',items:items.slice(n/2)}]);
   window.frameRequests=0;const raf=window.requestAnimationFrame;window.requestAnimationFrame=fn=>{frameRequests++;return raf(fn);};
   document.addEventListener('pointerdown',event=>{if(event.target.closest('#scroll-thumb'))window.thumbPointerId=event.pointerId;},true);
   window.commands=[];window.Android={request(raw){
    const command=JSON.parse(raw);commands.push(command);
    if(command.command==='bootstrap')nativeEvent('bootstrap',{raw:fixture,profiles:Object.fromEntries(items.map(item=>[item.pageId,{file:true,version:1}])),language:'en'});
    if(command.command==='language')nativeEvent('language',{choice:command.choice});
    if(command.command==='fetcher-results'){
     const outcomes=['SUCCESS','EXISTING','SKIPPED_UNAVAILABLE','SKIPPED_NO_IMAGE','FAILED','RETRY_LATER'];
     nativeEvent('fetcher-results',{status:command.status,users:items.filter((_,i)=>i%6===outcomes.indexOf(command.status)).map(item=>item.pageId)});
    }
   }};
  },count);
  await page.goto('http://127.0.0.1:'+server.address().port);
  await page.locator('#splash').waitFor({state:'hidden'});await settled(page);return page;
 }
 async function expand(page){
  await page.locator('.folder-header').nth(1).click();await page.locator('.folder-header').first().click();await settled(page);
 }
 async function layout(page,label){
  const result=await page.evaluate(()=>{
   const rail=document.getElementById('main-scroll').getBoundingClientRect(),nav=document.querySelector('.bottom-nav').getBoundingClientRect();
   const rows=[...document.querySelectorAll('.account')].map(node=>node.getBoundingClientRect());
   const targets=['scroll-start','scroll-end','scroll-thumb'].map(id=>document.getElementById(id).getBoundingClientRect());
   return {gap:Math.min(...rows.map(row=>rail.left-row.right)),edge:innerWidth-rail.right,navGap:nav.top-rail.bottom,
    targets:targets.every(rect=>rect.width===52&&rect.height>=52),rows:rows.every(rect=>rect.height===65),
    avatars:[...document.querySelectorAll('.avatar')].every(node=>node.clientWidth===60&&node.clientHeight===60),overflow:document.scrollingElement.scrollWidth>innerWidth,
    scrollbar:getComputedStyle(document.documentElement).scrollbarWidth};
  });
  check(result.gap<0&&result.edge>=6&&result.navGap>=12,label+': controls overlay the full-width list with edge/nav clearance '+JSON.stringify(result));
  check(result.targets&&result.rows&&result.avatars&&!result.overflow,label+': 48px targets, 65px rows, 60px avatars and no overflow');
  check(result.scrollbar==='none',label+': main native scrollbar is hidden');
  check(await page.locator('#scroll-thumb > span').evaluate(node=>{const r=node.getBoundingClientRect();return !document.getElementById('main-scroll').classList.contains('idle')||Math.abs(innerWidth-r.left-r.width/2)<1;}),label+': idle ball stays half exposed at the screen edge');
  check(await page.locator('#scroll-track').evaluate(node=>getComputedStyle(node,'::before').content==='none'),label+': rail stays invisible');
  check(await page.locator('#scroll-thumb > span').evaluate(node=>{const r=node.getBoundingClientRect(),s=getComputedStyle(node);return r.width===40&&r.height===40&&s.borderRadius==='50%'&&s.opacity==='1';}),label+': visible fixed round ball is easy to find');
  await page.emulateMedia({reducedMotion:'no-preference'});
  check(await page.locator('#scroll-end').evaluate(node=>{const s=getComputedStyle(node);return s.backgroundColor==='rgba(0, 0, 0, 0)'&&s.borderWidth==='0px'&&s.transitionProperty.includes('transform');}),label+': arrows have transparent interiors, no frame and sliding transitions');
  await page.emulateMedia({reducedMotion:'reduce'});
 }
 try{
  for(const count of [10,1000,10000,15000]){
   const page=await create(count),rail=page.locator('#main-scroll');
   check(await rail.isHidden(),'Closed '+count+'-account list hides controls');
   await expand(page);check(await rail.isVisible(),'Expanded '+count+'-account list shows controls');
   await layout(page,count+' accounts');
   let m=await metrics(page);check(m.top===0&&await page.locator('#scroll-start').isDisabled(),'Start is exactly zero and up is disabled');
   check(m.maximum===m.max,'ARIA range equals actual document range');
   check(m.offset===0&&m.markOffset===8,'The ball starts centered in its touch target');
   check(m.hit===56&&m.mark===40,'Ball and touch target stay fixed-size for every account count');
   await page.locator('#scroll-thumb').dispatchEvent('pointerenter');await page.locator('#scroll-end').tap();await settled(page);m=await metrics(page);
   check(m.top===m.max&&await page.locator('#scroll-end').isDisabled(),'One tap reaches the real end of '+count+' accounts');
   check(Math.abs(m.offset+m.hit-m.track)<1&&m.markOffset===8,'Fixed target reaches the real bottom with the ball centered');
   const finalRow=page.locator('[data-key="1:'+(count/2-1)+'"]');
   check(await finalRow.locator('.card-user').innerText()==='@account_'+(count-1),'Virtual list renders its actual final account');
   const last=await finalRow.boundingBox(),nav=await page.locator('.nav-container').boundingBox();
   check(last.y+last.height<=nav.y,'Final row is above bottom navigation');
   await page.locator('#scroll-thumb').dispatchEvent('pointerenter');await page.locator('#scroll-start').tap();await settled(page);check((await metrics(page)).top===0,'One tap returns to the real start');
   await page.evaluate(()=>scrollTo(0,(document.scrollingElement.scrollHeight-innerHeight)/2));await settled(page);m=await metrics(page);
   check(Math.abs(m.offset-(m.track-m.hit)*m.top/m.max)<1,'Thumb position follows the actual scroll ratio');
   check(m.markOffset===(m.hit-m.mark)/2,'The ball stays centered while position follows actual scroll ratio');
   check(m.thumb.rows<40&&m.thumb.active<=6&&m.thumb.retained<=64,'Virtual DOM and image pool remain bounded');
   await page.locator('#search-input').fill('account_'+(count-1));await page.waitForTimeout(200);await settled(page);
   check(await rail.isHidden()&&await page.locator('.account').count()===1,'Single-result search hides controls');
   await page.locator('#search-input').fill('account_');await page.waitForTimeout(200);await settled(page);
   check(await rail.isVisible(),'Search across all collections restores the full range');
   for(const [label,status,index]of [['Downloaded','SUCCESS',0],['Existing','EXISTING',1],['Skipped Unavailable','SKIPPED_UNAVAILABLE',2],['Skipped No Image','SKIPPED_NO_IMAGE',3],['Failed','FAILED',4],['Retry Later','RETRY_LATER',5]]){
    await page.locator('#result-filter').click();await page.getByRole('radio',{name:label,exact:true}).tap();await settled(page);
    check(await page.evaluate(code=>resultFilter===code&&rows.filter(row=>row.type==='account').every(row=>resultUsers.has(row.item.user)),status),label+' preserves filter/search intersection');
    check(await page.evaluate(()=>rows.filter(row=>row.type==='account').length)===Math.floor((count-1-index)/6)+1,label+' includes all matching accounts');
    await page.locator('#search-input').fill('account_'+index);await page.waitForTimeout(180);await settled(page);
    check(await page.locator('.card-user').first().innerText()==='@account_'+index,label+' retains matching search result');
    check(await page.evaluate(()=>rows.every(row=>row.type!=='account'||row.item.user.includes(query))),label+' excludes nonmatching search results');
    await page.locator('#search-input').fill('account_');await page.waitForTimeout(180);await settled(page);
   }
   await page.locator('#result-filter').click();await page.getByRole('radio',{name:'All accounts',exact:true}).tap();
   await page.locator('#clear-search').click();await settled(page);
   await page.evaluate(()=>scrollTo(0,0));await settled(page);
   await page.clock.install();await page.clock.pauseAt(new Date(Date.now()+1000));await page.clock.runFor(50);
   await page.locator('#scroll-thumb').dispatchEvent('pointerdown',{pointerId:1,pointerType:'touch',isPrimary:true,button:0,clientX:100,clientY:100});
   await page.clock.runFor(250);m=await metrics(page);check(m.active,count+' accounts: drag arms without jumping');
   await page.locator('#scroll-thumb').dispatchEvent('pointermove',{pointerId:1,pointerType:'touch',isPrimary:true,clientX:100,clientY:100+(m.track-m.hit)/2});
   await page.clock.runFor(32);m=await metrics(page);check(Math.abs(m.top-m.max/2)<=1,count+' accounts: drag ratio uses actual range');
   await page.locator('#scroll-thumb').dispatchEvent('pointerup',{pointerId:1,pointerType:'touch',isPrimary:true});
   await page.evaluate(()=>{opened.clear();rebuild(true);});await page.clock.runFor(32);
   check(await rail.isHidden(),'Collapsing every folder hides controls even after scrolling/filtering');
   await page.evaluate(()=>nativeEvent('cleared',''));await page.clock.runFor(32);check(await rail.isHidden(),'Empty collection hides controls');
   await page.close();
  }

  const closed=await create(10);
  await closed.evaluate(()=>nativeEvent('imported',{raw:JSON.stringify(Array.from({length:100},(_,i)=>({name:'Folder '+i,items:[{pageId:'closed_'+i}]}))),profiles:{}}));await settled(closed);
  check((await metrics(closed)).max>0&&await closed.locator('#main-scroll').isHidden(),'Even overflowing closed folder headers keep controls hidden');
  await closed.locator('.folder-header').first().click();await settled(closed);check(await closed.locator('#main-scroll').isVisible(),'Opening an account folder in an overflowing document shows controls');
  await closed.locator('.folder-header').first().click();await settled(closed);check(await closed.locator('#main-scroll').isHidden(),'Closing the final account folder hides controls while headers still overflow');
  await closed.close();
  const page=await create(15000);await expand(page);
  for(const viewport of [{width:320,height:480},{width:360,height:640},{width:393,height:850},{width:480,height:640},{width:480,height:320},{width:850,height:393}]){
   await page.setViewportSize(viewport);
   await page.evaluate(()=>{document.documentElement.style.fontSize='32px';});
   await page.addStyleTag({content:'.card-name{font-size:30px}.card-user{font-size:26px}'});await settled(page);
   await layout(page,viewport.width+'×'+viewport.height+' at 200% text');
   await page.locator('#scroll-thumb').dispatchEvent('pointerenter');await page.locator('#scroll-end').tap();await settled(page);check((await metrics(page)).top===(await metrics(page)).max,'Resized range ends at actual maximum');
   await page.locator('#scroll-thumb').dispatchEvent('pointerenter');await page.locator('#scroll-start').tap();await settled(page);
   if(process.env.SCROLL_SCREENSHOTS){fs.mkdirSync(process.env.SCROLL_SCREENSHOTS,{recursive:true});await page.screenshot({path:path.join(process.env.SCROLL_SCREENSHOTS,viewport.width+'x'+viewport.height+'.png')});}
  }
  await page.setViewportSize({width:393,height:850});await settled(page);
  // Freeze Chromium's clock so the activation boundary is asserted without wall-time tolerance.
  await page.clock.install();await page.clock.pauseAt(new Date(Date.now()+1000));await page.clock.runFor(50);
  const thumb=page.locator('#scroll-thumb');
  const pointer=async(type,{id=1,x=100,y=100,primary=true,target='#scroll-thumb'}={})=>page.locator(target).dispatchEvent(type,{pointerId:id,pointerType:'touch',isPrimary:primary,button:0,buttons:type==='pointerup'?0:1,clientX:x,clientY:y,bubbles:true});
  const active=async(expected,reason="")=>{const m=await metrics(page);check(m.active===expected&&(expected?m.glow!=='none':m.glow==='none'),reason+': Drag and glow are '+(expected?'active':'inactive'));};
  await pointer('pointerdown');await page.clock.runFor(249);await active(false);
  const before=(await metrics(page)).top;
  await page.clock.runFor(1);await active(true);check((await metrics(page)).top===before,'Activation at exactly 250ms causes no jump');
  let m=await metrics(page),travel=m.track-m.hit;
  await pointer('pointermove',{y:100+travel/2});await page.clock.runFor(32);m=await metrics(page);
  check(Math.abs(m.top-m.max/2)<=1,'Dragging half the rail maps to half the real range');
  await pointer('pointermove',{y:100+travel*5});await page.clock.runFor(32);m=await metrics(page);check(m.top===m.max,'Dragging beyond end clamps exactly to maximum');
  await pointer('pointermove',{y:100-travel*5});await page.clock.runFor(32);check((await metrics(page)).top===0,'Dragging above start clamps exactly to zero');
  await pointer('pointerup');await active(false);
  await pointer('pointerdown');await page.clock.runFor(249);await active(false);await pointer('pointerup');await page.clock.runFor(1);await active(false);
  for(const stop of ['release','move','cancel','lostcapture','multitouch','blur','visibility','pagehide','native-background','modal','list-change','resize']){
   await pointer('pointerdown');await page.clock.runFor(100);
   if(stop==='release')await pointer('pointerup');
   if(stop==='move')await pointer('pointermove',{x:109});
   if(stop==='cancel')await pointer('pointercancel');
   if(stop==='lostcapture')await thumb.dispatchEvent('lostpointercapture');
   if(stop==='multitouch'){await pointer('pointerdown',{id:2,primary:false,target:'#list'});await pointer('pointerup',{id:2,primary:false,target:'#list'});}
   if(stop==='blur')await page.evaluate(()=>dispatchEvent(new Event('blur')));
   if(stop==='visibility')await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));});
   if(stop==='pagehide')await page.evaluate(()=>dispatchEvent(new Event('pagehide')));
   if(stop==='native-background')await page.evaluate(()=>nativeEvent('app-background',''));
   if(stop==='modal')await page.locator('#result-filter').dispatchEvent('click');
   if(stop==='list-change')await page.evaluate(()=>{opened.delete(1);rebuild(true);});
   if(stop==='resize')await page.setViewportSize({width:394,height:850});
   await page.clock.runFor(150);await active(false,stop);check((await metrics(page)).top===0,stop+' cancels without scrolling');
   if(stop==='modal'){check(await page.locator('#main-scroll').isHidden(),'Open modal hides controls');await page.keyboard.press('Escape');}
   if(stop==='visibility')await page.evaluate(()=>{delete document.hidden;});
   if(stop==='list-change')await page.evaluate(()=>{opened.add(1);rebuild(true);});
   await pointer('pointerup');await page.clock.runFor(32);
  }
  await page.evaluate(()=>listScroll.refresh());await page.clock.runFor(64);
  await pointer('pointerdown');await pointer('pointermove',{x:108});await page.clock.runFor(250);await active(true);check((await metrics(page)).top===0,'Exactly 8px of pre-hold movement is tolerated without a jump');
  await pointer('pointerup');await active(false);
  // Short presses and the empty rail never scroll.
  await pointer('pointerdown');await page.clock.runFor(50);await pointer('pointerup');await page.clock.runFor(250);check((await metrics(page)).top===0,'A short thumb press does not scroll');
  await pointer('pointerdown',{target:'#scroll-track'});await page.clock.runFor(250);await pointer('pointerup',{target:'#scroll-track'});check((await metrics(page)).top===0,'Empty track press does not scroll');
  // Cancellation also extinguishes an already active drag.
  for(const stop of ['pointercancel','native-background','modal','list-change','multitouch']){
   await pointer('pointerdown');await page.clock.runFor(250);await active(true);
   if(stop==='pointercancel')await pointer('pointercancel');
   if(stop==='native-background')await page.evaluate(()=>nativeEvent('app-background',''));
   if(stop==='modal')await page.locator('#result-filter').dispatchEvent('click');
   if(stop==='list-change')await page.evaluate(()=>rebuild(true));
   if(stop==='multitouch'){await pointer('pointerdown',{id:2,primary:false});await pointer('pointerup',{id:2,primary:false});}
   await page.clock.runFor(32);await active(false);await pointer('pointerup');
   if(stop==='modal'){await page.keyboard.press('Escape');await page.clock.runFor(32);}
  }
  await thumb.focus();await page.keyboard.press('ArrowDown');await page.clock.runFor(32);check((await metrics(page)).top===65,'Keyboard down scrolls one account row immediately');
  await page.keyboard.press('PageDown');await page.clock.runFor(32);check((await metrics(page)).top>65,'PageDown scrolls by a viewport');
  await page.keyboard.press('End');await page.clock.runFor(32);m=await metrics(page);check(m.top===m.max,'Keyboard End reaches exact end');
  await page.keyboard.press('Home');await page.clock.runFor(32);check((await metrics(page)).top===0,'Keyboard Home reaches exact start');
  check(await thumb.evaluate(node=>node===document.activeElement),'Scrollbar keeps keyboard focus while virtual rows recycle');
  await page.evaluate(()=>InsfavI18n.choose('tr'));await page.clock.runFor(32);
  check(await page.getByRole('button',{name:'Listenin başına git',exact:true}).count()===1&&await thumb.getAttribute('aria-valuetext')==='Liste konumu: %0','Controls and position announce Turkish');
  await page.evaluate(()=>InsfavI18n.choose('en'));await page.clock.runFor(32);
  check(await page.getByRole('scrollbar',{name:'List position',exact:true}).count()===1,'Scrollbar announces English after language change');
  await pointer('pointerdown');await pointer('pointerup');
  const visibleName=await renderedInk(page,await page.locator('.card-name').first().screenshot());
  await page.clock.runFor(2999);
  check(await page.locator('#main-scroll').evaluate(node=>!node.classList.contains('idle')),'Controls stay visible before 3000ms');await page.clock.runFor(1);
  check(await page.locator('#main-scroll').evaluate(node=>node.classList.contains('idle')),'Unused controls fade after inactivity');
  check(await page.locator('#scroll-end').evaluate(node=>getComputedStyle(node).opacity==='0'),'Unused arrows disappear');
  const dockedName=await renderedInk(page,await page.locator('.card-name').first().screenshot());
  check(visibleName>100&&dockedName>=visibleName*.8&&dockedName<=visibleName*1.2,'Docking the translucent ball preserves rendered account text: '+visibleName+'/'+dockedName);
  check(await page.locator('#scroll-thumb > span').evaluate(node=>getComputedStyle(node).backdropFilter==='none'),'Offscreen docking disables blur to avoid Chromium text damage');
  check(await page.locator('#scroll-thumb > span').evaluate(node=>{const r=node.getBoundingClientRect();return Math.abs(innerWidth-r.left-r.width/2)<1;}),'Exactly half the idle ball remains visible at the physical right edge');
  const tapTop=(await metrics(page)).top;
  await pointer('pointerdown');await pointer('pointerup');
  check(await page.locator('#scroll-thumb > span').evaluate(node=>node.getBoundingClientRect().right<=innerWidth-6),'One tap fully reveals the ball in its original position');
  check((await metrics(page)).top===tapTop,'Revealing with a short tap never scrolls the list');
  check(await page.locator('#scroll-thumb > span').evaluate(node=>{const s=getComputedStyle(node);const color=s.backgroundColor.match(/[\d.]+/g).map(Number),blur=parseFloat(s.backdropFilter.slice(5));return color.length===4&&color[3]>0&&color[3]<.3&&blur>0&&blur<=2.5;}),'The ball has a translucent, lightly blurred interior');
  await page.clock.runFor(2999);check(await page.locator('#main-scroll').evaluate(node=>!node.classList.contains('idle')),'A tap restarts the full 3-second idle delay');
  await page.clock.runFor(1);check(await page.locator('#main-scroll').evaluate(node=>node.classList.contains('idle')),'The ball docks again after 3 seconds without touch');
  check(await page.locator('#scroll-grip').count()===0,'The protruding grip is removed');
  const idleWidth=await page.locator('#list').evaluate(node=>node.clientWidth);
  await pointer('pointerdown',{target:'#list'});await pointer('pointerup',{target:'#list'});await page.evaluate(()=>scrollTo(0,65));await page.clock.runFor(32);
  check(await page.locator('#main-scroll').evaluate(node=>node.classList.contains('idle')),'Normal list interaction does not reveal controls');
  await page.evaluate(()=>scrollTo(0,0));await page.clock.runFor(32);
  await pointer('pointerdown',{target:'#scroll-thumb'});await page.clock.runFor(249);await active(false);await page.clock.runFor(1);await active(true);
  await page.clock.runFor(3000);check(await page.locator('#main-scroll').evaluate(node=>!node.classList.contains('idle')),'Active dragging remains visible');
  check(await page.locator('#list').evaluate(node=>node.clientWidth)===idleWidth,'Revealing controls never resizes the list');
  await pointer('pointerup',{target:'#scroll-thumb'});await page.clock.runFor(3000);
  const frames=(await metrics(page)).frames;await page.clock.runFor(10000);check((await metrics(page)).frames===frames,'Idle controls do not poll or schedule continuous frames');
  await page.close();

  // Trusted Chromium touch input verifies capture, cancellation and normal list swiping.
  const touchPage=await create(1000);await expand(touchPage);const cdp=await touchPage.context().newCDPSession(touchPage);
  await touchPage.clock.install();await touchPage.clock.pauseAt(new Date(Date.now()+1000));await touchPage.clock.runFor(50);
  const box=await touchPage.locator('#scroll-thumb').boundingBox(),point={x:box.x+24,y:box.y+box.height/2,id:1};
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[point]});await touchPage.clock.runFor(250);
  check((await metrics(touchPage)).active,'Trusted touch activates after 250ms');
  check(await touchPage.locator('#scroll-thumb').evaluate(node=>node.hasPointerCapture(window.thumbPointerId)),'Trusted gesture captures the active pointer');
  await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{...point,y:point.y+70}]});await touchPage.clock.runFor(32);
  check((await metrics(touchPage)).top>0,'Trusted captured touch drags the document');
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await touchPage.clock.runFor(32);check(!(await metrics(touchPage)).active,'Trusted release removes drag and glow');
  await touchPage.evaluate(()=>scrollTo(0,0));await touchPage.clock.runFor(32);
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:160,y:600,id:1}]});
  for(const y of [570,520,460,400,320]){await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:160,y,id:1}]});await touchPage.clock.runFor(32);}
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await touchPage.clock.runFor(100);
  check((await metrics(touchPage)).top>0,'Ordinary touch swiping on the main list remains available');
  await touchPage.close();
  check(errors.length===0,'No browser errors: '+errors.join('; '));
  console.log('PASS '+checks+' main-list scroll checks: 10/1000/10000/15000, actual bounds/ratios, 249/250ms, cancellations/glow, real touch, keyboard, TR/EN, layouts and bounded resources');
 }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
