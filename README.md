# ResourcenOptimierung TFL4

Benchmarking von JVM-Konfigurationen fuer Spring-Boot-Container-Anwendungen.

Misst den Einfluss verschiedener JVM-Flags, Garbage Collectors, JVM-Implementierungen (HotSpot, OpenJ9, GraalVM) und Laufzeitmodelle (JIT, AOT/Native, CDS) auf Startzeit, Latenzen, Durchsatz, Ressourcenverbrauch und GC-Verhalten in Docker-Containern mit festen Limits (`--cpus 1`, `--memory 768m`, kein Swap).

Besteht aus:
1. **Spring-Boot-App** (System Under Test) -- REST-API mit synthetischen Workloads und optionaler EBICS-Banking-Anbindung
2. **Benchmark-CLI** -- startet die App in Docker-Containern, fuehrt Messungen durch, exportiert Ergebnisse (CSV, JSON, Excel mit Charts)

---

## Voraussetzungen

- Java JDK 17+ (Compile), JDK 25 (Container-Runtime)
- Docker 20+
- Maven Wrapper liegt im Repo (`./mvnw` / `mvnw.cmd`)

---

## Schnellstart

```powershell
# JAR bauen
.\mvnw clean package -DskipTests

# Docker-Images werden automatisch gebaut beim ersten Benchmark-Run.
# Oder manuell:
docker build -t tfl4-ek-bench:jvm .

# 401 Unit Tests
.\mvnw test

# 4 Docker-E2E-Tests (braucht Docker + gebautes Image)
.\mvnw test -DincludeDocker

# Benchmark interaktiv starten (32 Konfigurationen, 3 Wiederholungen)
.\mvnw exec:java

# Nicht-interaktiv
.\mvnw exec:java -Dexec.args="--scenario json --n 200000"

# Schnelldurchlauf (10 Warmup, 30 Mess-Requests, 1 Wiederholung)
.\mvnw exec:java -Dexec.args="--scenario json --quick"

# Ultra-leichter Smoke-Test (3 Warmup, 5 Mess-Requests, 1 Wiederholung)
.\mvnw exec:java -Dexec.args="--scenario json --smoke"

# Nur die 12 Laufzeitprofile (statt 20+12=32 Konfigurationen)
.\mvnw exec:java -Dexec.args="--scenario json --profiles"

# Einzelner Run mit ZGC
.\mvnw exec:java -Dexec.args="--scenario json --jvmArgs \"-XX:+UseZGC\""

# Excel aus vorhandenen CSVs regenerieren (kein Benchmark)
.\bench --merge-excel
```

---

## Zwei-Ebenen-Analyse

Das Framework verwendet eine **zweistufige Analyse**, um JVM-Konfigurationen systematisch zu bewerten:

### Level 1: Flag-Analyse (20 Konfigurationen)

Vergleicht 20 HotSpot-Konfigurationen auf **demselben** Docker-Image (`tfl4-ek-bench:jvm`, Temurin JRE 25). Unterschiede entstehen ausschliesslich durch JVM-Flags. Gruppiert in:

- **GC-Vergleich** (5): baseline, zgc, shenandoah, parallel-gc, serial-gc
- **G1-Tuning** (3): g1-low-pause, g1-heap-256m, g1-heap-512m
- **JVM-Interna** (2): coops-off, coh-on
- **Cloud-relevant** (2): ram-percentage-75, tiered-stop-1
- **Flag-Kombinationen** (8): serial-gc-256m, zgc-heap-512m, shenandoah-heap-512m, tiered-stop-1-serial, g1-coh-on, parallel-gc-256m, g1-large-young, zgc-tiered-stop-1

### Level 2: Laufzeitprofile (12 Konfigurationen)

Vergleicht 12 standardisierte Profile (P01-P12), die verschiedene **JVM-Implementierungen** und **Laufzeitmodelle** mit unterschiedlichen Docker-Images nutzen:

- **HotSpot** (P01-P03, P09): Standard, Fast-Startup, Low-Latency, Heap-256m
- **OpenJ9** (P04, P06-P08, P10): gencon, balanced, optthruput, optavgpause, Heap-256m
- **GraalVM Native Image** (P05): AOT-kompiliert, kein JVM-Overhead
- **HotSpot + CDS** (P11): Dynamic Class Data Sharing fuer schnelleren Startup
- **GraalVM JIT** (P12): JVMCI-basierter JIT-Compiler

