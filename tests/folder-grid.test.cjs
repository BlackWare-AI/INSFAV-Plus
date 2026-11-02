'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const C=require('../web/core.js');
const groups=Array.from({length:8},(_,key)=>({name:'Folder '+key,key,items:[{key:key+':0'}]}));
const closed=groups.map(group=>({type:'folder',key:'f'+group.key,group,height:74}));
for(const columns of [2,3,4,5]){
 test('folder grid '+columns+' columns groups only closed folders',()=>{
  const rows=C.folderGridRows(closed,columns,new Set(),false);
  assert.equal(rows.length,Math.ceil(closed.length/columns));
  assert.deepEqual(rows.flatMap(r=>r.groups.map(g=>g.key)),groups.map(g=>g.key));
  assert.ok(rows.every(r=>r.type==='folder-grid'&&r.columns===columns&&r.groups.length<=columns));
  assert.deepEqual(C.offsets(rows).at(-1),rows.length*112);
 });
}
test('list is unchanged and unsupported columns use list',()=>{
 for(const value of [0,1,-1,6,NaN,undefined])assert.strictEqual(C.folderGridRows(closed,value,new Set(),false),closed);
});
test('search keeps original folder rows and account presentation',()=>{
 assert.strictEqual(C.folderGridRows(closed,5,new Set(),true),closed);
});
test('open folders and their accounts keep full-width rows and original order',()=>{
 const open=new Set([2]);
 const mixed=[...closed.slice(0,3),{type:'account',key:'2:0',item:groups[2].items[0],height:65},
  {type:'spacer',key:'s2',height:14},...closed.slice(3)];
 const actual=C.folderGridRows(mixed,3,open,false);
 assert.deepEqual(actual.map(r=>r.type),['folder-grid','folder','account','spacer','folder-grid','folder-grid']);
 assert.equal(actual[1],closed[2]);
 assert.equal(actual[2],mixed[3]);
 assert.equal(actual[3],mixed[4]);
 assert.deepEqual(actual.flatMap(r=>r.groups?.map(g=>g.key)||[]),[0,1,3,4,5,6,7]);
});
test('other full-width rows stay untouched and separate adjacent grid runs',()=>{
 const other={type:'account',key:'account-7',height:65};
 const actual=C.folderGridRows([closed[0],closed[1],other,closed[2],closed[3]],2,new Set(),false);
 assert.deepEqual(actual.map(r=>r.type),['folder-grid','account','folder-grid']);
 assert.strictEqual(actual[1],other);
});
