# ResourcenOptimierung TFL4

Benchmarking von JVM-Konfigurationen für Spring-Boot-Container-Anwendungen.

Misst den Einfluss verschiedener JVM-Flags auf Startzeit, Latenzen, Durchsatz und Ressourcenverbrauch (CPU/Memory) in Docker-Containern mit festen Limits.

Besteht aus:
1. **Spring-Boot-App** (System Under Test) -- REST-API mit synthetischen Workloads und optionaler EBICS-Banking-Anbindung
2. **Benchmark-CLI** -- startet die App in Docker-Containern, führt Messungen durch, exportiert Ergebnisse

---

## Voraussetzungen

- Java JDK 17+
- Docker 20+
- Maven Wrapper liegt im Repo (`./mvnw`)

---

## Schnellstart

```bash
./mvnw clean package -DskipTests          # JAR bauen
docker build -t tfl4-ek-bench:jvm .       # Docker-Image bauen (Standard)
docker build -t tfl4-ek-bench:jvm-ek -f Dockerfile.with-ek .  # Docker-Image mit EBICS
./mvnw test                                # 165 Unit Tests
./mvnw test -DincludeDocker                # 4 Docker-E2E-Tests
./mvnw exec:java                           # Benchmark interaktiv starten
./mvnw exec:java -Dexec.args="--scenario json --n 200000"  # nicht-interaktiv
./mvnw exec:java -Dexec.args="--scenario json --jvmArgs \"-XX:+UseZGC\""  # einzelner Run mit ZGC
```

---

## Projektstruktur

```
TFL4/
├── pom.xml                          # Build-Konfiguration (Spring Boot 4.0.0, Java 17)
├── mvnw / mvnw.cmd                  # Maven Wrapper
├── Dockerfile                       # Temurin JRE 25, ohne EK
├── Dockerfile.with-ek               # Temurin JRE 25, mit EK-JARs + EBICS-Keys
├── Dockerfile.windows               # Windows Server Core, Oracle JDK 21
│
├── src/main/java/de/mattis/
│   ├── jvmoptimdemo/
│   │   ├── ResourcenOptimierungTfl4Application.java
│   │   ├── DemoController.java      # /json, /alloc
│   │   └── EkController.java        # /ebics/upload, /ebics/health, /ebics/stats
│   │
│   └── resourcenoptimierung/bench/
│       ├── BenchCli.java            # CLI-Einstiegspunkt
│       ├── BenchmarkPlan.java       # Definiert zu testende Konfigurationen
│       ├── BenchmarkConfig.java     # Name + Docker-Image + JVM-Flags
│       ├── BenchmarkScenario.java   # Enum: JSON, ALLOC, EBICS_UPLOAD
│       ├── BenchmarkRunner.java     # Iteriert über Plan, delegiert an SingleRun
│       ├── SingleRun.java           # Führt einen Run aus (Container-Lifecycle + Messung)
│       ├── MeasurementProfile.java  # Warmup/Messung/Concurrency/Sleep
│       ├── ReadinessProber.java     # Fallback-Kette für Readiness-Erkennung
│       ├── ReadinessCheckUsed.java  # Enum: ACTUATOR_READINESS, ACTUATOR_HEALTH, WORKLOAD_UNTIL_200
│       ├── RunResult.java           # Record mit allen Messwerten eines Runs
│       ├── DockerStatSample.java    # Einzelner docker-stats-Snapshot
│       ├── ConsoleSummaryPrinter.java
│       ├── ResultExporters.java     # CSV + JSON Export
│       ├── ExcelExporter.java       # Excel-Export (.xlsx) mit Charts + CSV-Merge
│       └── TravicLinkManager.java  # docker-compose Lifecycle für TravicLink (EBICS)
│
├── src/test/java/de/mattis/
│   ├── jvmoptimdemo/
│   │   ├── DemoControllerTest.java       # 7 MockMvc-Tests
│   │   └── EkControllerTest.java         # 12 Tests (MockMvc + maskSensitive)
│   └── resourcenoptimierung/bench/
│       ├── BenchCliTest.java             # 48 Tests
│       ├── BenchmarkConfigTest.java      # 8 Tests
│       ├── BenchmarkPlanTest.java        # 20 Tests
│       ├── MeasurementProfileTest.java   # 11 Tests
│       ├── DockerStatSampleTest.java     # 8 Tests
│       ├── RunResultTest.java            # 3 Tests
│       ├── ConsoleSummaryPrinterTest.java# 11 Tests
│       ├── ResultExportersTest.java      # 11 Tests
│       ├── ExcelExporterTest.java        # 22 Tests
│       ├── TravicLinkManagerTest.java    # 5 Tests
│       └── DockerEndToEndTest.java       # 4 Docker-E2E-Tests (@Tag("docker"))
│
├── lib/                             # EK-JARs (system scope, ~30 JARs + 2 DLLs)
├── ebics/                           # EBICS-Client-Config + PKCS#12-Schlüssel
├── bench-docker/docker-compose.yml  # PostgreSQL 16 + TravicLink (EBICS-Bankserver)
├── bench-results/                   # Ergebnisse (CSV + JSON, timestamped)
└── target/                          # Build-Output (~54 MB Fat JAR)
```