### Standard-Durchlauf

Per Default werden **beide Ebenen kombiniert** ausgefuehrt: 20 + 12 = **32 Konfigurationen**. Mit `--profiles` werden nur die 12 Profile ausgefuehrt.

---

## Die 20 Flag-Analyse-Konfigurationen (Level 1)

Alle verwenden das Image `tfl4-ek-bench:jvm` (Eclipse Temurin JRE 25).

### Garbage-Collector-Vergleich

| Config | JVM-Flags | Beschreibung |
|--------|-----------|-------------|
| `baseline` | *(keine)* | G1GC (Default seit Java 9). Referenzpunkt fuer alle Vergleiche. |
| `zgc` | `-XX:+UseZGC` | Z Garbage Collector. Seit JDK 24 ausschliesslich generational. Sub-Millisekunden-Pausen, concurrent, colored pointers. Hoehere CPU-Last. |
| `shenandoah` | `-XX:+UseShenandoahGC` | Shenandoah GC. Concurrent compacting, Brooks forwarding pointers. Aehnliche Ziele wie ZGC, anderer Ansatz. |
| `parallel-gc` | `-XX:+UseParallelGC` | Parallel/Throughput Collector. Stop-the-World mit mehreren GC-Threads. Maximiert Durchsatz, laengere Pausen. |
| `serial-gc` | `-XX:+UseSerialGC` | Serial Collector. Single-Thread, minimaler Overhead. Potentiell effizient bei `--cpus 1`. |

### G1-Tuning

| Config | JVM-Flags | Beschreibung |
|--------|-----------|-------------|
| `g1-low-pause` | `-XX:+UseG1GC -XX:MaxGCPauseMillis=50` | G1 mit aggressivem Pausenziel (50ms statt Default 200ms). Haeufigere, kleinere Collections. |
| `g1-heap-256m` | `-Xmx256m` | G1 mit eingeschraenktem Heap (256 MB bei 768 MB Container). Erzwingt haeufigere GC-Zyklen. |
| `g1-heap-512m` | `-Xmx512m` | G1 mit mittlerem Heap (512 MB). Vergleichspunkt zwischen 256 MB und Default-Ergonomics. |

### JVM-Interna

| Config | JVM-Flags | Beschreibung |
|--------|-----------|-------------|
| `coops-off` | `-XX:-UseCompressedOops` | Compressed Oops deaktiviert. 64-Bit-Referenzen statt 32-Bit. Groesserer Footprint, hoehere Cache-Miss-Rate. |
| `coh-on` | `-XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders` | Compact Object Headers (JEP 450). Object-Header von 12-16 Byte auf 8 Byte reduziert. Experimentell. |

### Cloud-relevante Konfigurationen

| Config | JVM-Flags | Beschreibung |
|--------|-----------|-------------|
| `ram-percentage-75` | `-XX:MaxRAMPercentage=75` | Container-aware Heap-Sizing. 75% des cgroup-Limits (~576 MB bei 768 MB Container). |
| `tiered-stop-1` | `-XX:TieredStopAtLevel=1` | Nur C1-Compiler, kein C2. Drastisch schnellerer Startup, geringerer Peak-Durchsatz. |

### Flag-Kombinationen

| Config | JVM-Flags | Beschreibung |
|--------|-----------|-------------|
| `serial-gc-256m` | `-XX:+UseSerialGC -Xmx256m` | Minimaler Footprint: Single-Thread-GC + kleiner Heap. |
| `zgc-heap-512m` | `-XX:+UseZGC -Xmx512m` | ZGC mit mehr Spielraum fuer concurrent GC. |
| `shenandoah-heap-512m` | `-XX:+UseShenandoahGC -Xmx512m` | Shenandoah mit mehr Spielraum fuer concurrent GC. |
| `tiered-stop-1-serial` | `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` | C1-only + Serial GC: schnellster Start + geringster GC-Overhead auf 1 CPU. |
| `g1-coh-on` | `-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders` | G1 + Compact Object Headers: reduziert Objekt-Overhead und GC-Druck. |
| `parallel-gc-256m` | `-XX:+UseParallelGC -Xmx256m` | Durchsatz-GC mit kleinem Heap. |
| `g1-large-young` | `-XX:+UseG1GC -XX:NewRatio=1` | G1 mit 50% Young Gen. Weniger Full GCs erwartet bei alloc-lastigen Workloads. |
| `zgc-tiered-stop-1` | `-XX:+UseZGC -XX:TieredStopAtLevel=1` | ZGC + C1-only: niedrige Pausen mit schnellem Start (Serverless/Cold-Start). |

