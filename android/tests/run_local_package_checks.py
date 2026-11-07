"""Exercise local imports against the same parser used at Android startup."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
jdk = Path(os.environ.get("MARCOMOVIE_JDK") or os.environ["JAVA_HOME"]) / "bin"
src = root / "android/src/main/java/com/pilipala/marcomovie"
parser = root / "android/tests/lib/json-20240303.jar"
with tempfile.TemporaryDirectory() as output, tempfile.TemporaryDirectory() as imports:
    shim = Path(output) / 'android/util/Base64.java'
    shim.parent.mkdir(parents=True)
    shim.write_text('package android.util; public final class Base64 { public static final int DEFAULT=0; public static byte[] decode(String value,int flags){return java.util.Base64.getMimeDecoder().decode(value);} }', encoding='utf-8')
    subprocess.run([jdk / "javac", "-encoding", "UTF-8", "-cp", parser, "-d", output,
                    root / "shared/java/com/pilipala/source/Warehouse.java", src / "Cms.java", src / "LocalSourcePackage.java", src / "RequestScope.java", src / "LiveCatalog.java",
                    root / "android/tests/LocalPackageChecks.java", shim,
                    root / 'lite/src/main/java/com/pilipala/marcomovie/lite/LiteCatalog.java',
                    root / 'lite/src/main/java/com/pilipala/marcomovie/lite/NetworkTls.java',
                    root / 'android/tests/LiteFormatChecks.java'], check=True)
    subprocess.run([jdk / "java", "-cp", output + os.pathsep + str(parser),
                    "com.pilipala.marcomovie.LocalPackageChecks", imports], check=True)
    subprocess.run([jdk / "java", "-cp", output + os.pathsep + str(parser), "LiteFormatChecks"], check=True)
