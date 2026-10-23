'use strict';
const fs=require('node:fs'),crypto=require('node:crypto'),path=require('node:path');
const root=path.resolve(__dirname,'..'),catalog={};
for(const line of fs.readFileSync(path.join(root,'web/i18n/catalog.tsv'),'utf8').trimEnd().split('\n')){const [en,tr]=line.split('\t');if(!en||!tr)throw Error('Incomplete translation: '+en);if(catalog[en])throw Error('Duplicate translation: '+en);if((en.match(/\{\w+\}/g)||[]).sort().join()!==(tr.match(/\{\w+\}/g)||[]).sort().join())throw Error('Translation placeholders differ');catalog[en]=tr;}
const esc=s=>s.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;').replaceAll("'","\\'");
for(const [locale,translated]of [['values',false],['values-tr',true]]){const dir=path.join(root,'app/src/main/res',locale);fs.mkdirSync(dir,{recursive:true});fs.writeFileSync(path.join(dir,'strings.xml'),'<!-- Generated from web/i18n/catalog.tsv by tools/i18n.cjs -->\n<resources>\n'+Object.entries(catalog).map(([en,tr])=>`    <string name="ui_${crypto.createHash('sha256').update(en).digest('hex').slice(0,12)}" formatted="false">${esc(translated?tr:en)}</string>`).join('\n')+'\n</resources>\n');}
const values=Object.keys(catalog).map(en=>`    <item>@string/ui_${crypto.createHash('sha256').update(en).digest('hex').slice(0,12)}</item>`).join('\n');
const arrays='<resources>\n  <string-array name="ui_sources" translatable="false">\n'+Object.keys(catalog).map(en=>'    <item>'+esc(en)+'</item>').join('\n')+'\n  </string-array>\n  <string-array name="ui_values">\n'+values+'\n  </string-array>\n</resources>\n';
fs.writeFileSync(path.join(root,'app/src/main/res/values/ui_catalog.xml'),arrays);
fs.writeFileSync(path.join(root,'web/i18n-catalog.js'),'// Generated from web/i18n/catalog.tsv\nwindow.InsfavCatalog='+JSON.stringify(catalog)+';\n');
