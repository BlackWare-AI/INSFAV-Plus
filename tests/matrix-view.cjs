'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),http=require('node:http'),path=require('node:path');
const {chromium}=require('playwright');
(async()=>{
 const root=path.resolve(__dirname,'../app/src/main/assets'),server=http.createServer((req,res)=>{
  if(req.url.startsWith('/thumb/')){res.setHeader('Content-Type','image/svg+xml');res.end('<svg xmlns="http://www.w3.org/2000/svg" width="100" height="160"><rect width="100" height="160" fill="#666"/></svg>');return;}
  const file=path.resolve(root,'.'+(req.url==='/'?'/index.html':req.url.split('?')[0]));
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':file.endsWith('.woff2')?'font/woff2':file.endsWith('.jpg')?'image/jpeg':'text/html');res.end(fs.readFileSync(file));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,args:['--no-sandbox','--disable-gpu']});
 let checks=0;const errors=[],check=(value,label)=>{assert.ok(value,label);checks++;};
 const settle=page=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 try{
  const page=await browser.newPage({viewport:{width:393,height:850},hasTouch:true,reducedMotion:'reduce',locale:'en-GB'});
  page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
   window.commands=[];const items=Array.from({length:15000},(_,i)=>({pageId:'user_'+i+'_long_username_abcdef',name:'Display name '+i}));
   window.fixture=JSON.stringify([{name:'First',items:items.slice(0,7500)},{name:'Second',items:items.slice(7500)}]);
   window.Android={request(raw){const c=JSON.parse(raw);commands.push(c);
    if(c.command==='bootstrap')nativeEvent('bootstrap',{raw:fixture,columns:Number(localStorage.getItem('fixture-columns'))||0,language:localStorage.getItem('fixture-language')||'en',profiles:Object.fromEntries(items.map(item=>[item.pageId,{file:true,version:1}]))});
    if(c.command==='view-mode'){localStorage.setItem('fixture-columns',c.columns);nativeEvent('view-mode',{columns:c.columns});}
    if(c.command==='language'){localStorage.setItem('fixture-language',c.choice);nativeEvent('language',{choice:c.choice});}
    if(c.command==='fetcher-results')nativeEvent('fetcher-results',{status:c.status,users:items.filter((_,i)=>i%2===0).map(item=>item.pageId)});
   }};
  });
  await page.goto('http://127.0.0.1:'+server.address().port);await page.locator('#splash').waitFor({state:'hidden'});
  async function settings(){await page.evaluate(()=>openSettings());}
  async function mode(columns){
   await settings();await page.locator('#view-settings').evaluate(node=>node.open=true);await page.locator('#view-choice').click();
   await page.getByRole('radio',{name:columns?'Matrix · '+columns+' columns':'List',exact:true}).click();
   check(await page.evaluate(()=>commands.filter(c=>c.command==='view-mode').at(-1).columns)===columns,'One selection persists '+columns+' mode');
   await page.locator('#settings-back').click();await settle(page);
  }
  await page.locator('.folder-header').nth(1).click();await page.locator('.folder-header').first().click();
  const original=await page.locator('.account').first().evaluate(node=>({height:node.clientHeight,avatar:node.querySelector('.avatar').clientWidth,width:document.getElementById('list').clientWidth}));
  for(const columns of [2,3,4]){
   await mode(columns);
   for(const viewport of [{width:320,height:640},{width:393,height:850},{width:480,height:640},{width:640,height:360}]){
    await page.setViewportSize(viewport);await page.evaluate(()=>scrollTo(0,0));await settle(page);
    const geometry=await page.evaluate(()=>{
     const row=document.querySelector('.matrix-row'),cells=[...row.children],avatars=cells.map(node=>node.querySelector('.avatar').getBoundingClientRect());
     const labels=cells.map(node=>node.querySelector('.matrix-user'));
     return {columns:cells.length,squares:avatars.every(rect=>Math.abs(rect.width-rect.height)<.6),sizes:avatars.map(rect=>rect.width),
      aligned:avatars.every(rect=>Math.abs(rect.top-avatars[0].top)<.6),labels:labels.every((node,i)=>node.textContent.startsWith('@')&&node.getBoundingClientRect().top>=avatars[i].bottom&&getComputedStyle(node).textOverflow==='ellipsis'),
      radius:getComputedStyle(cells[0].querySelector('.avatar')).borderRadius,fit:document.scrollingElement.scrollWidth<=innerWidth,
      extraName:!!row.querySelector('.card-name'),metrics:thumbnailMetrics()};
    });
    check(geometry.columns===columns&&geometry.squares&&geometry.aligned,'Equal square '+columns+'-column cells at '+viewport.width+'px');
    check(geometry.sizes.every(size=>Math.abs(size-geometry.sizes[0])<.6)&&geometry.sizes[0]>=48,'Consistent, usable image dimensions');
    check(geometry.labels&&!geometry.extraName&&geometry.radius==='18px'&&geometry.fit,'Only ellipsized usernames below images, existing rounding, no overflow');
    check(geometry.metrics.active<=6&&geometry.metrics.retained<=64&&geometry.metrics.rows<32,'Matrix DOM and images remain bounded');
    if(process.env.MATRIX_SCREENSHOTS){fs.mkdirSync(process.env.MATRIX_SCREENSHOTS,{recursive:true});await page.waitForTimeout(2250);await page.screenshot({path:path.join(process.env.MATRIX_SCREENSHOTS,columns+'-'+viewport.width+'.png')});}
   }
   await page.setViewportSize({width:320,height:640});
   await page.evaluate(()=>{document.documentElement.style.fontSize='32px';dispatchEvent(new Event('resize'));});await settle(page);
   check(await page.locator('.matrix-account .avatar').first().evaluate(node=>{const rect=node.getBoundingClientRect();return Math.abs(rect.width-rect.height)<.6;}),'Images stay square at 200% text');
   check(await page.locator('.matrix-user').first().evaluate(node=>getComputedStyle(node).textOverflow==='ellipsis'&&node.getBoundingClientRect().height>=38)&&await page.evaluate(()=>document.scrollingElement.scrollWidth<=innerWidth),'Large usernames retain equal rows without horizontal overflow');
   await page.evaluate(()=>{document.documentElement.style.fontSize='';dispatchEvent(new Event('resize'));scrollTo(0,document.scrollingElement.scrollHeight);});await settle(page);
   check(await page.locator('.matrix-user').filter({hasText:'user_14999_'}).count()===1,'Matrix reaches its actual last account');
   await page.locator('#search-input').fill('user_14999_');await page.waitForTimeout(180);await settle(page);
   check(await page.locator('.matrix-account').count()===1,'Matrix search uses all collections');
   await page.locator('#clear-search').click();await page.evaluate(()=>scrollTo(0,0));await settle(page);
  }
  await page.locator('#result-filter').click();await page.getByRole('radio',{name:'Downloaded',exact:true}).click();
  await page.locator('#search-input').fill('user_14998_');await page.waitForTimeout(180);
  check(await page.locator('.matrix-account').count()===1,'Matrix result filter intersects with search');
  await page.locator('#search-input').fill('user_14999_');await page.waitForTimeout(180);
  check(await page.locator('.matrix-account').count()===0,'Matrix excludes accounts outside result filter');
  await page.locator('#clear-search').click();await page.locator('#result-filter').click();await page.getByRole('radio',{name:'All accounts',exact:true}).click();
  await page.reload();await page.locator('#splash').waitFor({state:'hidden'});check(await page.evaluate(()=>viewMode)===4,'Matrix choice survives reload through native bootstrap');
  check(await page.locator('.matrix-account').count()===0,'Reload keeps collections collapsed');
  await page.locator('.folder-header').first().click();await settle(page);
  await page.locator('.matrix-user').first().click();check(await page.evaluate(()=>commands.at(-1).command)==='open','Username opens the corresponding profile');
  await page.locator('.matrix-account .avatar').first().click();check(await page.locator('#viewer').isVisible(),'Matrix image opens the existing viewer');
  await page.evaluate(()=>handleBack());await mode(0);await page.setViewportSize({width:393,height:850});await settle(page);
  const restored=await page.locator('.account').first().evaluate(node=>({height:node.clientHeight,avatar:node.querySelector('.avatar').clientWidth,width:document.getElementById('list').clientWidth}));
  check(JSON.stringify(restored)===JSON.stringify(original),'Returning to List restores the original row/avatar/list geometry');
  await settings();const language=await page.locator('#language-choice').boundingBox(),header=await page.locator('#settings header').boundingBox();
  check(language.y>=header.y&&language.y+language.height<=header.y+header.height&&language.x>header.x+header.width/2,'Language choice is at the upper right of settings');
  await page.locator('#language-choice').click();await page.getByRole('radio',{name:'Türkçe',exact:true}).click();
  await page.locator('#view-settings').evaluate(node=>node.open=true);await page.locator('#view-choice').click();
  check(await page.getByRole('radio',{name:'Matris · 4 sütun',exact:true}).count()===1,'View options follow Turkish');
  await page.getByRole('button',{name:'Kapat',exact:true}).click();await page.locator('#language-choice').click();await page.getByRole('radio',{name:'English',exact:true}).click();
  check(await page.locator('html').getAttribute('lang')==='en','Upper-right language choice also applies English');
  check(errors.length===0,'No script errors: '+errors.join('; '));
  console.log('PASS '+checks+' matrix checks: 2/3/4 square columns, labels, settings persistence, folders/search/filter, viewer, original list and language toolbar');
 }finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});
