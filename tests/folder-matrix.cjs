// Offline browser regression: independent folder layout, native bridge, focus and virtualization.
'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {chromium}=require('playwright');
const plus=require('../package.json').name.includes('plus'),assets=path.resolve(__dirname,'../app/src/main/assets');
(async()=>{
 const server=http.createServer((req,res)=>{const f=path.resolve(assets,'.'+(req.url==='/'?'/index.html':req.url.split('?')[0]));if(!f.startsWith(assets+path.sep)||!fs.existsSync(f)){res.writeHead(404).end();return;}res.setHeader('Content-Type',f.endsWith('.js')?'text/javascript':f.endsWith('.css')?'text/css':f.endsWith('.woff2')?'font/woff2':'text/html');res.end(fs.readFileSync(f));});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({args:['--no-sandbox'],executablePath:process.env.CHROME_BIN});let checks=0;const check=(v,m)=>{assert.ok(v,m);checks++;};
 try{for(const width of [320,393,600]){
  const page=await browser.newPage({viewport:{width,height:850},reducedMotion:'reduce'}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(({plus})=>{window.calls=[];window.Android={request(raw){const a=JSON.parse(raw);calls.push(a);let prefs=JSON.parse(localStorage.getItem('fixture-prefs')||'{"columns":0,"folderColumns":0,"language":"en"}');
   if(a.command==='bootstrap'){const raw=JSON.stringify(Array.from({length:211},(_,i)=>({name:'Long folder name repeated '+i,items:[{pageId:'user'+i,name:'User '+i}]})));nativeEvent('bootstrap',plus?{raw,profiles:{},columns:prefs.columns,folderColumns:prefs.folderColumns,language:prefs.language}:{raw,profiles:{},uiPreferences:prefs});}
   if(a.command==='ui-preferences'){prefs={columns:a.columns,folderColumns:a.folderColumns,language:a.language};localStorage.setItem('fixture-prefs',JSON.stringify(prefs));nativeEvent('ui-preferences',prefs);}
   if(a.command==='view-mode'){prefs.columns=a.columns;localStorage.setItem('fixture-prefs',JSON.stringify(prefs));nativeEvent('view-mode',{columns:a.columns});}
   if(a.command==='folder-view-mode'){prefs.folderColumns=a.columns;localStorage.setItem('fixture-prefs',JSON.stringify(prefs));nativeEvent('folder-view-mode',{folderColumns:a.columns});}
  }};},{plus});
  await page.goto('http://127.0.0.1:'+server.address().port);await page.locator('#splash').waitFor({state:'hidden'});
  for(const n of [2,3,4,5]){
   await page.evaluate(()=>openSettings());await page.locator(plus?'#folder-view-choice':'#folder-display-mode').click();
   if(plus)await page.locator('.choice-list [role=radio]').nth(n-1).click();else await page.locator('#preference-option-'+n).click();
   await page.evaluate(()=>closeSettings());await page.locator('#settings').waitFor({state:'hidden'});
   check(await page.evaluate(({n,plus})=>(plus?folderColumns:uiPreferences.folderColumns)===n&&((plus?viewMode:uiPreferences.columns)===0),{n,plus}),'Independent native bridge');
   check(await page.evaluate(()=>rows.flatMap(r=>r.groups||[]).length===211),'All closed folders retained');
   check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'No horizontal overflow');
   const tile=page.locator('.folder-grid-cell button').nth(1),key=await tile.getAttribute('data-folder-key');await tile.focus();await page.keyboard.press('Enter');
   check(await page.evaluate(key=>document.activeElement.dataset.folderKey===key&&document.activeElement.getAttribute('aria-expanded')==='true',key),'Enter transfers focus to expanded header');
   await page.keyboard.press('Enter');check(await page.evaluate(key=>document.activeElement.dataset.folderKey===key&&document.activeElement.getAttribute('aria-expanded')==='false',key),'Enter collapses and restores tile focus');
  }
  await page.evaluate(()=>window.scrollTo(0,document.scrollingElement.scrollHeight));await page.waitForTimeout(100);
  await page.locator('[data-folder-key="210"]').focus();await page.keyboard.press('Enter');
  check(await page.evaluate(()=>document.activeElement.dataset.folderKey==='210'&&document.activeElement.getAttribute('aria-expanded')==='true'),'Virtualized last folder retains focus');
  await page.keyboard.press('Enter');check(await page.evaluate(()=>document.activeElement.dataset.folderKey==='210'),'Virtualized collapse retains focus');
  await page.evaluate(()=>openSettings());await page.locator(plus?'#view-choice':'#display-mode').click();
  if(plus)await page.locator('.choice-list [role=radio]').nth(2).click();else await page.locator('#preference-option-3').click();
  await page.evaluate(()=>closeSettings());await page.locator('#settings').waitFor({state:'hidden'});
  await page.evaluate(()=>window.scrollTo(0,0));await page.waitForTimeout(100);await page.locator('[data-folder-key="1"]').click();
  check(await page.locator('.matrix-row').count()>0&&await page.locator('.folder-grid-cell').count()>0,'Account and folder matrices coexist');
  await page.locator('.folder-wrap.open .folder-header').click();
  await page.reload();await page.locator('#splash').waitFor({state:'hidden'});check(await page.evaluate(plus=>(plus?folderColumns:uiPreferences.folderColumns)===5,plus),'Reload restores folder setting');
  await page.locator('#search-input').fill('user10');await page.waitForTimeout(250);check(await page.evaluate(()=>!rows.some(r=>r.type==='folder-grid')&&rows.some(r=>r.type==='account'||r.type==='matrix')),'Search preserves full-width results');
  check(errors.length===0,'No JavaScript errors: '+errors.join(';'));await page.close();
 }console.log('PASS '+checks+' folder matrix checks (320/393/600px, native bridge, keyboard focus, search, persistence)');}
 finally{await browser.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
