# Query Tencent market data and compute holdings per account
# Run from workspace root (D:\Program\leeks)

$cfg = @'
sz300308
sh000001
sz399006
sh515880,0.737,301400,,1
sz159967,0.87,264300,,1
sh513300,2.852,68900,,1
sz159516,1.533,56400,,1
sh511380
sh511130
sz002384,211.637,1400
sh600487,96.788,1400
sz002222,100.216,500
sh603052,913.608,300
sh603083,172.778,100
hk01888,48.585,2000,,1
sh600176,39.801,2600
hk02476,412.662,200,,1
sz002463,147.603,200
sh601208,49.788,700
sz001309,-795.177,100
sh605376,191.254,100
sz000636
sz002484
sh603929
sh513130
sz399001
sz300502
sz300394
sh688498
sz300476
sh601138
sh688256
sz300750
sz002980
sz002975
sz001267
sz000988
sz003031
sh603306
sh601231
sz002281
sz002428
sh600330
sh601869
sh600522
sh603986
sz301308
sh688525
sz002916
sz002938
sz001389
sh603256
sz002080
sz001359
sz301526
sz300395
sh600183
sz002636
sh603186
sh605589
sz002008
sz301200
sh688630
sz300408
sz002353
sh603308
sz002364
sz002851
sz002837
sz002536
sh603757
sz000657
sz301377
sh600110
sz301511
sz301217
sh603601
sz002361
sz002149
sz002558
sh603259
sh600519
sz000063
sz000725
'@

# Parse lines into items
$items = @()
$codes = @()
foreach ($line in $cfg -split "`n") {
    $l = $line.Trim()
    if ([string]::IsNullOrWhiteSpace($l)) { continue }
    $parts = $l -split ',', -1
    $code = $parts[0].Trim()
    $cost = if ($parts.Count -gt 1) { $parts[1].Trim() } else { "" }
    $bonds = if ($parts.Count -gt 2) { $parts[2].Trim() } else { "" }
    $account = if ($parts.Count -gt 4) { $parts[4].Trim() } else { "" }
    $items += [pscustomobject]@{ code=$code; cost=$cost; bonds=$bonds; account=$account }
    $codes += $code
}

# Prepare query codes: prefix with s_ (e.g., s_sh600000)
$prefixed = $codes | ForEach-Object { "s_$_" }

# Function: safe parse decimal
function Parse-Decimal($s) {
    if ([string]::IsNullOrWhiteSpace($s)) { return $null }
    $t = $s -replace ',', ''
    try { return [decimal]::Parse($t) } catch { return $null }
}

# Query in batches (20 per batch like plugin)
$batchSize = 20
$snap = @{}
for ($i = 0; $i -lt $prefixed.Count; $i += $batchSize) {
    $end = [math]::Min($i + $batchSize - 1, $prefixed.Count - 1)
    $batch = $prefixed[$i..$end]
    $url = "https://qt.gtimg.cn/q=" + ($batch -join ',')
    Write-Host "Requesting batch: $($batch -join ',')"
    try {
        $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 15
        $content = $resp.Content
        $lines = $content -split "`n"
        foreach ($ln in $lines) {
            $line = $ln.Trim()
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $eqIdx = $line.IndexOf('=')
            if ($eqIdx -lt 0) { continue }
            $usIdx = $line.LastIndexOf('_')
            if ($usIdx -lt 0) { continue }
            $code = $line.Substring($usIdx + 1, $eqIdx - $usIdx - 1)
            $firstQ = $line.IndexOf('"')
            $lastQ = $line.LastIndexOf('"')
            if ($firstQ -lt 0 -or $lastQ -le $firstQ) { continue }
            $dataStr = $line.Substring($firstQ + 1, $lastQ - $firstQ - 1)
            $values = $dataStr -split '~'
                        # defensive checks
            if ($values.Count -lt 6) { continue }
            $name = $values[1]
            $now = $values[3]
            $change = if ($values.Count -gt 31) { $values[31] } else { '' }
            $amountWan = if ($values.Count -gt 37) { $values[37] } else { '' }
            $snap[$code] = [pscustomobject]@{ name=$name; now=$now; change=$change; amountWan=$amountWan }
        }
    } catch {
        Write-Host ("Request failed for batch starting at index {0}: {1}" -f $i, $_)
    }
    Start-Sleep -Milliseconds 150
}

