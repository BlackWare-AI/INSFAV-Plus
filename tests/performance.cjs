/* Host-browser diagnostics, not Android FPS certification. BASELINE_ASSETS enables comparison. */
const {chromium}=require('playwright'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
(async()=>{
 const roots={current:path.resolve(__dirname,'../app/src/main/assets')};if(process.env.BASELINE_ASSETS)roots.baseline=path.resolve(process.env.BASELINE_ASSETS);
 const server=http.createServer((req,res)=>{const [variant,...segments]=req.url.split('?')[0].slice(1).split('/'),root=roots[variant];if(!root){res.writeHead(404).end();return;}const name=segments.join('/')||'index.html',file=path.join(root,name);if(!file.startsWith(root)||!fs.existsSync(file)){res.writeHead(404).end();return;}res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':name.endsWith('.woff2')?'font/woff2':name.endsWith('.webp')?'image/webp':'text/html');res.end(fs.readFileSync(file));});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser;const output={};
 try{for(const variant of Object.keys(roots)){
  browser=await chromium.launch({executablePath:process.env.CHROME_BIN,headless:true,args:['--no-sandbox','--no-zygote','--single-process','--disable-gpu']});
  const page=await browser.newPage({viewport:{width:393,height:820}});
  await page.addInitScript(()=>{Android={request(raw){const request=JSON.parse(raw);if(request.command==='bootstrap')nativeEvent('bootstrap',{raw:JSON.stringify([{name:'Benchmark',items:Array.from({length:10000},(_,i)=>({pageId:'user'+i,name:'Account '+i}))}]),profiles:{},app:{version:'benchmark',build:0},stats:{usage:0,load:0,clear:0}});}};});
  await page.goto('http://127.0.0.1:'+server.address().port+'/'+variant+'/');await page.waitForSelector('#splash[hidden]',{state:'attached'});
  await page.locator('.folder-header').click();await page.waitForTimeout(200);
  output[variant]=await page.evaluate(async()=>{
   const work=[],frames=[];let peak=0,last=0;const original=renderVisible;
   renderVisible=function(){const start=performance.now();original();work.push(performance.now()-start);};
   for(let i=0;i<100;i++)await new Promise(resolve=>requestAnimationFrame(time=>{if(last)frames.push(time-last);last=time;scrollTo(0,(i%50)*700);peak=Math.max(peak,document.querySelectorAll('.account').length);resolve();}));
   const percentile=(values,p)=>{const sorted=[...values].sort((a,b)=>a-b);return Number((sorted[Math.min(sorted.length-1,Math.floor(sorted.length*p))]||0).toFixed(3));};
   return {frames:frames.length,renderCalls:work.length,peakAccountNodes:peak,renderP50Ms:percentile(work,.5),renderP95Ms:percentile(work,.95),frameP50Ms:percentile(frames,.5),frameP95Ms:percentile(frames,.95),framesOver32Ms:frames.filter(x=>x>32).length};
  });await browser.close();browser=null;
 }console.log(JSON.stringify(output,null,2));}finally{if(browser)await browser.close();server.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