---

## Die 12 Laufzeitprofile (Level 2)

| Profil | Laufzeit | Docker-Image | JVM-Flags | Beschreibung |
|--------|----------|-------------|-----------|-------------|
| `P01-hotspot-standard` | HOTSPOT | `jvm` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75` | Standard-Cloud-Deployment |
| `P02-hotspot-fast-startup` | HOTSPOT | `jvm` | `-XX:+UseG1GC -XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=75` | Serverless/Cold-Start-optimiert |
| `P03-hotspot-low-latency` | HOTSPOT | `jvm` | `-XX:+UseZGC -XX:MaxRAMPercentage=75` | Sub-Millisekunden-Pausen |
| `P04-openj9-low-memory` | OPENJ9 | `openj9` | `-XX:MaxRAMPercentage=75` | OpenJ9 gencon GC, Memory-optimiert |
| `P05-native` | NATIVE | `native` | *(keine)* | GraalVM Native Image, kein JVM-Overhead |
| `P06-openj9-balanced` | OPENJ9 | `openj9` | `-Xgcpolicy:balanced -XX:MaxRAMPercentage=75` | Region-basiert, NUMA-aware |
| `P07-openj9-optthruput` | OPENJ9 | `openj9` | `-Xgcpolicy:optthruput -XX:MaxRAMPercentage=75` | Durchsatz-optimiert |
| `P08-openj9-optavgpause` | OPENJ9 | `openj9` | `-Xgcpolicy:optavgpause -XX:MaxRAMPercentage=75` | Pausen-optimiert |
| `P09-hotspot-heap-256m` | HOTSPOT | `jvm` | `-XX:+UseG1GC -Xmx256m` | Speicher-limitiert |
| `P10-openj9-heap-256m` | OPENJ9 | `openj9` | `-Xmx256m` | Speicher-limitiert |
| `P11-hotspot-cds` | HOTSPOT | `jvm-cds` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75` | Dynamic CDS, Startup-optimiert |
| `P12-graalvm-jit` | HOTSPOT | `graalvm-jit` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75` | GraalVM JVMCI JIT-Compiler |

JVM-Flags werden ueber `JAVA_TOOL_OPTIONS` an den Container uebergeben. GC-Logging wird automatisch injiziert: `-Xlog:gc*:stdout` (HotSpot) bzw. `-verbose:gc` (OpenJ9). Bei Native Images wird `JAVA_TOOL_OPTIONS` nicht gesetzt.

---

## Docker-Images

Das Framework nutzt 10 Docker-Images (5 Standard + 5 EBICS-Varianten mit `-ek`-Suffix):

| Tag | Dockerfile | Base Image | Beschreibung |
|-----|-----------|------------|-------------|
| `tfl4-ek-bench:jvm` | `Dockerfile` | `eclipse-temurin:25-jre` | HotSpot Baseline |
| `tfl4-ek-bench:jvm-ek` | `Dockerfile.with-ek` | `eclipse-temurin:25-jre` | HotSpot + EBICS-Kernel |
| `tfl4-ek-bench:openj9` | `Dockerfile.openj9` | `ibm-semeru-runtimes:open-25-jre` | Eclipse OpenJ9 |
| `tfl4-ek-bench:openj9-ek` | `Dockerfile.openj9.with-ek` | `ibm-semeru-runtimes:open-25-jre` | OpenJ9 + EBICS-Kernel |
| `tfl4-ek-bench:native` | `Dockerfile.native` | `ghcr.io/graalvm/native-image-community:25` | GraalVM Native Image (Multi-Stage) |
| `tfl4-ek-bench:native-ek` | `Dockerfile.native.with-ek` | *(gleich)* | Native + EBICS-Kernel |
| `tfl4-ek-bench:graalvm-jit` | `Dockerfile.graalvm-jit` | `ghcr.io/graalvm/jdk-community:25` | GraalVM JIT (JVMCI) |
| `tfl4-ek-bench:graalvm-jit-ek` | `Dockerfile.graalvm-jit.with-ek` | *(gleich)* | GraalVM JIT + EBICS-Kernel |
| `tfl4-ek-bench:jvm-cds` | `Dockerfile.cds` | `eclipse-temurin:25-jre` | Dynamic CDS (2-Stage-Build: Training + Archive) |
| `tfl4-ek-bench:jvm-cds-ek` | `Dockerfile.cds.with-ek` | *(gleich)* | CDS + EBICS-Kernel |

Docker-Images werden **automatisch** gebaut, wenn sie beim Benchmark-Start nicht lokal vorhanden sind (`DockerImageBuilder`). Mit `--rebuild` wird der Neuaufbau erzwungen.

Fuer EBICS-Szenarien wird automatisch das `-ek`-Suffix angehaengt (via `withEbicsImages()`).

---

## Projektstruktur

```
TFL4/
├── pom.xml                          # Build-Konfiguration (Spring Boot 4.0.0, Java 17)
├── mvnw / mvnw.cmd                  # Maven Wrapper
├── Dockerfile                       # HotSpot Baseline (Temurin JRE 25)
├── Dockerfile.with-ek               # HotSpot + EBICS-Kernel
├── Dockerfile.openj9                # OpenJ9 (IBM Semeru JRE 25)
├── Dockerfile.openj9.with-ek        # OpenJ9 + EBICS-Kernel
├── Dockerfile.native                # GraalVM Native Image (Multi-Stage)
├── Dockerfile.native.with-ek        # Native + EBICS-Kernel
├── Dockerfile.graalvm-jit           # GraalVM JIT (JVMCI)
├── Dockerfile.graalvm-jit.with-ek   # GraalVM JIT + EBICS-Kernel
├── Dockerfile.cds                   # Dynamic CDS (2-Stage Training)
├── Dockerfile.cds.with-ek           # CDS + EBICS-Kernel
│
├── src/main/java/de/mattis/
│   ├── jvmoptimdemo/
│   │   ├── ResourcenOptimierungTfl4Application.java
│   │   ├── DemoController.java      # /json, /alloc
│   │   └── EkController.java        # /ebics/upload, /ebics/health, /ebics/stats
│   │
│   └── resourcenoptimierung/bench/
│       ├── BenchCli.java            # CLI-Einstiegspunkt
│       ├── BenchmarkPlan.java       # 20 Default-Configs + 12 Profile + combinedPlan()
│       ├── BenchmarkConfig.java     # Name + Docker-Image + JVM-Flags + RuntimeType
│       ├── BenchmarkScenario.java   # Enum: JSON, ALLOC, EBICS_UPLOAD
│       ├── BenchmarkRunner.java     # Iteriert ueber Plan, delegiert an SingleRun
│       ├── SingleRun.java           # Container-Lifecycle + Messung + GC-Log-Erfassung
│       ├── MeasurementProfile.java  # Warmup/Messung/Concurrency/Sleep
│       ├── RuntimeType.java         # Enum: HOTSPOT, OPENJ9, NATIVE
│       ├── ReadinessProber.java     # Fallback-Kette fuer Readiness-Erkennung
│       ├── ReadinessCheckUsed.java  # Enum: ACTUATOR_READINESS, ACTUATOR_HEALTH, WORKLOAD_UNTIL_200
│       ├── RunResult.java           # Record mit allen Messwerten eines Runs
│       ├── DockerStatSample.java    # Einzelner docker-stats-Snapshot
│       ├── DockerImageBuilder.java  # Automatischer Image-Build (10 Images, Maven-Package)
│       ├── BenchStats.java          # Statistische Hilfsmethoden (CI, Stddev, t-Verteilung)
│       ├── GcLogParser.java         # HotSpot GC-Log-Parsing (-Xlog:gc*:stdout)
│       ├── OpenJ9GcLogParser.java   # OpenJ9 verbose:gc XML-Parsing
│       ├── GcSummary.java           # GC-Zusammenfassung (Pausen, Overhead, Peak Heap)
│       ├── ConsoleSummaryPrinter.java
│       ├── ResultExporters.java     # CSV + JSON Export
│       ├── ExcelExporter.java       # Excel-Export (~2000 Zeilen) mit Charts + CSV-Merge
│       └── TravicLinkManager.java   # docker-compose Lifecycle fuer TravicLink (EBICS)
│
├── src/test/java/de/mattis/        # 401 Tests (19 Testklassen)
├── lib/                             # EK-JARs (system scope, ~30 JARs + 2 DLLs)
├── ebics/                           # EBICS-Client-Config + PKCS#12-Schluessel
├── bench-docker/docker-compose.yml  # PostgreSQL 16 + TravicLink (EBICS-Bankserver)
├── bench-results/                   # Ergebnisse (CSV, JSON, Excel, GC-Logs)
└── target/                          # Build-Output (~54 MB Fat JAR)
```

---

## REST-Endpunkte

### DemoController

| Endpunkt | Verhalten | Default-n |
|----------|-----------|-----------|
| `GET /json?n=` | Erzeugt `n` UserDto-Objekte, gibt JSON-Array zurueck. Stresst Objekterzeugung + Serialisierung. | 200.000 |
| `GET /alloc?n=` | Erzeugt `n` kurzlebige `byte[128]` in 50k-Chunks, gibt `"ok <sum>"` zurueck. Stresst GC + Heap-Layout. | 10.000.000 |

### EkController

| Endpunkt | Verhalten |
|----------|-----------|
| `GET /ebics/upload?n=` | Fuehrt `n` EBICS-Uploads durch (REAL oder SIMULATION) |
| `GET /ebics/health` | Status: mode, ekAvailable, ekInitialized, initError, requestCount |
| `GET /ebics/stats` | Zaehler: totalRequests, totalUploads |

### Actuator

```
GET /actuator/health              # Health-Check
GET /actuator/health/readiness    # Readiness-Probe (fuer Benchmark-Readiness-Erkennung)
GET /actuator/health/liveness     # Liveness-Probe
```

---

## Benchmark-CLI

### Ablauf

`BenchCli.main()` -> `resolvePlan()` -> `DockerImageBuilder.ensureImagesExist()` -> `BenchmarkRunner.runAll()` -> je `SingleRun.execute()`:

```
 1. docker run -d -p 8080:8080 --cpus 1 --memory 768m --memory-swap 768m
    [-e JAVA_TOOL_OPTIONS="<gc-logging> <flags>"] <image>
 2. Startup-Logs einsammeln (best-effort, max 200 Zeilen)
 3. Readiness abwarten (Fallback-Kette: /actuator/health/readiness -> /actuator/health -> Workload-Endpoint)
 4. IDLE docker-stats (3 Snapshots, 1s Intervall)
 5. LOAD docker-stats starten (10 Snapshots parallel zur Messphase)
 6. First Request messen -> firstSeconds
 7. Warmup (default 200 Requests)
 8. Messphase (default 500 Requests) -> latenciesSeconds[], totalMeasureTimeSeconds, throughputReqPerSec
 9. POST docker-stats (3 Snapshots)
