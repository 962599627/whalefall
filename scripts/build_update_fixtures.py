from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from build_android import ROOT,SDK,JDK,ANDROID,run,exe
out=ROOT/'android/build/update-fixtures';out.mkdir(parents=True,exist_ok=True)
for name,package,code,version in [('newer','com.pilipala.marcomovie',26,'0.4.13'),('older','com.pilipala.marcomovie',24,'0.4.11'),('foreign','com.example.updatefixture',26,'0.4.13')]:
    manifest=out/(name+'.xml')
    manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="{code}" android:versionName="{version}"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="28"/><application android:label="Never install test fixture"/></manifest>',encoding='utf-8')
    run(exe(SDK,'aapt2'),'link','-o',out/(name+'-unsigned.apk'),'--manifest',manifest,'-I',ANDROID)
    run(exe(JDK/'bin','java'),'-jar',SDK/'lib/apksigner.jar','sign','--ks',ROOT/'android/private/development.p12','--ks-key-alias','marcomovie','--ks-pass','env:MARCOMOVIE_KEY_PASSWORD','--out',out/(name+'.apk'),out/(name+'-unsigned.apk'))
key=out/'fixture.p12'
if not key.exists():run(exe(JDK/'bin','keytool'),'-genkeypair','-alias','fixture','-keystore',key,'-storepass','fixture','-keypass','fixture','-dname','CN=Private test fixture','-keyalg','RSA','-validity','30')
run(exe(JDK/'bin','java'),'-jar',SDK/'lib/apksigner.jar','sign','--ks',key,'--ks-pass','pass:fixture','--out',out/'wrong-signer.apk',out/'newer-unsigned.apk')
