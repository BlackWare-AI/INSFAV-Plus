/* Offline DOM fixtures; every request is intercepted. This is not a live Instagram test. */
const fs = require('node:fs'), assert = require('node:assert/strict');
const {chromium} = require('playwright');
(async () => {
 const browser = await chromium.launch({executablePath: process.env.CHROME_BIN, args: ['--no-sandbox', '--disable-gpu']});
 try {
  const page = await browser.newPage();
  const source = fs.readFileSync('app/src/main/assets/profile-probe.js', 'utf8');
  const origin = 'https://www.instagram.com/example/', image = 'https://fixture.cdninstagram.com/avatar.svg';
  const canonical = '<link rel="canonical" href="'+origin+'">';
  const routed = '<script type="application/json">{"rootView":{"resource":{"__dr":"PolarisErrorRoot.react"}}}</script>';
  const avatar = '<main><header><img alt="example profile picture" src="'+image+'"></header></main>';
  let body = '', status = 200, escaped = 0, checks = 0;
  await page.route('**/*', route => {
   if(route.request().url() === image) return route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128"><rect width="128" height="128"/></svg>'});
   if(route.request().url() === origin) return route.fulfill({status, contentType:'text/html; charset=utf-8', body});
   escaped++; return route.abort();
  });
  const probe = async () => JSON.parse(await page.evaluate(s => eval('(' + s + ')')('example', '123', false), source));
  const load = async (html, http = 200) => { body=html; status=http; await page.goto(origin); checks++; return probe(); };
  assert.equal((await load('<body>Sorry, this page isn’t available.</body>')).reason,'TEXT_MATCH');
  assert.equal((await load(routed)).reason,'ROUTED_ERROR');
  assert.equal((await load('<form id="loginForm"><input></form>')).reason,'COOKIE_OR_LOGIN_GATE');
  assert.equal((await load('<link rel="canonical" href="https://www.instagram.com/other/">')).reason,'CANONICAL_MISMATCH');
  assert.equal((await load(canonical)).reason,'NO_CANDIDATE');
  for(const marker of [routed, '<p>Sorry, this page isn’t available.</p>']) {
   const data=await load(canonical+marker+avatar); assert.equal(data.url,image); assert.equal(data.wall,undefined); assert.equal(data.candidateFound,true);
  }
  for(const http of [200,404]) {
   const data=await load(routed+'<p>Sorry, this page isn’t available.</p>',http);
   assert.equal(data.wall,'PROFILE_PAGE_UNAVAILABLE'); assert.equal(data.unavailableText,true); assert.equal(data.searchCompleted,true); assert.equal(data.candidateFound,false);
  }
  let data=await load(canonical+'<main id="profile"></main><script>setTimeout(()=>document.querySelector("main").innerHTML=\'<header><img alt="example profile picture" src="'+image+'"></header>\',200)</script>');
  assert.equal(data.url,undefined); await page.waitForTimeout(300); data=await probe(); assert.equal(data.url,image);
  data=await load(avatar); assert.equal(data.url,image); // Existing username-bound header behavior is retained.
  data=await load('<main></main>'); assert.equal(data.url,undefined); // Location alone is never an image candidate.
  data=await load('<script type="application/json">{"username":"example","id":"9","profile_pic_url":"'+image+'"}</script>');
  for(let i=0;i<20&&!data.url;i++){await page.waitForTimeout(25);data=await probe();}
  assert.equal(data.url,image); assert.equal(data.identityConfirmed,true); // Existing structured identity is retained.
  data=await load('<link rel="canonical" href="https://www.instagram.com/other/">'+avatar);assert.equal(data.url,undefined);assert.equal(data.reason,'CANONICAL_MISMATCH');
  for(const asset of ['profile-probe.js','profile-probe-hd.js']) {
   const script=fs.readFileSync('app/src/main/assets/'+asset,'utf8');
   const run=async()=>JSON.parse(await page.evaluate(s=>eval('('+s+')')('example','123',false),script));
   for(const phrase of ["Sorry, this page isn't available",'bu sayfa kullanılamıyor','bu sayfaya ulaşılamıyor','sayfa kaldırılmış','bağlantı bozuk olabilir']) {
    // A contradictory canonical and incomplete DOM must not delay a text-only page.
    await load('<link rel="canonical" href="https://www.instagram.com/other/"><p>'+phrase+'</p>');
    await page.evaluate(()=>Object.defineProperty(document,'readyState',{configurable:true,get:()=> 'loading'}));
    const data=await run();assert.equal(data.wall,'PROFILE_PAGE_UNAVAILABLE',asset+' '+phrase);assert.equal(data.reason,'TEXT_MATCH');assert.equal(data.unavailableText,true);assert.equal(data.pageReady,true);assert.equal(data.searchCompleted,true);assert.equal(data.candidateFound,false);
   }
   await load(canonical+"<p>Sorry, this page isn't available</p>"+routed+avatar);
   let data=await run();assert.equal(data.wall,undefined,asset);assert.equal(data.url,image);assert.equal(data.candidateFound,true);
   await load(canonical+"<p>bu sayfa kullanılamıyor</p><script type='application/json'>{\"username\":\"example\",\"id\":\"9\",\"profile_pic_url\":\""+image+"\"}</script>");
   data=await run();assert.notEqual(data.wall,'PROFILE_PAGE_UNAVAILABLE');assert.equal(data.candidateFound,true);
   for(const gate of ["<form id='loginForm'><input></form>","<script type='application/json'>{\"login_required\":true}</script>","<form action='/challenge/'><input></form>"]) {
    await load(gate+'<p>bu sayfa kullanılamıyor</p>');data=await run();assert.ok(['COOKIE_INVALID_EXPIRED','CHALLENGE_REQUIRED'].includes(data.wall),asset);
   }
  }
  data=await load("<p>please wait a few minutes before you try again</p><p>bu sayfa kullanılamıyor</p>");assert.equal(data.wall,'RATE_LIMITED');
  assert.equal(escaped,0);
  console.log('PASS '+checks+' offline DOM cases: routed/text errors with valid avatars, missing profiles, login, delayed profile, 200/404 errors, canonical identity; all requests intercepted');
 } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