---

## REST-Endpunkte

### DemoController

| Endpunkt         | Verhalten                                                        | Default-n   |
|------------------|------------------------------------------------------------------|-------------|
| `GET /json?n=`   | Erzeugt `n` UserDto-Objekte, gibt JSON-Array zurück. Stresst Objekterzeugung + Serialisierung. | 200.000 |
| `GET /alloc?n=`  | Erzeugt `n` kurzlebige `byte[128]` in 50k-Chunks, gibt `"ok <sum>"` zurück. Stresst GC + Heap-Layout. | 10.000.000 |

### EkController

| Endpunkt               | Verhalten                                                        |
|------------------------|------------------------------------------------------------------|
| `GET /ebics/upload?n=` | Führt `n` EBICS-Uploads durch (REAL oder SIMULATION)             |
| `GET /ebics/health`    | Status: mode, ekAvailable, ekInitialized, initError, requestCount|
| `GET /ebics/stats`     | Zähler: totalRequests, totalUploads                              |

### Actuator

```
GET /actuator/health              # Health-Check
GET /actuator/health/readiness    # Readiness-Probe (für Benchmark-Readiness-Erkennung)
GET /actuator/health/liveness     # Liveness-Probe
```

---

## EBICS-Integration

Der EkController nutzt den Travic EBICS Kernel (EK 4.0.9, PPI AG) ausschließlich über **Reflection**, damit die App auch ohne die proprietären JARs kompiliert.

### Modi

| Modus          | Bedingung                                | Verhalten                                |
|----------------|------------------------------------------|------------------------------------------|
| **REAL**       | EK-JARs + gültige Lizenz + Bankserver   | Echte EBICS-Kommunikation (HPB, Upload/Download via pain.001.003.03) |
| **SIMULATION** | EK-JARs fehlen oder Init fehlgeschlagen  | 50ms Sleep + 8KB Allokation pro Operation |

Der Modus wird in jeder Response als `"mode"` ausgegeben.

### Initialisierung (Lazy, bei erstem Request)

1. Lizenz setzen (`Configuration.setLicense()`)
2. PKCS#12-Schlüssel laden (A00/E00/X00)
3. Signer/Decrypter erstellen (A006, X002, E002)
4. TLS-Zertifikat prüfen (optional bypass via `verifyTls=false`)
5. HPB: Bank-Keys abrufen
6. ServerParameters mit Bank-Keys aufbauen

Bei Fehlern werden ausführliche Diagnostics geloggt (Config-Werte mit maskierten Passwörtern, Key-Datei-Existenz, Classpath-Check).

### Testdatei

`createTestFile()` generiert eine realistische SEPA-Testdatei (pain.001.003.03 XML, ~3-4 KB) mit 3 CreditTransferTransactions.

---

## Benchmark-CLI

### Ablauf

`BenchCli.main()` → `resolvePlan()` (Default-Plan oder `--jvmArgs`) → `BenchmarkRunner.runAll()` → je `SingleRun.execute()`:

