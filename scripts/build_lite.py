"""Build the isolated API14 proof of concept; does not alter full-edition outputs."""
from pathlib import Path
import os,sys,hashlib,json,zipfile,urllib.request
from build_android import ROOT,JDK,SDK,ENV,run,exe
SRC=ROOT/'lite/src/main';BUILD=ROOT/'android/build/lite';DIST=ROOT/'dist/lite'
API14=Path(os.environ.get('MARCOMOVIE_API14_JAR',str(ROOT/'tools/android-14/android.jar')))

def build():
 candidate='--candidate' in sys.argv
 global BUILD,DIST
 if candidate:BUILD=ROOT/'android/build/lite-candidate';DIST=ROOT/'verification/private/lite-candidate'
 for d in (BUILD/'classes',BUILD/'dex',BUILD/'generated',DIST):d.mkdir(parents=True,exist_ok=True)
 for folder in ('classes','dex','generated'):
  for p in (BUILD/folder).rglob('*'):
   if p.is_file() and p.suffix in ('.class','.dex','.java'):
    assert p.resolve().is_relative_to(BUILD.resolve());p.unlink()
 dep=ROOT/'tools/java-dependencies/core-3.2.1.jar'
 if not dep.exists():dep.write_bytes(urllib.request.urlopen('https://repo.maven.apache.org/maven2/com/google/zxing/core/3.2.1/core-3.2.1.jar',timeout=30).read())
 pin=ROOT/'lite/zxing.sha256'
 digest=hashlib.sha256(dep.read_bytes()).hexdigest()
 if not pin.exists():raise RuntimeError('Record independently reviewed dependency digest in lite/zxing.sha256')
 if digest!=pin.read_text().strip():raise RuntimeError('ZXing digest mismatch')
 dependencies=[dep];native={}
 if candidate:
  for item in json.loads((ROOT/'lite/candidate-dependencies.json').read_text()):
   archive=ROOT/'tools/lite-dependencies'/item['url'].rsplit('/',1)[1];archive.parent.mkdir(parents=True,exist_ok=True)
   if not archive.exists():archive.write_bytes(urllib.request.urlopen(item['url'],timeout=40).read())
   if hashlib.sha256(archive.read_bytes()).hexdigest()!=item['sha256']:raise RuntimeError('Candidate dependency mismatch')
   with zipfile.ZipFile(archive) as z:
    if 'classes.jar' in z.namelist():
     jar=BUILD/(item['name']+'.jar');jar.write_bytes(z.read('classes.jar'));dependencies.append(jar)
    for n in z.namelist():
     if n.startswith('jni/armeabi-v7a/') and n.endswith('.so'):native['lib/'+n[4:]]=z.read(n)
 run(exe(SDK,'aapt2'),'compile','--dir',SRC/'res','-o',BUILD/'res.zip')
 run(exe(SDK,'aapt2'),'link','-o',BUILD/'res.apk','--manifest',SRC/'AndroidManifest.xml','-I',API14,'--java',BUILD/'generated',BUILD/'res.zip')
 sources=list((SRC/'java').rglob('*.java'))+list((BUILD/'generated').rglob('*.java'))
 if candidate:sources+=list((ROOT/'lite/experimental').glob('*.java'))
 run(exe(JDK/'bin','javac'),'-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-bootclasspath',API14,'-classpath',os.pathsep.join(map(str,dependencies)),'-d',BUILD/'classes',*sources)
 with zipfile.ZipFile(BUILD/'classes.jar','w',zipfile.ZIP_DEFLATED) as z:
  for p in (BUILD/'classes').rglob('*.class'):z.write(p,p.relative_to(BUILD/'classes').as_posix())
 run(exe(JDK/'bin','java'),'-cp',SDK/'lib/d8.jar','com.android.tools.r8.D8','--min-api','14','--lib',API14,'--output',BUILD/'dex',BUILD/'classes.jar',*dependencies)
 dex=list((BUILD/'dex').glob('*.dex'))
 if len(dex)!=1:raise RuntimeError('API14 build must remain single-dex')
 with zipfile.ZipFile(BUILD/'res.apk') as src,zipfile.ZipFile(BUILD/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as out:
  for name in src.namelist():out.writestr(name,src.read(name))
  out.write(dex[0],'classes.dex')
  for name,data in native.items():out.writestr(name,data)
  for file in [ROOT/'lite/LICENSE',ROOT/'lite/NOTICE',*list((ROOT/'lite/licenses').glob('*.txt'))]:out.write(file,'assets/licenses/'+file.name)
 run(exe(SDK,'zipalign'),'-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
 apk=DIST/'MarcoMovie-Lite-0.1.1-alpha.apk'
 run(exe(JDK/'bin','java'),'-jar',SDK/'lib/apksigner.jar','sign','--min-sdk-version','14','--ks',ROOT/'android/private/development.p12','--ks-key-alias','marcomovie','--ks-pass','env:MARCOMOVIE_KEY_PASSWORD','--out',apk,BUILD/'aligned.apk')
 signature=run(exe(JDK/'bin','java'),'-jar',SDK/'lib/apksigner.jar','verify','--min-sdk-version','14','--verbose',apk,capture=True)
 run(exe(SDK,'zipalign'),'-c','4',apk)
 result=dict(package='com.pilipala.marcomovie.lite',experimental_candidate=candidate,compile_api=14,min_api=14,bytes=apk.stat().st_size,sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),signature=signature,physical_tv_tested=False)
 (BUILD/'build-report.json').write_text(json.dumps(result,indent=2),encoding='utf8');apk.with_suffix('.apk.sha256').write_text(result['sha256']+'  '+apk.name+'\n');print(json.dumps(result,indent=2))
if __name__=='__main__':build()