# Aggregate per account
$accounts = @{}
foreach ($item in $items) {
    $code = $item.code
    $entry = $snap[$code]
    if ([string]::IsNullOrWhiteSpace($item.bonds)) { continue }
    $bondsVal = Parse-Decimal($item.bonds)
    if ($bondsVal -eq $null -or $bondsVal -le 0) { continue }
    $acct = if (-not [string]::IsNullOrWhiteSpace($item.account)) { $item.account } else { '1' }
    # normalise numeric account
    try { $acct = [int]$acct } catch { $acct = $item.account }

    $nowVal = $null; $changeVal = $null
    if ($entry) {
        $nowVal = Parse-Decimal($entry.now)
        $changeVal = Parse-Decimal($entry.change)
    }
    $totalAmount = $null; $dayGain = $null; $cumProfit = $null; $dayGainRate = $null
    if ($nowVal -ne $null) {
        $totalAmount = [decimal]::Round($bondsVal * $nowVal, 2)
        if ($changeVal -ne $null) { $dayGain = [decimal]::Round($bondsVal * $changeVal, 2) }
    }
    if ($nowVal -ne $null -and -not [string]::IsNullOrWhiteSpace($item.cost)) {
        $costVal = Parse-Decimal($item.cost)
        if ($costVal -ne $null) { $cumProfit = [decimal]::Round($bondsVal * ($nowVal - $costVal), 2) }
    }
    if ($totalAmount -ne $null -and $dayGain -ne $null -and ($totalAmount - $dayGain) -gt 0) {
        $dayGainRate = [decimal]::Round(($dayGain / ($totalAmount - $dayGain)) * 100, 4)
    }

    if (-not $accounts.ContainsKey($acct)) {
        $accounts[$acct] = [pscustomobject]@{ totalAmount = 0; dayGain = 0; cumProfit = 0; positions = @() }
    }
    $rec = $accounts[$acct]
    if ($totalAmount -ne $null) { $rec.totalAmount = [decimal]::Round($rec.totalAmount + $totalAmount, 2) }
    if ($dayGain -ne $null) { $rec.dayGain = [decimal]::Round($rec.dayGain + $dayGain, 2) }
    if ($cumProfit -ne $null) { $rec.cumProfit = [decimal]::Round($rec.cumProfit + $cumProfit, 2) }
    $rec.positions += [pscustomobject]@{ code=$code; name=($entry.name -as [string]); bonds=$bondsVal; now=$nowVal; change=$changeVal; totalAmount=$totalAmount; dayGain=$dayGain; cumProfit=$cumProfit; dayGainRate=$dayGainRate }
}

# Print summary
if ($accounts.Keys.Count -eq 0) { Write-Host "No holdings detected (or all zero)."; exit }
foreach ($k in $accounts.Keys | Sort-Object { [int]$_ }) {
    $v = $accounts[$k]
    Write-Host ("`nAccount {0}:" -f $k)
    Write-Host ("  TotalAmount: {0}" -f ([string]::Format('{0:N2}', $v.totalAmount)))
    Write-Host ("  DayGain: {0}" -f ([string]::Format('{0:N2}', $v.dayGain)))
    Write-Host ("  CumProfit: {0}" -f ([string]::Format('{0:N2}', $v.cumProfit)))
    $rate = 0
    if ($v.totalAmount -ne 0) { $rate = [decimal]::Round(($v.dayGain / $v.totalAmount) * 100, 4) }
    Write-Host ("  DayGainRate (by total): {0}%" -f ([string]::Format('{0:N4}', $rate)))
    Write-Host "  Positions:"
    foreach ($p in $v.positions) {
        $nowStr = if ($p.now -ne $null) { $p.now } else { '--' }
        $ta = if ($p.totalAmount -ne $null) { $([string]::Format('{0:N2}', $p.totalAmount)) } else { '--' }
        $dg = if ($p.dayGain -ne $null) { $([string]::Format('{0:N2}', $p.dayGain)) } else { '--' }
        Write-Host ("    {0} {1} bonds {2} price {3} total {4} dayGain {5}" -f $p.code, $p.name, $p.bonds, $nowStr, $ta, $dg)
    }
}

# List codes without snapshot
$missing = @()
foreach ($it in $items) { if (-not $snap.ContainsKey($it.code)) { $missing += $it.code } }
if ($missing.Count -gt 0) { Write-Host ("`nMissing snapshot or parse failed for codes: {0}" -f ($missing -join ',')) }

# Export aggregated result to JSON (UTF-8) for programmatic consumption
try {
    $outPath = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'query_result.json'
    $export = @()
    foreach ($k in $accounts.Keys) {
        $v = $accounts[$k]
        $export += [pscustomobject]@{ account = $k; totalAmount = $v.totalAmount; dayGain = $v.dayGain; cumProfit = $v.cumProfit; positions = $v.positions }
    }
    $accountsJson = $export | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($outPath, $accountsJson, [System.Text.Encoding]::UTF8)
    Write-Host "Wrote JSON to: $outPath"
} catch {
    Write-Host ("Failed to write JSON: {0}" -f $_)
}

# End
