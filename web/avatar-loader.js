'use strict';
// Browser image scheduling and bounded retention. Bundled before app.js; no extra request.
const avatarImages=new Map(),avatarMetrics={hits:0,misses:0,cancelled:0,loaded:0,loadMs:0},rowPool=[];
let avatarActive=0,scrollTopLast=0,scrollTimeLast=0,scrollVelocity=0;
function clearBrowserImages(){
 for(const entry of avatarImages.values()){entry.status='cancelled';entry.img.onload=entry.img.onerror=null;entry.img.removeAttribute('src');}
 avatarImages.clear();avatarActive=0;
 for(const node of mounted.values())for(const button of node.querySelectorAll('.avatar'))avatarFor(button,button.dataset.user);
 prioritizeAvatars();
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
function prioritizeAvatars(top=-$('list').getBoundingClientRect().top){
 for(const entry of avatarImages.values()){entry.priority=Infinity;for(const button of entry.buttons){const index=button._rowIndex,row=rows[index];if(!row)continue;const y=positions[index]-top,bottom=y+row.height;entry.priority=Math.min(entry.priority,bottom>=0&&y<=innerHeight?0:(scrollVelocity>=0?y<0:bottom>innerHeight)?10000:Math.abs(y));}}
 for(const [key,entry]of avatarImages)if(!entry.buttons.size && (entry.status==='pending'||entry.status==='loading')){
  if(entry.status==='loading'){avatarActive--;entry.status='cancelled';entry.img.onload=entry.img.onerror=null;entry.img.removeAttribute('src');avatarMetrics.cancelled++;}avatarImages.delete(key);
 }
 const idle=[...avatarImages.entries()].filter(([,e])=>!e.buttons.size).sort((a,b)=>a[1].used-b[1].used);
 while(avatarImages.size>64&&idle.length){const [key,entry]=idle.shift();entry.img.removeAttribute('src');avatarImages.delete(key);}
 pumpAvatars();
}
window.thumbnailMetrics=()=>({...avatarMetrics,active:avatarActive,retained:avatarImages.size,rows:mounted.size,pool:rowPool.length});
