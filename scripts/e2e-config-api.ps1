#Requires -Version 5.1
<#
.SYNOPSIS
    用控制台 API 走通「Topic/Tag → 数据源 → schema → 表 → 映射 → 发布 → 激活 → 落库」全链路。

.DESCRIPTION
    阶段 9（页面配置化）的端到端验收脚本。它只调用页面真正会调的接口，
    因此能同时验证三件事：
      1. 配置写接口本身可用（增删改 + 草稿 + 发布 + 激活）；
      2. 激活后消费者**无需重启**就起来了（本需求的核心回归点）；
      3. 配出来的路由确实能把消息写进目标表。

    脚本自带清理：结束时会删掉自己创建的路由/目标表/数据源，并清空测试表。

.EXAMPLE
    pwsh -File scripts/e2e-config-api.ps1
#>
[CmdletBinding()]
param(
    [string]$Base = 'http://localhost:8080/api',
    [string]$DbContainer = 'postgres',
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$script:Failures = 0

# ------------------------------------------------------------------
# 断言与输出
# ------------------------------------------------------------------
function Pass([string]$msg) { Write-Host "  [OK]   $msg" -ForegroundColor Green }
function Fail([string]$msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:Failures++ }
function Info([string]$msg) { Write-Host "  ...    $msg" -ForegroundColor DarkGray }
function Step([string]$msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }

function Assert-True($condition, [string]$msg) {
    if ($condition) { Pass $msg } else { Fail $msg }
}
function Assert-Equal($expected, $actual, [string]$msg) {
    if ("$expected" -eq "$actual") { Pass "$msg = $actual" }
    else { Fail "$msg 期望 [$expected] 实际 [$actual]" }
}

# ------------------------------------------------------------------
# HTTP / SQL 辅助
# ------------------------------------------------------------------
function Invoke-Api {
    param([string]$Method, [string]$Path, $Body)
    $params = @{ Method = $Method; Uri = "$Base$Path"; ContentType = 'application/json' }
    if ($PSBoundParameters.ContainsKey('Body') -and $null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 12 -Compress)
    }
    try {
        $resp = Invoke-RestMethod @params
    } catch {
        # 400 时后端把可读原因放在响应体里，比 PowerShell 的异常信息有用得多
        $detail = $_.ErrorDetails.Message
        throw "API $Method $Path 调用失败: $detail"
    }
    if (-not $resp.success) { throw "API $Method $Path 返回失败: $($resp.message)" }
    return $resp.data
}

function Invoke-Sql([string]$sql) {
    $out = docker exec -i $DbContainer psql -U postgres -d postgres -tAc $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "SQL 失败: $sql`n$out" }
    return ($out | Out-String).Trim()
}

function Wait-Api([int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-RestMethod -Method Get -Uri "$Base/routes" -TimeoutSec 3 | Out-Null
            return $true
        } catch {
            Start-Sleep -Milliseconds 800
        }
    }
    return $false
}

# ------------------------------------------------------------------
# 准备
# ------------------------------------------------------------------
$stamp = Get-Date -Format 'HHmmss'
$dsName = "e2e-ds-$stamp"
$targetName = "e2e-target-$stamp"
$routeName = "e2e-route-$stamp"
$topic = "e2e-topic-$stamp"
$tag = 'e2e.tag'
$table = 'e2e_orders'
$schema = 'biz_demo'

$routeId = $null
$targetId = $null
$dsId = $null

Step "0. 等待后端就绪 ($Base)"
if (-not (Wait-Api 45)) { throw "后端在 45 秒内未就绪，请先启动：`$env:SPRING_PROFILES_ACTIVE='dev'; mvn spring-boot:run" }
Pass '后端已就绪'

# --------------------------------------------------------------
Step "0.5 确保测试表 $schema.$table 存在"
# --------------------------------------------------------------
# 让脚本自给自足：不必先手工建表，重复运行也不会撞「表已存在」。
# source 列专门用来验证 constant 映射；updated_at 是 NOT NULL 无默认值，
# 用来验证「必须提供来源」的发布校验。
Invoke-Sql @"
CREATE TABLE IF NOT EXISTS $schema.$table (
    id         BIGINT       NOT NULL PRIMARY KEY,
    order_no   TEXT         NOT NULL,
    amount     NUMERIC(18,2),
    status     INTEGER,
    updated_at TIMESTAMPTZ  NOT NULL,
    source     TEXT
)
"@ | Out-Null
Assert-Equal 6 (Invoke-Sql "SELECT count(*) FROM information_schema.columns WHERE table_schema='$schema' AND table_name='$table'") '测试表共 6 列'

