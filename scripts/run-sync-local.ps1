param(
    [string]$PostgresRepo = (Join-Path $PSScriptRoot '..\..\delta-api-postgres'),
    [string]$SchemaRepo = (Join-Path $PSScriptRoot '..\..\delta-mongo-database'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenHome = $env:MAVEN_HOME
)
$ErrorActionPreference = 'Stop'
$apiRepo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$JavaHome) { throw 'Configure JAVA_HOME com Java 21.' }
if (!$MavenHome) { throw 'Informe -MavenHome (Maven 3.9+). Nenhuma credencial de produção é necessária.' }
$java = Join-Path $JavaHome 'bin\java.exe'
function Invoke-Maven([string]$Repo, [string[]]$Arguments) {
    Push-Location -LiteralPath $Repo
    try {
        & $java "-Dmaven.multiModuleProjectDirectory=$Repo" "-Dmaven.home=$MavenHome" "-Dclassworlds.conf=$MavenHome\bin\m2.conf" -classpath "$MavenHome\boot\*" org.codehaus.plexus.classworlds.launcher.Launcher -B -ntp @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Maven falhou em $Repo" }
    } finally { Pop-Location }
}
$id = [guid]::NewGuid().ToString('N').Substring(0,12)
$pg = "delta-sync-pg-$id"
$mongo = "delta-sync-mongo-$id"
try {
    docker run -d --name $pg -p '127.0.0.1::5432' -e POSTGRES_USER=delta_test -e POSTGRES_PASSWORD=local-test-only -e POSTGRES_DB=delta_test_sync postgres:16 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao iniciar PostgreSQL descartável.' }
    docker run -d --name $mongo -p '127.0.0.1::27017' mongo:7 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao iniciar Mongo descartável.' }
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        docker exec $pg pg_isready -U delta_test -d delta_test_sync *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'PostgreSQL não ficou pronto.' }
    docker cp (Join-Path $SchemaRepo 'scripts') "${mongo}:/scripts"
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao copiar schemas.' }
    foreach ($file in @('delta-telemetry/script-collections.js','delta-app/script-collections.js','delta-telemetry/script-migrate-anomaly-fields.js','delta-app/script-migrate-user-hour-baseline.js','delta-app/script-migrate-alert-types.js','delta-telemetry/script-indexes.js','delta-app/script-indexes.js')) {
        docker exec $mongo mongosh --quiet --file "/scripts/$file"
        if ($LASTEXITCODE -ne 0) { throw "Falha no schema $file" }
    }
    $pgPort = ((docker port $pg 5432/tcp) -split ':')[-1]
    $mongoPort = ((docker port $mongo 27017/tcp) -split ':')[-1]
    Invoke-Maven $apiRepo @('-DskipTests','package')
    $jar = Join-Path $apiRepo 'target\delta_api_mongo-0.0.1-SNAPSHOT.jar'
    Invoke-Maven $PostgresRepo @('-Ddelta.test.sync=true',"-Ddelta.test.postgres-url=jdbc:postgresql://127.0.0.1:$pgPort/delta_test_sync","-Ddelta.test.mongo-uri=mongodb://127.0.0.1:$mongoPort","-Ddelta.test.mongo-jar=$jar",'-Dtest=ApiSyncPostgresIntegrationTest#synchronizesActualHttpIngestionOwnerJwtVisitAndDuplicate','test')
} finally {
    # Somente containers cujo nome foi gerado nesta execução; nenhum volume compartilhado.
    docker rm -f $pg $mongo *> $null
}
