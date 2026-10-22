/* Presentation regression: identical bridge event traces in baseline and current assets.
   Native persistence is not simulated as a device test; native implementation must stay identical. */
const {chromium}=require('playwright'),http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),cp=require('node:child_process');
(async()=>{
 const current=path.resolve(__dirname,'../app/src/main/assets'),baseline=process.env.BASELINE_ASSETS;
 assert.ok(baseline,'BASELINE_ASSETS must contain the unmodified previous packaged UI');
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,args:['--no-sandbox','--disable-gpu']});
 async function trace(root){
  const server=http.createServer((req,res)=>{const name=req.url==='/'?'index.html':req.url.slice(1).split('?')[0],f=path.join(root,name);if(!f.startsWith(root)||!fs.existsSync(f)||fs.statSync(f).isDirectory()){res.writeHead(404).end();return;}res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':name.endsWith('.woff2')?'font/woff2':name.endsWith('.webp')?'image/webp':'text/html');res.end(fs.readFileSync(f));});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const page=await browser.newPage();
  await page.addInitScript(()=>{window.Android={request(){}};});await page.goto('http://127.0.0.1:'+server.address().port);
  const result=await page.evaluate(()=>{
   const raw=JSON.stringify([{name:'A',items:[{pageId:'one'},{pageId:'two'}]},{name:'B',items:[{pageId:'one'}]}]);
   const snapshots=[];const capture=()=>{showInfo();snapshots.push({total:doc?.total||0,usage:stats.usage,load:stats.load,clear:stats.clear});closeDialog();};
   nativeEvent('bootstrap',{raw,stats:{usage:37,load:18,clear:4}});capture();capture();
   nativeEvent('imported',{raw});capture();nativeEvent('error','Import rejected');capture();nativeEvent('status',{profiles:{}});capture();
   nativeEvent('bootstrap',{raw,stats:{usage:38,load:19,clear:4}});capture();
   return snapshots;
  });await page.close();await new Promise(r=>server.close(r));return result;
 }
 try{
  const old=await trace(path.resolve(baseline)),now=await trace(current);assert.deepEqual(now,old);
  assert.deepEqual(now.map(x=>[x.total,x.usage,x.load]),[[3,37,18],[3,37,18],[3,37,19],[3,37,19],[3,37,19],[3,38,19]]);
  const native=fs.readFileSync(path.resolve(__dirname,'../app/src/main/java/com/instagram/insfav/MainActivity.java'),'utf8');
  const previous=fs.readFileSync(path.resolve(baseline,'../java/com/instagram/insfav/MainActivity.java'),'utf8');
  for(const pattern of [/private int incrementUsage\(\) \{[^}]+}/,/getPreferences\(0\)\.edit\(\)\.putInt\("load",[^;]+;/,/getPreferences\(0\)\.edit\(\)\.putInt\("clear",[^;]+;/])assert.equal(native.match(pattern)?.[0],previous.match(pattern)?.[0]);
  console.log('PASS baseline/current: 6 identical counter snapshots including repeated About, import, error, status, and reopened stored values');
  console.log('PASS Total Accounts keeps duplicate memberships: 3 records / 2 unique accounts');
  console.log('PASS native counter increment and preference statements are unchanged');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