```
1. docker run -d -p 8080:8080 --cpus 1 --memory 768m --memory-swap 768m [-e JAVA_TOOL_OPTIONS=...] <image>
2. Startup-Logs einsammeln (best-effort, max 200 Zeilen)
3. Readiness abwarten (Fallback-Kette, siehe unten)
4. IDLE docker-stats (3 Snapshots, 1s Intervall)
5. LOAD docker-stats starten (10 Snapshots parallel zur Messphase)
6. First Request messen → firstSeconds
7. Warmup (default 20 Requests)
8. Messphase (default 100 Requests) → latenciesSeconds[], totalMeasureTimeSeconds, throughputReqPerSec
9. POST docker-stats (3 Snapshots)
10. Container stoppen + entfernen (bei Fehler: Container bleibt für Inspektion)
```

Die Startup-Messung (`readinessMs`) umfasst die gesamte Zeitspanne vom `docker run`-Aufruf bis zur Readiness -- inklusive Container-Startup-Overhead.

### Readiness-Fallback-Kette

```
1. /actuator/health/readiness  → bevorzugt
2. /actuator/health            → falls Readiness-Probe nicht verfügbar
3. Workload-Endpoint           → letzter Fallback (bei EBICS: enthält EK-Init + HPB)
```

Polling alle 150ms. Bei 401/403/404 sofort nächster Fallback. Timeout: 120s.

### Wiederholungen und Randomisierung

Bei `--repetitions N` (Default: 3) wird jede Konfiguration N-mal ausgeführt. Pro Durchlauf wird die Reihenfolge der Konfigurationen **randomisiert**, um systematische Effekte (Cache-Warming, CPU-Throttling) zu vermeiden.

Die Konsolenausgabe zeigt nach den Einzelergebnissen eine **Aggregation** pro Konfiguration mit Mittelwert ± Standardabweichung für Readiness, First, p50, p95, Mean-Latenz und Throughput.

Swap ist deaktiviert (`--memory-swap 768m` = identisch mit `--memory`), um Kubernetes-Verhalten nachzubilden.

### Szenarien

| CLI-Wert         | Enum                  | Endpoint            | Default-n   |
|------------------|-----------------------|---------------------|-------------|
| `json`           | `PAYLOAD_HEAVY_JSON`  | `/json?n=`          | 200.000     |
| `alloc`          | `ALLOC_HEAVY_OK`      | `/alloc?n=`         | 10.000.000  |
| `ebics-upload`   | `EBICS_UPLOAD`        | `/ebics/upload?n=`  | 10          |

Auch akzeptiert: `payload`, `payload-heavy-json`, `/json`, `ok`, `upload`, `ebics`, etc.

### Standard-Plan (10 Konfigurationen)

Systematischer Vergleich von GC-Strategien, G1-Tuning und JVM-Interna auf Temurin JRE 25:

**Garbage-Collector-Vergleich:**
```java
BenchmarkConfig("baseline",    "tfl4-ek-bench:jvm", List.of())                          // G1GC (Default)
BenchmarkConfig("zgc",         "tfl4-ek-bench:jvm", List.of("-XX:+UseZGC"))              // ZGC (generational, Sub-ms-Pausen)
BenchmarkConfig("shenandoah",  "tfl4-ek-bench:jvm", List.of("-XX:+UseShenandoahGC"))     // Shenandoah (pausenarm)
BenchmarkConfig("parallel-gc", "tfl4-ek-bench:jvm", List.of("-XX:+UseParallelGC"))       // Throughput-optimiert
BenchmarkConfig("serial-gc",   "tfl4-ek-bench:jvm", List.of("-XX:+UseSerialGC"))         // Single-Thread, minimaler Overhead
```

**G1GC-Tuning:**
```java
BenchmarkConfig("g1-low-pause", "tfl4-ek-bench:jvm", List.of("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"))
BenchmarkConfig("g1-heap-256m", "tfl4-ek-bench:jvm", List.of("-Xmx256m"))               // eingeschränkter Heap
BenchmarkConfig("g1-heap-512m", "tfl4-ek-bench:jvm", List.of("-Xmx512m"))               // mittlerer Heap
```

**JVM-Interna:**
```java
BenchmarkConfig("coops-off", "tfl4-ek-bench:jvm", List.of("-XX:-UseCompressedOops"))     // 64-Bit-Referenzen
BenchmarkConfig("coh-on",    "tfl4-ek-bench:jvm", List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders"))
```

JVM-Flags werden über `JAVA_TOOL_OPTIONS` an den Container übergeben. Bei Native-Images wird `JAVA_TOOL_OPTIONS` nicht gesetzt.

