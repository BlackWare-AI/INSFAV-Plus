"""Same-version/build update: pin reviewed source, tests, existing signer and full-source report."""
import base64
import hashlib
import html
import io
import re
import zipfile

SOURCE='b066f5a9af27bf711d084ae1e3ffb076c8b04ced'
APK_SHA256='6c81c5a82b3e2f9fa0f6b156070d585f53d2c6a4759d276ce741ea61d85cb27a'
CERTIFICATE='705e090084a4e50c47a8eb122d1605963276e5bb76f873d6f185b965c01e8641'
PREVIOUS_APK='1918ee03378597605f3a00c6132ac53e5478d79f37be615a3b2ac61b3b2fbf5d'
TAG='v5.0.0-build.10-ui'
CI_RUN=39816872755
DRAFT_ID=420985124


def validate_record(record):
    expected=dict(sourceCommit=SOURCE,version='5.0.0',build=10,apkSha256=APK_SHA256,
                  certificateSha256=CERTIFICATE,nativeTests=dict(tests=222,failures=0,errors=0,skipped=0),
                  browserChecks=125,clipboardPreviewPassed=True,ciRun=CI_RUN,ciConclusion='success',
                  emulatorConclusion='success',nativeBridgeClipboardVerified=True,strictResolutionDeviceCount=7500,
                  physicalDeviceTested=False,liveInstagramTested=False,previousBuild10SignatureMatches=True,
                  sameVersionBuildAsPrevious=True,newSigningKey=False,debuggable=False,signedReleaseInstalledOnDevice=False,lintErrors=0)
    if any(key not in record or record[key]!=value or type(record[key]) is not type(value) for key,value in expected.items()):
        raise ValueError('Artifact identity, unchanged version/build, signer or test evidence is not verified')


def validate_draft(record,names):
    if (record.get('id')!=DRAFT_ID or record.get('draft') is not True or record.get('tag_name')!=TAG or record.get('target_commitish')!=SOURCE
            or any(asset['name'] not in names for asset in record.get('assets',[]))):
        raise ValueError('Refusing an unrelated or already public release')


def files(archive):
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        return {name:source.read(name) for name in source.namelist() if not name.endswith('/')}


def validate_report(report,source_archive,expected_archive):
    match=re.search(r'id="source-zip" type="application/octet-stream">([^<]+)',report)
    if not match or base64.b64decode(match[1],validate=True)!=source_archive or files(source_archive)!=files(expected_archive):
        raise ValueError('Report archive does not contain the exact reviewed source tree')
    normalized=report.replace('\r\n','\n').replace('\r','\n')
    for name,data in files(source_archive).items():
        try:text=data.decode('utf-8')
        except UnicodeDecodeError:continue
        if '\0' not in text and html.escape(text.replace('\r\n','\n').replace('\r','\n')) not in normalized:
            raise ValueError('A text source file is missing or truncated in the report appendix')
