[CmdletBinding()]
param(
    [string[]]$CardIds = @('windowlight', 'dusksea', 'bluehour', 'sunsettrain', 'citywatch', 'piratecat', 'rainkitten', 'silvermeadow'),
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SkillRoot = (Join-Path $env:USERPROFILE '.codex\skills\RuiC-card-skill'),
    [string]$PythonExecutable = 'py',
    [string]$BlenderExecutable,
    [switch]$SkipRender,
    [switch]$SkipNpm,
    [switch]$SkipWebVerification,
    [string]$BrowserExecutable
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$SkillRoot = [IO.Path]::GetFullPath($SkillRoot)
$verificationRoot = Join-Path $ProjectRoot 'verification'
New-Item -ItemType Directory -Path $verificationRoot -Force | Out-Null

function Invoke-LoggedCommand {
    param([string]$Executable, [string[]]$CommandArguments, [string]$LogPath)
    & $Executable @CommandArguments 2>&1 | Tee-Object -FilePath $LogPath -Append | Write-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}. See $LogPath"
    }
}

foreach ($requiredScript in @('ensure_blender.py', 'validate_assets.py', 'run_pipeline.py', 'verify_web.mjs')) {
    if (-not (Test-Path -LiteralPath (Join-Path $SkillRoot "scripts\$requiredScript") -PathType Leaf)) {
        throw "RuiC Card Skill is incomplete: missing $requiredScript in $SkillRoot"
    }
}
Get-Command $PythonExecutable -ErrorAction Stop | Out-Null
Get-Command node -ErrorAction Stop | Out-Null

if (-not $BlenderExecutable) {
    $sharedTools = Join-Path $ProjectRoot 'tools'
    if (Test-Path -LiteralPath $sharedTools) {
        $portableBlender = Get-ChildItem -LiteralPath $sharedTools -Filter 'blender.exe' -File -Recurse |
            Sort-Object FullName | Select-Object -First 1
        if ($portableBlender) { $BlenderExecutable = $portableBlender.FullName }
    }
    if (-not $BlenderExecutable) {
        Invoke-LoggedCommand $PythonExecutable @((Join-Path $SkillRoot 'scripts\ensure_blender.py'), $ProjectRoot) (Join-Path $verificationRoot 'blender-setup.log')
        $portableBlender = Get-ChildItem -LiteralPath $sharedTools -Filter 'blender.exe' -File -Recurse |
            Sort-Object FullName | Select-Object -First 1
        if ($portableBlender) { $BlenderExecutable = $portableBlender.FullName }
    }
}
if (-not $BlenderExecutable -or -not (Test-Path -LiteralPath $BlenderExecutable -PathType Leaf)) {
    throw 'A verified Blender executable is required. Set -BlenderExecutable to an existing portable installation.'
}
$BlenderExecutable = [IO.Path]::GetFullPath($BlenderExecutable)

$results = [Collections.Generic.List[object]]::new()
$summaryPath = Join-Path $verificationRoot 'card-builds.json'
if (Test-Path -LiteralPath $summaryPath -PathType Leaf) {
    $previousResults = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
    foreach ($previous in $previousResults) {
        if ($previous.id -notin $CardIds) { $results.Add($previous) }
    }
}
$builtCount = 0
foreach ($cardId in $CardIds) {
    if ($cardId -notmatch '^[a-z0-9][a-z0-9-]*$') { throw "Invalid card ID: $cardId" }
    $cardRoot = Join-Path $ProjectRoot "cards\$cardId"
    if (-not (Test-Path -LiteralPath (Join-Path $cardRoot 'card-config.json') -PathType Leaf)) {
        throw "Prepare card-config.json and the artwork layers before building: $cardRoot"
    }
    $buildLog = Join-Path $verificationRoot "build-$cardId.log"
    "`n[$([DateTime]::UtcNow.ToString('o'))] Building $cardId" | Add-Content -LiteralPath $buildLog -Encoding utf8
    Write-Host "Building RuiC card: $cardId"
    # run_pipeline creates text.png when missing, validates all layers, builds the editable
    # scene, exports real GLB geometry, and assembles the offline-capable Three.js viewer.
    $pipelineArguments = @((Join-Path $SkillRoot 'scripts\run_pipeline.py'), '--project', $cardRoot, '--blender', $BlenderExecutable, '--skip-render')
    if ($SkipNpm) { $pipelineArguments += '--skip-npm' }
    Invoke-LoggedCommand $PythonExecutable $pipelineArguments $buildLog
    # Apply config-driven restrained emission to the editable Blender scene.
    # GLB geometry and the browser's material-role contract remain unchanged.
    $postprocessArguments = @('--background', (Join-Path $cardRoot 'card.blend'), '--python', (Join-Path $PSScriptRoot 'postprocess-blender.py'), '--', $cardRoot)
    if ($SkipRender) { $postprocessArguments += '--skip-render' }
    Invoke-LoggedCommand $BlenderExecutable $postprocessArguments $buildLog
    Invoke-LoggedCommand $PythonExecutable @((Join-Path $SkillRoot 'scripts\validate_assets.py'), $cardRoot) $buildLog
    Invoke-LoggedCommand $PythonExecutable @((Join-Path $PSScriptRoot 'verify-card-files.py'), $cardRoot) $buildLog
    if (-not $SkipRender) {
        Invoke-LoggedCommand $BlenderExecutable @('--background', (Join-Path $cardRoot 'card.blend'), '--python', (Join-Path $PSScriptRoot 'render-tilts.py'), '--', $cardRoot) $buildLog
    }
    if (-not $SkipWebVerification) {
        # Project copy fixes the upstream verifier treating the absent optional
        # effects layer's legal 1x1 transparent placeholder as supplied artwork.
        $verifyArguments = @((Join-Path $PSScriptRoot 'verify-card-web.mjs'), $cardRoot)
        if ($BrowserExecutable) { $verifyArguments += @('--browser', $BrowserExecutable) }
        Invoke-LoggedCommand 'node' $verifyArguments $buildLog
    }
    $results.Add([ordered]@{
        id = $cardId
        project = $cardRoot
        blend = (Join-Path $cardRoot 'card.blend')
        model = (Join-Path $cardRoot 'web\assets\card.glb')
        assetsValidated = $true
        blenderPostprocessed = $true
        rendersProduced = (-not $SkipRender)
        webVerified = (-not $SkipWebVerification)
        completedUtc = [DateTime]::UtcNow.ToString('o')
    })
    $builtCount++
    $results | ConvertTo-Json -Depth 8 -AsArray | Set-Content -LiteralPath $summaryPath -Encoding utf8
}
Write-Host "Built $builtCount card(s); $($results.Count) total recorded. Reports: $verificationRoot"
