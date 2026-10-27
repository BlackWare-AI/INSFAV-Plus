'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
require('./i18n.cjs');
const {minify}=require('terser'),CleanCSS=require('clean-css');
const root=path.resolve(__dirname,'..'),source=path.join(root,'web'),assets=path.join(root,'app/src/main/assets');
const sha=data=>crypto.createHash('sha256').update(data).digest('hex');
(async()=>{
 const result={};
 const logo=fs.readFileSync(path.join(source,'static/logo.jpg'));
 fs.writeFileSync(path.join(root,'app/src/main/res/drawable-nodpi/insfav_plus_logo.jpg'),logo);
 const oldLogo=path.join(assets,'logo.webp');if(fs.existsSync(oldLogo))fs.unlinkSync(oldLogo);
 for(const file of ['i18n-catalog.js','i18n.js','core.js','list-scroll.js','app.js','profile-probe.js','profile-probe-hd.js','style.css','fonts.css']){
  const input=fs.readFileSync(path.join(source,file),'utf8');let output;
  const included=file==='app.js'?fs.readFileSync(path.join(source,'avatar-loader.js'),'utf8'):null;
  const inputs=included?{'avatar-loader.js':included,[file]:input}:{[file]:input};
  if(file.endsWith('.js')){
   const probe=file.startsWith('profile-probe');
   const bundle=await minify(probe?{[file]:'('+input+')'}:inputs,{ecma:2020,toplevel:false,compress:probe?false:{passes:2,toplevel:false},mangle:{toplevel:false},format:{comments:false},sourceMap:{filename:file,includeSources:false}});
   output=(probe?bundle.code.replace(/;$/,''):bundle.code)+'\n';fs.writeFileSync(path.join(source,'maps',file+'.map'),bundle.map+'\n');
  }else{const bundle=new CleanCSS({level:1}).minify(input);if(bundle.errors.length)throw Error(bundle.errors.join('; '));output=bundle.styles+'\n';}
  fs.writeFileSync(path.join(assets,file),output);
  result[file]={sourceBytes:Buffer.byteLength(input),bundleBytes:Buffer.byteLength(output),sourceSha256:sha(input),bundleSha256:sha(output)};
  if(included){result[file].sourceBytes+=Buffer.byteLength(included);result[file].includedSources={'avatar-loader.js':{sourceSha256:sha(included)}};}
 }
 for(const file of ['index.html',...fs.readdirSync(path.join(source,'static'))]){const input=fs.readFileSync(path.join(source,file==='index.html'?file:'static/'+file));fs.writeFileSync(path.join(assets,file),input);result[file]={sourceBytes:input.length,bundleBytes:input.length,sourceSha256:sha(input),bundleSha256:sha(input)};}
 fs.writeFileSync(path.join(root,'tools/web-bundle.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
})().catch(error=>{console.error(error);process.exitCode=1;});
