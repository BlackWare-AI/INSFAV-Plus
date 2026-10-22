(function(root){
'use strict';
const foldDisplay=s=>String(s).normalize('NFKC').replace(/[Iİı]/g,'i').toLowerCase().replace(/i\u0307/g,'i');
const normalize=u=>String(u??'').trim().replace(/^@/,'').toLowerCase();
function parseDocument(text){
 const original=JSON.parse(text);
 let collections=Array.isArray(original)?original:original?.favorite_page_pref;
 if(typeof collections==='string')collections=JSON.parse(collections);
 if(!Array.isArray(collections))throw Error('Expected an array or favorite_page_pref collection array.');
 for(const c of collections){
  if(!c||typeof c!=='object'||Array.isArray(c)||typeof c.name!=='string'||!Array.isArray(c.items))throw Error('Invalid collection structure. Original data has not been changed.');
  for(const i of c.items)if(!i||typeof i!=='object'||typeof i.pageId!=='string'||(i.name!==undefined&&typeof i.name!=='string'))throw Error('Invalid account structure. Original data has not been changed.');
 }
 const indexed=collections.map((c,ci)=>({name:c.name,key:ci,items:c.items.map((i,ii)=>({source:i,key:ci+':'+ii,user:normalize(i.pageId),name:i.name||i.pageId,search:(i.pageId+' '+(i.name||'')).normalize('NFKC').toLocaleLowerCase('en'),displaySearch:foldDisplay(i.name||'')}))}));
 return {original,collections,indexed,raw:text,total:indexed.reduce((n,c)=>n+c.items.length,0),unique:[...new Set(indexed.flatMap(c=>c.items.map(i=>i.user)))].filter(Boolean)};
}
function filterCollections(doc,q){q=String(q).normalize('NFKC').toLocaleLowerCase('en').trim();return !q?doc.indexed:doc.indexed.map(c=>({...c,items:c.items.filter(i=>i.search.includes(q)||i.displaySearch.includes(foldDisplay(q)))})).filter(c=>c.items.length);}
function rowsFor(groups,open,search){const rows=[];for(const c of groups){const expanded=search||open.has(c.key);rows.push({type:'folder',key:'f'+c.key,group:c,height:expanded?66:74});if(expanded)for(let p=0;p<c.items.length;p++)rows.push({type:'account',key:c.items[p].key,item:c.items[p],group:c,last:p===c.items.length-1,height:65});if(expanded)rows.push({type:'spacer',key:'s'+c.key,height:14});}return rows;}
function offsets(rows){const out=[0];for(const r of rows)out.push(out[out.length-1]+r.height);return out;}
function windowRange(off,top,height,overscan=350){let lo=0,hi=off.length-1;const before=typeof overscan==='number'?overscan:overscan.before,after=typeof overscan==='number'?overscan:overscan.after;const start=Math.max(0,top-before),end=top+height+after;while(lo<hi){const mid=(lo+hi)>>1;if(off[mid+1]<=start)lo=mid+1;else hi=mid;}const a=lo;while(lo<off.length-1&&off[lo]<end)lo++;return [a,lo];}
function overscanFor(velocity){const speed=Math.abs(velocity),forward=speed>12?240:Math.min(700,180+speed*90),back=100;return velocity<0?{before:forward,after:back}:{before:back,after:forward};}
const api={foldDisplay,overscanFor,normalize,parseDocument,filterCollections,rowsFor,offsets,windowRange};if(typeof module!=='undefined')module.exports=api;root.InsfavCore=api;
})(typeof globalThis!=='undefined'?globalThis:this);