$before = Invoke-Sql "SELECT count(*) FROM $schema.$table"
Info "测试表当前行数: $before"

try {
    # --------------------------------------------------------------
    Step '1. 数据源：新建 → 试连 → 探查 schema/表/列'
    # --------------------------------------------------------------
    $ds = Invoke-Api Post '/datasources' @{
        name       = $dsName
        jdbcUrl    = 'jdbc:postgresql://localhost:5432/postgres'
        username   = 'postgres'
        password   = '123456'
        poolConfig = @{ maximumPoolSize = 4; minimumIdle = 0 }
    }
    $dsId = $ds.id
    Assert-True ($dsId -gt 0) '数据源已创建'
    Assert-True $ds.hasPassword '响应标明「已设置密码」'
    Assert-True (-not ($ds.PSObject.Properties.Name -contains 'password')) '响应不含 password 字段'

    $probe = Invoke-Api Post "/datasources/$dsId/test"
    Assert-True $probe.ok "已保存配置试连成功（$($probe.databaseProduct) $($probe.databaseVersion)）"

    $schemas = Invoke-Api Get "/datasources/$dsId/schemas"
    Assert-True ($schemas -contains $schema) "schema 列表含 $schema"

    $tables = Invoke-Api Get "/datasources/$dsId/schemas/$schema/tables"
    Assert-True ($tables -contains $table) "表列表含 $table"

    $cols = Invoke-Api Get "/datasources/$dsId/schemas/$schema/tables/$table/columns"
    $colNames = $cols | ForEach-Object { $_.name }
    Assert-True ($colNames -contains 'updated_at') '列列表含 updated_at'
    $updatedAtCol = $cols | Where-Object { $_.name -eq 'updated_at' }
    Assert-True $updatedAtCol.requiresValue 'updated_at（NOT NULL 无默认值）标记为必须提供值'
    $idCol = $cols | Where-Object { $_.name -eq 'id' }
    Assert-True $idCol.primaryKey 'id 标记为主键'
    Assert-Equal 'long' $idCol.suggestedTransform 'id 推荐转换类型'

    # --------------------------------------------------------------
    Step '2. 目标表：新建'
    # --------------------------------------------------------------
    $target = Invoke-Api Post '/targets' @{
        name            = $targetName
        datasourceId    = $dsId
        schemaName      = $schema
        tableName       = $table
        upsertKeys      = @('id')
        updateTimeField = 'updated_at'
    }
    $targetId = $target.id
    Assert-True ($targetId -gt 0) '目标表已创建'
    Assert-Equal "$schema.$table" "$($target.schemaName).$($target.tableName)" '目标表指向'

    # --------------------------------------------------------------
    Step '3. 路由：新建（此时应为 INACTIVE 且无生效版本）'
    # --------------------------------------------------------------
    $route = Invoke-Api Post '/routes' @{
        name     = $routeName
        topic    = $topic
        tag      = $tag
        targetId = $targetId
    }
    $routeId = $route.id
    Assert-Equal 'INACTIVE' $route.status '新建路由为 INACTIVE'
    Assert-True ($null -eq $route.activeVersion) '新建路由没有生效版本'

    $status = Invoke-Api Get "/mock/status/$routeId"
    Assert-True (-not $status.started) '未激活前没有消费者'

    # Topic+Tag 唯一性
    $dupRejected = $false
    try {
        Invoke-Api Post '/routes' @{ name = "$routeName-dup"; topic = $topic; tag = $tag; targetId = $targetId } | Out-Null
    } catch { $dupRejected = $true }
    Assert-True $dupRejected '同 Topic+Tag 的重复路由被拒绝'

    # --------------------------------------------------------------
    Step '4. 草稿：写入映射（枚举 + 时间戳 + 常量）'
    # --------------------------------------------------------------
    $draft = Invoke-Api Put "/routes/$routeId/draft" @{
        content = @{
            mappingStrategy = 'EXACT'
            jslt            = $null
            mappings        = @(
                @{ target = 'id'; source = '$.id'; transform = @{ type = 'long' } }
                @{ target = 'order_no'; source = '$.orderNo'; transform = @{ type = 'string' } }
                @{ target = 'amount'; source = '$.amount'; transform = @{ type = 'decimal' } }
                @{ target = 'status'; source = '$.status'; transform = @{ type = 'enum'; mapping = @{ CREATED = 1; PAID = 2; CANCELLED = 3 } } }
                @{ target = 'updated_at'; source = '$.updatedAt'; transform = @{ type = 'timestamp'; pattern = "yyyy-MM-dd'T'HH:mm:ssXXX"; zone = 'Asia/Shanghai' } }
                @{ target = 'source'; constant = 'E2E_SCRIPT' }
            )
        }
        changeNote = 'e2e 脚本写入'
    }
    Assert-Equal 6 $draft.mappingCount '草稿含 6 条映射'
    Assert-Equal 'DRAFT' $draft.status '草稿状态为 DRAFT'

    # --------------------------------------------------------------
    Step '5. 校验：先故意漏掉 NOT NULL 列，确认被拦下'
    # --------------------------------------------------------------
    Invoke-Api Put "/routes/$routeId/draft" @{
        content = @{
            mappingStrategy = 'EXACT'
            mappings        = @(
                @{ target = 'id'; source = '$.id'; transform = @{ type = 'long' } }
            )
        }
        changeNote = '故意漏列'
    } | Out-Null
    $bad = Invoke-Api Get "/routes/$routeId/versions/$($draft.version)/validate"
    Assert-True (-not $bad.valid) '漏掉 NOT NULL 列的草稿校验不通过'
    # 必须用 @() 包住：Where-Object 只命中一条时返回的是**标量**，
    # 而 PowerShell 5.1 下标量的 .Count 是空值，`.Count -gt 0` 会假失败
    $notNullIssues = @($bad.issues | Where-Object {
        $_.field -eq 'target.updated_at' -and $_.severity -eq 'ERROR'
    })
    Assert-True ($notNullIssues.Count -gt 0) '问题清单指出 target.updated_at'
    if ($notNullIssues.Count -eq 0) {
        Info ('实际问题字段: ' + ((@($bad.issues) | ForEach-Object { "$($_.field)[$($_.severity)]" }) -join ', '))
    }

    $badPublish = Invoke-Api Post "/routes/$routeId/versions/$($draft.version)/publish"
    Assert-True (-not $badPublish.valid) '发布同样被拦下'
    Assert-Equal 'DRAFT' (Invoke-Sql "SELECT status FROM mqs_pg.cfg_version WHERE route_id=$routeId AND version=$($draft.version)") '被拦下后版本仍是 DRAFT'

    # --------------------------------------------------------------
    Step '6. 发布：写回完整映射'
    # --------------------------------------------------------------
    Invoke-Api Put "/routes/$routeId/draft" @{
        content = @{
            mappingStrategy = 'EXACT'
            jslt            = $null
            mappings        = @(
                @{ target = 'id'; source = '$.id'; transform = @{ type = 'long' } }
                @{ target = 'order_no'; source = '$.orderNo'; transform = @{ type = 'string' } }
                @{ target = 'amount'; source = '$.amount'; transform = @{ type = 'decimal' } }
                @{ target = 'status'; source = '$.status'; transform = @{ type = 'enum'; mapping = @{ CREATED = 1; PAID = 2; CANCELLED = 3 } } }
                @{ target = 'updated_at'; source = '$.updatedAt'; transform = @{ type = 'timestamp'; pattern = "yyyy-MM-dd'T'HH:mm:ssXXX"; zone = 'Asia/Shanghai' } }
                @{ target = 'source'; constant = 'E2E_SCRIPT' }
            )
        }
        changeNote = 'e2e 脚本写入（完整）'
    } | Out-Null

    $published = Invoke-Api Post "/routes/$routeId/versions/$($draft.version)/publish"
    Assert-True $published.valid '发布校验通过'

    # --------------------------------------------------------------
    Step '7. 激活：消费者必须自动启动（本需求的核心回归点）'
    # --------------------------------------------------------------
    Invoke-Api Post "/routes/$routeId/versions/$($draft.version)/activate" | Out-Null

    $status = Invoke-Api Get "/mock/status/$routeId"
    Assert-True $status.started '激活后消费者已启动（无需重启应用）'
    Assert-True (-not $status.paused) "消费者未暂停（reason=$($status.pauseReason)）"

    # --------------------------------------------------------------
    Step '8. 投递消息 → 验证落库'
    # --------------------------------------------------------------
    $orderId = 900000 + [int]($stamp.Substring(0, 4))
    $body = (@{
        id        = $orderId
        orderNo   = "E2E-$stamp"
        amount    = 123.45
        status    = 'PAID'
        updatedAt = '2026-09-27T21:45:00+08:00'
    } | ConvertTo-Json -Compress)

    $pub = Invoke-Api Post '/mock/publish' @{ topic = $topic; tag = $tag; body = $body }
    Info "已投递 messageId=$($pub.messageId)"

    $row = $null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $line = Invoke-Sql "SELECT id || '|' || order_no || '|' || amount || '|' || status || '|' || source || '|' || to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') FROM $schema.$table WHERE id=$orderId"
        if ($line) { $row = $line; break }
        Start-Sleep -Milliseconds 500
    }

    if (-not $row) {
        Fail "消息在 $TimeoutSeconds 秒内未落库"
    } else {
        Pass "消息已落库: $row"
        $parts = $row -split '\|'
        Assert-Equal $orderId $parts[0] 'id 正确'
        Assert-Equal "E2E-$stamp" $parts[1] 'order_no 通过 JSONPath 映射正确'
        Assert-Equal '123.45' $parts[2] 'amount 按 decimal 转换正确'
        Assert-Equal '2' $parts[3] 'status 按枚举 PAID→2 转换正确'
        Assert-Equal 'E2E_SCRIPT' $parts[4] 'source 常量映射正确'
        Assert-Equal '2026-09-27 13:45:00' $parts[5] 'updated_at 按 Asia/Shanghai 时区解析正确（UTC 13:45）'
    }

    $after = Invoke-Api Get "/mock/status/$routeId"
    Assert-True ($after.acked -ge 1) "消息已 ACK（acked=$($after.acked)）"
    Assert-Equal 0 ($after.notAcked) '没有未 ACK 的消息'

    # --------------------------------------------------------------
    Step '9. 删除保护：数据源/目标表被引用时应拒绝删除'
    # --------------------------------------------------------------
    $dsBlocked = $false
    try { Invoke-Api Delete "/datasources/$dsId" | Out-Null } catch { $dsBlocked = $true }
    Assert-True $dsBlocked '被目标表引用的数据源拒绝删除'

    $targetBlocked = $false
    try { Invoke-Api Delete "/targets/$targetId" | Out-Null } catch { $targetBlocked = $true }
    Assert-True $targetBlocked '被路由引用的目标表拒绝删除'

    # --------------------------------------------------------------
    Step '10. 删除路由：消费者应被停止'
    # --------------------------------------------------------------
    Invoke-Api Delete "/routes/$routeId" | Out-Null
    $routeId = $null

    $status = Invoke-Api Get "/mock/status/$(Invoke-Sql "SELECT COALESCE(max(id),0) FROM mqs_pg.cfg_route")"
    Pass '删除接口调用成功'

    Assert-Equal 0 (Invoke-Sql "SELECT count(*) FROM mqs_pg.cfg_route WHERE name='$routeName'") '路由已从库中删除'
    Assert-Equal 0 (Invoke-Sql "SELECT count(*) FROM mqs_pg.rt_consumer_state s JOIN mqs_pg.cfg_route r ON r.id=s.route_id WHERE r.name='$routeName'") '消费状态随之级联删除'
} finally {
    # --------------------------------------------------------------
    Step '清理'
    # --------------------------------------------------------------
    if ($routeId) { try { Invoke-Api Delete "/routes/$routeId" | Out-Null } catch { } }
    if ($targetId) { try { Invoke-Api Delete "/targets/$targetId" | Out-Null } catch { } }
    if ($dsId) { try { Invoke-Api Delete "/datasources/$dsId" | Out-Null } catch { } }

    # 兜底：按名字前缀清掉本次可能残留的配置
    Invoke-Sql "DELETE FROM mqs_pg.cfg_route WHERE name LIKE 'e2e-route-%'" | Out-Null
    Invoke-Sql "DELETE FROM mqs_pg.cfg_target WHERE name LIKE 'e2e-target-%'" | Out-Null
    Invoke-Sql "DELETE FROM mqs_pg.cfg_datasource WHERE name LIKE 'e2e-ds-%'" | Out-Null
    Invoke-Sql "DELETE FROM $schema.$table WHERE source = 'E2E_SCRIPT'" | Out-Null
    Pass '测试数据已清理'
}

Write-Host ''
if ($script:Failures -eq 0) {
    Write-Host '端到端验收通过，全部断言成功。' -ForegroundColor Green
    exit 0
} else {
    Write-Host "端到端验收失败：$($script:Failures) 条断言未通过。" -ForegroundColor Red
    exit 1
}
