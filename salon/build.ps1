# Compile l'appli "Salon" (sans Gradle) et produit Salon.apk
# Usage : powershell -ExecutionPolicy Bypass -File build.ps1 [-Install]
# Les codes viennent de donnees\flipper_tv.ir : relancer d'abord outils\convertir_codes.py s'il change.
param([switch]$Install)
# Pas de 'Stop' : les outils ecrivent des avertissements sur stderr ; Check() teste le code de sortie
$ErrorActionPreference = 'Continue'

$tools  = 'C:\Users\djame\Desktop\04_Ressources\Outils'
$jdk    = (Get-ChildItem "$tools\Java" -Directory | Select-Object -First 1).FullName
$bt     = "$tools\Android-SDK\build-tools\34.0.0"
$jar    = "$tools\Android-SDK\platforms\android-34\android.jar"
$adb    = "$tools\platform-tools\adb.exe"
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$here  = $PSScriptRoot
$build = Join-Path $here 'build'
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force "$build\classes", "$build\dex" | Out-Null

function Check($what) { if ($LASTEXITCODE -ne 0) { throw "Echec : $what" } }

# 1. Ressources
& "$bt\aapt2.exe" compile --dir "$here\res" -o "$build\res.zip"; Check 'aapt2 compile'
& "$bt\aapt2.exe" link -I $jar --manifest "$here\AndroidManifest.xml" -o "$build\base.apk" "$build\res.zip"; Check 'aapt2 link'

# 2. Code Java -> .class -> classes.dex
$sources = Get-ChildItem "$here\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
# --release 8 + android.jar en classpath : le android.jar du SDK n'a pas LambdaMetafactory
& javac -encoding UTF-8 --release 8 -classpath $jar -d "$build\classes" $sources; Check 'javac'
$classes = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --min-api 29 --release --lib $jar --output "$build\dex" $classes; Check 'd8'

# 3. Assemblage, alignement, signature
Copy-Item "$build\base.apk" "$build\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$build\unsigned.apk", 'Update')
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build\dex\classes.dex", 'classes.dex')
$zip.Dispose()
& "$bt\zipalign.exe" -f -p 4 "$build\unsigned.apk" "$build\aligned.apk"; Check 'zipalign'

$ks = Join-Path $here 'cle-signature.jks'
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass salon-tv -keypass salon-tv -alias salon `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Djamel, O=Maison, C=FR' 2>$null; Check 'keytool'
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:salon-tv --ks-key-alias salon --out "$here\Salon.apk" "$build\aligned.apk"; Check 'apksigner'
Write-Host "OK : $here\Salon.apk" -ForegroundColor Green

if ($Install) {
    & $adb -s 4769987b install -r "$here\Salon.apk"; Check 'adb install'
}