### CLI-Argumente

| Argument                    | Default              | Beschreibung                          |
|-----------------------------|----------------------|---------------------------------------|
| `--scenario`                | interaktiv           | json / alloc / ebics-upload            |
| `--n`                       | szenarioabhängig     | Workload-Größe                        |
| `--warmupRequests`          | 20                   | Aufwärm-Requests                      |
| `--measureRequests`         | 100                  | Mess-Requests                         |
| `--concurrency`             | 1                    | Parallele Requests                    |
| `--sleepBetweenRequestsMs`  | 0                    | Pause zwischen Requests (ms)          |
| `--jvmArgs`                 | —                    | JVM-Flags für einen einzelnen Run (überschreibt Default-Plan) |
| `--configName`              | `cli-custom`         | Name der Konfiguration (nur mit `--jvmArgs`) |
| `--dockerImage`             | `tfl4-ek-bench:jvm`  | Docker-Image (nur mit `--jvmArgs`)    |
| `--repetitions`             | 3                    | Anzahl Wiederholungen pro Konfiguration (Mittelwert ± Stddev) |
| `--skipTravicLink`          | —                    | TravicLink-Start überspringen (wenn bereits nativ läuft) |
| `--merge-excel`             | —                    | Standalone: alle CSVs aus bench-results/ zu benchmark-vergleich.xlsx zusammenführen |

Beide Formen: `--scenario json` und `--scenario=json`.

### Beispiele

```bash
# Default-Plan (10 Konfigurationen, 3 Wiederholungen):
./mvnw exec:java -Dexec.args="--scenario json --n 200000"

# 5 Wiederholungen mit EBICS-Szenario (TravicLink muss nativ laufen):
./mvnw exec:java -Dexec.args="--scenario ebics-upload --repetitions 5 --skipTravicLink"

# Einzelner Run mit ZGC:
./mvnw exec:java -Dexec.args="--scenario json --jvmArgs \"-XX:+UseZGC -Xmx1g\" --configName zgc-test"

# Einzelner Run ohne zusätzliche Flags (Baseline):
./mvnw exec:java -Dexec.args="--scenario alloc --jvmArgs \"\""

# Eigenes Docker-Image:
./mvnw exec:java -Dexec.args="--scenario json --jvmArgs \"-Xmx2g\" --dockerImage myapp:latest"

# Nur CSV-Merge (kein Benchmark-Durchlauf):
./mvnw exec:java -Dexec.args="--merge-excel"
```

Wenn `--jvmArgs` gesetzt ist, wird **nur eine** Konfiguration mit den angegebenen Flags ausgeführt (statt des Default-Plans mit mehreren Konfigurationen).

### Ergebnis-Export

- **Konsole**: Gruppiert nach Szenario, Readiness/First/Latenz-Perzentile/Throughput als Übersicht, dann pro Run sortiert nach p95 (langsamste zuerst) mit Docker-Stats (IDLE/LOAD/POST). Bei mehreren Wiederholungen: Aggregation mit Mittelwert ± Standardabweichung pro Konfiguration.
- **CSV** (`bench-results/results-<timestamp>.csv`): Eine Zeile pro Run, alle Kennzahlen + Messprofil + `cpuLoadAvg`/`memLoadAvg`/`memLoadMax` + `repetition`
- **JSON** (`bench-results/results-<timestamp>.json`): Wie CSV, zusätzlich rohe Latenz-Arrays und Messprofil als verschachteltes Objekt
- **Excel** (`bench-results/results-<timestamp>.xlsx`): 5-Sheet-Workbook mit Balkendiagrammen:
  - Übersicht -- alle Kennzahlen tabellarisch, AutoFilter, Section-Headers, Zebra-Striping
  - Latenzen -- p50/p95/p99-Vergleich als gruppiertes Balkendiagramm
  - Startup & Throughput -- Readiness- und Durchsatz-Diagramme
  - Ressourcen -- Docker CPU%/Mem% (IDLE/LOAD/POST) als Diagramm
  - Rohdaten -- Einzellatenzen für eigene Auswertungen
- **Vergleichs-Excel** (`bench-results/benchmark-vergleich.xlsx`): Wird nach jedem Run automatisch neu generiert. Fasst alle CSV-Dateien aus bench-results/ zusammen (4 Sheets: Übersicht alle Runs, Latenzen alle Runs, Startup alle Runs, Hinweis Ressourcen). Auch standalone via `--merge-excel` erzeugbar.

