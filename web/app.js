'use strict';
const C=InsfavCore,$=id=>document.getElementById(id),native=!!window.Android,I=window.InsfavI18n,t=(text,args)=>I.text(text,args),uiText=(node,text)=>I.bind(node,text);
let bootstrapReady=false,bootstrapTimer;
let choiceOpener=null;
const listScroll=createListScroll();
let viewMode=native?0:(()=>{try{return Number(localStorage.getItem('insfav-plus-columns'))||0;}catch(_){return 0;}})();
if(![0,2,3,4].includes(viewMode))viewMode=0;
function releaseRow(node){for(const button of node.querySelectorAll('.avatar'))releaseAvatar(button);}
function gridGeometry(){if(!viewMode)return null;const width=$('list').clientWidth,tile=(width-18-8*(viewMode-1))/viewMode,label=Math.max(24,Math.ceil(parseFloat(getComputedStyle(document.documentElement).fontSize)*.8125*1.45+6));$('list').style.setProperty('--grid-columns',viewMode);$('list').style.setProperty('--tile-size',tile+'px');$('list').style.setProperty('--grid-label-height',label+'px');return {columns:viewMode,height:tile+label+8};}
function applyViewMode(columns){viewMode=[0,2,3,4].includes(Number(columns))?Number(columns):0;$('app').classList.toggle('matrix-mode',!!viewMode);rebuild();updateViewChoice();}

