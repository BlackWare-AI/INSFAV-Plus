(function(root){'use strict';
const catalog=root.InsfavCatalog||{},system=()=>/^tr(?:-|$)/i.test(navigator.language)?'tr':'en';let choice='system',language=system();
function text(source,args={}){const translated=language==='tr'?catalog[source]||source:source;return String(translated).replace(/\{(\w+)\}/g,(match,key)=>Object.hasOwn(args,key)?String(args[key]):match);}
function bind(node,source){node.dataset.i18n=source;const value=text(source);if(node.textContent!==value)node.textContent=value;}
function apply(){document.documentElement.lang=language;for(const node of document.querySelectorAll('[data-i18n]'))bind(node,node.dataset.i18n);for(const attr of ['aria-label','placeholder','title'])for(const node of document.querySelectorAll('[data-i18n-'+attr+']'))node.setAttribute(attr,text(node.getAttribute('data-i18n-'+attr)));}
const api={text,bind,apply,status:value=>String(value).split(' · ').map(part=>text(part)).join(' · '),locale:()=>language==='tr'?'tr-TR':'en-GB',choice:()=>choice,choose(value){choice=['en','tr','system'].includes(value)?value:'system';language=choice==='system'?system():choice;apply();api.onChange?.();}};root.InsfavI18n=api;apply();
})(window);
