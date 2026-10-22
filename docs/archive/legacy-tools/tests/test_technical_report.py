import base64
import importlib.util
import io
from pathlib import Path
import re
import unittest
import zipfile
spec=importlib.util.spec_from_file_location('technical_report',Path(__file__).parents[1]/'technical-report.py')
report=importlib.util.module_from_spec(spec);spec.loader.exec_module(report)

class TechnicalReportTest(unittest.TestCase):
 def archive(self,extra=None):
  data={'version.properties':b'VERSION_NAME=5.0.0\nVERSION_CODE=10\n','app/Main.java':b"class Main { String text=\"</pre><script>alert('fixture')</script>\"; }",'app/icon.bin':b'\x00\xff\x01'}
  data.update(extra or {});out=io.BytesIO()
  with zipfile.ZipFile(out,'w') as z:
   for path,value in data.items():z.writestr(path,value)
  return out.getvalue(),data
 def test_contains_untruncated_escaped_source_and_exact_embedded_archive(self):
  source,files=self.archive();html=report.build_html('# Report\n\n## Details\nWorks.',source,dict(sourceCommit='a'*40,nativeTests=dict(tests=222,failures=0)))
  self.assertIn('&lt;/pre&gt;&lt;script&gt;',html);self.assertNotIn("</pre><script>alert('fixture')",html)
  self.assertIn('Build 10',html);self.assertIn('app/Main.java',html);self.assertIn('app/icon.bin',html)
  encoded=re.search(r'id="source-zip" type="application/octet-stream">([^<]+)',html).group(1)
  self.assertEqual(source,base64.b64decode(encoded))
  with zipfile.ZipFile(io.BytesIO(base64.b64decode(encoded))) as z:
   self.assertEqual(files,{name:z.read(name) for name in z.namelist()})
 def test_refuses_private_signing_material_and_parent_paths(self):
  for name in ('release.p12','../escape.java','.env','signer.keystore'):
   source,_=self.archive({name:b'fixture'})
   with self.assertRaises(ValueError):report.build_html('Report',source,dict(sourceCommit='a'*40))
 def test_summary_uses_source_version_and_does_not_copy_arbitrary_private_fields(self):
  source,_=self.archive();html=report.build_html('Report',source,dict(sourceCommit='b'*40,version='9.9.9',username='PRIVATE_ID',cookie='PRIVATE_SECRET'))
  self.assertIn('Sürüm 5.0.0',html);self.assertNotIn('9.9.9',html);self.assertNotIn('PRIVATE_ID',html);self.assertNotIn('PRIVATE_SECRET',html)
 def test_markdown_escapes_code_and_renders_technical_tables(self):
  value=report.markdown('## Title\n\n| Name | Value |\n|---|---|\n| `<script>` | 256 |')
  self.assertIn('<table>',value);self.assertIn('&lt;script&gt;',value);self.assertNotIn('<script>',value)

if __name__=='__main__':unittest.main()
