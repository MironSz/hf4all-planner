# build.ps1 — finds Maven (bundled in IntelliJ or on PATH) and compiles the project

$ProjectDir = $PSScriptRoot

# 1. Check if mvn is already on PATH
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) {
    Write-Host "Found Maven on PATH: $($mvn.Source)" -ForegroundColor Green
    & mvn -f "$ProjectDir\pom.xml" compile
    exit $LASTEXITCODE
}

# 2. Look for Maven bundled inside IntelliJ IDEA installations
$ideaPaths = @(
    "$env:LOCALAPPDATA\JetBrains",
    "$env:ProgramFiles\JetBrains",
    "${env:ProgramFiles(x86)}\JetBrains"
)

$bundledMvn = $null
foreach ($base in $ideaPaths) {
    if (Test-Path $base) {
        $found = Get-ChildItem $base -Recurse -Filter "mvn.cmd" -ErrorAction SilentlyContinue |
                 Where-Object { $_.FullName -like "*maven*" } |
                 Select-Object -First 1
        if ($found) {
            $bundledMvn = $found.FullName
            break
        }
    }
}

if ($bundledMvn) {
    Write-Host "Found IntelliJ bundled Maven: $bundledMvn" -ForegroundColor Green
    & $bundledMvn -f "$ProjectDir\pom.xml" compile
    exit $LASTEXITCODE
}

# 3. Fall back to mvnw.cmd wrapper (downloads Maven on first run)
$wrapper = "$ProjectDir\mvnw.cmd"
if (Test-Path $wrapper) {
    Write-Host "Using Maven wrapper (will download Maven on first run)..." -ForegroundColor Yellow
    & $wrapper -f "$ProjectDir\pom.xml" compile
    exit $LASTEXITCODE
}

Write-Host "Could not find Maven. Options:" -ForegroundColor Red
Write-Host "  A) Open IntelliJ -> right-click pom.xml -> 'Add as Maven Project' -> Build"
Write-Host "  B) Install Maven: https://maven.apache.org/download.cgi"
exit 1
