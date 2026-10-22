import base64
import html
import importlib.util
import io
from pathlib import Path
import unittest
import zipfile
spec=importlib.util.spec_from_file_location('polish_build10',Path(__file__).parents[1]/'polish_build10.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

class PolishGateTest(unittest.TestCase):
 def record(self):
  return dict(sourceCommit=gate.SOURCE,version='5.0.0',build=10,apkSha256=gate.APK_SHA256,certificateSha256=gate.CERTIFICATE,
              nativeTests=dict(tests=222,failures=0,errors=0,skipped=0),browserChecks=125,clipboardPreviewPassed=True,
              ciRun=gate.CI_RUN,ciConclusion='success',emulatorConclusion='success',nativeBridgeClipboardVerified=True,
              strictResolutionDeviceCount=7500,physicalDeviceTested=False,liveInstagramTested=False,
              previousBuild10SignatureMatches=True,sameVersionBuildAsPrevious=True,newSigningKey=False,
              debuggable=False,signedReleaseInstalledOnDevice=False,lintErrors=0)
 def test_accepts_only_same_version_build_and_verified_evidence(self):gate.validate_record(self.record())
 def test_rejects_missing_device_clipboard_source_version_signer_or_false_live_claims(self):
  for change in (dict(build=11),dict(version='5.0.1'),dict(sourceCommit='wrong'),dict(certificateSha256='wrong'),dict(nativeBridgeClipboardVerified=False),dict(strictResolutionDeviceCount=0),dict(liveInstagramTested=True),dict(ciConclusion='failure')):
   with self.assertRaises(ValueError):gate.validate_record({**self.record(),**change})
 def test_cannot_modify_published_or_unrelated_draft(self):
  draft=dict(id=gate.DRAFT_ID,draft=True,tag_name=gate.TAG,target_commitish=gate.SOURCE,assets=[]);gate.validate_draft(draft,{'app.apk'})
  for change in (dict(id=0),dict(draft=False),dict(tag_name='previous'),dict(target_commitish='other'),dict(assets=[dict(name='extra.apk')])):
   with self.assertRaises(ValueError):gate.validate_draft({**draft,**change},{'app.apk'})
 def test_full_source_appendix_and_embedded_zip_are_verified(self):
  out=io.BytesIO()
  with zipfile.ZipFile(out,'w') as z:z.writestr('Main.java','class Main { String html="<example>"; }')
  archive=out.getvalue();report=html.escape('class Main { String html="<example>"; }')+'<script id="source-zip" type="application/octet-stream">'+base64.b64encode(archive).decode()+'</script>'
  gate.validate_report(report,archive,archive)
  with self.assertRaises(ValueError):gate.validate_report(report.replace('class Main','truncated'),archive,archive)
  other=io.BytesIO()
  with zipfile.ZipFile(other,'w') as z:z.writestr('Other.java','other source')
  with self.assertRaises(ValueError):gate.validate_report(report,archive,other.getvalue())

 def test_source_zip_preserves_crlf_while_html_rendering_normalizes_line_endings(self):
  out=io.BytesIO()
  with zipfile.ZipFile(out,'w') as z:z.writestr('gradlew.bat',b'first\r\nsecond\r\n')
  data=out.getvalue();report='first\nsecond\n<script id="source-zip" type="application/octet-stream">'+base64.b64encode(data).decode()+'</script>'
  gate.validate_report(report,data,data)

if __name__=='__main__':unittest.main()
