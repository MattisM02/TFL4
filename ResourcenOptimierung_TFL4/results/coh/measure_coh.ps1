$ProgressPreference = "SilentlyContinue"
New-Item -ItemType Directory -Force -Path results | Out-Null

$Image = "jvm-optim-demo:jvm25-baseline"
$Mem   = "768m"
$Cpu   = "1"
$Port  = 8080
$Runs  = 5

$configs = @(
  @{ id = "coh_off_default"; javaOpts = "" },
  @{ id = "coh_on";          javaOpts = "-XX:+UseCompactObjectHeaders" }
)

foreach ($cfg in $configs) {

  $cfgId   = $cfg.id
  $javaOpt = $cfg.javaOpts

  # Nachweis: Flags (COH/COOPs) dokumentieren
  $tsCfg  = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
  $cfgOut = "results\step_coh_${cfgId}_verify_$tsCfg.txt"

  "=== Config: $cfgId ===" | Out-File -Encoding utf8 $cfgOut
  "Timestamp: $(Get-Date -Format o)" | Out-File -Append $cfgOut
  "Image: $Image" | Out-File -Append $cfgOut
  "JAVA_TOOL_OPTIONS: $javaOpt" | Out-File -Append $cfgOut
  "" | Out-File -Append $cfgOut

  "--- PrintFlagsFinal (filtered) ---" | Out-File -Append $cfgOut
  if ($javaOpt -ne "") {
    docker run --rm --memory $Mem -e JAVA_TOOL_OPTIONS="$javaOpt" --entrypoint java $Image `
      -XX:+PrintFlagsFinal -version 2>&1 | Select-String "UseCompactObjectHeaders|UseCompressedOops|UseCompressedClassPointers" `
      | Out-File -Append $cfgOut
  } else {
    docker run --rm --memory $Mem --entrypoint java $Image `
      -XX:+PrintFlagsFinal -version 2>&1 | Select-String "UseCompactObjectHeaders|UseCompressedOops|UseCompressedClassPointers" `
      | Out-File -Append $cfgOut
  }
  "" | Out-File -Append $cfgOut

  for ($n=1; $n -le $Runs; $n++) {

    $ts   = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
    $name = "demo-$cfgId-$n"
    $out  = "results\step_coh_${cfgId}_run${n}_$ts.txt"

    docker rm -f $name 2>$null | Out-Null

    "=== $cfgId / run $n ===" | Out-File -Encoding utf8 $out
    "Timestamp: $(Get-Date -Format o)" | Out-File -Append $out
    "JAVA_TOOL_OPTIONS: $javaOpt" | Out-File -Append $out
    "" | Out-File -Append $out

    # t0 direkt vor docker run
    $t0 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

    if ($javaOpt -ne "") {
      $cid = docker run -d --name $name --memory $Mem --cpus $Cpu -e JAVA_TOOL_OPTIONS="$javaOpt" -p ${Port}:8080 $Image
    } else {
      $cid = docker run -d --name $name --memory $Mem --cpus $Cpu -p ${Port}:8080 $Image
    }

    "--- docker run ---" | Out-File -Append $out
    $cid | Out-File -Append $out
    "" | Out-File -Append $out

    # Readiness polling (max 120s)
    $url = "http://127.0.0.1:$Port/actuator/health/readiness"
    $deadline = (Get-Date).AddSeconds(120)

    while ($true) {
      if ((Get-Date) -gt $deadline) {
        "ERROR: readiness not reached within 120s" | Out-File -Append $out
        docker logs --tail 200 $name | Out-File -Append $out
        docker rm -f $name | Out-Null
        throw "Readiness timeout (120s)."
      }
      $status = (curl.exe -s -o NUL -w "%{http_code}" $url)
      if ($status -ge 200 -and $status -lt 300) { break }
      Start-Sleep -Milliseconds 100
    }

    $tReady  = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $readyMs = $tReady - $t0

    "--- time-to-ready (ms) [/actuator/health/readiness, 2xx only] ---" | Out-File -Append $out
    "ready_ms=$readyMs" | Out-File -Append $out
    "" | Out-File -Append $out

    "--- docker stats (5 samples, 1s) ---" | Out-File -Append $out
    for ($i=1; $i -le 5; $i++) { docker stats --no-stream $name | Out-File -Append $out; Start-Sleep -Seconds 1 }
    "" | Out-File -Append $out

    "--- first /json (traffic-ready) ---" | Out-File -Append $out
    $first = (curl.exe -m 20 --connect-timeout 3 -s -o NUL -w "%{time_total}`n" "http://127.0.0.1:$Port/json")
    "first_json_s=$first" | Out-File -Append $out
    "" | Out-File -Append $out

    "--- warm-up (5x /json) ---" | Out-File -Append $out
    1..5 | ForEach-Object { curl.exe -m 20 --connect-timeout 3 -s -o NUL "http://127.0.0.1:$Port/json" }
    "" | Out-File -Append $out

    "--- /json latency (20 runs, seconds) ---" | Out-File -Append $out
    1..20 | ForEach-Object {
       $t = (curl.exe -m 20 --connect-timeout 3 -s -o NUL -w "%{time_total}" "http://127.0.0.1:$Port/json")
       "run_${_}=$t" | Out-File -Append $out
    }
    "" | Out-File -Append $out

    "--- docker stats after /json runs (no-stream) ---" | Out-File -Append $out
    docker stats --no-stream $name | Out-File -Append $out
    "" | Out-File -Append $out

    "--- docker logs (last 200 lines) ---" | Out-File -Append $out
    docker logs --tail 200 $name | Out-File -Append $out
    "" | Out-File -Append $out

    docker rm -f $name | Out-Null
    Start-Sleep -Seconds 2
  }
}

"Done. Check .\results\step_coh_*"