10. GC-Log erfassen: Rohlogs speichern (bench-results/gc-logs/) + parsen (GcLogParser/OpenJ9GcLogParser)
11. Container stoppen + entfernen
```

### Wiederholungen und Randomisierung

Bei `--repetitions N` (Default: 3) wird jede Konfiguration N-mal ausgefuehrt. Pro Durchlauf wird die Reihenfolge **randomisiert** (`Collections.shuffle()`). Die Aggregation berechnet Mittelwert +/- Standardabweichung und 95%-Konfidenzintervalle (t-Verteilung, `BenchStats`).

### Szenarien

| CLI-Wert | Enum | Endpoint | Default-n |
|----------|------|----------|-----------|
| `json` | `PAYLOAD_HEAVY_JSON` | `/json?n=` | 200.000 |
| `alloc` | `ALLOC_HEAVY_OK` | `/alloc?n=` | 10.000.000 |
| `ebics-upload` | `EBICS_UPLOAD` | `/ebics/upload?n=` | 10 |

Auch akzeptiert: `payload`, `payload-heavy-json`, `/json`, `ok`, `upload`, `ebics`, etc.

### CLI-Argumente

| Argument | Default | Beschreibung |
|----------|---------|-------------|
| `--scenario` | interaktiv | json / alloc / ebics-upload |
| `--n` | szenarioabhaengig | Workload-Groesse |
| `--warmupRequests` | 200 | Aufwaerm-Requests |
| `--measureRequests` | 500 | Mess-Requests |
| `--concurrency` | 1 | Parallele Requests |
| `--sleepBetweenRequestsMs` | 0 | Pause zwischen Requests (ms) |
| `--repetitions` | 3 (1 bei --quick/--smoke) | Wiederholungen pro Konfiguration |
| `--jvmArgs` | -- | JVM-Flags fuer einen einzelnen Run (ueberschreibt Plan) |
| `--configName` | `cli-custom` | Name der Konfiguration (nur mit `--jvmArgs`) |
| `--dockerImage` | `tfl4-ek-bench:jvm` | Docker-Image (nur mit `--jvmArgs`) |
| `--profiles` | -- | Nur 12 Laufzeitprofile statt kombiniertem Plan (32 Configs) |
| `--rebuild` | -- | Erzwingt Neuaufbau von Maven-JAR und Docker-Images |
| `--skipTravicLink` | -- | TravicLink nicht automatisch starten |
| `--merge-excel` | -- | Alle CSVs aus bench-results/ zu benchmark-vergleich.xlsx zusammenfuehren (kein Benchmark) |
| `--quick` | -- | Schnelldurchlauf: 10 Warmup, 30 Mess-Requests, 1 Wiederholung |
| `--smoke` | -- | Ultra-leichter Smoke-Test: 3 Warmup, 5 Mess-Requests, 1 Wdh. Nur Pipeline-Validierung. `--smoke` hat Vorrang vor `--quick`. |

Beide Formen: `--scenario json` und `--scenario=json`. Explizite CLI-Werte ueberschreiben Quick/Smoke-Defaults.

### Beispiele

```powershell
# Kombinierter Plan (20 + 12 = 32 Konfigurationen, 3 Wiederholungen):
.\bench --scenario json --n 200000

