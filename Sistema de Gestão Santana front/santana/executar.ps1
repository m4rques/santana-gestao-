param(
    [Parameter(Position = 0)]
    [ValidateSet('servidor', 'teste', 'teste-integracao')]
    [string]$Modo = 'servidor'
)

$ErrorActionPreference = 'Stop'
$Modo = $Modo.ToLowerInvariant()

if ($env:JAVA_HOME) {
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
        -not (Test-Path -LiteralPath $javac -PathType Leaf)) {
        throw 'Aponte JAVA_HOME para a pasta de um JDK 17 ou superior.'
    }
} else {
    $java = (Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    $javac = (Get-Command javac -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if (-not $java -or -not $javac) {
        throw 'Use um JDK 17 ou superior. Defina JAVA_HOME ou inclua java e javac no PATH.'
    }
}

Push-Location -LiteralPath $PSScriptRoot
try {
    $classes = '.build/classes'
    New-Item -Path $classes -ItemType Directory -Force | Out-Null
    $pastaTestes = Join-Path $PSScriptRoot 'src\test\'
    $fontes = @(Get-ChildItem -LiteralPath 'src' -Recurse -File -Filter '*.java' |
        Where-Object { $Modo -ne 'servidor' -or -not $_.FullName.StartsWith($pastaTestes, [StringComparison]::OrdinalIgnoreCase) } |
        Select-Object -ExpandProperty FullName)

    & $javac --release 17 -encoding UTF-8 -d $classes @fontes
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Copy-Item -Path 'src/main/resources/*' -Destination $classes -Recurse -Force

    $entrada = switch ($Modo) {
        'teste' { 'Testes' }
        'teste-integracao' { 'TestesIntegracao' }
        'servidor' { 'Main' }
    }
    & $java '-Dfile.encoding=UTF-8' -cp "$classes;lib/*" $entrada
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