---

## Metriken-Referenz

| Metrik                | Einheit | Beschreibung |
|-----------------------|---------|-------------|
| `readinessMs`         | ms      | docker run → Service ready (inkl. Container-Overhead) |
| `firstSeconds`        | s       | Erster Request nach Readiness (Cold-Path: JIT, Lazy Init) |
| `latencyP50/P95/P99`  | s       | Perzentile der Mess-Requests |
| `totalMeasureTimeSeconds` | s   | Wandzeit der gesamten Messphase |
| `throughputReqPerSec` | req/s   | measureRequests / totalMeasureTimeSeconds |
| `cpuLoadAvg`          | %       | Mittlere CPU-Auslastung während LOAD-Phase |
| `memLoadAvg`          | %       | Mittlere Speicherauslastung während LOAD-Phase |
| `memLoadMax`          | %       | Maximale Speicherauslastung während LOAD-Phase |
| `docker CPU%`         | %       | Container-CPU-Auslastung (IDLE/LOAD/POST) |
| `docker Mem%`         | %       | Speicherauslastung relativ zum Limit (768 MB) |
| `readinessCheckUsed`  | enum    | Welcher Probe-Typ gegriffen hat |
| `repetition`          | int     | Wiederholungsnummer (1-basiert) |

---

## Tests

### Ausführen

```bash
./mvnw test                  # 165 Unit Tests (kein Docker nötig)
./mvnw test -DincludeDocker  # 4 Docker-E2E-Tests (braucht Docker + gebautes Image)
```

### Trennung

Docker-E2E-Tests sind mit `@Tag("docker")` markiert. Standardmäßig schließt Surefire die Gruppe `docker` aus. `-DincludeDocker` aktiviert ein Maven-Profil, das die Logik umdreht (`surefire.groups=docker`, `surefire.excludedGroups` leer).

### Abdeckung

