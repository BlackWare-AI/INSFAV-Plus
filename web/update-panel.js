/* App-specific release updates. Release notes are rendered as text, never executable HTML. */
(()=>{'use strict';
 let data={check:false,download:false,install:false,backup:false},pending=false,poll=0;
 const tr=()=>document.documentElement.lang==='tr',label=(en,turkish)=>tr()?turkish:en;
 const section=document.createElement('section');section.id='update-settings';
 const version=document.getElementById('version-info');version.before(section);
 const baseDialog=showDialog;showDialog=function(title,body,actions){document.getElementById('dialog').classList.toggle('update-dialog',!!body?.classList&&(body.classList.contains('update-preview')||body.classList.contains('session-engine-options')||body.classList.contains('update-history')));baseDialog(title,body,actions);};
 const previousEvent=window.nativeEvent;
 window.nativeEvent=(event,value)=>{
  if(event==='updates-state'){data=value;pending=false;render();clearTimeout(poll);if(['checking','downloading','backing-up'].includes(data.status)&&native)poll=setTimeout(()=>request('updates-status'),1500);return;}
  if(event==='updates-review'){preview(data.release);return;}
  previousEvent(event,value);
  if(event==='bootstrap'&&native)request('updates-status');
 };
 function button(text,run,id){const b=document.createElement('button');b.type='button';b.textContent=text;if(['updates-check','updates-review'].includes(id))b.disabled=['checking','downloading','backing-up'].includes(data.status);if(id)b.id=id;b.onclick=run;return b;}
 function note(text){const p=document.createElement('p');p.className='muted';p.textContent=text;return p;}
 function render(){
  section.replaceChildren();const h=document.createElement('h3');h.textContent=label('Updates & recovery','Güncellemeler ve yedekleme');section.append(h);
  const card=document.createElement('div');card.className='update-card';
  const badge=document.createElement('span');badge.className='update-badge';badge.textContent=label('GITHUB RELEASES','GITHUB SÜRÜMLERİ');card.append(badge);
  const status={checking:label('Checking releases…','Sürümler kontrol ediliyor…'),available:label('A new update is available','Yeni güncelleme var'),current:label('You are up to date','Güncel sürümdesiniz'),downloading:label('Downloading the signed APK…','İmzalı APK indiriliyor…'),ready:label('Update ready · Review and install','Güncelleme hazır · İncele ve yükle'),'backing-up':label('Saving and verifying your backup…','Yedek kaydediliyor ve doğrulanıyor…'),'backup-failed':label('Backup failed · Installation blocked','Yedek başarısız · Kurulum başlatılmadı'),'check-failed':label('Could not check · Try again','Kontrol edilemedi · Tekrar deneyin'),'download-failed':label('Download/verification failed · Try again','İndirme/doğrulama başarısız · Tekrar deneyin')};
  const line=note(status[data.status]||label('Automatic updates are off until you choose them.','Otomatik güncelleme, siz izin verene kadar kapalı.'));line.setAttribute('role','status');card.append(line);
  if(data.available&&data.release){const title=document.createElement('strong');title.textContent=data.release.title||data.release.tag;card.append(title);}
  card.append(button(label('Check for updates','Güncellemeleri kontrol et'),()=>{pending=true;data.status='checking';render();request('updates-check');if(native)poll=setTimeout(()=>request('updates-status'),500);},'updates-check'));
  if(data.available&&data.release)card.append(button(label('Review update','Güncellemeyi incele'),()=>preview(data.release),'updates-review'));
  if(data.lastCheck)card.append(note(label('Last check: ','Son kontrol: ')+new Date(data.lastCheck).toLocaleString()));section.append(card);
  const options=document.createElement('div');options.className='update-card update-options';
  for(const [key,en,turkish]of [['check','Check automatically (about every 12 hours)','Otomatik kontrol et (yaklaşık 12 saatte bir)'],['download','Always allow automatic APK downloads','Otomatik APK indirmesine kalıcı izin ver'],['install','Automatically open the installer after review','İncelemeden sonra kurulum ekranını otomatik aç'],['backup','Back up all app data before installation','Kurulumdan önce tüm uygulama verisini yedekle']]){
   const row=document.createElement('label'),input=document.createElement('input'),span=document.createElement('span');row.className='fetcher-toggle';input.type='checkbox';input.id='updates-'+key;input.checked=!!data[key];input.disabled=pending;span.textContent=label(en,turkish);row.append(span,input);input.onchange=()=>{data[key]=input.checked;if(key==='check'&&!input.checked)data.download=data.install=false;if(key==='download'&&!input.checked)data.install=false;if(key==='download'&&input.checked)data.check=true;if(key==='install'&&input.checked)data.check=data.download=true;pending=true;request('updates-options',{check:data.check,download:data.download,install:data.install,backup:data.backup});render();};options.append(row);
  }
  options.append(note(label('Your choices are saved until you turn them off. Android may delay checks and always controls installation permission and confirmation. Each release is reviewed before installation.','Seçimleriniz siz kapatana kadar korunur. Android kontrolleri geciktirebilir; kurulum izni ve sistem onayı Android tarafından yönetilir. Her sürüm kurulumdan önce incelenir.')));
  options.append(button(label('Choose backup folder','Yedek klasörünü seç'),()=>request('updates-folder'),'updates-folder'));
  options.append(note(data.folder||label('No backup folder selected','Yedek klasörü seçilmedi')));
  options.append(note(label('Encrypted backup: favorites, settings, cookies, indexes, queue, operation history and logs. A visible 4-digit PIN is requested just before installation. External thumbnail files and browser site storage are not copied. Failed backup stops installation.','Şifreli yedek: favoriler, ayarlar, çerezler, indeksler, kuyruk, işlem geçmişi ve loglar. Kurulum öncesinde görünür 4 haneli PIN istenir. Harici thumbnail dosyaları ve tarayıcı site depolaması kopyalanmaz. Yedek başarısızsa kurulum durur.')));section.append(options);
  const actions=document.createElement('div');actions.className='action-list';actions.append(button(label('Release notes & fixes','Yenilikler ve düzeltmeler'),history,'updates-history'));section.append(actions);
  const other=data.repo?.endsWith('-Plus')?'INSFAV':'INSFAV Plus';section.append(note(label('Also available: ','Diğer uygulamamız: ')+other+label(' · Separate app and Instagram session.',' · Ayrı uygulama ve Instagram oturumu.')));
  const a=document.createElement('a');a.className='update-repo';a.href='https://github.com/BlackWare-AI/'+(other==='INSFAV'?'INSFAV':'INSFAV-Plus');a.textContent='GitHub · '+other;a.onclick=e=>{if(native){e.preventDefault();request('updates-repository',{other:true});}};section.append(a);
 }
 function preview(release,readOnly=false){
  if(!release){message(label('Check for updates to load release notes.','Yayın notlarını almak için güncellemeleri kontrol edin.'));return;}
  const body=document.createElement('div');body.className='update-preview';const tag=document.createElement('span');tag.className='update-badge';tag.textContent=release.tag;const title=document.createElement('h3');title.textContent=release.title||release.tag;
  body.append(tag,title,note(label('Review what changes before you update. Your favorites, session and files are kept by an in-place update with the same signature.','Güncellemeden önce değişiklikleri inceleyin. Aynı imzayla yerinde güncellemede favorileriniz, oturumunuz ve dosyalarınız korunur.')));
  const notes=document.createElement('div');notes.className='release-notes';notes.textContent=release.notes||label('No release notes supplied.','Bu sürüm için açıklama sağlanmadı.');body.append(notes);
  const link=document.createElement('a');link.href=release.url;link.textContent=label('Open this release on GitHub','Bu sürümü GitHub’da aç');link.onclick=e=>{if(native){e.preventDefault();request('updates-release-page',{id:release.id});}};body.append(link);
  const actions=[];if(!readOnly&&data.available&&data.release?.id===release.id)actions.push({label:label(data.ready?(data.backup?'Back up & install':'Install update'):'Download & continue',data.ready?(data.backup?'Yedekle ve yükle':'Güncellemeyi yükle'):'İndir ve devam et'),run:()=>{data.status=data.ready?'ready':'downloading';render();request('updates-install',{id:release.id});if(native)poll=setTimeout(()=>request('updates-status'),500);}});actions.push({label:label('Close','Kapat')});showDialog(label('What’s new','Güncellemede neler var?'),body,actions);
 }
 function history(){const releases=data.history||[];if(!releases.length){preview(data.release,true);return;}const body=document.createElement('div');body.className='action-list update-history';for(const release of releases)body.append(button(release.title||release.tag,()=>closeDialog(()=>preview(release,true))));showDialog(label('Release notes & fixes','Yenilikler ve düzeltmeler'),body,[{label:label('Close','Kapat')}]);}
 new MutationObserver(render).observe(document.documentElement,{attributes:true,attributeFilter:['lang']});render();if(native)request('updates-status');
 document.addEventListener('visibilitychange',()=>{if(!document.hidden&&native)request('updates-status');});
 // Replace the platform select with an AMOLED choice dialog at the existing control location.
 const select=document.getElementById('instagram-engine'),trigger=button('',()=>{
  const body=document.createElement('div');body.className='action-list session-engine-options';
  for(const [value,en,turkish]of [['legacy','Legacy · Persistent WebView','Legacy · Kalıcı WebView'],['netscape','YTDLnis-style · Netscape / WebView','YTDLnis tarzı · Netscape / WebView']]){
   const b=button(label(en,turkish),()=>closeDialog(()=>{select.value=value;select.onchange();syncEngine();}));b.setAttribute('role','radio');b.setAttribute('aria-checked',String(select.value===value));body.append(b);
  }body.setAttribute('role','radiogroup');showDialog(label('WebView download method','WebView indirme yöntemi'),body,[{label:label('Close','Kapat')}]);
 },'instagram-engine-choice');
 trigger.className='engine-choice-trigger';trigger.setAttribute('aria-haspopup','dialog');select.hidden=true;select.after(trigger);select.parentElement.htmlFor='instagram-engine-choice';
 function syncEngine(){trigger.textContent=select.value==='netscape'?'YTDLnis · Netscape / WebView':'Legacy · WebView';}
 const engineEvent=window.nativeEvent;window.nativeEvent=(event,value)=>{engineEvent(event,value);if(event==='fetcher-status')syncEngine();};select.addEventListener('change',syncEngine);syncEngine();
})();