let doc=null,profiles={},stats={usage:0,load:0,clear:0},state={},opened=new Set(),query='',rows=[],positions=[0],mounted=new Map(),scheduled=false,previousFocus=null,lastFocus=null,viewerUser='',toastTimer=0,cacheVersion=0,lastRevision='',imageFailureTimer=0,fullLoader=null,appInfo={},fetcherState={},viewerToken='',remoteLoader=null,remoteShown=false,fetcherPoll=0;
function placeholder(button){const icon=document.createElement('span');icon.className='person-placeholder';icon.setAttribute('aria-hidden','true');button.replaceChildren(icon);}
const motionStyle=getComputedStyle(document.documentElement),motion={duration:parseFloat(motionStyle.getPropertyValue('--motion-base'))||160,easing:motionStyle.getPropertyValue('--motion-ease').trim()||'ease-out'};
function request(command,extra={}){if(native)Android.request(JSON.stringify({command,...extra}));else previewCommand(command,extra);}
function message(text){const copied=String(text).match(/^(\d+) usernames copied\.$/),outside=String(text).match(/^Outside working hours\. Configured window: (\d{2}:\d{2})–(\d{2}:\d{2})\. Next eligible time: (.+)\.$/);if(copied)text=t('{count} usernames copied.',{count:copied[1]});if(outside)text=t('Outside working hours. Configured window: {start}–{end}. Next eligible time: {next}.',{start:outside[1],end:outside[2],next:outside[3]});$('toast').textContent=t(text);$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4200);}
function setDocument(raw){const next=C.parseDocument(raw);doc=next;opened.clear();query='';$('search-input').value='';$('clear-search').hidden=true;rebuild();}
window.nativeEvent=(event,data)=>{
 try{
  if(event==='bootstrap'||event==='imported'){
   if(event==='bootstrap'&&data.columns!==undefined)viewMode=[0,2,3,4].includes(data.columns)?data.columns:0;
   if(data.language)I.choose(data.language);profiles=data.profiles||{};state=data;if(data.app)appInfo=data.app;if(data.stats)stats=data.stats;
   if(data.raw)setDocument(data.raw);else{doc=null;rebuild();}
   if(event==='imported'){stats.load++;message('JSON imported. All original fields preserved.');}
   bootstrapReady=true;clearTimeout(bootstrapTimer);$('startup-error').hidden=true;updateSettings();$('splash').hidden=true;
  }
  if(event==='status'){
   if(data.folderMessage&&data.folderMessage!==state.folderMessage)message(data.folderMessage);
   const oldProfiles=profiles;if(data.profiles)profiles=data.profiles;else if(data.profileChanges)profiles={...profiles,...data.profileChanges};state={...state,...data};
   const revision=[data.revision,data.matched,data.missing,data.unreadable,data.folderStatus,data.scopeRevision].join(':');
   if(revision!==lastRevision){lastRevision=revision;cacheVersion++;refreshAvatars(oldProfiles);}
   updateSettings();
  }
  if(event==='view-mode')applyViewMode(data.columns);
  if(event==='app-background')listScroll.cancel();
  if(event==='inventory')renderInventory(data);
  if(event==='performance')renderPerformance(data);
  if(event==='history')renderHistory(data);
  if(event==='fetcher-results'&&data.status===resultFilter){resultUsers=new Set(data.users);rebuild();}
  if(event==='error'&&!bootstrapReady)showStartupFailure();
  if(event==='image-cache-cleared')clearBrowserImages();
  if(event==='thumbnail-cleanup'){message(`${data.deleted} deleted · ${data.preserved} preserved · ${data.errors} errors. ${data.state}`);}
  if(event==='scan-progress'){state.scan=data;state.scanState=data.state;updateSettings();}
  if(event==='cleared'){doc=null;stats.clear++;opened.clear();rebuild();message('Collections cleared. Local photos are unchanged.');request('status');}
  if(event==='cookie-paste'&&!$('fetcher-screen').hidden)$('cookie-text').value=data;
  if(event==='fetcher-status'){
   const revision=[fetcherState.imageRevision||0,fetcherState.sourceRevision||0].join(':');fetcherState=data;updateFetcher();
   if((data.imageRevision||data.sourceRevision)&&[data.imageRevision||0,data.sourceRevision||0].join(':')!==revision)request('status');
  }
  if(event==='remote-full'&&viewerToken===data.token&&viewerUser===data.user){
   if(remoteLoader){remoteLoader.onload=remoteLoader.onerror=null;remoteLoader.src='';}
   remoteLoader=new Image();remoteLoader.onload=()=>{if(viewerToken===data.token){remoteShown=true;$('full-image').src=remoteLoader.src;$('viewer-caption').textContent='@'+data.user+' · '+t('Instagram image')+' · '+data.width+' × '+data.height;applyTransform();}};
   remoteLoader.src='/remote-full/'+encodeURIComponent(data.token);
  }
  if(event==='remote-full-failed'&&viewerToken===data.token){$('viewer-caption').textContent+=' · '+t('Online image unavailable; local image retained');}
  if(event==='language')I.choose(data.choice);if(event==='message'||event==='error')message(data);
 }catch(error){if(event==='bootstrap'||!bootstrapReady)showStartupFailure();else message(t('Unable to read data')+': '+error.message);}
};
function rebuild(preserve=false){
 listScroll.cancel();
 let groups=doc?C.filterCollections(doc,query):[];
 if(resultFilter)groups=groups.map(group=>({...group,items:group.items.filter(item=>resultUsers.has(item.user))})).filter(group=>group.items.length);
 $('filter-empty').hidden=!doc||groups.length>0;
 $('app').classList.toggle('matrix-mode',!!viewMode);
 rows=C.rowsFor(groups,opened,!!query.trim(),gridGeometry());positions=C.offsets(rows);
 $('list').style.height=positions.at(-1)+'px';
 if(!preserve){for(const node of mounted.values()){releaseRow(node);node.remove();}mounted.clear();}
 $('empty').hidden=!!doc;$('search-info').classList.toggle('visible',!!query.trim()||!!resultFilter);
 $('search-info').textContent=groups.reduce((count,collection)=>count+collection.items.length,0)+' '+t('RESULTS');renderVisible();listScroll.contentChanged(rows.some(row=>row.type==='account'||row.type==='matrix'));
}
function highlighted(el,text){el.replaceChildren();const q=query.trim();if(!q){el.textContent=text;return;}const displayName=el.classList.contains('card-name'),source=displayName?C.foldDisplay(text):text.toLocaleLowerCase('en'),needle=displayName?C.foldDisplay(q):q.toLocaleLowerCase('en');let p=0,i;while((i=source.indexOf(needle,p))!==-1){el.append(document.createTextNode(text.slice(p,i)));const mark=document.createElement('mark');mark.textContent=text.slice(i,i+q.length);el.append(mark);p=i+q.length;}el.append(document.createTextNode(text.slice(p)));}
function notifyImageFailure(){clearTimeout(imageFailureTimer);imageFailureTimer=setTimeout(()=>request('image-failed'),180);}
// A bounded pool of browser Image elements, never a native bitmap or disk cache.
const avatarImages=new Map(),avatarMetrics={hits:0,misses:0,cancelled:0,loaded:0,loadMs:0},rowPool=[];
let avatarActive=0,scrollTopLast=0,scrollTimeLast=0,scrollVelocity=0;
function clearBrowserImages(){
 for(const entry of avatarImages.values()){entry.status='cancelled';entry.img.onload=entry.img.onerror=null;entry.img.removeAttribute('src');}
 avatarImages.clear();avatarActive=0;
 for(const node of mounted.values())for(const button of node.querySelectorAll('.avatar'))avatarFor(button,button.dataset.user);
 prioritizeAvatars(0);
}
function avatarVersion(user){return profiles[user]?.version??cacheVersion;}
function releaseAvatar(button){
 const entry=button._imageEntry;if(entry){entry.buttons.delete(button);button._imageEntry=null;button.replaceChildren();}
}
function attachAvatar(button,entry){
 if(button._imageEntry!==entry)return;
 const image=entry.img.isConnected?entry.img.cloneNode():entry.img;
 image.onload=image.onerror=null;button.replaceChildren(image);
}
function pumpAvatars(){
 for(const entry of [...avatarImages.values()].filter(e=>e.status==='pending').sort((a,b)=>a.priority-b.priority)){
  if(avatarActive>=6)break;if(!entry.buttons.size)continue;
  entry.status='loading';avatarActive++;entry.started=performance.now();
  const finish=ok=>{
   if(entry.status!=='loading')return;avatarActive--;entry.status=ok?'ready':'failed';entry.img.onload=entry.img.onerror=null;
   if(ok){avatarMetrics.loaded++;avatarMetrics.loadMs+=performance.now()-entry.started;for(const button of entry.buttons)attachAvatar(button,entry);}
   else{for(const button of entry.buttons)if(button._imageEntry===entry)placeholder(button);notifyImageFailure();}
   pumpAvatars();
  };
  entry.img.fetchPriority=entry.priority===0?'high':'low';entry.img.onload=()=>finish(true);entry.img.onerror=()=>finish(false);entry.img.src=entry.url;
 }
}
function avatarFor(button,user){
 releaseAvatar(button);button.dataset.user=user;
 if(!profiles[user]?.file){placeholder(button);return;}
 const key=user+'|'+avatarVersion(user);let entry=avatarImages.get(key);
 if(entry){avatarMetrics.hits++;entry.used=performance.now();if(entry.status==='failed')entry.status='pending';}
 else{avatarMetrics.misses++;const img=new Image();img.alt='';img.decoding='async';img.loading='eager';entry={img,url:'/thumb/'+encodeURIComponent(user)+'?v='+encodeURIComponent(avatarVersion(user)),status:'pending',buttons:new Set(),priority:0,used:performance.now()};avatarImages.set(key,entry);}
 button._imageEntry=entry;entry.buttons.add(button);
 if(entry.status==='ready')attachAvatar(button,entry);else placeholder(button);
}
function prioritizeAvatars(top){
 for(const entry of avatarImages.values()){entry.priority=Infinity;for(const button of entry.buttons){const rect=button.getBoundingClientRect();entry.priority=Math.min(entry.priority,rect.bottom>=0&&rect.top<=innerHeight?0:(scrollVelocity>=0?rect.top<0:rect.bottom>innerHeight)?10000:Math.abs(rect.top));}}
 for(const [key,entry]of avatarImages)if(!entry.buttons.size && (entry.status==='pending'||entry.status==='loading')){
  if(entry.status==='loading'){avatarActive--;entry.status='cancelled';entry.img.onload=entry.img.onerror=null;entry.img.removeAttribute('src');avatarMetrics.cancelled++;}avatarImages.delete(key);
 }
 const idle=[...avatarImages.entries()].filter(([,e])=>!e.buttons.size).sort((a,b)=>a[1].used-b[1].used);
 while(avatarImages.size>64&&idle.length){const [key,entry]=idle.shift();entry.img.removeAttribute('src');avatarImages.delete(key);}
 pumpAvatars();
}
window.thumbnailMetrics=()=>({...avatarMetrics,active:avatarActive,retained:avatarImages.size,rows:mounted.size,pool:rowPool.length});
function showAccountDetails(item){const body=document.createElement('div');body.className='account-details';for(const value of [item.name,'@'+item.source.pageId.replace(/^@/,'')]){const p=document.createElement('p');p.textContent=value;body.append(p);}showDialog(t('Account details'),body,[{label:'Open profile',run:()=>request('open',{user:item.user})},{label:'Close'}]);}
function bindAccountDetails(link,item){
 clearTimeout(link._hold);let suppress=false,start;const cancel=()=>clearTimeout(link._hold),open=()=>{cancel();if(!suppress&&link.isConnected){suppress=true;showAccountDetails(item);}};
 link.title=item.name+' (@'+item.user+') · '+t('Press and hold to read the full name and username.');link.setAttribute('aria-label',item.name+' (@'+item.user+')');
 link.onclick=e=>{if(suppress){suppress=false;e.preventDefault();return;}request('open',{user:item.user});};
 link.oncontextmenu=e=>{e.preventDefault();open();};link.onkeydown=e=>{if(e.key==='ContextMenu'||e.key==='F10'&&e.shiftKey){e.preventDefault();open();}else if(e.key==='Enter'||e.key===' ')suppress=false;};
 link.onpointerdown=e=>{suppress=false;cancel();if(e.pointerType==='touch'||e.pointerType==='pen'){start={x:e.clientX,y:e.clientY};link._hold=setTimeout(open,550);}};
 link.onpointermove=e=>{if(start&&Math.hypot(e.clientX-start.x,e.clientY-start.y)>8)cancel();};link.onpointerup=link.onpointercancel=cancel;
}
function makeRow(r,index){const reused=r.type==='account'?rowPool.pop():null;const node=reused||document.createElement('div');node.className='virtual-row';node.style.top=positions[index]+'px';node.style.height=r.height+'px';node.dataset.key=r.key;
 if(r.type==='spacer'){node.setAttribute('aria-hidden','true');return node;}
 if(r.type==='folder'){const open=!!query.trim()||opened.has(r.group.key);node.classList.add('folder-wrap');node.classList.toggle('open',open);const head=document.createElement('button');head.className='folder-header';head.setAttribute('aria-expanded',String(open));const title=document.createElement('span');title.className='folder-title';const name=document.createElement('span');name.textContent=r.group.name.toUpperCase();const count=document.createElement('span');count.className='folder-count';count.textContent=r.group.items.length;title.append(name,count);const arrow=document.createElement('span');arrow.className='folder-arrow';arrow.textContent='›';head.append(title,arrow);head.onclick=()=>{if(query.trim()){message('Clear search to collapse collections.');return;}opened.has(r.group.key)?opened.delete(r.group.key):opened.add(r.group.key);rebuild(true);};node.append(head);}
 else if(r.type==='matrix'){
  node.classList.add('matrix-row');node.classList.toggle('last',r.last);
  for(const item of r.items){const cell=document.createElement('div');cell.className='matrix-account';const avatar=document.createElement('button');avatar.className='avatar';avatar.setAttribute('aria-label',t('View profile image for {name} (@{user})',{name:item.name,user:item.user}));avatarFor(avatar,item.user);avatar.onclick=()=>openViewer(item.user);const name=document.createElement('button');name.className='matrix-user';name.textContent='@'+item.source.pageId.replace(/^@/,'');name.title=name.textContent;name.setAttribute('aria-label',name.textContent);name.onclick=()=>request('open',{user:item.user});cell.append(avatar,name);node.append(cell);}
 }
 else if(reused){node.classList.add('account');node.classList.toggle('last',r.last);const a=node.querySelector('.avatar'),link=node.querySelector('.card-link');a.setAttribute('aria-label',t('View profile image for {name} (@{user})',{name:r.item.name,user:r.item.user}));avatarFor(a,r.item.user);a.onclick=()=>openViewer(r.item.user);highlighted(link.querySelector('.card-name'),r.item.name);highlighted(link.querySelector('.card-user'),'@'+r.item.source.pageId.replace(/^@/,''));bindAccountDetails(link,r.item);}
 else{node.classList.add('account');node.classList.toggle('last',r.last);const a=document.createElement('button');a.className='avatar';a.setAttribute('aria-label',t('View profile image for {name} (@{user})',{name:r.item.name,user:r.item.user}));avatarFor(a,r.item.user,r.item.name);a.onclick=()=>openViewer(r.item.user);const link=document.createElement('button');link.className='card-link';const name=document.createElement('div');name.className='card-name';highlighted(name,r.item.name);const username=document.createElement('div');username.className='card-user';highlighted(username,'@'+r.item.source.pageId.replace(/^@/,''));link.append(name,username);bindAccountDetails(link,r.item);node.append(a,link);}
 return node;
}
function renderVisible(){
 scheduled=false;const list=$('list'),top=-list.getBoundingClientRect().top;
 const now=performance.now(),elapsed=now-scrollTimeLast;if(elapsed>0&&elapsed<160)scrollVelocity=(top-scrollTopLast)/elapsed;else scrollVelocity=0;scrollTopLast=top;scrollTimeLast=now;
 const [start,end]=C.windowRange(positions,top,window.innerHeight,viewMode?100:C.overscanFor(scrollVelocity)),wanted=new Set(),fragment=document.createDocumentFragment();
 for(let i=start;i<end;i++){
  const row=rows[i];wanted.add(row.key);let node=mounted.get(row.key);
  if(!node){node=makeRow(row,i);mounted.set(row.key,node);fragment.append(node);}
  else{
   const position=positions[i]+'px';if(node.style.top!==position)node.style.top=position;
   const height=row.height+'px';if(node.style.height!==height)node.style.height=height;
   if(row.type==='folder'){const open=!!query.trim()||opened.has(row.group.key);node.classList.toggle('open',open);node.querySelector('button').setAttribute('aria-expanded',String(open));}
  }
 }
 list.append(fragment);
 for(const [key,node]of mounted)if(!wanted.has(key)){
  const button=node.classList.contains('account')?node.querySelector('.avatar'):null;releaseRow(node);
  node.remove();mounted.delete(key);if(button&&rowPool.length<32){button.replaceChildren();rowPool.push(node);}
 }
 prioritizeAvatars(top);
}
function schedule(){if(!scheduled){scheduled=true;requestAnimationFrame(renderVisible);}}
function refreshAvatars(previous={}){for(const node of mounted.values())for(const button of node.querySelectorAll('.avatar')){const user=button.dataset.user;if(previous[user]?.file!==profiles[user]?.file||previous[user]?.version!==profiles[user]?.version)avatarFor(button,user);}prioritizeAvatars(0);}
window.addEventListener('scroll',schedule,{passive:true});window.addEventListener('resize',()=>{if(viewMode)rebuild();else schedule();},{passive:true});
let searchTimer;$('search-input').addEventListener('input',()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>{query=$('search-input').value;$('clear-search').hidden=!query;window.scrollTo(0,0);rebuild();},130);});$('clear-search').onclick=()=>{$('search-input').value='';query='';$('clear-search').hidden=true;rebuild();$('search-input').focus();};
function resetChoiceDialog(){choiceOpener?.setAttribute('aria-expanded','false');choiceOpener=null;$('dialog').classList.remove('choice-dialog');}
function showDialog(title,body,actions){listScroll.cancel();const overlay=$('dialog');resetChoiceDialog();overlay._exitAnimation?.cancel();delete overlay.dataset.closing;overlay.style.pointerEvents='';overlay.classList.toggle('about-dialog',!title);lastFocus=document.activeElement;uiText($('dialog-title'),title);$('dialog-title').hidden=!title;const modal=$('dialog-title').parentElement;if(title){modal.setAttribute('aria-labelledby','dialog-title');modal.removeAttribute('aria-label');}else{modal.removeAttribute('aria-labelledby');modal.setAttribute('aria-label','Application information');}for(const id of ['import-btn','clear-btn']){const button=$(id);if(button&&$('dialog-body').contains(button))$('collection-actions').append(button);}$('dialog-body').replaceChildren();if(typeof body==='string'){const p=document.createElement('p');p.className='muted';uiText(p,body);$('dialog-body').append(p);}else $('dialog-body').append(body);$('dialog-actions').replaceChildren();for(const action of actions){const b=document.createElement('button');b.className='btn'+(action.danger?' destructive':'');uiText(b,action.label);b.onclick=()=>{b.disabled=true;closeDialog(action.run);};$('dialog-actions').append(b);}$('dialog').hidden=false;document.body.classList.add('overlay-open');$('dialog-actions').querySelector('button')?.focus();}
function closeDialog(done){if($('dialog').hidden){resetChoiceDialog();done?.();return;}const returnFocus=lastFocus;hideScreen($('dialog'),()=>{resetChoiceDialog();if($('settings').hidden&&$('fetcher-screen').hidden)document.body.classList.remove('overlay-open');returnFocus?.focus?.();done?.();});}
function showChoice(opener,title,entries,value,apply){
 const body=document.createElement('div');body.className='action-list choice-list';body.setAttribute('role','radiogroup');body.setAttribute('aria-labelledby','dialog-title');
 let committed=false;
 const buttons=entries.map(([label,code])=>{
  const button=document.createElement('button'),name=document.createElement('span'),check=document.createElementNS('http://www.w3.org/2000/svg','svg'),path=document.createElementNS('http://www.w3.org/2000/svg','path');
  button.type='button';button.setAttribute('role','radio');button.setAttribute('aria-checked',String(code===value));button.tabIndex=code===value?0:-1;name.textContent=label;
  check.classList.add('choice-check');check.setAttribute('viewBox','0 0 24 24');check.setAttribute('aria-hidden','true');path.setAttribute('d','m5 12 4 4L19 6');check.append(path);button.append(name,check);
  button.onclick=()=>{if(committed)return;committed=true;closeDialog(()=>{if(code!==value)apply(code);});};body.append(button);return button;
 });
 // Moving focus previews an option; only Enter, Space or a tap commits it.
 body.onkeydown=event=>{
  const index=buttons.indexOf(document.activeElement);if(index<0)return;let next;
  if(event.key==='ArrowDown'||event.key==='ArrowRight')next=(index+1)%buttons.length;
  else if(event.key==='ArrowUp'||event.key==='ArrowLeft')next=(index+buttons.length-1)%buttons.length;
  else if(event.key==='Home')next=0;else if(event.key==='End')next=buttons.length-1;else return;
  event.preventDefault();for(const button of buttons)button.tabIndex=-1;buttons[next].tabIndex=0;buttons[next].focus();
 };
 showDialog(title,body,[{label:'Close'}]);$('dialog').classList.add('choice-dialog');choiceOpener=opener;lastFocus=opener;opener.setAttribute('aria-expanded','true');(buttons.find(button=>button.tabIndex===0)||buttons[0])?.focus();
}
$('dialog').addEventListener('click',event=>{if(event.target===$('dialog')&&choiceOpener)closeDialog();});
function confirmAction(title,text,action){showDialog(title,text,[{label:'Confirm',danger:true,run:action},{label:'Cancel'}]);}
const features=['Instagram account collections','JSON import and export','Collection management','Account search','Local profile thumbnails','Optional session-based thumbnail retrieval','External thumbnail folder integration','Fullscreen image viewer','Efficient large-list rendering','Persistent settings','AMOLED interface'];
function showInfo(){
 const container=document.createElement('div');
 for(const [label,value]of [['Developer','BlackWare'],['Version',appInfo.version||'Unavailable'],['Build',appInfo.build??'Unavailable'],['Total Accounts',doc?.total||0],['Usage Count',stats.usage],['Load Count',stats.load],['Reset Count',stats.clear]]){
  const card=document.createElement('div');card.className='about-detail';const name=document.createElement('span');uiText(name,label);const detail=document.createElement('strong');detail.textContent=value;card.append(name,detail);container.append(card);
 }
 for(const [label,id]of [['Add List','import-btn'],['Delete List','clear-btn']]){const row=document.createElement('div');row.className='about-detail about-action';const name=document.createElement('span');uiText(name,label);row.append(name,$(id));container.append(row);}
 const menu=document.createElement('button');menu.id='features-menu';menu.className='about-menu';uiText(menu,'Features & Functions');menu.onclick=openFeatures;container.append(menu);
 const help=document.createElement('p');help.className='muted footnote';uiText(help,'Press and hold to read the full name and username.');container.append(help);
 const note=document.createElement('p');note.className='muted footnote';uiText(note,'INSFAV Plus is an independent Instagram account collection manager and is not affiliated with Meta or Instagram.');container.append(note);
 showDialog('',container,[{label:'Settings',run:openSettings},{label:'Close'}]);
}
function openFeatures(){
 const list=$('features-list');list.replaceChildren();for(const feature of features){const item=document.createElement('li');uiText(item,feature);list.append(item);}
 $('dialog').inert=true;$('features-screen').hidden=false;$('features-screen').scrollTop=0;$('features-back').focus();
}
function closeFeatures(){hideScreen($('features-screen'),()=>{$('dialog').inert=false;$('features-menu')?.focus();});}
$('features-back').onclick=closeFeatures;
function hideScreen(element,done){
 if(element.dataset.closing)return;element.dataset.closing='true';element.style.pointerEvents='none';
 const finish=()=>{element.hidden=true;delete element.dataset.closing;element.style.pointerEvents='';element._exitAnimation=null;done?.();};
 if(matchMedia('(prefers-reduced-motion: reduce)').matches){finish();return;}
 element._exitAnimation=element.animate([{opacity:1},{opacity:0}],{duration:motion.duration,easing:motion.easing});element._exitAnimation.onfinish=finish;
}
function openSettings(){previousFocus=document.activeElement;$('settings').hidden=false;document.body.classList.add('overlay-open');updateSettings();request('status');$('settings-back').focus();}
function closeSettings(){hideScreen($('settings'),()=>{document.body.classList.remove('overlay-open');previousFocus?.focus?.();});}
const actions=[['Select Thumbnail Folder','select-folder'],['Rescan Thumbnails','rescan'],['Cancel Scan','cancel-scan']];
for(const [label,action]of actions){const button=document.createElement('button');uiText(button,label);button.dataset.action=action;$('profile-actions').append(button);}
function bytes(value){return (Number(value||0)/1048576).toFixed(2)+' MiB';}
const metricPools=new WeakMap();
function changedText(node,value){const text=String(value);if(node.textContent!==text)node.textContent=text;}
function metricNode(container,label){
 let pool=metricPools.get(container);if(!pool){pool=new Map();metricPools.set(container,pool);}
 let node=pool.get(label);if(!node){node=document.createElement('div');node.className='metric';node.dataset.metric=label;const count=document.createElement('b'),name=document.createElement('span');uiText(name,label);node.append(count,name);node._count=count;node._label=name;pool.set(label,node);container.append(node);}return node;
}
function updateSettings(){
 if($('settings').hidden)return;
 const scan=state.scan||{},busy=!!state.scanPending||state.scanState==='scanning',date=state.lastScan?new Date(state.lastScan).toLocaleString(I.locale(),{hour12:false}):t('Never');
 const values=[['Total Accounts',doc?.total||0],['Unique Accounts',doc?.unique.length||0],['Matched Images',state.matched||0],['Missing Images',state.missing??doc?.unique.length??0],['Indexed Folder Size',bytes(state.folderBytes)+(state.unknownSizes?' + '+t('unknown sizes'):'')],['Image Cache Size',bytes(state.cacheBytes)],['Thumbnail Index Size',bytes(state.storage?.thumbnailIndex)],['WebView Storage',bytes(state.storage?.app_webview)],['Scan Checkpoint Index Size',bytes(state.storage?.thumbnailCheckpoint)],['Non-256×256 Images',state.resolution?.count||0],['Last Scan Date',date],['Selected Folder',state.folderStatus==='not_selected'?t('Not selected'):state.folder||t('Not selected')]];
   for(const [label,value]of values){
   const basic=['Total Accounts','Unique Accounts','Matched Images','Missing Images','Selected Folder','Last Scan Date'];const metric=metricNode(basic.includes(label)?$('basic-metrics'):$('metrics'),label),detail=metric._count;
   changedText(detail,value);
   if(label==='Missing Images'){
    let copy=metric.querySelector('.copy-missing');
    if(!copy){const head=document.createElement('div');head.className='metric-head';copy=document.createElement('button');copy.id='copy-missing-users';copy.className='copy-missing';copy.setAttribute('aria-label',t('Copy missing usernames'));copy.title=t('Copy missing usernames');copy.setAttribute('data-i18n-title','Copy missing usernames');copy.setAttribute('data-i18n-aria-label','Copy missing usernames');copy.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/></svg>';copy.onclick=()=>request('copy-missing-users');head.append(detail,copy);metric.prepend(head);}
    copy.disabled=Number(value)===0;
   }
   if(label==='Selected Folder'){metric.classList.add('folder-metric');if(detail.title!==String(value))detail.title=String(value);}
   if(label==='Non-256×256 Images'){
    const visible=!state.resolution?.complete||state.resolution?.unknown;
    let note=metric.querySelector('.resolution-note');
    if(!note&&visible){note=document.createElement('span');note.className='resolution-note';metric.append(note);}
    if(note){if(note.hidden===!!visible)note.hidden=!visible;changedText(note,!state.resolution?.complete?'Rescan for full folder count':state.resolution.unknown+' unknown dimensions');}
    const title='Target: 256×256 · '+(state.resolution?.complete?'All images in the last completed scan':'Saved dimensions; Rescan includes every folder image')+(state.resolution?.unknown?' · '+state.resolution.unknown+' unknown dimensions':'');if(metric.title!==title)metric.title=title;
   }
  }
$('scan-state').textContent=t((state.scanState||'idle').replaceAll('_',' ').replace(/^./,value=>value.toUpperCase()));
 $('progress').max=busy?Math.max(1,scan.total||1):1;$('progress').value=busy?(scan.processed||0):state.scanState==='completed'?1:0;
 $('progress-detail').textContent=scan.message||(busy?t('Checked {processed} / {total} files',{processed:scan.processed||0,total:scan.total||0}):t('Rescan reads source files. Cancel preserves completed work; rescan again to resume. Scan after external folder changes.'));
 $('folder-warning').hidden=!state.folderMessage;$('folder-warning').textContent=t(state.folderMessage||'');
 $('version-info').textContent=`INSFAV Plus ${appInfo.version||''} · Build ${appInfo.build??'—'} · BlackWare`;
 for(const button of $('profile-actions').querySelectorAll('button')){button.disabled=button.dataset.action==='cancel-scan'?!busy:busy&&button.dataset.action!=='clear-cache';button.hidden=button.dataset.action==='cancel-scan'&&!busy;}
}
document.addEventListener('click',event=>{
 const action=event.target.closest('[data-action]')?.dataset.action;if(!action)return;
 if(action.startsWith('clear-'))confirmAction('Confirm deletion',cleanupText[action],()=>request(action));
 else request(action);
});
$('info-btn').onclick=showInfo;$('import-btn').onclick=()=>closeDialog(()=>request('import'));$('clear-btn').onclick=()=>closeDialog(()=>confirmAction('Reset collections?','The collection database will be cleared. Profile images are managed separately.',()=>request('clear-data')));$('settings-back').onclick=closeSettings;
function openFetcher(){
 previousFocus=document.activeElement;$('settings').inert=true;$('fetcher-screen').hidden=false;$('fetcher-back').focus();request('fetcher-status');
 clearInterval(fetcherPoll);fetcherPoll=0;
}
function closeFetcher(){clearInterval(fetcherPoll);fetcherPoll=0;$('cookie-text').value='';hideScreen($('fetcher-screen'),()=>{$('settings').inert=false;$('fetcher-menu').focus();});}
function outsideSchedule(f){return t('Outside working hours. Configured window: {start}–{end}. Next eligible time: {next}.',{start:f.start,end:f.end,next:f.nextAllowedAt?new Date(f.nextAllowedAt).toLocaleString(I.locale(),{hour12:false}):'—'});}
function updateFetcher(){
 const f=fetcherState;if($('fetcher-screen').hidden)return;
 $('cookie-state').textContent=f.cookiesSaved?t('Cookies saved · session checked during retrieval'):t('Not configured');
 $('fetcher-state').textContent=(f.outsideScheduleMessage?outsideSchedule(f):(I.status(f.scheduleStatus||'')+' · '+I.status(f.state||'Idle')))+(f.blocked==='RATE_LIMITED'?' · '+(f.blockedHttp===429?'HTTP 429':'Instagram restriction')+(f.retryAt?' · Cooldown until '+new Date(f.retryAt).toLocaleTimeString(I.locale(),{hour12:false}):'')+(f.automaticResume?' · Automatic resume scheduled':' · Manual resume'):'');$('fetcher-folder').textContent=t(f.folder||'Not selected')+' · '+t(f.writable?'Write access granted':'Read only');$('fetcher-write').hidden=!!f.writable;
 for(const [id,value] of [['fetcher-concurrency',f.requestedViews],['fetcher-min',f.minimum],['fetcher-max',f.maximum],['fetcher-start',f.start],['fetcher-end',f.end]]){const input=$(id);if(value!==undefined&&document.activeElement!==input&&!input.dataset.edited)input.value=value;}
 if(!$('fetcher-background').dataset.edited)$('fetcher-background').checked=!!f.background;
 $('fetcher-capacity').textContent=t('Requested WebViews: {requested} · Effective WebViews: {effective} · Active WebViews: {active}',{requested:f.requestedViews||1,effective:f.effectiveViews||1,active:f.activeViews||0})+(f.effectiveViews<f.requestedViews?' · '+t('Reduced by the memory budget'):'');
 const values=[['Active Accounts',f.activeAccounts||0],['Accounts / Minute',Number(f.completedPerMinute||0).toFixed(1)],['Peak Active Accounts',f.peakAccounts||0],['App PSS (MiB)',((f.memoryPssKiB||0)/1024).toFixed(1)],['Total Unique Accounts',f.total||0],['Successful',f.success||0],['Existing',f.existing||0],['Failed',f.failed||0],['Skipped Profiles',f.skipped||0],['Retry Later',f.retry||0],['Pending',f.pending||0],['Processed',f.processed||0]];
 for(const [label,value]of values)changedText(metricNode($('fetcher-metrics'),label)._count,value);
 $('fetcher-progress').max=Math.max(1,f.total||0);$('fetcher-progress').value=f.processed||0;
 const process=f.process||{},reviewed=Math.min(f.total||0,(f.processed||0)+(f.held||0));
 $('fetcher-process-phase').textContent=I.status(process.phase||f.state||'Idle');
 $('fetcher-process-detail').textContent=t('{active} active · {queued} queued · {handled} handled this run',{active:process.active??f.activeAccounts??0,queued:process.queued||0,handled:process.handledThisRun||0})+(process.waitSeconds>0?' · '+t('Next request in {seconds} s',{seconds:process.waitSeconds}):'');
 $('fetcher-process-result').textContent=process.lastResult?t('Last result')+': '+process.lastResult.replaceAll('_',' '):t('Waiting for the next result');
 $('fetcher-run-progress').max=Math.max(1,f.total||0);$('fetcher-run-progress').value=reviewed;
 $('fetcher-history').textContent=t('{reviewed} / {total} accounts reviewed · {saved} saved outcomes · Retried only with Retry Skipped / Failed Profiles',{reviewed,total:f.total||0,saved:(f.skipped||0)+(f.failed||0)+(f.held||0)});
 $('fetcher-current').textContent=(f.resolution?.verificationRecommended?'External folder changes detected · Verify Fetcher Index recommended · ':'')+t('Elapsed')+' '+(f.elapsedSeconds||0)+' s'+(f.etaSeconds>=0?' · ETA '+f.etaSeconds+' s':'');
 $('fetcher-verify').disabled=!!f.running;$('fetcher-missing').disabled=!!f.running;$('fetcher-resume').disabled=!!f.running;$('fetcher-retry-skipped').disabled=!!f.running;
}
$('fetcher-menu').onclick=openFetcher;$('fetcher-back').onclick=closeFetcher;
for(const input of $('fetcher-screen').querySelectorAll('input'))input.addEventListener('input',()=>input.dataset.edited='true');
$('cookie-paste').onclick=async()=>{if(native){request('fetcher-paste');return;}try{$('cookie-text').value=await navigator.clipboard.readText();}catch(error){$('cookie-text').focus();message('Use the Android Paste command in the cookie field.');}};
$('cookie-save').onclick=()=>{const text=$('cookie-text').value;$('cookie-text').value='';request('fetcher-cookies-save',{text});};
$('cookie-remove').onclick=()=>{$('cookie-text').value='';confirmAction('Remove Instagram Cookies?','Retrieval stops. Collections and local photos stay unchanged.',()=>request('fetcher-cookies-remove'));};
$('fetcher-save').onclick=()=>{request('fetcher-config',{minimum:Number($('fetcher-min').value),maximum:Number($('fetcher-max').value),start:$('fetcher-start').value,end:$('fetcher-end').value,background:$('fetcher-background').checked,concurrentWebViews:Number($('fetcher-concurrency').value)});for(const input of $('fetcher-screen').querySelectorAll('input'))delete input.dataset.edited;};
$('fetcher-write').onclick=()=>request('fetcher-write-access');
for(const [id,action]of [['fetcher-missing','missing'],['fetcher-pause','pause'],['fetcher-resume','resume'],['fetcher-stop','stop'],['fetcher-retry-skipped','retry_skipped']])$(id).onclick=()=>{if(!['pause','stop'].includes(action)&&fetcherState.withinSchedule===false){message(outsideSchedule(fetcherState));return;}request('fetcher-action',{action});};
$('fetcher-verify').onclick=()=>confirmAction('Verify Fetcher Index?','Explicitly validate the selected folder and repair Fetcher metadata. Photos, normal INSFAV indexing and collections are unchanged.',()=>request('fetcher-action',{action:'verify_index'}));
$('fetcher-diagnostics').onclick=()=>request('logs');
let scale=1,tx=0,ty=0,pointers=new Map(),gesture=null,lastTap=0,lastTapX=0,lastTapY=0;
function applyTransform(){const img=$('full-image'),stage=$('image-stage');const mx=Math.max(0,(img.offsetWidth*scale-stage.clientWidth)/2),my=Math.max(0,(img.offsetHeight*scale-stage.clientHeight)/2);tx=Math.min(mx,Math.max(-mx,tx));ty=Math.min(my,Math.max(-my,ty));img.style.transform=`translate(${tx}px,${ty}px) scale(${scale})`;}
function resetTransform(){scale=1;tx=ty=0;applyTransform();}
function openViewer(user){
 if(!profiles[user]?.file&&!fetcherState.cookiesSaved){message('No readable local image. Select a thumbnail folder or rescan in Settings.');return;}
 previousFocus=document.activeElement;viewerUser=user;viewerToken='v'+Date.now().toString(36)+Math.random().toString(36).slice(2);remoteShown=false;$('viewer').hidden=false;document.body.classList.add('overlay-open');
 $('full-image').src='/thumb/'+encodeURIComponent(user)+'?v='+cacheVersion;
 const image=profiles[user]||{},dimensions=image.width&&image.height?` · ${image.width} × ${image.height}`:'';
 $('viewer-caption').textContent='@'+user+' · '+t('Local image')+dimensions+(Math.max(image.width||0,image.height||0)>2048?' · '+t('Preview up to 2048 px'):'');
 resetTransform();$('viewer-back').focus();
 fullLoader=new Image();fullLoader.onload=()=>{if(viewerUser===user&&!remoteShown){$('full-image').src=fullLoader.src;applyTransform();}};
 fullLoader.onerror=()=>{if(viewerUser===user)notifyImageFailure();};fullLoader.src='/full/'+encodeURIComponent(user)+'?v='+cacheVersion;
 request('view-remote',{user,token:viewerToken});
}
function closeViewer(){
 viewerUser='';viewerToken='';remoteShown=false;request('view-closed');if(remoteLoader){remoteLoader.onload=remoteLoader.onerror=null;remoteLoader.src='';remoteLoader=null;}if(fullLoader){fullLoader.onload=fullLoader.onerror=null;fullLoader.src='';fullLoader=null;}
 pointers.clear();hideScreen($('viewer'),()=>{$('full-image').removeAttribute('src');if($('settings').hidden&&$('fetcher-screen').hidden)document.body.classList.remove('overlay-open');previousFocus?.focus?.();});
}
$('viewer-back').onclick=closeViewer;$('full-image').onload=applyTransform;
const stage=$('image-stage');stage.addEventListener('pointerdown',e=>{stage.setPointerCapture(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY,startX:e.clientX,startY:e.clientY});if(pointers.size===2){const [a,b]=[...pointers.values()];gesture={distance:Math.hypot(a.x-b.x,a.y-b.y),scale,tx,ty,cx:(a.x+b.x)/2,cy:(a.y+b.y)/2};}else gesture=null;});stage.addEventListener('pointermove',e=>{const p=pointers.get(e.pointerId);if(!p)return;const dx=e.clientX-p.x,dy=e.clientY-p.y;p.x=e.clientX;p.y=e.clientY;if(pointers.size===2&&gesture){const [a,b]=[...pointers.values()];scale=Math.min(6,Math.max(1,gesture.scale*Math.hypot(a.x-b.x,a.y-b.y)/Math.max(1,gesture.distance)));tx=gesture.tx+(a.x+b.x)/2-gesture.cx;ty=gesture.ty+(a.y+b.y)/2-gesture.cy;}else if(scale>1){tx+=dx;ty+=dy;}applyTransform();});function release(e){const p=pointers.get(e.pointerId);if(e.type==='pointerup'&&pointers.size===1&&p&&Math.hypot(p.x-p.startX,p.y-p.startY)<10){const now=Date.now();if(now-lastTap<300&&Math.hypot(p.x-lastTapX,p.y-lastTapY)<40){scale=scale>1?1:2.5;tx=ty=0;applyTransform();lastTap=0;}else{lastTap=now;lastTapX=p.x;lastTapY=p.y;}}pointers.delete(e.pointerId);gesture=null;}stage.addEventListener('pointerup',release);stage.addEventListener('pointercancel',release);stage.addEventListener('wheel',e=>{e.preventDefault();scale=Math.min(6,Math.max(1,scale+(e.deltaY<0?.2:-.2)));applyTransform();},{passive:false});
window.handleBack=()=>{if(!$('dialog').hidden&&!$('fetcher-screen').hidden){closeDialog();return true;}if(!$('fetcher-screen').hidden){closeFetcher();return true;}if(!$('features-screen').hidden){closeFeatures();return true;}if(!$('viewer').hidden){closeViewer();return true;}if(!$('dialog').hidden){closeDialog();return true;}if(!$('settings').hidden){closeSettings();return true;}if(query){$('clear-search').click();return true;}return false;};
document.addEventListener('keydown',e=>{if(e.key==='Escape')window.handleBack();if(e.key==='Tab'){const surface=!$('dialog').hidden&&!$('fetcher-screen').hidden?$('dialog'):!$('fetcher-screen').hidden?$('fetcher-screen'):!$('features-screen').hidden?$('features-screen'):!$('viewer').hidden?$('viewer'):!$('dialog').hidden?$('dialog'):!$('settings').hidden?$('settings'):null;if(!surface)return;const focus=[...surface.querySelectorAll('button:not(:disabled),input,select,textarea,summary')].filter(el=>el.tabIndex>=0&&!el.hidden&&el.getClientRects().length);const first=focus[0],last=focus[focus.length-1];if(e.shiftKey&&document.activeElement===first){last.focus();e.preventDefault();}else if(!e.shiftKey&&document.activeElement===last){first.focus();e.preventDefault();}}});
function previewCommand(command,p){if(command==='bootstrap'){appInfo={version:'Browser preview',build:'—'};try{const raw=localStorage.getItem('insfav-preview-data');window.nativeEvent('bootstrap',{raw:raw||'',profiles:{},stats:{usage:1,load:0,clear:0}});}catch(e){window.nativeEvent('error',e.message);$('splash').hidden=true;}}else if(command==='import'){const input=document.createElement('input');input.type='file';input.accept='.json,application/json';input.onchange=async()=>{try{const raw=await input.files[0].text();C.parseDocument(raw);localStorage.setItem('insfav-preview-data',raw);window.nativeEvent('imported',{raw,profiles:{}});}catch(e){message(e.message);}};input.click();}else if(command==='export'){if(!doc)return;const u=URL.createObjectURL(new Blob([doc.raw],{type:'application/json'}));const a=document.createElement('a');a.href=u;a.download='INSFAV-Plus-backup.json';a.click();setTimeout(()=>URL.revokeObjectURL(u),1000);}else if(command==='clear-data'){localStorage.removeItem('insfav-preview-data');window.nativeEvent('cleared','');}else if(command==='copy-missing-users'){const users=(doc?.unique||[]).filter(user=>/^[a-z0-9._]{1,30}$/.test(user)&&!profiles[user]?.file);if(!users.length){message('No missing usernames.');return;}navigator.clipboard.writeText(users.join('\n')).then(()=>message(users.length+' usernames copied.')).catch(()=>message('Clipboard unavailable. Try again.'));}else if(command==='open')window.open('https://www.instagram.com/'+encodeURIComponent(p.user)+'/','_blank','noopener');else if(command!=='status'&&command!=='image-failed')message('This feature uses the Android application.');}
window.addEventListener('error',e=>{if(native)request('frontend-error',{code:'script_line_'+e.lineno});});window.addEventListener('unhandledrejection',()=>{if(native)request('frontend-error',{code:'unhandled_promise'});});

