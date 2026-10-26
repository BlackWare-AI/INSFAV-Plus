'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http'),crypto=require('node:crypto');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'..'),baseline=process.env.BASELINE_ASSETS;
if(!baseline)throw Error('Set BASELINE_ASSETS to this application’s own unmodified assets');
const out=process.env.EVIDENCE_DIR||path.join(root,'artifacts/optimization-browser');fs.mkdirSync(out,{recursive:true});
const plus=fs.existsSync(path.join(root,'web/list-scroll.js'));
const mime=file=>file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':file.endsWith('.woff2')?'font/woff2':file.endsWith('.jpg')?'image/jpeg':file.endsWith('.webp')?'image/webp':'text/html';
(async()=>{
 const server=http.createServer((req,res)=>{
  if(/\/(thumb|full)\//.test(req.url)){res.setHeader('Content-Type','image/svg+xml');res.end('<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"><rect width="512" height="512" fill="#777"/></svg>');return;}
  const parts=req.url.split('?')[0].split('/').filter(Boolean),variant=parts.shift();
  const base=variant==='before'?baseline:path.join(root,'app/src/main/assets');
  // Absolute local image paths are shared; all application scripts use a variant prefix.
  const file=path.resolve(base,parts.join('/')||'index.html');
  if(!file.startsWith(path.resolve(base)+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',mime(file));res.end(fs.readFileSync(file));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;const report={applicationBeforeSha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(baseline,'app.js'))).digest('hex'),applicationAfterSha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(root,'app/src/main/assets/app.js'))).digest('hex'),physicalDevice:false,viewport:[393,820],fontScale:1,comparisons:[],datasets:[]};
 try{
  browser=await chromium.launch({headless:true,args:['--no-sandbox','--disable-gpu']});
  for(const count of [5000,15000,20000]){
   const captures={};
   for(const variant of ['before','after']){
    const context=await browser.newContext({viewport:{width:393,height:820},deviceScaleFactor:1,locale:'en-US',timezoneId:'UTC'}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(({count})=>{
     const items=Array.from({length:count},(_,i)=>({pageId:'fixture_'+i,name:'Name '+i,unknown:{preserved:true}}));
     const profiles={};for(let i=0;i<count;i++)profiles['fixture_'+i]={file:true,width:512,height:512,version:'v1'};
     window.fixture={raw:JSON.stringify([{name:'Synthetic',items},{name:'Second',items:[items[0]]}]),profiles,total:count+1,unique:count,matched:count,missing:0,revision:1,scopeRevision:'fixture',unreadable:0,folderStatus:'ready',scanState:'completed',folder:'Synthetic folder',lastScan:100000,app:{version:'Fixture',build:1},stats:{usage:1,load:1,clear:0},uiPreferences:{language:'en',columns:0}};
     window.Android={request(raw){const p=JSON.parse(raw);if(p.command==='bootstrap')nativeEvent('bootstrap',structuredClone(fixture));else if(p.command==='status')nativeEvent('status',structuredClone(fixture));else if(p.command==='fetcher-status')nativeEvent('fetcher-status',{total:count,pending:count,state:'Paused',running:false,minimum:15,maximum:30,start:'02:00',end:'07:00',folder:'Synthetic folder',imageRevision:1});}};
     window.avatarRectReads=0;const rect=Element.prototype.getBoundingClientRect;Element.prototype.getBoundingClientRect=function(){if(this.classList.contains('avatar')&&this.closest('.virtual-row'))window.avatarRectReads++;return rect.call(this);};
    },{count});
    await page.goto('http://127.0.0.1:'+server.address().port+'/'+variant+'/');await page.locator('#splash').waitFor({state:'hidden'});await page.evaluate(()=>document.fonts.ready);await page.waitForTimeout(250);
    captures[variant]={};
    const shot=async name=>{await page.waitForTimeout(250);const png=await page.screenshot({animations:'disabled'});captures[variant][name]=png;fs.writeFileSync(path.join(out,`${count}-${variant}-${name}.png`),png);};
    await shot('closed');await page.locator('.folder-header').first().click();await page.waitForSelector('.avatar img');await shot('list');
    const rendering=await page.evaluate(async()=>{
     const durations=[],intervals=[],originalRender=renderVisible;let previous=0,peakRows=0,peakActive=0,peakRetained=0;
     renderVisible=function(){const start=performance.now();try{return originalRender();}finally{durations.push(performance.now()-start);}};
     try{for(let i=0;i<90;i++)await new Promise(resolve=>requestAnimationFrame(timestamp=>{if(previous)intervals.push(timestamp-previous);previous=timestamp;scrollTo(0,(i*120)%Math.max(1,document.scrollingElement.scrollHeight-innerHeight));const m=thumbnailMetrics();peakRows=Math.max(peakRows,m.rows);peakActive=Math.max(peakActive,m.active);peakRetained=Math.max(peakRetained,m.retained);resolve();}));}
     finally{renderVisible=originalRender;scrollTo(0,0);}
     const p95=values=>values.length?values.sort((a,b)=>a-b)[Math.min(values.length-1,Math.floor(values.length*.95))]:null;
     return {renderP95Ms:p95(durations),renderSamples:durations.length,frameIntervalP95Ms:p95(intervals),frameIntervalsOver25Ms:intervals.filter(n=>n>25).length,peakRows,peakActive,peakRetained};
    });
    await page.evaluate(()=>{scrollTo(0,2500);});await shot('scroll');
    await page.locator('#search-input').fill('Name 123');await page.waitForTimeout(300);await shot('search');
    await page.locator('#search-input').fill('');await page.waitForTimeout(250);await page.evaluate(()=>scrollTo(0,0));
    for(const columns of [2,3,4,5]){await page.evaluate(({plus,columns})=>{if(plus)applyViewMode(columns);else applyUiPreferences({...uiPreferences,columns});},{plus,columns});await shot('matrix'+columns);}
    await page.evaluate(plus=>{if(plus)applyViewMode(0);else applyUiPreferences({...uiPreferences,columns:0});scrollTo(0,0);},plus);
    await page.evaluate(()=>showInfo());await shot('about');await page.evaluate(()=>closeDialog());await page.waitForTimeout(250);
    await page.evaluate(()=>openSettings());await shot('settings');await page.evaluate(()=>openFetcher());await shot('fetcher');await page.evaluate(()=>closeFetcher());await page.waitForTimeout(250);await page.evaluate(()=>closeSettings());await page.waitForTimeout(250);
    await page.evaluate(()=>openViewer('fixture_0'));await page.waitForFunction(()=>document.getElementById('full-image').complete);await shot('viewer');await page.evaluate(()=>closeViewer());await page.waitForTimeout(250);
    const measured=await page.evaluate(async()=>{
     const original=profiles,untouched=profiles.fixture_1,avatar=document.querySelector('.avatar[data-user="fixture_1"] img');
     nativeEvent('status',{revision:2,matched:fixture.unique,missing:0,unreadable:0,folderStatus:'ready',scopeRevision:'fixture',profileChanges:{fixture_0:{file:true,width:512,height:512,version:'v2'}}});
     const stableMap=profiles===original,untouchedRecord=profiles.fixture_1===untouched,stableAvatar=avatar===document.querySelector('.avatar[data-user="fixture_1"] img');
     openViewer('fixture_0');await new Promise(requestAnimationFrame);scale=2;applyTransform();await new Promise(requestAnimationFrame);
     let writes=0;const observer=new MutationObserver(rows=>{writes+=rows.length;});observer.observe(document.getElementById('full-image'),{attributes:true,attributeFilter:['style']});for(let i=0;i<100;i++){tx+=1;applyTransform();}await new Promise(requestAnimationFrame);await Promise.resolve();observer.disconnect();closeViewer();
     return {stableMap,untouchedRecord,stableAvatar,transformWritesFor100Inputs:writes,avatarRectReads,metrics:thumbnailMetrics()};
    });
    assert.equal(errors.length,0,errors.join('\n'));assert.ok(measured.metrics.active<=6&&measured.metrics.retained<=64&&measured.metrics.rows<40);
    if(variant==='after'){assert.ok(measured.stableMap&&measured.untouchedRecord&&measured.stableAvatar);assert.ok(measured.transformWritesFor100Inputs<=1);assert.equal(measured.avatarRectReads,0);}
    report.datasets.push({count,variant,rendering,...measured});await context.close();
   }
   for(const name of Object.keys(captures.before)){const same=captures.before[name].equals(captures.after[name]);report.comparisons.push({count,screen:name,identical:same,sha256Before:crypto.createHash('sha256').update(captures.before[name]).digest('hex'),sha256After:crypto.createHash('sha256').update(captures.after[name]).digest('hex')});}
  }
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(report,null,2)+'\n');assert.ok(report.comparisons.every(row=>row.identical),'Screenshot difference; inspect evidence');console.log(JSON.stringify(report,null,2));
 } finally {if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
