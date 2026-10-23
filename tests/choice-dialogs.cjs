'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),http=require('node:http'),path=require('node:path');
const {chromium}=require('playwright');

(async()=>{
 const root=path.resolve(__dirname,'../app/src/main/assets');
 const server=http.createServer((req,res)=>{
  const file=path.resolve(root,'.'+(req.url==='/'?'/index.html':req.url.split('?')[0]));
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':file.endsWith('.woff2')?'font/woff2':file.endsWith('.jpg')?'image/jpeg':'text/html');
  res.end(fs.readFileSync(file));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser,checks=0;
 const check=(value,message)=>{assert.ok(value,message);checks++;};
 try{
  browser=await chromium.launch({executablePath:process.env.CHROME_BIN,args:['--no-sandbox','--disable-gpu']});
  const context=await browser.newContext({locale:'en-GB',viewport:{width:393,height:850},reducedMotion:'reduce',hasTouch:true});
  const page=await context.newPage(),errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{
   window.commands=[];
   window.original=JSON.stringify([{name:'A',items:[{pageId:'one'},{pageId:'two'}]},{name:'B',items:[{pageId:'one'},{pageId:'three'}]}]);
   window.Android={request(raw){
    const command=JSON.parse(raw);commands.push(command);
    if(command.command==='bootstrap')nativeEvent('bootstrap',{raw:original,profiles:{},language:'system',app:{version:'1.0.0-debug',build:1}});
    if(command.command==='language')nativeEvent('language',{choice:command.choice});
    if(command.command==='fetcher-results')nativeEvent('fetcher-results',{status:command.status,users:['one']});
   }};
  });
  await page.goto('http://127.0.0.1:'+server.address().port);
  await page.locator('#splash').waitFor({state:'hidden'});
  const dialog=page.locator('#dialog'),radios=page.getByRole('radio');
  const focused=locator=>locator.evaluate(node=>node===document.activeElement);
  const requests=command=>page.evaluate(name=>commands.filter(item=>item.command===name),command);
  async function open(selector,count){
   await page.locator(selector).click();await dialog.waitFor({state:'visible'});
   check(await radios.count()===count,'Every option is available in the themed dialog');
   check(await radios.filter({has:page.locator('.choice-check')}).count()===count,'Each option has its own local selection marker');
   check(await focused(page.getByRole('radio',{checked:true})),'Opening focuses the saved selection');
   check(await page.locator(selector).getAttribute('aria-expanded')==='true','Opener exposes expanded state');
  }
  async function closed(selector){
   await dialog.waitFor({state:'hidden'});
   check(await focused(page.locator(selector)),'Closing returns focus to the opener');
   check(await page.locator(selector).getAttribute('aria-expanded')==='false','Closing clears expanded state');
  }
  async function settings(){
   await page.locator('#info-btn').click();
   check(await page.locator('#dialog-body img').count()===0,'Information modal omits launcher artwork');
   await page.getByRole('button',{name:/^(Settings|Ayarlar)$/}).click();
  }
  async function screenshot(name){
   if(!process.env.SELECTOR_SCREENSHOTS)return;
   fs.mkdirSync(process.env.SELECTOR_SCREENSHOTS,{recursive:true});
   await page.screenshot({path:path.join(process.env.SELECTOR_SCREENSHOTS,name+'.png')});
  }

  await open('#result-filter',7);
  check(await page.getByRole('dialog',{name:'Fetcher results',exact:true}).count()===1,'Filter dialog has a translated accessible title');
  check(await page.getByRole('radiogroup',{name:'Fetcher results',exact:true}).count()===1,'Options form a named radio group');
  check(await dialog.locator('.modal').evaluate(node=>getComputedStyle(node).backgroundColor==='rgb(0, 0, 0)'),'Modal uses AMOLED black');
  check(await radios.evaluateAll(nodes=>nodes.every(node=>getComputedStyle(node).backgroundColor==='rgb(0, 0, 0)')),'Option rows use AMOLED black');
  await screenshot('result-filter');
  await page.keyboard.press('End');
  check(await focused(page.getByRole('radio',{name:'Retry Later',exact:true})),'End moves focus to the final option');
  check(await page.getByRole('radio',{name:'All accounts',exact:true}).getAttribute('aria-checked')==='true','Keyboard navigation does not silently change the filter');
  await page.keyboard.press('Shift+Tab');
  check(await focused(page.getByRole('button',{name:'Close',exact:true})),'Shift+Tab wraps to Close');
  await page.keyboard.press('Tab');
  check(await focused(page.getByRole('radio',{name:'Retry Later',exact:true})),'Tab wraps to the current roving option');
  await page.keyboard.press('Home');await page.keyboard.press('ArrowUp');
  check(await focused(page.getByRole('radio',{name:'Retry Later',exact:true})),'Arrow navigation wraps through all options');
  await page.keyboard.press('Escape');await closed('#result-filter');
  check((await requests('fetcher-results')).length===0,'Dismissing keyboard navigation sends no filter request');
  for(const method of ['button','outside','hardware']){
   await open('#result-filter',7);await page.keyboard.press('ArrowDown');
   if(method==='button')await page.getByRole('button',{name:'Close',exact:true}).click();
   else if(method==='outside')await page.mouse.click(5,5);
   else check(await page.evaluate(()=>handleBack()),'Android Back is handled by the open modal');
   await closed('#result-filter');
   check((await requests('fetcher-results')).length===0,method+' dismissal does not apply a pending selection');
  }
  const outcomes=[['Downloaded','SUCCESS'],['Existing','EXISTING'],['Skipped Unavailable','SKIPPED_UNAVAILABLE'],['Skipped No Image','SKIPPED_NO_IMAGE'],['Failed','FAILED'],['Retry Later','RETRY_LATER']];
  for(const [label,code]of outcomes){
   await open('#result-filter',7);await page.getByRole('radio',{name:label,exact:true}).tap();await closed('#result-filter');
   check((await requests('fetcher-results')).at(-1).status===code,'Selection dispatches the unchanged '+code+' outcome');
   check(await page.locator('#result-filter-value').innerText()===label,'Trigger displays the applied outcome');
   check(await page.locator('#result-refresh').isVisible(),'Filtered results retain explicit refresh');
  }
  check((await requests('fetcher-results')).length===6,'Each changed outcome sends exactly one request');
  await open('#result-filter',7);await page.getByRole('radio',{name:'Retry Later',exact:true}).click();await closed('#result-filter');
  check((await requests('fetcher-results')).length===6,'Reselecting the same outcome does not request a duplicate snapshot');
  await page.locator('#result-refresh').click();check((await requests('fetcher-results')).length===7,'Explicit refresh still sends one request');
  await open('#result-filter',7);await page.getByRole('radio',{name:'All accounts',exact:true}).click();await closed('#result-filter');
  check((await requests('fetcher-results')).length===7&&await page.locator('#result-refresh').isHidden(),'All accounts clears the filter locally');
  check(await page.evaluate(()=>doc.raw===original&&doc.total===4),'Filter selection preserves raw JSON and duplicate memberships');

  await settings();await page.locator('#settings').evaluate(node=>node.scrollTop=20);
  const settingsScroll=await page.locator('#settings').evaluate(node=>node.scrollTop);
  await open('#language-choice',3);
  check(await page.locator('#settings').evaluate(node=>node.scrollTop)===settingsScroll,'Opening preserves the underlying settings scroll position');
  check(await page.getByRole('dialog',{name:'Language',exact:true}).count()===1,'Language dialog is labelled');
  check(await page.getByRole('radio',{name:'System language',exact:true}).getAttribute('aria-checked')==='true','System preference stays distinct from resolved English');
  await screenshot('language-choice');
  await page.keyboard.press('ArrowDown');
  check((await requests('language')).length===0,'Moving language focus does not persist a preference');
  await page.keyboard.press('Enter');await closed('#language-choice');
  check(await page.locator('#settings').evaluate(node=>node.scrollTop)===settingsScroll,'Choosing a language preserves the underlying settings scroll position');
  check((await requests('language')).at(-1).choice==='en'&&await page.locator('#language-value').innerText()==='English','Enter applies English through the existing native bridge');
  await open('#language-choice',3);await page.getByRole('radio',{name:'Türkçe',exact:true}).tap();await closed('#language-choice');
  check((await requests('language')).at(-1).choice==='tr'&&await page.locator('html').getAttribute('lang')==='tr','Turkish selection applies immediately after the bridge reply');
  await page.locator('#settings-back').click();await open('#result-filter',7);
  check(await page.getByRole('dialog',{name:'İndirici sonuçları',exact:true}).count()===1,'Filter dialog title follows Turkish');
  check(JSON.stringify(await radios.allTextContents())===JSON.stringify(['Tüm hesaplar','İndirilen','Mevcut','Erişilemediği için atlanan','Görsel olmadığı için atlanan','Başarısız','Daha sonra dene']),'Every filter option follows Turkish');
  await screenshot('result-filter-tr');
  await page.getByRole('button',{name:'Kapat',exact:true}).click();await closed('#result-filter');await settings();
  await open('#language-choice',3);
  check(await page.getByRole('dialog',{name:'Dil',exact:true}).count()===1,'Reopened language dialog follows Turkish');
  await screenshot('language-choice-tr');
  await page.getByRole('radio',{name:'Sistem dili',exact:true}).focus();await page.keyboard.press('Space');await closed('#language-choice');
  check((await requests('language')).at(-1).choice==='system'&&await page.locator('html').getAttribute('lang')==='en','Space restores system language without converting it into a fixed English choice');
  await page.locator('#settings-back').click();

  for(const width of [320,360,480]){
   await page.setViewportSize({width,height:480});
   await page.evaluate(()=>document.documentElement.style.fontSize='32px');
   for(const selector of ['#result-filter','#language-choice']){
    if(selector==='#language-choice')await settings();
    await open(selector,selector==='#result-filter'?7:3);
    const layout=await dialog.evaluate(overlay=>{
     const modal=overlay.querySelector('.modal'),body=overlay.querySelector('#dialog-body'),rect=modal.getBoundingClientRect();
     const buttons=[...overlay.querySelectorAll('[role="radio"],#dialog-actions button')];
     return {within:rect.left>=19&&rect.right<=innerWidth-19&&rect.top>=19&&rect.bottom<=innerHeight-19,
      overflow:modal.scrollWidth>modal.clientWidth||buttons.some(button=>button.scrollWidth>button.clientWidth),
      targets:buttons.every(button=>button.getBoundingClientRect().height>=48),
      font:parseFloat(getComputedStyle(buttons[0]).fontSize),scrollable:body.scrollHeight>body.clientHeight};
    });
    check(layout.within&&!layout.overflow,'Dialog fits '+width+'px screen with 200% text');
    check(layout.targets&&layout.font>=26,'Text enlarges and touch targets stay at least 48px');
    if(selector==='#result-filter')check(layout.scrollable,'Long options scroll within the modal');
    await radios.last().scrollIntoViewIfNeeded();
    check(await page.getByRole('button',{name:'Close',exact:true}).evaluate(button=>{const rect=button.getBoundingClientRect();return rect.top>=0&&rect.bottom<=innerHeight;}),'Close stays reachable while options scroll');
    await page.getByRole('button',{name:'Close',exact:true}).click();await closed(selector);
    if(selector==='#language-choice')await page.locator('#settings-back').click();
   }
  }
  await page.setViewportSize({width:393,height:850});await page.evaluate(()=>document.documentElement.style.fontSize='');
  await page.emulateMedia({reducedMotion:'no-preference'});
  await open('#result-filter',7);await page.keyboard.press('Escape');await closed('#result-filter');
  check(await page.evaluate(()=>!document.body.classList.contains('overlay-open')),'Animated dismissal releases the page scroll lock');

  const project=path.resolve(__dirname,'..');
  check(fs.readFileSync(path.join(project,'app/src/main/AndroidManifest.xml'),'utf8').includes('android:icon="@mipmap/ic_launcher_plus"'),'Application keeps the Plus launcher icon');
  check(fs.readFileSync(path.join(project,'app/src/main/res/drawable/ic_launcher_plus_foreground.xml'),'utf8').includes('@drawable/insfav_plus_logo'),'Launcher foreground references the supplied logo');
  check(fs.readFileSync(path.join(project,'web/static/logo.jpg')).equals(fs.readFileSync(path.join(project,'app/src/main/res/drawable-nodpi/insfav_plus_logo.jpg'))),'Launcher artwork preserves the supplied image bytes');
  check(errors.length===0,'No browser errors: '+errors.join('; '));
  console.log('PASS '+checks+' choice-dialog checks: AMOLED, every outcome, native language bridge, keyboard/touch/back, focus, 200% text, scrolling, correct icon and no About artwork');
 }finally{
  await browser?.close();await new Promise(resolve=>server.close(resolve));
 }
})().catch(error=>{console.error(error);process.exitCode=1;});