const cleanupText={
 'clear-cache':'Clear browser and legacy image caches? Source photos, collections, cookies and the thumbnail index are preserved.',
 'clear-index':'Clear thumbnail metadata and scan checkpoints? Source photos, collections and cookies are preserved. Rescan rebuilds the index.',
 'clear-data':'Remove the collection document? Local photos and their index are unchanged.'
};
function menu(title,entries){const body=document.createElement('div');body.className='action-list';for(const [label,action]of entries){const button=document.createElement('button');uiText(button,label);button.onclick=()=>closeDialog(()=>action());body.append(button);}showDialog(title,body,[{label:'Close'}]);}
$('maintenance-menu').onclick=()=>menu('Maintenance / Cleanup',[['Clear Image Cache',()=>confirmAction('Confirm deletion',cleanupText['clear-cache'],()=>request('clear-cache'))],['Clear Thumbnail Index',()=>confirmAction('Confirm deletion',cleanupText['clear-index'],()=>request('clear-index'))],['Clear Collections',()=>confirmAction('Confirm deletion',cleanupText['clear-data'],()=>request('clear-data'))]]);
$('backup-menu').onclick=()=>menu('Backup / Restore',[['Import JSON',()=>request('import')],['Export Original JSON',()=>request('export')],['Export All Settings',()=>request('settings-export')],['Import All Settings',()=>request('settings-import')],['Import settings from original INSFAV',()=>request('settings-import-original')]]);
let resultFilter='',resultUsers=new Set(),inventoryState={kind:'missing',offset:0,query:''},inventoryRequest=0;
const resultOptions=[['All accounts',''],['Downloaded','SUCCESS'],['Existing','EXISTING'],['Skipped Unavailable','SKIPPED_UNAVAILABLE'],['Skipped No Image','SKIPPED_NO_IMAGE'],['Failed','FAILED'],['Retry Later','RETRY_LATER']];
function languageOptions(){return [[t('System language'),'system'],['English','en'],['Türkçe','tr']];}
function updateChoiceLabels(){$('language-value').textContent=languageOptions().find(([,code])=>code===I.choice())[0];uiText($('result-filter-value'),resultOptions.find(([,code])=>code===resultFilter)[0]);}
$('language-choice').onclick=()=>showChoice($('language-choice'),'Language',languageOptions(),I.choice(),choice=>{if(native)request('language',{choice});else{try{localStorage.setItem('insfav-plus-language',choice);}catch(_){}I.choose(choice);}});
const viewOptions=[['List',0],['Matrix · 2 columns',2],['Matrix · 3 columns',3],['Matrix · 4 columns',4]];
function updateViewChoice(){uiText($('view-value'),viewOptions.find(([,value])=>value===viewMode)[0]);}
$('view-choice').onclick=()=>showChoice($('view-choice'),'View mode',viewOptions.map(([label,value])=>[t(label),value]),viewMode,columns=>{if(native)request('view-mode',{columns});else{try{localStorage.setItem('insfav-plus-columns',columns);}catch(_){}applyViewMode(columns);}});
I.onChange=()=>{listScroll.refresh();updateChoiceLabels();updateViewChoice();updateSettings();updateFetcher();for(const node of mounted.values()){const r=rows.find(row=>row.key===node.dataset.key);if(!r)continue;for(const button of node.querySelectorAll('.avatar')){const item=r.item||r.items?.find(item=>item.user===button.dataset.user);if(item){button.setAttribute('aria-label',t('View profile image for {name} (@{user})',{name:item.name,user:item.user}));const link=node.querySelector('.card-link');if(link)bindAccountDetails(link,item);}}}};
I.choose(native?'system':(()=>{try{return localStorage.getItem('insfav-plus-language')||'system';}catch(_){return 'system';}})());
function refreshResults(){if(resultFilter)request('fetcher-results',{status:resultFilter});}
$('result-filter').onclick=()=>showChoice($('result-filter'),'Fetcher results',resultOptions.map(([label,code])=>[t(label),code]),resultFilter,code=>{resultFilter=code;resultUsers.clear();updateChoiceLabels();$('result-refresh').hidden=!resultFilter;refreshResults();rebuild();});$('result-refresh').onclick=refreshResults;
$('inventory-menu').onclick=()=>menu('Thumbnail management',[['Missing images',()=>openInventory('missing')],['Images available',()=>openInventory('present')],['Orphan thumbnails',()=>openInventory('orphan')]]);
function queryInventory(){request('inventory',{...inventoryState,requestId:++inventoryRequest});}
function openInventory(kind=inventoryState.kind){inventoryState.kind=kind;const body=document.createElement('div'),note=document.createElement('p'),input=document.createElement('input'),count=document.createElement('p'),list=document.createElement('div'),pager=document.createElement('div');
 note.className='muted';uiText(note,kind==='orphan'?'Orphan candidates are relative to loaded collections. Missing or incomplete imports can misclassify existing images. Nothing is deleted.':'Read-only. Orphans are never deleted. Inventory reflects the last completed rescan.');input.id='inventory-search';input.value=inventoryState.query;input.setAttribute('aria-label',t('Search files'));input.placeholder=t('Search files');let timer;input.oninput=()=>{inventoryState.query=input.value;inventoryState.offset=0;clearTimeout(timer);timer=setTimeout(queryInventory,180);};count.id='inventory-count';count.className='muted';count.setAttribute('role','status');list.id='inventory-list';list.className='action-list';pager.className='fetcher-inline';for(const [label,step]of [['Previous',-100],['Next',100]]){const button=document.createElement('button');button.className='btn';button.id='inventory-'+(step<0?'previous':'next');uiText(button,label);button.onclick=()=>{inventoryState.offset=Math.max(0,inventoryState.offset+step);queryInventory();};pager.append(button);}body.append(note,input,count,list,pager);showDialog(t({missing:'Missing images',present:'Images available',orphan:'Orphan thumbnails'}[kind]),body,[{label:'Close'}]);queryInventory();}
