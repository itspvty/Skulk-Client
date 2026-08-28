param([string]$JdkPath = $env:JAVA_HOME)

# Read-only audit of the compiled shipping source sets. Run a clean build first.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jdeps = if ($JdkPath) { Join-Path $JdkPath 'bin/jdeps.exe' } else { (Get-Command jdeps).Source }
$jarTool = if ($JdkPath) { Join-Path $JdkPath 'bin/jar.exe' } else { (Get-Command jar).Source }
$classRoots = @('build/classes/java/main', 'build/classes/java/client') | ForEach-Object {
    (Resolve-Path -LiteralPath (Join-Path $projectRoot $_)).Path
}
$units = [Collections.Generic.HashSet[string]]::new()
foreach ($classRoot in $classRoots) {
    Get-ChildItem -LiteralPath $classRoot -Recurse -Filter '*.class' | ForEach-Object {
        $name = $_.FullName.Substring($classRoot.Length + 1).Replace('\', '.') -replace '\.class$', ''
        [void]$units.Add(($name -split '\$')[0])
    }
}
$edges = @{}
foreach ($unit in $units) { $edges[$unit] = [Collections.Generic.HashSet[string]]::new() }
$missing = [Collections.Generic.HashSet[string]]::new()
$bytecode = & $jdeps --ignore-missing-deps -verbose:class -filter:none @classRoots
if ($LASTEXITCODE -ne 0) { throw 'jdeps failed.' }
foreach ($line in $bytecode) {
    if ($line -match '^\s+(com\.ariesninja\.skulkpk\S*)\s+->\s+(com\.ariesninja\.skulkpk\S*)\s+') {
        $from = ($Matches[1] -split '\$')[0]
        $to = ($Matches[2] -split '\$')[0]
        if (-not $units.Contains($to)) { [void]$missing.Add($to) }
        if ($edges.ContainsKey($from)) { [void]$edges[$from].Add($to) }
    }
}
$manifest = Get-Content -LiteralPath (Join-Path $projectRoot 'src/main/resources/fabric.mod.json') -Raw | ConvertFrom-Json
$roots = [Collections.Generic.HashSet[string]]::new()
foreach ($entrypoint in $manifest.entrypoints.PSObject.Properties) {
    foreach ($entry in $entrypoint.Value) {
        $value = if ($entry -is [string]) { $entry } else { $entry.value }
        [void]$roots.Add(($value -split '::')[0])
    }
}
foreach ($unit in $units) {
    if ($unit.StartsWith('com.ariesninja.skulkpk.client.license.')) { [void]$roots.Add($unit) }
}
if ($manifest.PSObject.Properties.Name -contains 'mixins') {
    foreach ($entry in $manifest.mixins) {
        $configName = if ($entry -is [string]) { $entry } else { $entry.config }
        $configFile = @('src/main/resources', 'src/client/resources') | ForEach-Object {
            Join-Path (Join-Path $projectRoot $_) $configName
        } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if (-not $configFile) { throw "Missing registered mixin config: $configName" }
        $config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json
        foreach ($side in @('mixins', 'client', 'server')) {
            if ($config.PSObject.Properties.Name -contains $side) {
                foreach ($mixin in $config.$side) { [void]$roots.Add($config.package + '.' + $mixin) }
            }
        }
    }
}
$seen = [Collections.Generic.HashSet[string]]::new()
$queue = [Collections.Generic.Queue[string]]::new()
foreach ($root in $roots) {
    if (-not $units.Contains($root)) { throw "Missing runtime root: $root" }
    $queue.Enqueue($root)
}
while ($queue.Count) {
    $unit = $queue.Dequeue()
    if (-not $seen.Add($unit) -or -not $edges.ContainsKey($unit)) { continue }
    foreach ($dependency in $edges[$unit]) { $queue.Enqueue($dependency) }
}
$unreachable = @($units | Where-Object { -not $seen.Contains($_) } | Sort-Object)
$widenerEntries = @(Get-Content -LiteralPath (Join-Path $projectRoot 'src/main/resources/skulkpk.accesswidener') |
    Where-Object { $_.Trim() -and -not $_.StartsWith('#') -and -not $_.StartsWith('accessWidener ') })
$javap = if ($JdkPath) { Join-Path $JdkPath 'bin/javap.exe' } else { (Get-Command javap).Source }
$memberAudit = @()
foreach ($entry in $widenerEntries) {
    # This client-only field is read to capture vanilla input history, never written.
    # Fail closed for any new widening rather than treating a non-empty file as audited.
    if ($entry -ne 'accessible field net/minecraft/client/network/ClientPlayerEntity ticksLeftToDoubleTapSprint I') {
        throw "Unaudited access-widener entry: $entry"
    }
    $captureClass = Join-Path $projectRoot 'build/classes/java/client/com/ariesninja/skulkpk/client/core/analysis/PlayerSnapshot.class'
    $members = & $javap -p -c $captureClass
    if ($LASTEXITCODE -ne 0) { throw 'Member-level bytecode inspection failed.' }
    $reference = $members | Where-Object { $_ -match 'getfield.*ClientPlayerEntity.ticksLeftToDoubleTapSprint:I' }
    if (-not $reference) { throw 'Sprint-timer widening has no retained bytecode reader.' }
    if ($members | Where-Object { $_ -match 'putfield.*ClientPlayerEntity.ticksLeftToDoubleTapSprint:I' }) {
        throw 'Snapshot code must not modify the vanilla sprint timer.'
    }
    $memberAudit += 'ClientPlayerEntity.ticksLeftToDoubleTapSprint:I -> PlayerSnapshot.capture (read only)'
}
$artifacts = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build/libs') -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '-sources\.jar$' })
if ($artifacts.Count -ne 1) { throw 'Expected one shipping jar after clean build.' }
$entries = & $jarTool tf $artifacts[0].FullName
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect shipping jar.' }
$testLeaks = @($entries | Where-Object { $_ -match 'skulkpk/trials/|skulkpk-trials|client-gametest|junit' })
[pscustomobject]@{
    TopLevelUnits = $units.Count
    RuntimeRoots = $roots.Count
    Unreachable = $unreachable
    MissingInternalReferences = @($missing)
    AccessWidenerEntries = $widenerEntries.Count
    AccessWidenerMemberAudit = $memberAudit
    TestArtifactLeaks = $testLeaks
    Artifact = $artifacts[0].Name
} | ConvertTo-Json -Depth 4
if ($unreachable.Count -or $missing.Count -or $testLeaks.Count) { throw 'Client audit failed.' }
