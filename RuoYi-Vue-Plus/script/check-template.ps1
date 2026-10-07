$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$backend = Join-Path $workspace 'RuoYi-Vue-Plus'
$frontend = Join-Path $workspace 'plus-ui'
$removed = @(
    'ruoyi-demo', 'ruoyi-ai', 'ruoyi-workflow', 'ruoyi-monitor-admin', 'ruoyi-snailai-server',
    'ruoyi-common-ai', 'ruoyi-common-mcp', 'ruoyi-common-liteflow',
    'ruoyi-common-elasticsearch', 'ruoyi-common-mqtt'
)
$failures = [System.Collections.Generic.List[string]]::new()
Get-ChildItem -LiteralPath $backend -Filter pom.xml -Recurse |
    Where-Object { $_.FullName -notmatch '\\target\\' } |
    ForEach-Object {
        [xml]$pom = Get-Content -LiteralPath $_.FullName -Raw
        foreach ($node in $pom.SelectNodes("//*[local-name()='module' or local-name()='artifactId']")) {
            if ($node.InnerText -in $removed -or $node.InnerText -like 'spring-boot-admin-*') {
                $failures.Add("残留 Maven 引用：$($_.FullName) -> $($node.InnerText)")
            }
        }
        foreach ($node in $pom.SelectNodes("/*[local-name()='project']/*[local-name()='modules']/*")) {
            if (-not (Test-Path -LiteralPath (Join-Path $_.DirectoryName "$($node.InnerText)\pom.xml"))) {
                $failures.Add("不存在的 Maven 模块：$($_.FullName) -> $($node.InnerText)")
            }
        }
    }
foreach ($relative in @('src\views\demo', 'src\views\ai', 'src\views\workflow', 'src\views\monitor\snailai', 'src\api\demo', 'src\api\ai', 'src\api\workflow')) {
    if (Test-Path -LiteralPath (Join-Path $frontend $relative)) {
        $failures.Add("残留前端目录：$relative")
    }
}
foreach ($module in @('ruoyi-modules\ruoyi-system', 'ruoyi-modules\ruoyi-job', 'ruoyi-modules\ruoyi-gen', 'ruoyi-extend\ruoyi-snailjob-server')) {
    if (-not (Test-Path -LiteralPath (Join-Path $backend "$module\pom.xml"))) {
        $failures.Add("应保留的模块缺失：$module")
    }
}
[xml]$jobPom = Get-Content -LiteralPath (Join-Path $backend 'ruoyi-extend\ruoyi-snailjob-server\pom.xml') -Raw
if (-not $jobPom.SelectSingleNode("/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='artifactId']='spring-boot-starter-actuator']")) {
    $failures.Add('SnailJob 必须保留 Actuator，不能依赖已删除的监控客户端间接引入。')
}
foreach ($sqlFile in (Get-ChildItem -LiteralPath (Join-Path $backend 'script\sql') -Filter '*ry_vue.sql' -Recurse)) {
    $menus = @{}
    $roleMenus = [System.Collections.Generic.List[string]]::new()
    $hasJobMenu = $false
    foreach ($line in (Get-Content -LiteralPath $sqlFile.FullName)) {
        $menu = [regex]::Match($line.Trim(), "^insert(?:\s+into)?\s+sys_menu\s+values\s*\(\s*(\d+)\s*,\s*N?'(?:[^']|'')*'\s*,\s*(\d+)", 'IgnoreCase')
        if ($menu.Success) {
            $id = $menu.Groups[1].Value
            if ($menus.ContainsKey($id)) {
                $failures.Add("重复菜单：$($sqlFile.Name) -> $id")
            }
            $menus[$id] = $menu.Groups[2].Value
            if ($line -match 'demo:|workflow:|ai:|monitor/(admin|snailai)/index|AI会话|PLUS官网|aichat|ai/chat/index|https://gitee\.com/dromara/RuoYi-Vue-Plus') {
                $failures.Add("残留功能菜单：$($sqlFile.Name) -> $id")
            }
            if ($line -match 'monitor/snailjob/index') { $hasJobMenu = $true }
        }
        $roleMenu = [regex]::Match($line.Trim(), '^insert(?:\s+into)?\s+sys_role_menu\s+values\s*\(\s*(\d+)\s*,\s*(\d+)', 'IgnoreCase')
        if ($roleMenu.Success) { $roleMenus.Add($roleMenu.Groups[2].Value) }
        if ($line -match '(?i)(create|drop)\s+table\s+(if\s+(not\s+)?exists\s+)?["\[]?test_(demo|tree)\b') {
            $failures.Add("残留演示表：$($sqlFile.Name)")
        }
    }
    foreach ($entry in $menus.GetEnumerator()) {
        if ($entry.Value -ne '0' -and -not $menus.ContainsKey($entry.Value)) {
            $failures.Add("菜单父节点不存在：$($sqlFile.Name) -> $($entry.Key)")
        }
    }
    foreach ($id in $roleMenus) {
        if (-not $menus.ContainsKey($id)) {
            $failures.Add("角色引用不存在的菜单：$($sqlFile.Name) -> $id")
        }
    }
    if (-not $menus.Count -or -not $hasJobMenu) {
        $failures.Add("缺少系统或任务调度菜单：$($sqlFile.Name)")
    }
}
if ($failures.Count) {
    $failures | ForEach-Object { Write-Output $_ }
    throw "模板结构检查失败：$($failures.Count) 项"
}
Write-Output '模板结构检查通过。'
