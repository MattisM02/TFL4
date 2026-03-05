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
docker build -t tfl4-ek-bench:jvm .       # Docker-Image bauen
./mvnw test                                # 104 Unit Tests
./mvnw test -DincludeDocker                # 4 Docker-E2E-Tests
./mvnw exec:java                           # Benchmark interaktiv starten
./mvnw exec:java -Dexec.args="--scenario json --n 200000"  # nicht-interaktiv
```

---

## Projektstruktur

```
TFL4/
├── pom.xml                          # Build-Konfiguration (Spring Boot 4.0.0, Java 17)
├── mvnw / mvnw.cmd                  # Maven Wrapper
├── Dockerfile                       # Temurin JRE 25, ohne EK
├── Dockerfile.with-ek               # Temurin JRE 21, mit EK-JARs + EBICS-Keys
├── Dockerfile.windows               # Windows Server Core, Oracle JDK 21
│
├── src/main/java/de/mattis/
│   ├── jvmoptimdemo/
│   │   ├── ResourcenOptimierungTfl4Application.java
│   │   ├── DemoController.java      # /json, /alloc
│   │   └── EkController.java        # /ebics/upload, /ebics/download, /ebics/health, /ebics/stats
│   │
│   └── resourcenoptimierung/bench/
│       ├── BenchCli.java            # CLI-Einstiegspunkt
│       ├── BenchmarkPlan.java       # Definiert zu testende Konfigurationen
│       ├── BenchmarkConfig.java     # Name + Docker-Image + JVM-Flags
│       ├── BenchmarkScenario.java   # Enum: JSON, ALLOC, EBICS_UPLOAD, EBICS_DOWNLOAD
│       ├── BenchmarkRunner.java     # Iteriert über Plan, delegiert an SingleRun
│       ├── SingleRun.java           # Führt einen Run aus (Container-Lifecycle + Messung)
│       ├── MeasurementProfile.java  # Warmup/Messung/Concurrency/Sleep
│       ├── ReadinessProber.java     # Fallback-Kette für Readiness-Erkennung
│       ├── ReadinessCheckUsed.java  # Enum: ACTUATOR_READINESS, ACTUATOR_HEALTH, WORKLOAD_UNTIL_200
│       ├── RunResult.java           # Record mit allen Messwerten eines Runs
│       ├── DockerStatSample.java    # Einzelner docker-stats-Snapshot
│       ├── ConsoleSummaryPrinter.java
│       └── ResultExporters.java     # CSV + JSON Export
│
├── src/test/java/de/mattis/
│   ├── jvmoptimdemo/
│   │   ├── DemoControllerTest.java       # 7 MockMvc-Tests
│   │   └── EkControllerTest.java         # 12 Tests (MockMvc + maskSensitive)
│   └── resourcenoptimierung/bench/
│       ├── BenchCliTest.java             # 26 Tests
│       ├── BenchmarkConfigTest.java      # 8 Tests
│       ├── BenchmarkPlanTest.java        # 7 Tests
│       ├── MeasurementProfileTest.java   # 11 Tests
│       ├── DockerStatSampleTest.java     # 8 Tests
│       ├── RunResultTest.java            # 3 Tests
│       ├── ConsoleSummaryPrinterTest.java# 11 Tests
│       ├── ResultExportersTest.java      # 11 Tests
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
| `GET /ebics/download?n=`| Führt `n` EBICS-Downloads durch (REAL oder SIMULATION)          |
| `GET /ebics/health`    | Status: mode, ekAvailable, ekInitialized, initError, requestCount|
| `GET /ebics/stats`     | Zähler: totalRequests, totalUploads, totalDownloads              |

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

`BenchCli.main()` → `BenchmarkPlan.defaultPlan()` → `BenchmarkRunner.runAll()` → je `SingleRun.execute()`:

