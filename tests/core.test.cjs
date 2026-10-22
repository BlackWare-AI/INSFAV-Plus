const {test}=require('node:test');const assert=require('node:assert/strict');const C=require('../app/src/main/assets/core.js');
const sample=[{name:'Collection A',extra:{preserved:true},items:[{pageId:'Example',name:'Example Name',unknown:1},{pageId:'@EXAMPLE',name:'Repeated membership'},{pageId:'second.user',name:'Second Name'}]},{name:'Collection B',items:[{pageId:'example',name:'Same user'}]}];
test('preserves wrapper, collection order, memberships and unknown fields',()=>{const original={setting:'unchanged',favorite_page_pref:JSON.stringify(sample)};const raw=JSON.stringify(original);const d=C.parseDocument(raw);assert.equal(d.raw,raw);assert.deepEqual(d.original,original);assert.deepEqual(d.collections,sample);assert.equal(d.total,4);assert.deepEqual(d.unique,['example','second.user']);});
test('accepts array and nested-array wrapper',()=>{assert.equal(C.parseDocument(JSON.stringify(sample)).total,4);assert.equal(C.parseDocument(JSON.stringify({favorite_page_pref:sample})).total,4);});
test('invalid imports fail before replacing a document',()=>{for(const raw of ['invalid','null','{}','[{"name":"A"}]','[{"name":"A","items":[{"pageId":123}]}]'])assert.throws(()=>C.parseDocument(raw));});
test('search supports display names and usernames without changing source',()=>{const d=C.parseDocument(JSON.stringify(sample));assert.equal(C.filterCollections(d,'SECOND')[0].items.length,1);assert.equal(C.filterCollections(d,'example').length,2);assert.equal(C.filterCollections(d,'missing').length,0);assert.deepEqual(d.collections,sample);});
test('closed collections do not produce account rows',()=>{const d=C.parseDocument(JSON.stringify(sample));assert.equal(C.rowsFor(d.indexed,new Set(),false).length,2);assert.equal(C.rowsFor(d.indexed,new Set([0]),false).length,6);});
for(const n of [10,1000,5000,10000,15000])test(`virtual window stays bounded for ${n} accounts`,()=>{const raw=JSON.stringify([{name:'Large',items:Array.from({length:n},(_,i)=>({pageId:'user'+i,name:'Name '+i}))}]);const start=performance.now();const d=C.parseDocument(raw);const rows=C.rowsFor(d.indexed,new Set([0]),false),off=C.offsets(rows);assert.equal(rows.length,n+2);for(const top of [0,30000,Math.max(0,off.at(-1)-800)]){const [a,b]=C.windowRange(off,top,800);assert.ok(b-a<25);assert.ok(a>=0&&b<=rows.length);}console.log(`${n}: parse + derived rows ${(performance.now()-start).toFixed(1)} ms`);});
test('user-provided markup remains plain data',()=>{const d=C.parseDocument(JSON.stringify([{name:'<img onerror=alert(1)>',items:[{pageId:'example',name:'<script>alert(1)</script>'}]}]));assert.equal(d.indexed[0].name,'<img onerror=alert(1)>');});
test('directional overscan stays bounded and reverses priorities',()=>{for(const velocity of [-100,-5,0,5,100]){const range=C.overscanFor(velocity);assert.ok(range.before<=900&&range.after<=900);assert.ok(range.before>=100&&range.after>=100);if(velocity<0)assert.ok(range.before>range.after);if(velocity>0)assert.ok(range.after>range.before);}const off=Array.from({length:20001},(_,i)=>i*65);for(const velocity of [-100,0,100]){const [a,b]=C.windowRange(off,30000,820,C.overscanFor(velocity));assert.ok(b-a<30);assert.ok(off[a]<=30000&&off[b]>=30820);}});

test('very fast flings skip wasteful intermediate preloading',()=>{assert.ok(C.overscanFor(50).after<C.overscanFor(5).after);assert.ok(C.overscanFor(-50).before<C.overscanFor(-5).before);});

test('all account rows use 65px and have contiguous coordinates',()=>{const d=C.parseDocument(JSON.stringify(sample));const rows=C.rowsFor(d.indexed,new Set([0,1]),false),off=C.offsets(rows);for(let i=0;i<rows.length;i++)if(rows[i].type==='account'){assert.equal(rows[i].height,65);assert.equal(off[i+1]-off[i],65);if(rows[i+1]?.type==='account')assert.equal(off[i+1],off[i]+65);}});

test('Turkish display-name search preserves Instagram identity normalization and memberships',()=>{
 const d=C.parseDocument(JSON.stringify([{name:'One',items:[{pageId:'ISTANBUL',name:'IŞIK İpek'},{pageId:'ipek',name:'Other'}]},{name:'Two',items:[{pageId:'ISTANBUL',name:'IŞIK'}]}]));
 assert.equal(C.normalize('ISTANBUL'),'istanbul');
 for(const query of ['ışık','IŞIK','isik','İPEK','ipek']){
  const names=C.filterCollections(d,query).flatMap(c=>c.items.map(i=>i.name));
  if(query==='isik')assert.equal(names.length,0);else assert.ok(names.length>0,query);
 }
 assert.deepEqual(C.filterCollections(d,'ışık').map(c=>c.items[0].user),['istanbul','istanbul']);
 assert.equal(C.filterCollections(d,'IŞIK').length,2);
});
