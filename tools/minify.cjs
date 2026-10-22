'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
require('./i18n.cjs');
const {minify}=require('terser'),CleanCSS=require('clean-css');
const root=path.resolve(__dirname,'..'),source=path.join(root,'web'),assets=path.join(root,'app/src/main/assets');
const sha=data=>crypto.createHash('sha256').update(data).digest('hex');
(async()=>{
 const result={};
 for(const file of ['i18n-catalog.js','i18n.js','core.js','app.js','style.css','fonts.css']){
  const input=fs.readFileSync(path.join(source,file),'utf8');let output;
  if(file.endsWith('.js')){
   const bundle=await minify({[file]:input},{ecma:2020,toplevel:false,compress:{passes:2,toplevel:false},mangle:{toplevel:false},format:{comments:false},sourceMap:{filename:file,includeSources:false}});
   output=bundle.code+'\n';fs.writeFileSync(path.join(source,'maps',file+'.map'),bundle.map+'\n');
  }else{const bundle=new CleanCSS({level:1}).minify(input);if(bundle.errors.length)throw Error(bundle.errors.join('; '));output=bundle.styles+'\n';}
  fs.writeFileSync(path.join(assets,file),output);
  result[file]={sourceBytes:Buffer.byteLength(input),bundleBytes:Buffer.byteLength(output),sourceSha256:sha(input),bundleSha256:sha(output)};
 }
 fs.writeFileSync(path.join(root,'tools/web-bundle.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
})().catch(error=>{console.error(error);process.exitCode=1;});
