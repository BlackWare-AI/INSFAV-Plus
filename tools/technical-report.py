"""Build one offline HTML report with literal source appendix and embedded exact source ZIP."""
import argparse
import base64
import hashlib
import html
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile


def inline(value):
    return re.sub(r'`([^`]+)`', r'<code>\1</code>', html.escape(value))


def markdown(value):
    lines=value.splitlines();out=[];paragraph=[];i=0
    def flush():
        if paragraph:out.append('<p>'+inline(' '.join(paragraph))+'</p>');paragraph.clear()
    while i<len(lines):
        line=lines[i]
        if line.startswith('|'):
            flush();rows=[]
            while i<len(lines) and lines[i].startswith('|'):
                cells=[c.strip() for c in lines[i].strip('|').split('|')]
                if not all(re.fullmatch(r'[-: ]+',c) for c in cells):rows.append(cells)
                i+=1
            out.append('<div class="table-wrap"><table>'+''.join('<tr>'+''.join('<'+('th' if n==0 else 'td')+'>'+inline(c)+'</'+('th' if n==0 else 'td')+'>' for c in row)+'</tr>' for n,row in enumerate(rows))+'</table></div>');continue
        match=re.match(r'^(#{1,4}) (.*)$',line)
        if match:
            flush();level=min(4,len(match[1])+1);out.append(f'<h{level}>'+inline(match[2])+f'</h{level}>')
        elif not line.strip():flush()
        else:paragraph.append(line)
        i+=1
    flush();return '\n'.join(out)


