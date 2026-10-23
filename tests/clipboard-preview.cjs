/* Browser-preview clipboard integration; native clipboard has separate Android tests. */
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
// Poll from the test process: Playwright 1.51 waitForFunction eval conflicts with the app CSP.
async function waitForValue(page,predicate){const deadline=Date.now()+10000;while(Date.now()<deadline){if(await page.evaluate(predicate))return;await page.waitForTimeout(25);}assert.fail('Timed out waiting for the actual UI/native event');}
(async()=>{
 const root=path.resolve(__dirname,'../app/src/main/assets'),server=http.createServer((req,res)=>{
  const file=path.join(root,req.url==='/'?'index.html':req.url.split('?')[0]);
  if(!file.startsWith(root)||!fs.existsSync(file)){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':file.endsWith('.woff2')?'font/woff2':file.endsWith('.webp')?'image/webp':'text/html');res.end(fs.readFileSync(file));
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,args:['--no-sandbox','--disable-gpu']});
 try {
  const context=await browser.newContext({viewport:{width:393,height:860},permissions:['clipboard-read','clipboard-write']}),page=await context.newPage();
  await page.addInitScript(()=>localStorage.setItem('insfav-preview-data',JSON.stringify([{name:'Folder A',items:[{pageId:'fixture_one'},{pageId:'fixture_two'}]},{name:'Folder B',items:[{pageId:'@FIXTURE_ONE'},{pageId:'fixture_three'}]}])));
  await page.goto('http://127.0.0.1:'+server.address().port);await page.waitForSelector('#splash[hidden]',{state:'attached'});
  await page.locator('#info-btn').click();if(process.env.POLISH_SCREENSHOTS)await page.screenshot({path:process.env.POLISH_SCREENSHOTS+'/about.png'});
  await page.getByRole('button',{name:'Settings',exact:true}).click();await page.locator('#settings').waitFor({state:'visible'});
  await page.evaluate(()=>nativeEvent('status',{profiles:{fixture_one:{file:true,width:128,height:128}},matched:1,missing:2,lastScan:100000,folder:'.thumbnails · primary:Download/Very/Long/Folder/Name/.thumbnails',resolution:{count:1,complete:true,expected:'256×256'},scanState:'completed'}));
  await page.locator('#copy-missing-users').click();await waitForValue(page,()=>document.getElementById('toast').textContent==='2 usernames copied.');
  assert.equal(await page.evaluate(()=>navigator.clipboard.readText()),'fixture_two\nfixture_three');
  await page.evaluate(()=>document.getElementById('toast').hidden=true);if(process.env.POLISH_SCREENSHOTS)await page.screenshot({path:process.env.POLISH_SCREENSHOTS+'/settings.png'});
  await page.evaluate(()=>Object.defineProperty(navigator.clipboard,'writeText',{value:async()=>{throw Error('fixture rejection');},configurable:true}));
  await page.locator('#copy-missing-users').click();await waitForValue(page,()=>document.getElementById('toast').textContent==='Clipboard unavailable. Try again.');
  await page.evaluate(()=>nativeEvent('status',{profiles:{},missing:0}));assert.equal(await page.locator('#copy-missing-users').isDisabled(),true);
  console.log('PASS preview clipboard: unique missing usernames only, actual clipboard contents, rejection feedback, empty list; native Android tested separately');
 } finally {await browser.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
