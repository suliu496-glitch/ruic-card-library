$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path $PSScriptRoot -Parent
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { throw 'JAVA_HOME is required.' }
$androidJar = Join-Path $env:ANDROID_HOME 'platforms/android-35/android.jar'
$classes = Join-Path $androidRoot 'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$output = Join-Path $androidRoot 'build/host-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
# Explicit UTF-8 is required for multibyte boundary cases on Windows JDK 17.
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -cp ($classes + ';' + $androidJar) -d $output (Join-Path $PSScriptRoot 'ImportPolicySmokeTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Host check compilation failed. Run :app:compileDebugJavaWithJavac first.' }
& (Join-Path $javaBin 'java.exe') -cp ($output + ';' + $classes + ';' + $androidJar) 'cn.liuyingbin.ruiccards.ImportPolicySmokeTest'
if ($LASTEXITCODE -ne 0) { throw 'Host import policy checks failed.' }
