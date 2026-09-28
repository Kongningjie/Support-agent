param(
    [string]$MySqlHost = "localhost",
    [int]$MySqlPort = 13307,
    [string]$MySqlDatabase = "support_agent",
    [string]$MySqlUser = "support_agent",
    [string]$ElasticsearchUrl = "http://localhost:9200",
    [string]$KnowledgeAlias = "support_knowledge_current"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($env:SUPPORT_AGENT_MYSQL_PASSWORD)) {
    throw "请通过 SUPPORT_AGENT_MYSQL_PASSWORD 提供只读核对账号密码。"
}

$mysql = Get-Command mysql -ErrorAction Stop
$previousPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $env:SUPPORT_AGENT_MYSQL_PASSWORD
try {
    $sourceSql = @"
SELECT 'MANAGED_DOCUMENT', id, version, LOWER(CONCAT(
  SUBSTR(HEX(space_id),1,8),'-',SUBSTR(HEX(space_id),9,4),'-',
  SUBSTR(HEX(space_id),13,4),'-',SUBSTR(HEX(space_id),17,4),'-',
  SUBSTR(HEX(space_id),21,12)))
FROM managed_document WHERE status='PUBLISHED' AND deleted=FALSE
UNION ALL
SELECT 'RESOLVED_CASE', id, version, LOWER(CONCAT(
  SUBSTR(HEX(space_id),1,8),'-',SUBSTR(HEX(space_id),9,4),'-',
  SUBSTR(HEX(space_id),13,4),'-',SUBSTR(HEX(space_id),17,4),'-',
  SUBSTR(HEX(space_id),21,12)))
FROM resolved_case WHERE status='PUBLISHED';
"@
    $rows = & $mysql.Source --batch --raw --skip-column-names `
        --host=$MySqlHost --port=$MySqlPort --user=$MySqlUser $MySqlDatabase `
        --execute=$sourceSql
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL 只读来源核对失败。"
    }

    $expected = @{}
    foreach ($row in $rows) {
        $parts = $row -split "`t"
        if ($parts.Count -ne 4) { throw "MySQL 核对结果列数异常。" }
        $expected["$($parts[0]):$($parts[1])"] = @{
            version = [long]$parts[2]
            spaceId = $parts[3]
            chunks = 0
        }
    }

    $body = @{
        size = 0
        aggs = @{
            sources = @{
                composite = @{
                    size = 10000
                    sources = @(
                        @{ sourceType = @{ terms = @{ field = "sourceType" } } },
                        @{ sourceId = @{ terms = @{ field = "sourceId" } } },
                        @{ sourceVersion = @{ terms = @{ field = "sourceVersion" } } },
                        @{ spaceId = @{ terms = @{ field = "spaceId" } } }
                    )
                }
            }
        }
    } | ConvertTo-Json -Depth 10
    $response = Invoke-RestMethod -Method Post `
        -Uri "$($ElasticsearchUrl.TrimEnd('/'))/$KnowledgeAlias/_search" `
        -ContentType "application/json" -Body $body

    $actual = @{}
    foreach ($bucket in $response.aggregations.sources.buckets) {
        $key = "$($bucket.key.sourceType):$($bucket.key.sourceId)"
        $actual[$key] = @{
            version = [long]$bucket.key.sourceVersion
            spaceId = [string]$bucket.key.spaceId
            chunks = [long]$bucket.doc_count
        }
    }

    $problems = @()
    foreach ($key in $expected.Keys) {
        if (-not $actual.ContainsKey($key)) {
            $problems += "索引缺少来源：$key"
            continue
        }
        if ($actual[$key].version -ne $expected[$key].version -or
            $actual[$key].spaceId -ne $expected[$key].spaceId -or
            $actual[$key].chunks -le 0) {
            $problems += "来源版本、空间或分块数量不一致：$key"
        }
    }
    foreach ($key in $actual.Keys) {
        if (-not $expected.ContainsKey($key)) { $problems += "索引存在额外来源：$key" }
    }
    if ($problems.Count -gt 0) {
        $problems | ForEach-Object { Write-Error $_ }
        throw "知识空间完整性核对失败，共 $($problems.Count) 项。"
    }
    Write-Output "知识空间完整性核对通过：$($expected.Count) 个已发布来源，空间、版本和分块数量一致。"
}
finally {
    $env:MYSQL_PWD = $previousPassword
}
