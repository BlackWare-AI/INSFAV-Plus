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
 await page.evaluate(({small,hd})=>{document.querySelector('script[type="application/json"]').textContent=JSON.stringify({username:'example',id:'1',profile_pic_url:small,hd_profile_pic_url_info:{url:hd}});},{small,hd});
 const withInfo=JSON.parse(await page.evaluate(source=>eval('('+source+')')("example","1"),probe('profile-probe-hd.js')));assert.equal(withInfo.url,hd);console.log('PASS explicit HD info wins over the small profile URL');
 const wrong=JSON.parse(await page.evaluate(source=>eval('('+source+')')("different","1"),probe('profile-probe-hd.js')));assert.equal(wrong.url,undefined);console.log('PASS HD adapter rejects another account and never uses post images');
 const fresh='https://scontent.cdninstagram.com/v/t51.2885-19/123_456_789_n.jpg?oh=new&oe=2',changed=fresh.replace('123_456_789','987_654_321');
 let hdBodies=0;
 await page.unroute('**/*');await page.route('**/*',route=>{
  const url=route.request().url();if(url===origin)return route.fulfill({contentType:'text/html',body:'<html><head><link rel="canonical" href="'+origin+'"></head><body><script type="application/json">'+JSON.stringify({username:'example',id:'1',profile_pic_url_hd:fresh})+'</script></body></html>'});
  if(url===fresh||url===changed){hdBodies++;return route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="640" height="480"><rect width="100%" height="100%" fill="#888"/></svg>'});}escaped++;return route.abort();
 });
 await page.goto(origin);const hint={user:'example',url:fresh.replace('oh=new&oe=2','oh=old&oe=1'),immutable:true,path:new URL(fresh).pathname,query:[],width:640,height:480};
 const cached=JSON.parse(await page.evaluate(({source,hint})=>eval('('+source+')')('example','1',hint),{source:probe('profile-probe-hd.js'),hint}));
 assert.equal(cached.url,fresh);await page.waitForTimeout(100);assert.equal(hdBodies,0);
 await page.evaluate(changed=>document.querySelector('script[type="application/json"]').textContent=JSON.stringify({username:'example',id:'1',profile_pic_url_hd:changed}),changed);
 await page.evaluate(({source,hint})=>eval('('+source+')')('example','1',hint),{source:probe('profile-probe-hd.js'),hint});
 await page.waitForFunction(url=>window.__insfavHD?.get(url)?.complete,changed);const updated=JSON.parse(await page.evaluate(({source,hint})=>eval('('+source+')')('example','1',hint),{source:probe('profile-probe-hd.js'),hint}));assert.equal(updated.url,changed);assert.equal(hdBodies,1);
 await page.evaluate(fresh=>{document.querySelector('script[type="application/json"]').textContent=JSON.stringify({username:'example',id:'1',profile_pic_url_hd:fresh});document.body.insertAdjacentHTML('beforeend','<form id="loginForm" action="/accounts/login/"><input></form>');},fresh);
 const login=JSON.parse(await page.evaluate(({source,hint})=>eval('('+source+')')('example','1',hint),{source:probe('profile-probe-hd.js'),hint}));assert.equal(login.wall,'COOKIE_INVALID_EXPIRED');assert.equal(login.url,undefined);
 console.log('PASS verified HD cache hint avoids duplicate browser image load, refreshes changed photo and preserves login walls');
 assert.equal(escaped,0);console.log('PASS all probe fixtures are intercepted; no live requests');
 }finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
