/* Browser DOM fixtures only: no real Instagram requests or credentials. */
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,args:['--no-sandbox','--disable-gpu']});try{
 const page=await browser.newPage();let escaped=0;const origin='https://www.instagram.com/example/',small='https://scontent.cdninstagram.com/profile-small.jpg',hd='https://scontent.cdninstagram.com/profile-hd.jpg';
 const html='<html><head><link rel="canonical" href="'+origin+'"></head><body><main><header><img alt="example profile picture" src="'+small+'"></header></main><article><img alt="example post" src="https://scontent.cdninstagram.com/post.jpg"></article><script type="application/json">'+JSON.stringify({username:'example',id:'1',profile_pic_url_hd:hd})+'</script></body></html>';
 await page.route('**/*',route=>{const url=route.request().url();if(url===origin)return route.fulfill({contentType:'text/html',body:html});if(url.startsWith('https://scontent.cdninstagram.com/'))return route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="'+(url===hd?640:128)+'" height="'+(url===hd?480:128)+'"><rect width="100%" height="100%" fill="#888"/></svg>'});escaped++;return route.abort();});
 await page.goto(origin);await page.waitForFunction(()=>document.images[0].complete);
 const probe=name=>fs.readFileSync(path.resolve(__dirname,'../app/src/main/assets/'+name),'utf8');
 const low=JSON.parse(await page.evaluate(source=>eval('('+source+')')("example","1"),probe('profile-probe.js')));assert.equal(low.url,small);assert.equal(low.width,128);
 let high;
 for(let i=0;i<20;i++){high=JSON.parse(await page.evaluate(source=>eval('('+source+')')("example","1"),probe('profile-probe-hd.js')));if(high.url)break;await page.waitForTimeout(50);}
 assert.equal(high.url,hd);assert.equal(high.width,640);assert.equal(high.height,480);console.log('PASS original thumbnail probe is retained; HD adapter selects actual larger identity-bound image');
 const wrong=JSON.parse(await page.evaluate(source=>eval('('+source+')')("different","1"),probe('profile-probe-hd.js')));assert.equal(wrong.url,undefined);console.log('PASS HD adapter rejects another account and never uses post images');
 assert.equal(escaped,0);console.log('PASS all probe fixtures are intercepted; no live requests');
 }finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