```
1. docker run -d -p 8080:8080 --cpus 1 --memory 768m [-e JAVA_TOOL_OPTIONS=...] <image>
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

### Readiness-Fallback-Kette

```
1. /actuator/health/readiness  → bevorzugt
2. /actuator/health            → falls Readiness-Probe nicht verfügbar
3. Workload-Endpoint           → letzter Fallback (bei EBICS: enthält EK-Init + HPB)
```

Polling alle 150ms. Bei 401/403/404 sofort nächster Fallback. Timeout: 120s.

### Szenarien

| CLI-Wert         | Enum                  | Endpoint            | Default-n   |
|------------------|-----------------------|---------------------|-------------|
| `json`           | `PAYLOAD_HEAVY_JSON`  | `/json?n=`          | 200.000     |
| `alloc`          | `ALLOC_HEAVY_OK`      | `/alloc?n=`         | 10.000.000  |
| `ebics-upload`   | `EBICS_UPLOAD`        | `/ebics/upload?n=`  | 10          |
| `ebics-download` | `EBICS_DOWNLOAD`      | `/ebics/download?n=`| 10          |

Auch akzeptiert: `payload`, `payload-heavy-json`, `/json`, `ok`, `upload`, `download`, etc.

### Standard-Plan

```java
BenchmarkConfig("baseline",  "tfl4-ek-bench:jvm", List.of())
BenchmarkConfig("coops-off", "tfl4-ek-bench:jvm", List.of("-XX:-UseCompressedOops"))
// deaktiviert: coh-on (braucht JDK 24+), native (kein Image gebaut)
```

JVM-Flags werden über `JAVA_TOOL_OPTIONS` an den Container übergeben. Bei Native-Images wird `JAVA_TOOL_OPTIONS` nicht gesetzt.

### CLI-Argumente

| Argument                    | Default              | Beschreibung                          |
|-----------------------------|----------------------|---------------------------------------|
| `--scenario`                | interaktiv           | json / alloc / ebics-upload / ebics-download |
| `--n`                       | szenarioabhängig     | Workload-Größe                        |
| `--warmupRequests`          | 20                   | Aufwärm-Requests                      |
| `--measureRequests`         | 100                  | Mess-Requests                         |
| `--concurrency`             | 1                    | Parallele Requests                    |
| `--sleepBetweenRequestsMs`  | 0                    | Pause zwischen Requests (ms)          |

Beide Formen: `--scenario json` und `--scenario=json`.

### Ergebnis-Export

- **Konsole**: Gruppiert nach Szenario, Readiness/First/Latenz-Perzentile/Throughput als Übersicht, dann pro Run sortiert nach p95 (langsamste zuerst) mit Docker-Stats (IDLE/LOAD/POST)
- **CSV** (`bench-results/results-<timestamp>.csv`): Eine Zeile pro Run, alle Kennzahlen + Messprofil
- **JSON** (`bench-results/results-<timestamp>.json`): Wie CSV, zusätzlich rohe Latenz-Arrays und Messprofil als verschachteltes Objekt

---

## Metriken-Referenz

| Metrik                | Einheit | Beschreibung |
|-----------------------|---------|-------------|
| `readinessMs`         | ms      | Containerstart → Service ready |
| `firstSeconds`        | s       | Erster Request nach Readiness (Cold-Path: JIT, Lazy Init) |
| `latencyP50/P95/P99`  | s       | Perzentile der Mess-Requests |
| `totalMeasureTimeSeconds` | s   | Wandzeit der gesamten Messphase |
| `throughputReqPerSec` | req/s   | measureRequests / totalMeasureTimeSeconds |
| `docker CPU%`         | %       | Container-CPU-Auslastung (IDLE/LOAD/POST) |
| `docker Mem%`         | %       | Speicherauslastung relativ zum Limit (768 MB) |
| `readinessCheckUsed`  | enum    | Welcher Probe-Typ gegriffen hat |

---

## Tests

### Ausführen

```bash
./mvnw test                  # 104 Unit Tests (kein Docker nötig)
./mvnw test -DincludeDocker  # 4 Docker-E2E-Tests (braucht Docker + gebautes Image)
```

### Trennung

Docker-E2E-Tests sind mit `@Tag("docker")` markiert. Standardmäßig schließt Surefire die Gruppe `docker` aus. `-DincludeDocker` aktiviert ein Maven-Profil, das die Logik umdreht (`surefire.groups=docker`, `surefire.excludedGroups` leer).

### Abdeckung

| Klasse                   | Tests | Schwerpunkt                                    |
|--------------------------|-------|------------------------------------------------|
| BenchCliTest             | 26    | Argument-Parsing, Szenario-Auflösung, Defaults |
| MeasurementProfileTest   | 11    | Validierung, Defaults, ungültige Werte         |
| BenchmarkConfigTest      | 8     | isNative()-Erkennung, Record-Felder            |
| BenchmarkPlanTest        | 7     | defaultPlan()-Struktur                         |
| DockerStatSampleTest     | 8     | Parsing realer docker-stats-Ausgaben           |
| RunResultTest            | 3     | Record-Zugriff                                 |
| ConsoleSummaryPrinterTest| 11    | Ausgabe-Formatierung, Sortierung               |
| ResultExportersTest      | 11    | CSV/JSON-Korrektheit, Sonderfälle              |
| DemoControllerTest       | 7     | /json + /alloc via MockMvc                     |
| EkControllerTest         | 12    | /ebics/* via MockMvc + maskSensitive()         |
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
| `Dockerfile.with-ek` | `eclipse-temurin:21-jre`        | 21   | Ja      | EBICS-Benchmarks (REAL) |
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

### EK-JARs einrichten

```bash
./setup-ek.sh   # kopiert JARs + DLLs aus dem EK-Installationsverzeichnis nach lib/
```

---

## Build

```bash
./mvnw clean package -DskipTests   # ~54 MB Fat JAR
docker build -t tfl4-ek-bench:jvm . # Docker-Image
```

Der `spring-boot-maven-plugin` bindet System-Scope-JARs ein (`includeSystemScope=true`) und schließt die log4j/slf4j-JARs des EK aus (Konflikt mit Spring Boot Logging). Surefire macht dasselbe für den Test-Classpath (`classpathDependencyExcludes`).

---

## Interpretation und Grenzen

- Workloads sind **synthetisch** -- isolieren JVM-Effekte, bilden keine Business-Logik ab
- Ein Container mit `--cpus 1 --memory 768m` bildet kein Produktionsszenario ab
- Für belastbare Aussagen: mehrere Wiederholungen, Konfidenzintervalle, Störfaktoren kontrollieren (CPU-Scaling, Background Noise)
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
| Java | 17 (Compile), 21/25 (Container-Runtime) |
| Build | Maven via Wrapper |
| Docker Base | Eclipse Temurin JRE 25 / 21 |
| EBICS Kernel | Travic EK 4.0.9 (PPI AG), optional |
| Tests | JUnit 5, Spring Boot WebMvc Test, 104 Unit + 4 E2E |
| Container Limits | 1 CPU, 768 MB RAM |
| Fat JAR | ~54 MB |
