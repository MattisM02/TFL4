# Benchmark-Methodik und JVM-Konfigurationsanalyse

Fachliche Dokumentation zum Benchmark-Framework fuer die Thesis *"Ressourcenoptimierung von javabasierten Containeranwendungen im Cloudbetrieb"*.

Zielgruppe: Pruefer und Betreuer mit JVM-Grundkenntnissen.

---

## Inhaltsverzeichnis

1. [Was wird gemessen?](#1-was-wird-gemessen)
2. [Wie wird gemessen?](#2-wie-wird-gemessen)
3. [Zwei-Ebenen-Analyse](#3-zwei-ebenen-analyse)
4. [Level 1: Die 20 Flag-Analyse-Konfigurationen](#4-level-1-die-20-flag-analyse-konfigurationen)
5. [Level 2: Die 12 Laufzeitprofile](#5-level-2-die-12-laufzeitprofile)
6. [Docker-Image-Architektur](#6-docker-image-architektur)
7. [Messwerte und deren Erhebung](#7-messwerte-und-deren-erhebung)
8. [GC-Log-Erfassung und -Auswertung](#8-gc-log-erfassung-und-auswertung)
9. [Statistische Methodik](#9-statistische-methodik)
10. [Excel-Darstellung](#10-excel-darstellung)
11. [CLI-Optionen und Messprofile](#11-cli-optionen-und-messprofile)
12. [Limitierungen](#12-limitierungen)

---

## 1. Was wird gemessen?

Das Framework misst den Einfluss von JVM-Konfigurationen auf das Laufzeitverhalten einer Spring-Boot-Anwendung in Docker-Containern mit festen Ressourcenlimits (1 CPU, 768 MB RAM, kein Swap).

Die gemessenen Dimensionen:

| Dimension | Kennzahlen | Relevanz |
|-----------|-----------|----------|
| **Startup** | Readiness-Zeit (ms) | Skalierungsfaehigkeit, Cold-Start-Kosten |
| **Latenz** | First Request, p50, p95, p99, Mean | Service-Level-Objectives, Tail-Latenz |
| **Durchsatz** | Requests/Sekunde | Kapazitaetsplanung, Kosten pro Request |
| **Ressourcen** | CPU%, Mem% (IDLE/LOAD/POST) | Container-Sizing, Ueberprovisionierung |
| **GC-Verhalten** | Pausenanzahl, Pausendauer, Overhead, Peak Heap | GC-Effizienz, OOM-Risiko |

Drei Szenarien decken unterschiedliche Workload-Profile ab:
- **JSON** (CPU-intensiv): 200.000 Objekte erzeugen + JSON-Serialisierung
- **Alloc** (GC-intensiv): 10 Mio. kurzlebige Byte-Arrays, stresst den Garbage Collector
- **EBICS Upload** (I/O + Crypto): Reale EBICS-Bankueberweisung ueber TravicLink-Server

---

## 2. Wie wird gemessen?

### Isolation: Ein Container pro Konfiguration

Jede Konfiguration wird in einem **eigenen** Docker-Container ausgefuehrt. Es laeuft nie mehr als ein Container gleichzeitig. Der Ablauf pro Run:

```
docker run -d --cpus 1 --memory 768m --memory-swap 768m \
  -e JAVA_TOOL_OPTIONS="<gc-logging> <flags>" <image>

-> Readiness-Polling (max 120s, Fallback-Kette)
-> IDLE Docker-Stats (3 Snapshots, 1s Intervall)
-> First Request messen
-> Warmup (200 Requests, verworfen)
-> LOAD Docker-Stats starten (10 Snapshots parallel)
-> Messphase (500 Requests, aufgezeichnet)
-> POST Docker-Stats (3 Snapshots)
-> GC-Log erfassen: Rohlog speichern + parsen
-> Container stoppen + entfernen
```

### Wiederholungen und Randomisierung

Bei 3 Wiederholungen (Default) und 32 Konfigurationen (kombinierter Plan) werden **96 Container-Runs** ausgefuehrt. Pro Durchlauf wird die Reihenfolge **randomisiert** (`Collections.shuffle()`), um systematische Effekte zu eliminieren:
- CPU-Throttling nach laengerer Last
- Filesystem-Cache-Aufwaermung
- Docker-Daemon-Overhead-Schwankungen

### Flag-Injektion

JVM-Flags werden ueber die Umgebungsvariable `JAVA_TOOL_OPTIONS` uebergeben. Die JVM wertet diese Variable automatisch beim Start aus. Die Zusammensetzung ist laufzeittypabhaengig:

| RuntimeType | GC-Logging | JAVA_TOOL_OPTIONS |
|-------------|-----------|-------------------|
| HOTSPOT | `-Xlog:gc*:stdout` | GC-Logging + konfigurationsspezifische Flags |
| OPENJ9 | `-verbose:gc` | GC-Logging + konfigurationsspezifische Flags |
| NATIVE | *(nicht gesetzt)* | Kein JAVA_TOOL_OPTIONS (kein JVM-Overhead) |

Das GC-Logging wird **immer** automatisch vorangestellt, damit GC-Events fuer die Auswertung verfuegbar sind.

### Swap-Deaktivierung

`--memory-swap 768m` (identisch mit `--memory`) deaktiviert Swap. Das bildet Kubernetes-Verhalten nach, wo Swap standardmaessig deaktiviert ist. OOM-Kills bei Speicherueberschreitung sind damit moeglich und erwuenscht (realitaetsnah).

### Readiness-Fallback-Kette

```
1. /actuator/health/readiness  -> bevorzugt (semantisch korrekt)
2. /actuator/health            -> falls Readiness-Probe nicht verfuegbar
3. Workload-Endpoint           -> letzter Fallback (bei EBICS: enthaelt EK-Init + HPB)
```

Polling alle 150ms. Bei 401/403/404 sofort naechster Fallback. Timeout: 120s.

Die `readinessMs`-Messung umfasst die gesamte Zeitspanne vom `docker run`-Aufruf bis zur Readiness -- inklusive Container-Startup-Overhead (Image-Pull, Overlay-FS, Namespace-Setup).

---

## 3. Zwei-Ebenen-Analyse

Das Framework verwendet eine **zweistufige Analyse**, die unterschiedliche Fragestellungen adressiert:

### Level 1: Flag-Analyse (20 Konfigurationen)

**Fragestellung:** Welchen Einfluss haben einzelne JVM-Flags und deren Kombinationen auf dasselbe JVM (HotSpot)?

Alle 20 Konfigurationen verwenden dasselbe Docker-Image (`tfl4-ek-bench:jvm`, Eclipse Temurin JRE 25). Unterschiede entstehen **ausschliesslich** durch die gesetzten JVM-Flags. Dies isoliert den Effekt der Flags von Unterschieden in der JVM-Implementierung.

### Level 2: Laufzeitprofile (12 Konfigurationen)

**Fragestellung:** Wie vergleichen sich verschiedene JVM-Implementierungen und Laufzeitmodelle unter praxisnahen Cloud-Deployment-Strategien?

Die 12 Profile verwenden **unterschiedliche Docker-Images** mit verschiedenen JVMs (HotSpot, OpenJ9, GraalVM Native, GraalVM JIT, CDS). Jedes Profil repraesentiert eine typische Cloud-Deployment-Strategie.

### Kombinierter Plan (Default)

Per Default werden **beide Ebenen** ausgefuehrt: 20 + 12 = **32 Konfigurationen**. Mit `--profiles` werden nur die 12 Profile ausgefuehrt. Mit `--jvmArgs` wird eine einzelne benutzerdefinierte Konfiguration ausgefuehrt.

---

## 4. Level 1: Die 20 Flag-Analyse-Konfigurationen

Alle verwenden das Image `tfl4-ek-bench:jvm` (Eclipse Temurin JRE 25) und `RuntimeType.HOTSPOT`.

### 4.1 Garbage-Collector-Vergleich (5 Konfigurationen)

#### baseline (keine Flags)
**GC:** G1GC (Default seit Java 9)
**Algorithmus:** Region-basiert, generational, concurrent marking, mixed collections. Pausenziel: ~200ms.
**Relevanz:** Referenzpunkt fuer alle Vergleiche. G1 ist der De-facto-Standard fuer Server-Workloads.

#### zgc (`-XX:+UseZGC`)
**GC:** Z Garbage Collector (seit JDK 24 ausschliesslich generational)
**Algorithmus:** Concurrent, region-basiert, colored pointers, load barriers. Pausenzeiten im Sub-Millisekunden-Bereich, unabhaengig von Heap-Groesse.
**Trade-off:** Hoeherer CPU-Overhead durch concurrent Arbeit, dafuer extrem niedrige Tail-Latenzen.
**Cloud-Relevanz:** Relevant fuer latenz-kritische Microservices mit strengen SLOs (z.B. p99 < 10ms).

#### shenandoah (`-XX:+UseShenandoahGC`)
**GC:** Shenandoah (Red Hat, upstream seit Java 12)
**Algorithmus:** Concurrent compacting, Brooks forwarding pointers. Aehnliche Ziele wie ZGC (niedrige Pausen), anderer Ansatz.
**Trade-off:** Etwas hoeherer Speicher-Overhead durch Forwarding-Pointer. Vergleichspunkt zu ZGC unter identischen Bedingungen.

#### parallel-gc (`-XX:+UseParallelGC`)
**GC:** Parallel Collector (Throughput-Collector)
**Algorithmus:** Stop-the-World, mehrere GC-Threads parallel, generational. Maximiert Durchsatz (= minimale GC-Zeit / Gesamtzeit).
**Trade-off:** Laengere einzelne Pausen, dafuer hoeherer Gesamtdurchsatz. Ungeeignet fuer latenz-sensitive Workloads.
**Cloud-Relevanz:** Batch-Processing, Datenverarbeitung, wo Latenz unkritisch ist.

#### serial-gc (`-XX:+UseSerialGC`)
**GC:** Serial Collector
**Algorithmus:** Single-Thread, Stop-the-World, mark-compact. Minimaler CPU- und Speicher-Overhead.
**Trade-off:** Laengste Pausen, aber geringster Footprint. Kein Thread-Management-Overhead.
**Cloud-Relevanz:** Besonders relevant bei `--cpus 1`: Multi-Thread-GCs (G1, ZGC) haben Overhead durch Thread-Koordination, der bei einer einzigen CPU nicht amortisiert wird.

### 4.2 G1GC-Tuning (3 Konfigurationen)

#### g1-low-pause (`-XX:+UseG1GC -XX:MaxGCPauseMillis=50`)
G1 mit aggressiverem Pausenziel (50ms statt Default 200ms). Der GC fuehrt haeufigere, kleinere Collections durch. Das kann zu geringerem Durchsatz fuehren, verbessert aber die Latenz-Verteilung.

#### g1-heap-256m (`-Xmx256m`)
G1 mit eingeschraenktem Heap (256 MB bei 768 MB Container-Limit). Erzwingt haeufigere GC-Zyklen und fruehere Promotions in die Old Generation. Zeigt Verhalten unter Memory-Pressure und ob die Anwendung mit weniger Heap auskommt (= kleinere Container moeglich).

#### g1-heap-512m (`-Xmx512m`)
G1 mit mittlerem Heap (512 MB). Vergleichspunkt zwischen Default-Ergonomics (JVM waehlt ~25% des verfuegbaren Speichers), 256 MB und dem vollen Container-Speicher.

### 4.3 JVM-Interna (2 Konfigurationen)

#### coops-off (`-XX:-UseCompressedOops`)
Deaktiviert Compressed Ordinary Object Pointers. Normalerweise komprimiert die JVM 64-Bit-Referenzen auf 32 Bit (bei Heaps < 32 GB). Ohne Compressed Oops sind alle Referenzen 8 Byte statt 4 Byte.
**Erwartung:** Hoeherer Speicherverbrauch, potenziell hoehere Cache-Miss-Rate.

#### coh-on (`-XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders`)
Compact Object Headers (JEP 450, experimentell). Reduziert den Object-Header von 12-16 Byte auf 8 Byte.
**Erwartung:** Signifikante Speicherersparnis bei Workloads mit vielen kleinen Objekten (wie JSON-Serialisierung mit 200.000 UserDto-Objekten). Potenziell bessere Cache-Effizienz.

### 4.4 Cloud-relevante Konfigurationen (2 Konfigurationen)

#### ram-percentage-75 (`-XX:MaxRAMPercentage=75`)
Container-aware Heap-Sizing. Die JVM erkennt das Container-Memory-Limit (768 MB via cgroups) und setzt den maximalen Heap auf 75% davon (~576 MB).

**Hintergrund:** Seit Java 10 ist die JVM container-aware. `MaxRAMPercentage` steuert den Heap-Anteil des erkannten Speichers. Der Rest wird fuer Metaspace, Thread-Stacks, Native Memory, JIT-Code-Cache benoetigt. 75% ist ein praxisueblicher Wert in Kubernetes-Deployments.

#### tiered-stop-1 (`-XX:TieredStopAtLevel=1`)
Deaktiviert den C2-Compiler (Server-Compiler). Nur C1 (Client-Compiler) laeuft.

**Kompilierungsstufen der HotSpot JVM:**
- Stufe 0: Interpreter
- Stufe 1-3: C1-Compiler (schnell, moderate Optimierung)
- Stufe 4: C2-Compiler (langsam, aggressive Optimierungen: Loop-Unrolling, Escape-Analysis, Inlining)

`TieredStopAtLevel=1` stoppt nach C1. Drastisch schnellerer Startup (C2-Kompilierung entfaellt), geringerer Peak-Durchsatz bei lang laufenden Workloads.
**Cloud-Relevanz:** Ideal fuer kurzlebige Container, Serverless Functions, Autoscaling-Szenarien.

### 4.5 Flag-Kombinationen (8 Konfigurationen)

| Config | JVM-Flags | Hypothese |
|--------|-----------|-----------|
| `serial-gc-256m` | `-XX:+UseSerialGC -Xmx256m` | Absolut minimaler Footprint: Single-Thread-GC + kleiner Heap. Relevant fuer Sidecar-Container oder serverlose Funktionen. |
| `zgc-heap-512m` | `-XX:+UseZGC -Xmx512m` | ZGC mit definiertem Heap-Limit. Mehr Spielraum fuer concurrent GC als bei Default-Ergonomics. |
| `shenandoah-heap-512m` | `-XX:+UseShenandoahGC -Xmx512m` | Shenandoah mit definiertem Heap. Vergleichspunkt zu ZGC unter identischen Heap-Bedingungen. |
| `tiered-stop-1-serial` | `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` | C1-only + Serial GC: schnellstmoeglicher Start + bester GC auf 1 CPU. Optimiert fuer Cold-Start. |
| `g1-coh-on` | `-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders` | G1 + Compact Object Headers: reduziert Objekt-Overhead und GC-Druck durch kleinere Header. |
| `parallel-gc-256m` | `-XX:+UseParallelGC -Xmx256m` | Durchsatz-GC mit kleinem Heap. Zeigt, ob Parallel GC unter Memory-Pressure noch Vorteile bietet. |
| `g1-large-young` | `-XX:+UseG1GC -XX:NewRatio=1` | G1 mit 50% Young Generation (statt Default ~33%). Weniger Full GCs erwartet bei alloc-lastigen Workloads. |
| `zgc-tiered-stop-1` | `-XX:+UseZGC -XX:TieredStopAtLevel=1` | ZGC + C1-only: niedrige Pausen mit schnellem Start. Optimiert fuer Serverless/Autoscaling. |

---

## 5. Level 2: Die 12 Laufzeitprofile

Die Laufzeitprofile vergleichen verschiedene JVM-Implementierungen und Laufzeitmodelle. Jedes Profil hat ein eigenes Docker-Image (wo noetig) und repraesentiert eine typische Cloud-Deployment-Strategie.

### HotSpot-Profile

#### P01-hotspot-standard (Image: jvm)
**Flags:** `-XX:+UseG1GC -XX:MaxRAMPercentage=75`
Standard-Cloud-Deployment mit G1GC und container-aware Heap-Sizing. Referenzprofil fuer Level 2.

#### P02-hotspot-fast-startup (Image: jvm)
**Flags:** `-XX:+UseG1GC -XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=75`
Serverless/Cold-Start-optimiert. Wie P01, aber mit deaktiviertem C2-Compiler fuer schnelleren Startup.

#### P03-hotspot-low-latency (Image: jvm)
**Flags:** `-XX:+UseZGC -XX:MaxRAMPercentage=75`
ZGC fuer Sub-Millisekunden-Pausen. Zeigt, ob ZGC unter Container-Limits (1 CPU, 768 MB) die Latenzvorteile gegenueber G1 haelt.

#### P09-hotspot-heap-256m (Image: jvm)
**Flags:** `-XX:+UseG1GC -Xmx256m`
HotSpot mit stark eingeschraenktem Heap. Direkter Vergleich zu P10 (OpenJ9 mit 256 MB).

### OpenJ9-Profile

Eclipse OpenJ9 (IBM Semeru Runtimes) ist eine alternative JVM-Implementierung mit eigenen GC-Policies. Image: `tfl4-ek-bench:openj9` (ibm-semeru-runtimes:open-25-jre).

#### P04-openj9-low-memory (Image: openj9)
**Flags:** `-XX:MaxRAMPercentage=75`
OpenJ9 mit gencon GC (Default). Generational concurrent collector, optimiert fuer niedrigen Speicherverbrauch. OpenJ9 ist bekannt fuer geringeren Memory-Footprint als HotSpot.

#### P06-openj9-balanced (Image: openj9)
**Flags:** `-Xgcpolicy:balanced -XX:MaxRAMPercentage=75`
Region-basierter GC, NUMA-aware. Aehnliches Konzept wie G1, aber OpenJ9-Implementierung. Optimiert fuer grosse Heaps und NUMA-Architekturen.

#### P07-openj9-optthruput (Image: openj9)
**Flags:** `-Xgcpolicy:optthruput -XX:MaxRAMPercentage=75`
Durchsatz-optimierter GC. Analog zu Parallel GC bei HotSpot: maximiert Durchsatz, akzeptiert laengere Pausen.

#### P08-openj9-optavgpause (Image: openj9)
**Flags:** `-Xgcpolicy:optavgpause -XX:MaxRAMPercentage=75`
Pausen-optimierter GC. Concurrent Mark-Sweep mit dem Ziel, durchschnittliche GC-Pausen zu minimieren.

#### P10-openj9-heap-256m (Image: openj9)
**Flags:** `-Xmx256m`
OpenJ9 mit eingeschraenktem Heap. Direkter Vergleich zu P09 (HotSpot mit 256 MB): Welche JVM arbeitet effizienter unter Memory-Pressure?

### GraalVM Native Image

#### P05-native (Image: native)
**Flags:** *(keine -- kein JAVA_TOOL_OPTIONS)*
GraalVM Native Image kompiliert die gesamte Anwendung Ahead-of-Time (AOT) in ein natives Executable. Kein JVM-Overhead: kein Classloading, kein JIT, kein Metaspace.

**Dockerfile:** Multi-Stage-Build mit `ghcr.io/graalvm/native-image-community:25` (Build-Stage) und `debian:bookworm-slim` (Runtime-Stage).
**Besonderheit:** Erfordert `--initialize-at-build-time=org.springframework.boot.loader.nio.file.NestedFileSystemProvider` fuer Spring Boot 4 + GraalVM 25.

Erwartungen:
- **Startup:** Groessenordnung Millisekunden statt Sekunden
- **Peak-Durchsatz:** Typisch 10-30% geringer als JIT-kompilierter Code
- **Speicher:** Deutlich geringerer RSS-Footprint
- **GC:** Kein GC-Logging (RuntimeType.NATIVE, `hasGcLogs() == false`)

### CDS-Profil

#### P11-hotspot-cds (Image: jvm-cds)
**Flags:** `-XX:+UseG1GC -XX:MaxRAMPercentage=75`
HotSpot mit **Dynamic Class Data Sharing (CDS)**. Optimiert den Startup durch Vorladen von Klassen aus einem CDS-Archiv.

**Dockerfile:** 2-Stage-Build:
1. Stage 1: App mit `-XX:ArchiveClassesAtExit=app-cds.jsa` starten, 30s Training, dann Kill
2. Stage 2: App mit `-XX:SharedArchiveFile=app-cds.jsa` im ENTRYPOINT starten

Das SharedArchiveFile ist im Dockerfile-ENTRYPOINT gesetzt (nicht in `jvmArgs` / JAVA_TOOL_OPTIONS), da es eine Build-Zeit-Konfiguration ist.

### GraalVM JIT-Profil

#### P12-graalvm-jit (Image: graalvm-jit)
**Flags:** `-XX:+UseG1GC -XX:MaxRAMPercentage=75`
GraalVM JIT-Compiler (JVMCI) anstelle des Standard-C2-Compilers. GraalVM's JIT-Compiler kann in einigen Szenarien bessere Codegenerierung liefern als C2.

**Image:** `ghcr.io/graalvm/jdk-community:25`

---

## 6. Docker-Image-Architektur

### 10 Images (5 Standard + 5 EBICS)

| Tag | Dockerfile | Base Image | Beschreibung |
|-----|-----------|------------|-------------|
| `tfl4-ek-bench:jvm` | `Dockerfile` | `eclipse-temurin:25-jre` | HotSpot Baseline |
| `tfl4-ek-bench:jvm-ek` | `Dockerfile.with-ek` | `eclipse-temurin:25-jre` | HotSpot + EBICS-Kernel |
| `tfl4-ek-bench:openj9` | `Dockerfile.openj9` | `ibm-semeru-runtimes:open-25-jre` | Eclipse OpenJ9 |
| `tfl4-ek-bench:openj9-ek` | `Dockerfile.openj9.with-ek` | `ibm-semeru-runtimes:open-25-jre` | OpenJ9 + EBICS-Kernel |
| `tfl4-ek-bench:native` | `Dockerfile.native` | `ghcr.io/graalvm/native-image-community:25` | GraalVM Native (Multi-Stage) |
| `tfl4-ek-bench:native-ek` | `Dockerfile.native.with-ek` | *(gleich)* | Native + EBICS-Kernel |
| `tfl4-ek-bench:graalvm-jit` | `Dockerfile.graalvm-jit` | `ghcr.io/graalvm/jdk-community:25` | GraalVM JIT (JVMCI) |
| `tfl4-ek-bench:graalvm-jit-ek` | `Dockerfile.graalvm-jit.with-ek` | *(gleich)* | GraalVM JIT + EBICS-Kernel |
| `tfl4-ek-bench:jvm-cds` | `Dockerfile.cds` | `eclipse-temurin:25-jre` | Dynamic CDS (2-Stage Training) |
| `tfl4-ek-bench:jvm-cds-ek` | `Dockerfile.cds.with-ek` | *(gleich)* | CDS + EBICS-Kernel |

### Automatischer Image-Build

`DockerImageBuilder.ensureImagesExist()` prueft vor jedem Benchmark, ob alle benoetigten Images lokal vorhanden sind. Fehlende Images werden automatisch gebaut (inkl. Maven-Package, falls die JAR fehlt). Mit `--rebuild` wird der Neuaufbau aller Images erzwungen.

### EBICS-Image-Konvention

Fuer EBICS-Szenarien wird an jeden Image-Tag das Suffix `-ek` angehaengt (`BenchmarkPlan.withEbicsImages()`). Die EBICS-Varianten enthalten die EK-JARs, EBICS-Client-Konfiguration und PKCS#12-Schluessel.

---

## 7. Messwerte und deren Erhebung

### Gleiche Messwerte fuer alle Konfigurationen

Jede Konfiguration durchlaeuft exakt den gleichen Messprozess. Es werden keine Messwerte selektiv weggelassen. Pro Run entstehen folgende strukturierte Messwerte:

| # | Messwert | Einheit | Erhebung |
|---|----------|---------|----------|
| 1 | `readinessMs` | ms | Zeitdifferenz: vor `docker run` bis Readiness-Probe HTTP 200 |
| 2 | `firstSeconds` | s | Erster Workload-Request nach Readiness (Cold-Path) |
| 3 | `latencyP50` | s | 50. Perzentil der 500 Mess-Requests (Median) |
| 4 | `latencyP95` | s | 95. Perzentil -- relevantester Wert fuer SLOs |
| 5 | `latencyP99` | s | 99. Perzentil -- Tail-Latenz, zeigt Worst-Case-Verhalten |
| 6 | `latencyMean` | s | Arithmetisches Mittel aller Mess-Request-Latenzen |
| 7 | `throughputReqPerSec` | req/s | measureRequests / totalMeasureTimeSeconds |
| 8 | `totalMeasureTimeSeconds` | s | Wandzeit der Messphase |
| 9 | `cpuLoadAvg` | % | Mittlere CPU% aus 10 Docker-Stats-Snapshots waehrend LOAD |
| 10 | `memLoadAvg` | % | Mittlere Mem% waehrend LOAD |
| 11 | `memLoadMax` | % | Maximale Mem% waehrend LOAD |
| 12-16 | IDLE/POST Stats | % | 3 Snapshots vor/nach Last (Ruhezustand-Vergleich) |
| 17 | `gcCount` | int | Anzahl GC-Pausen (aus GC-Log geparst) |
| 18 | `fullGcCount` | int | Anzahl Full GCs |
| 19 | `totalPauseMs` | ms | Kumulierte GC-Pausenzeit |
| 20 | `maxPauseMs` | ms | Laengste einzelne GC-Pause |
| 21 | `gcOverheadPercent` | % | GC-Pausenzeit / Gesamtlaufzeit * 100 |
| 22 | `peakHeapAfterGcMb` | MB | Peak Heap-Auslastung nach GC |
| 23 | `readinessCheckUsed` | enum | ACTUATOR_READINESS / ACTUATOR_HEALTH / WORKLOAD_UNTIL_200 |
| 24 | `repetition` | int | 1-basierte Wiederholungsnummer |
| 25-28 | Messprofil | - | warmupRequests, measureRequests, concurrency, sleepMs |
| 29 | `effectiveJavaToolOptions` | String | Tatsaechlich gesetzte JVM-Flags |
| 30 | `latenciesSeconds` | List | Alle 500 Einzellatenzen (nur in JSON + Excel-Rohdaten) |

### Perzentil-Berechnung

Die Latenzen werden sortiert, Perzentile per Index berechnet:
- p50 = Wert an Position `ceil(0.50 * n) - 1`
- p95 = Wert an Position `ceil(0.95 * n) - 1`
- p99 = Wert an Position `ceil(0.99 * n) - 1`

Bei 500 Mess-Requests: p50 = Position 250, p95 = Position 475, p99 = Position 495.

### Docker-Stats-Phasen

Docker-Stats werden in drei Phasen erhoben:
1. **IDLE** (3 Snapshots, 1s Intervall): Direkt nach Readiness, vor jeglicher Last. Zeigt Baseline-Ressourcenverbrauch.
2. **LOAD** (10 Snapshots, parallel zur Messphase): Waehrend der 500 Mess-Requests. Zeigt Verbrauch unter Last.
3. **POST** (3 Snapshots): Direkt nach Ende der Messphase. Zeigt Erholungsverhalten.

Die Werte `cpuLoadAvg`, `memLoadAvg`, `memLoadMax` im CSV beziehen sich ausschliesslich auf die LOAD-Phase.

**Hinweis zu CPU%:** Der Wert kann bei Multi-Thread-GCs (G1, ZGC, Parallel) ueber 100% liegen. Docker meldet CPU-Zeit relativ zu einem Kern. Bei `--cpus 1` ist die tatsaechliche CPU-Zuteilung auf 1 Kern begrenzt, aber docker stats zaehlt die Nutzung aller Threads kumulativ.

---

## 8. GC-Log-Erfassung und -Auswertung

Die GC-Log-Auswertung ist **vollstaendig automatisiert**:

### Dreistufige Verarbeitung

| Stufe | Beschreibung | Output |
|-------|-------------|--------|
| 1. Rohlog | Vollstaendige Container-Logs nach dem Run | `bench-results/gc-logs/<config>-rep<n>.log` |
| 2. GcSummary | Aggregierte Metriken | gcCount, fullGcCount, totalPauseMs, maxPauseMs, gcOverheadPercent, peakHeapAfterGcMb |
| 3. GC-Events | Einzelne Pausen mit Zeitstempel | Fuer GC-Timeline-Chart (Scatter-Diagramm) |

### Parser-Auswahl nach RuntimeType

| RuntimeType | Parser | Log-Format |
|-------------|--------|-----------|
| HOTSPOT | `GcLogParser.java` | Unified Logging (`-Xlog:gc*:stdout`, JEP 158) |
| OPENJ9 | `OpenJ9GcLogParser.java` | XML-basiertes verbose:gc (unterstuetzt gencon, balanced, optavgpause, optthruput) |
| NATIVE | *(kein Parsing)* | Kein GC-Log verfuegbar (`hasGcLogs() == false`) |

### Rohlogs fuer externe Tools

Die gespeicherten Rohlogs (`bench-results/gc-logs/`) koennen mit externen Tools wie GCViewer, GCEasy oder Eclipse MAT analysiert werden.

---

## 9. Statistische Methodik

### BenchStats

Die Klasse `BenchStats` implementiert die statistische Auswertung:

- **Mittelwert:** Arithmetisches Mittel (`mean()`)
- **Standardabweichung:** Stichproben-Stddev mit Bessel-Korrektur (Division durch `n-1`, `sampleStddev()`)
- **95%-Konfidenzintervall:** `t(alpha/2, n-1) * s / sqrt(n)` mit vorberechneten t-Werten (df=1..120, danach z=1.96)
- **Relative Baseline:** `relativeToBaseline()` normalisiert Messwerte auf einen Referenzwert (= 100%)

### Fehlerbalken in Charts

Die Excel-Diagramme zeigen 95%-Konfidenzintervall-Fehlerbalken bei aggregierten Wiederholungen. Bei 3 Wiederholungen (df=2) betraegt der kritische t-Wert 4.303, was die Unsicherheit bei kleinen Stichproben korrekt widerspiegelt.

### Inkrementelle CSV-Sicherung

Waehrend des Benchmarks wird nach jedem erfolgreichen Run das Ergebnis sofort an eine Partial-CSV angehaengt. Damit sind Teilergebnisse bei spaeteren Fehlern (OOM-Kill, Verbindungsabbruch) nicht verloren. Die Partial-CSV wird nach erfolgreichem Abschluss geloescht.

---

## 10. Excel-Darstellung

### Einzelrun-Excel (7 Sheets)

| Sheet | Inhalt | Diagramme |
|-------|--------|-----------|
| **Uebersicht** | Alle Messwerte inkl. GC-Metriken. Section-Header, AutoFilter, Freeze-Pane. | -- |
| **Latenzen** | p50/p95/p99/Mean pro Config | Gruppiertes Balkendiagramm mit 95%-CI-Fehlerbalken |
| **Startup & Throughput** | Readiness, First, Throughput | Balkendiagramme mit CI |
| **Ressourcen** | CPU%/Mem% (IDLE/LOAD/POST) | Gruppiertes Balkendiagramm mit CI |
| **Rohdaten** | Alle 500 Einzellatenzen pro Config | -- |
| **GC-Zusammenfassung** | GC-Metriken tabellarisch (Pausen, Overhead, Peak Heap) | Logarithmisches Balkendiagramm |
| **GC-Timeline** | GC-Pausen ueber Zeit pro Config | Scatter-Chart (gerade Linien, `smooth=false`) |

### Merge-Excel (6 Sheets)

| Sheet | Inhalt |
|-------|--------|
| **Uebersicht alle Runs** | Alle CSVs zusammengefasst, AutoFilter |
| **Latenzen alle Runs** | Latenz-Vergleich ueber Runs mit CI-Fehlerbalken |
| **Startup alle Runs** | Startup/Throughput-Vergleich mit CI |
| **Ressourcen alle Runs** | Ressourcen + GC-Vergleich mit CI |
| **Zusammenfassung** | Aggregierte Metriken (Mean +/- Stddev) |
| **Ranking** | Normalisierung auf Baseline-Konfiguration (100%) |

### Chart-Features

- **Runtime-Farbkodierung:** Blau = HotSpot, Tuerkis = OpenJ9, Orange = Native
- **95%-Konfidenzintervall-Fehlerbalken** (t-Verteilung)
- **Logarithmische Y-Achse** fuer GC-Pausen-Diagramm (grosse Unterschiede zwischen GCs)
- **AutoFilter + Freeze-Pane** auf allen Datensheets
- **Sauberer weisser Hintergrund** mit Farbkodierung (kein gestreiftes Excel-Tabellenformat)

### Farbschema

| Element | Farbe | Hex |
|---------|-------|-----|
| Header-Zeilen | Dunkelblau, weisse Schrift | #2B579A / #FFFFFF |
| Section-Header | Hellblau | #DCE6F1 |
| p50-Balken | Gruen | #27AE60 |
| p95-Balken | Orange | #F39C12 |
| p99-Balken | Rot | #E74C3C |
| Readiness/Mem%-Balken | Dunkelblau | #2C3E50 |
| Throughput-Balken | Gruen | #27AE60 |
| CPU%-Balken | Orange | #F39C12 |
| HotSpot (Profil-Charts) | Blau | Standardblau |
| OpenJ9 (Profil-Charts) | Tuerkis | Tuerkis |
| Native (Profil-Charts) | Orange | Orange |

---

## 11. CLI-Optionen und Messprofile

### Messprofile

| Profil | Warmup | Messung | Wiederholungen | Einsatz |
|--------|--------|---------|----------------|---------|
| **Standard** | 200 | 500 | 3 | Thesis-Ergebnisse. 200 Warmup genuegen fuer C2-Steady-State. 500 Messungen liefern belastbare Perzentile. |
| **Quick** (`--quick`) | 10 | 30 | 1 | Entwicklungstests. 10 Warmup genuegen nicht fuer C2-Steady-State. p99 nur eingeschraenkt aussagekraeftig (Position 30 * 0.99 ~ 30). |
| **Smoke** (`--smoke`) | 3 | 5 | 1 | Pipeline-Validierung. Keine statistische Aussagekraft. Dient nur zur Pruefung, ob Docker, Endpunkte und Exports funktionieren. Fuer EBICS: n=3 statt 10. |

`--smoke` hat Vorrang ueber `--quick`, falls beide angegeben sind. Explizite CLI-Werte (z.B. `--measureRequests 50`) ueberschreiben die jeweiligen Defaults.

### Vollstaendige CLI-Referenz

| Argument | Default | Beschreibung |
|----------|---------|-------------|
| `--scenario` | interaktiv | json / alloc / ebics-upload |
| `--n` | szenarioabhaengig | Workload-Groesse |
| `--warmupRequests` | 200 (10/3 bei quick/smoke) | Aufwaerm-Requests |
| `--measureRequests` | 500 (30/5 bei quick/smoke) | Mess-Requests |
| `--concurrency` | 1 | Parallele Requests |
| `--sleepBetweenRequestsMs` | 0 | Pause zwischen Requests (ms) |
| `--repetitions` | 3 (1 bei quick/smoke) | Wiederholungen pro Konfiguration |
| `--jvmArgs` | -- | JVM-Flags fuer einzelnen Run (ueberschreibt Plan) |
| `--configName` | `cli-custom` | Config-Name (nur mit --jvmArgs) |
| `--dockerImage` | `tfl4-ek-bench:jvm` | Image (nur mit --jvmArgs) |
| `--profiles` | -- | Nur 12 Laufzeitprofile statt kombiniertem Plan |
| `--rebuild` | -- | Erzwingt Neuaufbau von Maven-JAR + Docker-Images |
| `--skipTravicLink` | -- | TravicLink nicht automatisch starten |
| `--merge-excel` | -- | Standalone CSV-Merge zu Excel (kein Benchmark) |
| `--quick` | -- | Schnelldurchlauf (10/30/1) |
| `--smoke` | -- | Smoke-Test (3/5/1), Vorrang ueber --quick |

---

## 12. Limitierungen

| Limitierung | Auswirkung | Mitigation |
|-------------|-----------|------------|
| **Synthetische Workloads** | Isolieren JVM-Effekte, bilden keine reale Business-Logik ab | EBICS-Szenario als realitaetsnaher Workload |
| **1 CPU / 768 MB** | Kein Produktionsszenario, aber typisch fuer Cloud-Kontingente | Bewusst gewaehlt, um Container-Constraints abzubilden |
| **Kein Netzwerk-Jitter** | Alle Requests lokal (Host -> Container) | Isoliert JVM-Effekte von Netzwerk-Effekten |
| **JIT-Varianz** | C2-Kompilierung kann zwischen Runs variieren | 3 Wiederholungen + Randomisierung + CI-Fehlerbalken |
| **Kein Profiling** | Keine CPU-Flame-Graphs oder Heap-Dumps | Framework misst Black-Box-Metriken; Profiling waere separater Schritt |
| **Docker-Stats Aufloesung** | `docker stats` liefert ~1s Aufloesung | 10 Snapshots waehrend LOAD-Phase, Mittelwert gebildet |
| **CPU% Interpretation** | Kann >100% bei Multi-Thread-GCs anzeigen | Kommentar in Excel-Uebersicht erklaert die Semantik |
| **Native Image: kein GC-Log** | GC-Verhalten von Native Images nicht direkt vergleichbar | Latenz- und Ressourcen-Metriken als Proxy |
| **CDS: Training-Artefakt** | CDS-Archiv ist Build-Zeit-Artefakt, repraesentiert nur den Trainings-Workload | 30s-Training deckt typische Startup-Klassen ab |
