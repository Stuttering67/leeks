$base='https://image.sinajs.cn/newchart'
$types = @('min','daily','weekly','monthly')
$codes = @('sh600519','sz000001','hk00700')
$ts = [int][double]::Parse((Get-Date -UFormat %s))
foreach($code in $codes) {
    Write-Output "\n=== $code ==="
    $prefix = $code.Substring(0,2)
    $suffix = if ($code.Length -gt 2) { $code.Substring(2) } else { $code }
    foreach ($type in $types) {
        Write-Output "-- $type --"
        $cands = @()
        if ($prefix -eq 'sh' -or $prefix -eq 'sz') {
            $cands += "$base/$type/n/$code.gif?$ts"
            $cands += "$base/$type/$code.gif?$ts"
            $cands += "$base/png/$type/$code.png?$ts"
            $cands += "$base/$type/n/$code.png?$ts"
        } elseif ($prefix -eq 'us') {
            $cands += "$base/png/$type/us/$suffix.png?$ts"
            $cands += "$base/us/$type/$suffix.gif?$ts"
            $cands += "$base/$type/us/$suffix.gif?$ts"
            $cands += "$base/png/$type/$prefix/$suffix.png?$ts"
        } elseif ($prefix -eq 'hk') {
            $cands += "$base/png/$type/hk/$suffix.png?$ts"
            $cands += "$base/hk/$type/$suffix.gif?$ts"
            $cands += "$base/${prefix}_stock/$type/$suffix.gif?$ts"
            $cands += "$base/png/$type/$prefix/$suffix.png?$ts"
        } else {
            $cands += "$base/$type/n/$code.gif?$ts"
            $cands += "$base/png/$type/$code.png?$ts"
        }
        foreach ($u in $cands) {
            try {
                $r = Invoke-WebRequest -Uri $u -Method Head -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
                $ct = $r.Headers['Content-Type']
                Write-Output "$u -> $($r.StatusCode) CT=$ct"
            } catch {
                Write-Output "$u -> ERR: $($_.Exception.Message)"
            }
        }
    }
}
