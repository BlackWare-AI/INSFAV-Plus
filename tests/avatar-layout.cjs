/* Actual DOM geometry and recycled-row checks; offline synthetic data only. */
const {chromium}=require('playwright'),fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
(async()=>{
 const root=path.resolve(__dirname,'../app/src/main/assets'),image=fs.readFileSync(path.join(root,'logo.webp'));
 const server=http.createServer((req,res)=>{
  if(req.url.startsWith('/thumb/')){res.setHeader('Content-Type','image/webp');res.end(image);return;}
  const name=req.url.split('?')[0]==='/'?'index.html':req.url.split('?')[0].slice(1),file=path.resolve(root,name);
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':name.endsWith('.woff2')?'font/woff2':name.endsWith('.webp')?'image/webp':'text/html');res.end(fs.readFileSync(file));
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,args:['--no-sandbox','--disable-gpu']});let checks=0;
 try{for(const count of [10,1000,10000,15000])for(const width of [320,393,480]){
  const page=await browser.newPage({viewport:{width,height:820}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(n=>{
   const items=Array.from({length:n},(_,i)=>({pageId:'layout_'+i,name:'A very long visible profile name repeated for geometry '+i}));
   const profiles=Object.fromEntries(items.filter((_,i)=>i%3!==1).map(i=>[i.pageId,{file:true,width:256,height:256,version:'1'}]));
   window.Android={request(raw){if(JSON.parse(raw).command==='bootstrap')nativeEvent('bootstrap',{raw:JSON.stringify([{name:'First',items:items.slice(0,n/2)},{name:'Second',items:items.slice(n/2)}]),profiles,revision:1,stats:{usage:1,load:0,clear:0}});}};
  },count);
  await page.goto('http://127.0.0.1:'+server.address().port+'/');await page.waitForSelector('#splash[hidden]',{state:'attached'});
  assert.equal(await page.locator('.account').count(),0);
  await page.locator('.folder-header').nth(1).click({noWaitAfter:true});await page.locator('.folder-header').nth(0).click({noWaitAfter:true});
  await page.waitForSelector('.avatar img');
  const geometry=await page.evaluate(()=>{
   const rows=[...document.querySelectorAll('.account')].map(e=>e.getBoundingClientRect());
   const image=document.querySelector('.avatar img').getBoundingClientRect(),avatar=document.querySelector('.avatar').getBoundingClientRect();
   return {rows:rows.sort((a,b)=>a.top-b.top).map(e=>({top:e.top,bottom:e.bottom,height:e.height})),image:[image.width,image.height],avatar:[avatar.width,avatar.height],placeholder:!!document.querySelector('.person-placeholder')};
  });
  assert.deepEqual(geometry.avatar,[60,60]);assert.deepEqual(geometry.image,[60,60]);assert.ok(geometry.placeholder);assert.ok(geometry.rows.every(r=>r.height===65));
  for(let i=1;i<geometry.rows.length;i++)assert.ok(geometry.rows[i].top>=geometry.rows[i-1].bottom);
  for(const position of [10000,300000,1000,99999999]){await page.evaluate(y=>scrollTo(0,y),position);await page.waitForTimeout(30);assert.ok(await page.locator('.account').count()<40);}
  await page.evaluate(()=>scrollTo(0,document.body.scrollHeight));await page.waitForTimeout(100);
  const last=await page.locator('.account.last').last().boundingBox(),nav=await page.locator('.nav-container').boundingBox();assert.ok(last.y+last.height<=nav.y,'last account remains above bottom navigation');
  await page.locator('#search-input').fill('layout_'+(count-1));await page.waitForTimeout(200);assert.equal(await page.locator('.account').count(),1);
  await page.addStyleTag({content:'.card-name{font-size:30px}.card-user{font-size:26px}'});
  assert.equal(await page.locator('.account').evaluate(e=>e.getBoundingClientRect().height),65);
  assert.ok((await page.locator('.avatar').getAttribute('aria-label')).includes('A very long visible profile name'));
  assert.deepEqual(errors,[]);checks++;await page.close();
 }console.log('PASS '+checks+' avatar/list matrices: 60px images, 65px rows, large data, narrow screens, search, large text and navigation');}
 finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