function renderInventory(data){if(data.requestId!==inventoryRequest||!$('inventory-list'))return;const list=$('inventory-list');list.replaceChildren();$('inventory-count').textContent=data.complete?t('{total} files',{total:data.total}):t(data.collectionLoaded===false?'Import all collections before interpreting orphan candidates.':'Rescan first to inspect orphan thumbnails.');for(const item of data.items){const button=document.createElement('button');button.textContent=item.name+(item.width?' · '+item.width+' × '+item.height:'');button.disabled=!item.preview;button.onclick=()=>{const body=document.createElement('div'),img=new Image();img.className='inventory-preview';img.alt=item.name;img.src='/thumb/inventory?preview='+encodeURIComponent(item.preview);body.append(img);showDialog(t('Local image preview'),body,[{label:'Back',run:()=>openInventory()},{label:'Close'}]);};list.append(button);}$('inventory-previous').disabled=data.offset===0;$('inventory-next').disabled=data.offset+data.items.length>=data.total;}
$('performance-menu').onclick=()=>request('performance');$('history-menu').onclick=()=>request('history');
function renderPerformance(data){const body=document.createElement('div');body.className='metrics';const metrics=[...data.metrics,{label:'Average browser image load',value:avatarMetrics.loaded?Number((avatarMetrics.loadMs/avatarMetrics.loaded).toFixed(2)):null,measured:avatarMetrics.loaded>0,unit:'ms',scope:'Current browser session'},{label:'Peak process RAM',measured:false,unit:'',scope:'Not sampled'}];for(const metric of metrics){const card=document.createElement('div'),label=document.createElement('span'),value=document.createElement('b'),note=document.createElement('p');card.className='metric';uiText(label,metric.label);value.textContent=metric.measured?metric.value+' '+metric.unit:t('Not measured');note.className='muted';note.textContent=t(metric.scope);card.append(label,value,note);body.append(card);}if(data.lastScan&&data.previousScan){const compare=document.createElement('p');compare.className='muted';compare.textContent=t('Previous scan comparison')+': '+(data.lastScan.elapsedMs-data.previousScan.elapsedMs)+' ms';body.append(compare);}showDialog('Performance diagnostics',body,[{label:'Close'}]);}
function renderHistory(data){const body=document.createElement('ol');body.className='operation-history';for(const line of data.items){const row=document.createElement('li'),[stamp,...parts]=line.split(' ');row.textContent=new Date(Number(stamp)).toLocaleString(I.locale(),{hour12:false})+' · '+parts.join(' ');body.append(row);}if(!data.items.length){const row=document.createElement('li');uiText(row,'No recorded operations');body.append(row);}showDialog('Recent operations',body,[{label:'Close'}]);}
function showStartupFailure(){clearTimeout(bootstrapTimer);bootstrapReady=false;$('startup-error').hidden=false;$('splash').hidden=false;$('splash').querySelector('.loader').hidden=true;$('startup-retry').focus();}
function beginBootstrap(){bootstrapReady=false;$('startup-error').hidden=true;$('splash').querySelector('.loader').hidden=false;clearTimeout(bootstrapTimer);bootstrapTimer=setTimeout(showStartupFailure,12000);request('bootstrap',{profileDeltas:true});}
$('startup-retry').onclick=beginBootstrap;$('startup-logs').onclick=()=>request('logs');$('startup-import').onclick=()=>request('import');

beginBootstrap();