| Klasse                   | Tests | Schwerpunkt                                    |
|--------------------------|-------|------------------------------------------------|
| BenchCliTest             | 49    | Argument-Parsing, Szenario-Auflösung, Defaults, --jvmArgs, --repetitions, hasFlag, EBICS-Image-Auswahl |
| MeasurementProfileTest   | 11    | Validierung, Defaults, ungültige Werte         |
| BenchmarkConfigTest      | 8     | isNative()-Erkennung, Record-Felder            |
| BenchmarkPlanTest        | 20    | defaultPlan()-Struktur, alle 10 Konfigurationen, withDockerImage()|
| DockerStatSampleTest     | 8     | Parsing realer docker-stats-Ausgaben           |
| RunResultTest            | 3     | Record-Zugriff                                 |
| ConsoleSummaryPrinterTest| 12    | Ausgabe-Formatierung, Sortierung, Wiederholungs-Aggregation |
| ResultExportersTest      | 11    | CSV/JSON-Korrektheit, Sonderfälle              |
| ExcelExporterTest        | 22    | writeExcel (5 Sheets, Charts), mergeFromCsvDirectory, parseCsv, extractTimestamp |
| TravicLinkManagerTest    | 4     | isEbicsScenario(), Konstruktion                |
| DemoControllerTest       | 7     | /json + /alloc via MockMvc                     |
| EkControllerTest         | 10    | /ebics/* via MockMvc + maskSensitive()         |
| DockerEndToEndTest       | 4     | Volle SingleRun-Durchläufe mit echten Containern|

### Docker-E2E-Tests im Detail

- **singleRun_jsonScenario**: PAYLOAD_HEAVY_JSON, prüft alle RunResult-Felder
- **singleRun_allocScenario**: ALLOC_HEAVY_OK
- **singleRun_withJvmArgs_passesFlags**: Verifiziert, dass `-XX:-UseCompressedOops` via JAVA_TOOL_OPTIONS ankommt
- **singleRun_concurrent**: concurrency=2

---

## Dockerfiles

| Datei                | Base Image                      | JRE  | EK-JARs | Einsatz |
|----------------------|---------------------------------|------|---------|---------|
| `Dockerfile`         | `eclipse-temurin:25-jre`        | 25   | Nein    | JSON/Alloc-Benchmarks |
| `Dockerfile.with-ek` | `eclipse-temurin:25-jre`        | 25   | Ja      | EBICS-Benchmarks (REAL) |
| `Dockerfile.windows` | `mcr.microsoft.com/windows/servercore:ltsc2022` | 21 | Ja | Windows-Container |

---

## EBICS-Setup

### Dateien

```
ebics/
├── ebicsclient.config     # Server-URL, Host-ID, Customer-ID, User-ID, Lizenz, OrderType
├── ebicsclient_a00.p12    # Authentication Key
├── ebicsclient_e00.p12    # Encryption Key
└── ebicsclient_x00.p12    # Signature Key
```

### Bankserver (für REAL-Modus)

```bash
docker compose -f bench-docker/docker-compose.yml up -d
```

Startet PostgreSQL 16 + TravicLink auf Port 7070. Der TravicLink-Server ist proprietär (PPI AG).

Bei EBICS-Szenarien (`--scenario ebics-upload`) startet die Benchmark-CLI den TravicLink-Stack **automatisch** per `TravicLinkManager` und stoppt ihn nach Abschluss aller Runs. Manuelles `docker compose up` ist nur für Debugging nötig.

Innerhalb der Benchmark-Container wird `--add-host nbag0342:host-gateway` gesetzt, damit die EBICS-URL `https://nbag0342:7070/` auf den Host (= TravicLink) auflöst. Das Docker-Image wird automatisch auf `tfl4-ek-bench:jvm-ek` umgestellt.

### EK-JARs einrichten

```bash
./setup-ek.sh   # kopiert JARs + DLLs aus dem EK-Installationsverzeichnis nach lib/
```

---

## Build

```bash
./mvnw clean package -DskipTests   # ~54 MB Fat JAR
docker build -t tfl4-ek-bench:jvm .            # Standard-Image (JSON/Alloc)
docker build -t tfl4-ek-bench:jvm-ek -f Dockerfile.with-ek .  # EK-Image (EBICS, mit Keys + Config)
```

Der `spring-boot-maven-plugin` bindet System-Scope-JARs ein (`includeSystemScope=true`) und schließt die log4j/slf4j-JARs des EK aus (Konflikt mit Spring Boot Logging). Surefire macht dasselbe für den Test-Classpath (`classpathDependencyExcludes`).

---

## Interpretation und Grenzen

- Workloads sind **synthetisch** -- isolieren JVM-Effekte, bilden keine Business-Logik ab
- Ein Container mit `--cpus 1 --memory 768m` bildet kein Produktionsszenario ab
- Für belastbare Aussagen: ausreichend Wiederholungen (`--repetitions`, Default 3), Konfidenzintervalle aus der Aggregation ablesen, Störfaktoren kontrollieren (CPU-Scaling, Background Noise)
- JIT-Effekte können zwischen Runs variieren
- Readiness-Zeiten hängen vom Host-System und Docker-Konfiguration ab

---

## Troubleshooting

| Problem | Lösung |
|---------|--------|
| Port 8080 belegt | Prozess beenden oder Port in `SingleRun` ändern |
| `Unable to find image 'tfl4-ek-bench:jvm'` | `./mvnw package -DskipTests && docker build -t tfl4-ek-bench:jvm .` |
| log4j-Konflikt in Tests | `classpathDependencyExcludes` in Surefire-Config prüfen (pom.xml:322-326) |
| EBICS-Endpunkte geben `"status":"error"` | Erwartetes Verhalten ohne gültige Lizenz/Bankserver. `"mode":"SIMULATION"` bestätigt Simulationsmodus. |
| Maven findet kein Java | `export JAVA_HOME=/pfad/zum/jdk17` |

---

## Technische Übersicht

| | |
|---|---|
| Spring Boot | 4.0.0 |
| Java | 17 (Compile), 25 (Container-Runtime) |
| Build | Maven via Wrapper |
| Docker Base | Eclipse Temurin JRE 25 |
| EBICS Kernel | Travic EK 4.0.9 (PPI AG), optional |
| Tests | JUnit 5, Spring Boot WebMvc Test, 165 Unit + 4 E2E |
| Container Limits | 1 CPU, 768 MB RAM, Swap deaktiviert |
| Fat JAR | ~54 MB |