def build_html(document,source_zip,metadata):
    files=[]
    with zipfile.ZipFile(io.BytesIO(source_zip)) as archive:
        for name in sorted(archive.namelist()):
            if name.endswith('/'):continue
            path=Path(name)
            if path.is_absolute() or '..' in path.parts or path.suffix.lower() in {'.p12','.jks','.keystore','.key'} or path.name=='.env':
                raise ValueError('Private signing material or unsafe archive path is not application source')
            data=archive.read(name);digest=hashlib.sha256(data).hexdigest()
            try:text=data.decode('utf-8');text=None if '\0' in text else text
            except UnicodeDecodeError:text=None
            files.append((name,data,digest,text))
    versions=next((data.decode() for name,data,_,_ in files if name=='version.properties'),None)
    if versions is None:raise ValueError('Source version.properties is required')
    props=dict(line.split('=',1) for line in versions.splitlines() if '=' in line)
    source=metadata['sourceCommit']
    if not re.fullmatch(r'[a-f0-9]{40}',source):raise ValueError('Exact source commit required')
    safe={key:metadata.get(key) for key in ('sourceCommit','apkSha256','certificateSha256','nativeTests','browserChecks','clipboardPreviewPassed','lintErrors','lintWarnings','ciRun','ciConclusion','emulatorConclusion','nativeBridgeClipboardVerified','strictResolutionDeviceCount','physicalDeviceTested','liveInstagramTested','previousBuild10SignatureMatches','sameVersionBuildAsPrevious','debuggable','signedReleaseInstalledOnDevice','releaseUrl') if key in metadata}
    safe.update(version=props['VERSION_NAME'],build=int(props['VERSION_CODE']),sourceZipSha256=hashlib.sha256(source_zip).hexdigest(),sourceFiles=len(files))
    measurements=[]
    for key,label in [('rescanBefore','Önce — mevcut build 10'),('rescanExperiment','Toplu-task denemesi — geri alındı'),('rescanFinal','Son kaynak — yerel tam test'),('rescanDevice','Son kaynak — Android emülatörü')]:
        row=metadata.get(key)
        if row:measurements.append('<tr><td>'+html.escape(label)+'</td>'+''.join('<td>'+html.escape(str(row.get(k,'—')))+'</td>' for k in ('coldMs','warmMs','coldOpens','warmOpens','sourceWrites'))+'</tr>')
    measurement_table='<div class="table-wrap"><table><tr><th>Koşul</th><th>İlk (ms)</th><th>Sıcak (ms)</th><th>İlk açma</th><th>Sıcak açma</th><th>Kaynak yazma</th></tr>'+''.join(measurements)+'</table></div>'
    text_count=sum(text is not None for _,_,_,text in files)
    appendix=[];inventory=[]
    for number,(name,data,digest,text) in enumerate(files):
        escaped=html.escape(name);inventory.append('<tr><td><a href="#source-'+str(number)+'">'+escaped+'</a></td><td>'+str(len(data))+'</td><td class="hash">'+digest+'</td></tr>')
        if text is not None:appendix.append('<details class="source-file" id="source-'+str(number)+'" data-name="'+html.escape(name,quote=True)+'"><summary>'+escaped+' <small>'+str(len(data))+' byte · SHA-256 '+digest+'</small></summary><pre><code>'+html.escape(text)+'</code></pre></details>')
        else:appendix.append('<div class="binary-file" id="source-'+str(number)+'">'+escaped+' — '+str(len(data))+' byte; birebir gömülü kaynak ZIP içinde.</div>')
    payload=base64.b64encode(source_zip).decode('ascii')
    return '''<!doctype html><html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>INSFAV teknik rapor ve tam kaynak</title><style>
:root{color-scheme:light dark}body{font:16px/1.7 system-ui,sans-serif;max-width:1080px;margin:auto;padding:28px;color:#20252b;background:#fafbfc}h1,h2,h3{line-height:1.25;color:#111b29;margin-top:2em}h1{font-size:30px}h2{font-size:25px}h3{font-size:21px}p{overflow-wrap:anywhere}.badge{display:inline-block;background:#e9eef5;border-radius:8px;padding:5px 10px;margin:0 6px 8px 0}a{color:#1556a4}button,input{font:inherit;padding:9px 13px;border:1px solid #bec8d5;border-radius:8px;background:#edf2f7;color:#18283d;margin:6px 8px 6px 0}input{width:min(90%,500px)}.table-wrap{overflow:auto}table{border-collapse:collapse;width:100%;margin:18px 0;font-size:14px}th,td{border:1px solid #cbd3dd;padding:10px;text-align:left;vertical-align:top}th{background:#eaf0f7}pre{font:12px/1.6 ui-monospace,monospace;overflow:auto;white-space:pre;padding:16px;background:#101722;color:#e2e8f2;border-radius:8px;max-height:75vh}code{font-family:ui-monospace,monospace}details{border:1px solid #c8d1de;border-radius:8px;margin:12px 0;padding:10px}summary{cursor:pointer;font-weight:650;overflow-wrap:anywhere}summary small{display:block;font-weight:400;font-size:11px;overflow-wrap:anywhere}.binary-file{padding:9px;font-size:13px;overflow-wrap:anywhere}.hash{font:11px ui-monospace,monospace;overflow-wrap:anywhere;max-width:240px}.muted{color:#536174}.evidence{background:#eef3f9;padding:20px;border-radius:12px}#source-area{margin-top:50px}@media(prefers-color-scheme:dark){body{background:#11161c;color:#d6e0eb}h1,h2,h3{color:#f2f6fb}a{color:#8dc0ff}th,.badge,.evidence{background:#202d3d}th,td,details{border-color:#3e4d60}.muted{color:#b0bfd0}button,input{background:#23354a;color:#e4edf8;border-color:#586d86}}@media print{body{max-width:none;font-size:11pt}button,input{display:none}.source-file:not([open]){display:none}pre{max-height:none;white-space:pre-wrap}h2,h3{break-after:avoid}}
</style></head><body><h1>INSFAV — Teknik rapor ve tam kaynak kod</h1><div class="badge">Sürüm '''+html.escape(props['VERSION_NAME'])+'''</div><div class="badge">Build '''+html.escape(props['VERSION_CODE'])+''' — değişmedi</div><p class="muted">Bu HTML çevrimdışı açılır. Raporun en altında '''+str(text_count)+''' metin dosyasının tam içeriği ve tüm '''+str(len(files))+''' dosyayı içeren birebir kaynak ZIP'i bulunur.</p>
<section class="evidence"><h2>Son doğrulama kaydı</h2><p>Uygulama kaynağı: <code>'''+html.escape(source)+'''</code>. Bu alanlar ham test çıktıları ve APK doğrulamasından üretilir; fixture sonucu canlı Instagram sonucu değildir.</p><pre>'''+html.escape(json.dumps(safe,ensure_ascii=False,indent=2))+'''</pre><h3>15.000 dosya ölçümü</h3>'''+measurement_table+'''<p>Yerel koşular Robolectric ve 1 ms yapay SAF açma gecikmesi kullanır; Android emülatörü gerçek Binder ve yapay gecikmesiz ayrı koşuldur. Farklı koşullar doğrudan hız garantisi vermez. Geri alınan deney nihai koda dahil değildir.</p></section>
'''+markdown(document)+'''
<section id="source-area"><h2>Tam uygulama kaynak kodu</h2><p>Kaynak commit: <code>'''+source+'''</code>. Metinler kırpılmadan ve çalıştırılmadan gösterilir. İkili varlıklar gömülü ZIP içindedir. ZIP SHA-256: <code>'''+hashlib.sha256(source_zip).hexdigest()+'''</code>.</p><button id="download-source">Tüm kaynak ZIP'ini indir</button><button id="open-all">Tüm metin dosyalarını aç</button><button id="close-all">Dosyaları kapat</button><p><input id="source-search" type="search" placeholder="Kaynak dosya adıyla filtrele" aria-label="Kaynak dosya adıyla filtrele"></p><details><summary>Eksiksiz dosya manifesti ve SHA-256 özetleri</summary><div class="table-wrap"><table><tr><th>Dosya</th><th>Byte</th><th>SHA-256</th></tr>'''+''.join(inventory)+'''</table></div></details>
'''+''.join(appendix)+'''</section><script id="source-zip" type="application/octet-stream">'''+payload+'''</script><script>
document.getElementById('download-source').onclick=()=>{const raw=atob(document.getElementById('source-zip').textContent.trim()),parts=[];for(let at=0;at<raw.length;at+=32768){const part=raw.slice(at,at+32768);parts.push(Uint8Array.from(part,c=>c.charCodeAt(0)));}const url=URL.createObjectURL(new Blob(parts,{type:'application/zip'})),link=document.createElement('a');link.href=url;link.download='INSFAV-5.0.0-build10-Tam-Kaynak.zip';link.click();setTimeout(()=>URL.revokeObjectURL(url),10000);};
document.getElementById('open-all').onclick=()=>document.querySelectorAll('.source-file').forEach(e=>e.open=true);document.getElementById('close-all').onclick=()=>document.querySelectorAll('.source-file').forEach(e=>e.open=false);document.getElementById('source-search').oninput=e=>{const query=e.target.value.toLowerCase();document.querySelectorAll('.source-file').forEach(file=>file.hidden=!file.dataset.name.toLowerCase().includes(query));};
</script></body></html>'''


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-ref',required=True);parser.add_argument('--document',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();source=subprocess.check_output(['git','rev-parse',args.source_ref],text=True).strip()
    archive=subprocess.check_output(['git','archive','--format=zip',source]);metadata=json.loads(args.evidence.read_text());metadata['sourceCommit']=source
    args.output.write_text(build_html(args.document.read_text(),archive,metadata),encoding='utf-8')
    print(json.dumps(dict(report=str(args.output),bytes=args.output.stat().st_size,sourceCommit=source,sha256=hashlib.sha256(args.output.read_bytes()).hexdigest())))


if __name__=='__main__':main()