# Nur Laufzeitprofile (12 Profile):
.\bench --scenario json --profiles

# Schnelldurchlauf:
.\bench --scenario json --quick

# Ultra-leichter Smoke-Test:
.\bench --scenario json --smoke

# Quick mit mehr Mess-Requests (Quick-Defaults als Basis, measureRequests ueberschrieben):
.\bench --scenario alloc --quick --measureRequests 100

# 5 Wiederholungen mit EBICS-Szenario:
.\bench --scenario ebics-upload --repetitions 5 --skipTravicLink

# Einzelner Run mit ZGC:
.\bench --scenario json --jvmArgs "-XX:+UseZGC -Xmx1g" --configName zgc-test

# Einzelner Run ohne Flags (Baseline):
.\bench --scenario alloc --jvmArgs ""

# Nur CSV-Merge (kein Benchmark-Durchlauf):
.\bench --merge-excel

# Neuaufbau aller Images erzwingen:
.\bench --scenario json --smoke --rebuild
```

---

## Metriken-Referenz

| Metrik | Einheit | Beschreibung |
|--------|---------|-------------|
| `readinessMs` | ms | docker run -> Service ready (inkl. Container-Overhead) |
| `firstSeconds` | s | Erster Request nach Readiness (Cold-Path: JIT, Lazy Init) |
| `latencyP50/P95/P99` | s | Perzentile der Mess-Requests |
| `latencyMean` | s | Arithmetisches Mittel aller Mess-Latenzen |
| `throughputReqPerSec` | req/s | measureRequests / totalMeasureTimeSeconds |
| `totalMeasureTimeSeconds` | s | Wandzeit der gesamten Messphase |
| `cpuLoadAvg` | % | Mittlere CPU% waehrend LOAD-Phase |
| `memLoadAvg` | % | Mittlere Mem% waehrend LOAD-Phase |
| `memLoadMax` | % | Maximale Mem% waehrend LOAD-Phase |
| `docker CPU%` | % | Container-CPU (IDLE/LOAD/POST), kann >100% bei Multi-Thread-GC |
| `docker Mem%` | % | Speicher relativ zum Limit (768 MB) |
| `gcCount` | int | Anzahl GC-Pausen (aus GC-Log geparst) |
| `fullGcCount` | int | Anzahl Full GCs |
| `totalPauseMs` | ms | Kumulierte GC-Pausenzeit |
| `maxPauseMs` | ms | Laengste einzelne GC-Pause |
| `gcOverheadPercent` | % | GC-Pausenzeit / Gesamtlaufzeit |
| `peakHeapAfterGcMb` | MB | Peak Heap-Auslastung nach GC |
| `readinessCheckUsed` | enum | Welcher Probe-Typ gegriffen hat |
| `repetition` | int | Wiederholungsnummer (1-basiert) |

---

## GC-Log-Erfassung und -Auswertung

GC-Logging wird **automatisch** injiziert und **vollstaendig** ausgewertet:

1. **HotSpot**: `-Xlog:gc*:stdout` -> `GcLogParser.java` parst Unified Logging (JEP 158)
2. **OpenJ9**: `-verbose:gc` -> `OpenJ9GcLogParser.java` parst XML-Format (gencon, balanced, optavgpause, optthruput)
3. **Native**: Kein GC-Logging (kein JAVA_TOOL_OPTIONS)

Pro Run werden gespeichert:
- **Rohlog**: `bench-results/gc-logs/<config>-rep<n>.log` (fuer GCViewer/GCEasy)
- **GcSummary**: gcCount, fullGcCount, totalPauseMs, maxPauseMs, gcOverheadPercent, peakHeapAfterGcMb
- **GC-Events**: Einzelne Pausen mit Zeitstempel (fuer GC-Timeline-Chart)

---

## Ergebnis-Export

### Einzelner Run

- **Konsole**: Gruppiert nach Szenario, Readiness/First/Latenz-Perzentile/Throughput/GC, bei Wiederholungen Aggregation mit Mittelwert +/- Stddev
- **CSV** (`bench-results/results-<timestamp>.csv`): Eine Zeile pro Run, alle Kennzahlen + GC-Metriken + Messprofil
- **JSON** (`bench-results/results-<timestamp>.json`): Wie CSV, plus rohe Latenz-Arrays
- **Excel** (`bench-results/results-<timestamp>.xlsx`): **7-Sheet-Workbook**:

| Sheet | Inhalt | Diagramme |
|-------|--------|-----------|
| **Uebersicht** | Alle Messwerte, AutoFilter, Freeze-Pane, Section-Headers | -- |
| **Latenzen** | p50/p95/p99/Mean pro Config | Gruppiertes Balkendiagramm mit 95%-CI-Fehlerbalken |
| **Startup & Throughput** | Readiness, First, Throughput | Balkendiagramme mit CI-Fehlerbalken |
| **Ressourcen** | CPU%/Mem% IDLE/LOAD/POST | Gruppiertes Balkendiagramm mit CI |
| **Rohdaten** | Alle Einzellatenzen | -- |
| **GC-Zusammenfassung** | GC-Metriken tabellarisch | Logarithmisches Balkendiagramm (GC-Pausen) |
| **GC-Timeline** | GC-Pausen ueber Zeit | Scatter-Chart (gerade Linien, kein smooth) |

### Vergleichs-Excel (Merge)

- **Automatisch** nach jedem Benchmark: `benchmark-vergleich.xlsx`
- **Standalone**: `.\bench --merge-excel`
- **6-Sheet-Workbook**:

| Sheet | Inhalt |
|-------|--------|
| **Uebersicht alle Runs** | Alle CSVs zusammengefasst |
| **Latenzen alle Runs** | Latenz-Vergleich ueber Runs |
| **Startup alle Runs** | Startup/Throughput-Vergleich |
| **Ressourcen alle Runs** | Ressourcen + GC-Vergleich |
| **Zusammenfassung** | Aggregierte Metriken |
| **Ranking** | Normalisierung auf Baseline (100%) |

### Chart-Features

- **95%-Konfidenzintervall-Fehlerbalken** bei aggregierten Wiederholungen (t-Verteilung)
- **Runtime-Farbkodierung**: Blau = HotSpot, Tuerkis = OpenJ9, Orange = Native
- **Logarithmische Y-Achse** fuer GC-Pausen-Diagramm
- **AutoFilter + Freeze-Pane** auf allen Datensheets

---

## Tests

### Ausfuehren

```powershell
.\mvnw test                  # 401 Unit Tests (kein Docker noetig)
.\mvnw test -DincludeDocker  # 4 Docker-E2E-Tests (braucht Docker + gebautes Image)
```

### Abdeckung

| Klasse | Tests | Schwerpunkt |
|--------|-------|-------------|
| BenchCliTest | 49+ | Argument-Parsing, Szenario-Aufloesung, --quick, --smoke, --profiles, EBICS-Images |
| BenchmarkPlanTest | 22 | defaultPlan (20 Configs), profilePlan (12 Profile), combinedPlan, withEbicsImages |
| MeasurementProfileTest | 11+ | defaults, quickDefaults, smokeDefaults, Validierung |
| BenchmarkConfigTest | 8 | RuntimeType, Record-Felder |
| DockerStatSampleTest | 8 | Parsing realer docker-stats-Ausgaben |
| RunResultTest | 3 | Record-Zugriff |
| ConsoleSummaryPrinterTest | 12 | Formatierung, Sortierung, Wiederholungs-Aggregation |
| ResultExportersTest | 11 | CSV/JSON-Korrektheit |
| ExcelExporterTest | 22 | writeExcel (7 Sheets, Charts), mergeFromCsvDirectory, GC-Sheets |
| TravicLinkManagerTest | 4 | isEbicsScenario(), Konstruktion |
| DemoControllerTest | 7 | /json + /alloc via MockMvc |
| EkControllerTest | 10 | /ebics/* via MockMvc + maskSensitive() |
| DockerEndToEndTest | 4 | Volle SingleRun-Durchlaeufe mit echten Containern |

---

## EBICS-Integration

Der EkController nutzt den Travic EBICS Kernel (EK 4.0.9, PPI AG) ausschliesslich ueber **Reflection**, damit die App auch ohne die proprietaeren JARs kompiliert.

### Modi

| Modus | Bedingung | Verhalten |
|-------|-----------|-----------|
| **REAL** | EK-JARs + gueltige Lizenz + Bankserver | Echte EBICS-Kommunikation (HPB, Upload via pain.001.003.03) |
| **SIMULATION** | EK-JARs fehlen oder Init fehlgeschlagen | 50ms Sleep + 8KB Allokation pro Operation |

### Bankserver

```powershell
docker compose -f bench-docker/docker-compose.yml up -d
```

Bei EBICS-Szenarien (`--scenario ebics-upload`) startet die Benchmark-CLI den TravicLink-Stack **automatisch** per `TravicLinkManager`. Innerhalb der Benchmark-Container wird `--add-host nbag0342:host-gateway` gesetzt.

---

## Interpretation und Grenzen

- Workloads sind **synthetisch** -- isolieren JVM-Effekte, bilden keine Business-Logik ab
- Ein Container mit `--cpus 1 --memory 768m` bildet kein Produktionsszenario ab, ist aber typisch fuer Cloud-Kontingente
- Fuer belastbare Aussagen: Standard-Defaults verwenden (200/500/3), Konfidenzintervalle und GC-Metriken beruecksichtigen
- JIT-Effekte koennen zwischen Runs variieren (daher Randomisierung + Wiederholungen)
- Docker-Stats-Aufloesung: ~1s (10 Snapshots waehrend LOAD-Phase, Mittelwert)
- CPU% kann bei Multi-Thread-GCs (G1, ZGC, Parallel) >100% anzeigen (bezogen auf `--cpus 1`)
- Native Images haben kein GC-Logging und keine JAVA_TOOL_OPTIONS

---

## Troubleshooting

| Problem | Loesung |
|---------|--------|
| Port 8080 belegt | Prozess beenden; SingleRun bereinigt Zombie-Container automatisch |
| `Unable to find image` | `--rebuild` verwenden oder manuell `docker build` |
| log4j-Konflikt in Tests | `classpathDependencyExcludes` in Surefire-Config pruefen (pom.xml) |
| EBICS gibt `"status":"error"` | Erwartetes Verhalten ohne Lizenz/Bankserver. `"mode":"SIMULATION"` bestaetigt Simulationsmodus. |
| Native Image Build scheitert | `ghcr.io/graalvm/native-image-community:25` (nicht `native-image:25`) und `--initialize-at-build-time` pruefen |
| OpenJ9 GC-Policy nicht erkannt | GC-Policy wird via `-Xgcpolicy:` in JAVA_TOOL_OPTIONS gesetzt, kein Dockerfile-Aenderung noetig |
| Maven findet kein Java | `JAVA_HOME` auf JDK 17+ setzen |

---

## Technische Uebersicht

| | |
|---|---|
| Spring Boot | 4.0.0 |
| Java | 17 (Compile), 25 (Container-Runtime) |
| Build | Maven via Wrapper |
| Docker Base | Temurin JRE 25, Semeru JRE 25, GraalVM 25 |
| EBICS Kernel | Travic EK 4.0.9 (PPI AG), optional |
| Tests | JUnit 5, Spring Boot WebMvc Test, 401 Unit + 4 E2E |
| Container Limits | 1 CPU, 768 MB RAM, Swap deaktiviert |
| Fat JAR | ~54 MB |
| Benchmark-Konfigurationen | 20 Flag-Analyse + 12 Laufzeitprofile = 32 |
| Docker-Images | 10 (5 Standard + 5 EBICS) |
| Statistik | 95%-CI (t-Verteilung), Bessel-korrigierte Stddev |
