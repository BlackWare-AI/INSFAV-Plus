/* Offline first-party session responses; never contacts a real account. */
const fs=require('node:fs'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,args:['--no-sandbox']});
 try {
  const context=await browser.newContext();
  await context.addCookies([{name:'sessionid',value:'OFFLINE_ONLY',domain:'.instagram.com',path:'/',secure:true,httpOnly:true}]);
  const page=await context.newPage();let response={},status=200,type='application/json',requests=0,cookieSent=false,html='<body>Feed without legacy viewer JSON</body>';
  await page.route('**/*',async route=>{
   const url=new URL(route.request().url());
   assert.equal(url.origin,'https://www.instagram.com');
   if(url.pathname==='/api/v1/accounts/current_user/'){
    requests++;cookieSent=((await route.request().allHeaders()).cookie||'').includes('sessionid=OFFLINE_ONLY');
    return route.fulfill({status,contentType:type,body:typeof response==='string'?response:JSON.stringify(response)});
   }
   return route.fulfill({contentType:'text/html',body:html});
  });
  let checks=0;
  for(const file of ['profile-probe.js','profile-probe-hd.js']){
   const source=fs.readFileSync('app/src/main/assets/'+file,'utf8');
   for(const test of [
    {response:{status:'ok',user:{pk:'123',username:'fixture'}},authenticated:true},
    {response:{status:'ok',user:{pk:'123',username:'fixture'}},account:'',authenticated:true},
    {response:{status:'ok',user:{pk:'999',username:'other'}},authenticated:false},
    {response:{status:'fail'},authenticated:false},
    {response:{status:'ok',user:{pk:'0',username:'anonymous'}},account:'0',authenticated:false},
    {status:401,response:{},authenticated:false,wall:'COOKIE_INVALID_EXPIRED'},
    {status:403,response:{challenge:{url:'/challenge/'}},authenticated:false,wall:'CHALLENGE_REQUIRED'},
    {status:403,response:{status:'fail'},authenticated:false,wall:'ACCESS_BLOCKED'},
    {status:429,response:{status:'fail'},authenticated:false,wall:'RATE_LIMITED'},
    {type:'text/html',response:'<html>Unknown response</html>',authenticated:false}
   ]){
    html='<body>Feed without legacy viewer JSON</body>';response=test.response;status=test.status||200;type=test.type||'application/json';requests=0;cookieSent=false;
    await page.goto('https://www.instagram.com/');
    const probe=()=>page.evaluate(({source,id})=>JSON.parse(eval('('+source+')')('',id,false)),{source,id:test.account===undefined?'123':test.account});
    let result=await probe();
    assert.notEqual(result.authenticated,true,'a pending request cannot prove authentication');
    for(let i=0;i<100;i++){
     if(await page.evaluate(()=>window.__insfavSessionCheck?.complete))break;
     await page.waitForTimeout(10);
    }
    result=await probe();
    assert.equal(!!result.authenticated,test.authenticated);
    assert.equal(result.wall,test.wall);
    await probe();assert.equal(requests,1,'polling must reuse one per-document check');
    assert.equal(cookieSent,true,'first-party browser request must use its HttpOnly cookie');checks++;
   }
   const inbox='<a href="/direct/inbox/">Inbox</a>', avatar='<nav><a href="/fixture/"><img width="32" height="32" alt="Profile"></a></nav>';
   for(const test of [
    {html:inbox+avatar,authenticated:true},
    {html:inbox,authenticated:false},
    {html:inbox+avatar.replace('<nav>','<nav style="display:none">'),authenticated:false},
    {html:inbox.replace('/direct/inbox/','https://other.invalid/direct/inbox/')+avatar,authenticated:false},
    {html:inbox+avatar+'<form id="loginForm">Sign in</form>',authenticated:false,wall:'COOKIE_INVALID_EXPIRED'}
   ]){
    html=test.html;response={status:'fail'};status=200;type='application/json';requests=0;
    await page.goto('https://www.instagram.com/');
    const probe=()=>page.evaluate(source=>JSON.parse(eval('('+source+')')('','123',false)),source);
    let result=await probe();
    if(!test.authenticated&&!test.wall){
     for(let i=0;i<100&&!await page.evaluate(()=>window.__insfavSessionCheck?.complete);i++)await page.waitForTimeout(10);
     result=await probe();
    }
    assert.equal(!!result.authenticated,test.authenticated);assert.equal(result.wall,test.wall);
    if(test.authenticated)assert.equal(requests,0,'proven page evidence needs no extra server request');checks++;
   }
  }
  console.log(checks+' offline WebView session proof checks passed');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
