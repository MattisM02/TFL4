# Benchmark-Methodik und JVM-Flag-Analyse

Fachliche Dokumentation zum Benchmark-Framework für die Thesis *"Ressourcenoptimierung von javabasierten Containeranwendungen im Cloudbetrieb"*.

Zielgruppe: Prüfer und Betreuer mit JVM-Grundkenntnissen.

---

## Inhaltsverzeichnis

1. [Was wird gemessen?](#1-was-wird-gemessen)
2. [Wie wird gemessen?](#2-wie-wird-gemessen)
3. [Die 12 Konfigurationen im Detail](#3-die-12-konfigurationen-im-detail)
4. [Messwerte und deren Erhebung](#4-messwerte-und-deren-erhebung)
5. [Excel-Darstellung](#5-excel-darstellung)
6. [GC-Logging](#6-gc-logging)
7. [Ausblick: Flag-Kombinationen](#7-ausblick-flag-kombinationen)
8. [Ausblick: GraalVM Native Image](#8-ausblick-graalvm-native-image)
9. [Limitierungen](#9-limitierungen)

---

## 1. Was wird gemessen?

Das Framework misst den Einfluss von JVM-Konfigurationen auf das Laufzeitverhalten einer Spring-Boot-Anwendung in Docker-Containern mit festen Ressourcenlimits (1 CPU, 768 MB RAM, kein Swap).

Die gemessenen Dimensionen:

| Dimension | Kennzahlen | Relevanz |
|-----------|-----------|----------|
| **Startup** | Readiness-Zeit (ms) | Skalierungsfähigkeit, Cold-Start-Kosten |
| **Latenz** | First Request, p50, p95, p99, Mean | Service-Level-Objectives, Tail-Latenz |
| **Durchsatz** | Requests/Sekunde | Kapazitätsplanung, Kosten pro Request |
| **Ressourcen** | CPU%, Mem% (IDLE/LOAD/POST) | Container-Sizing, Überprovisionierung |

Drei Szenarien decken unterschiedliche Workload-Profile ab:
- **JSON** (CPU-intensiv): 200.000 Objekte erzeugen + JSON-Serialisierung
- **Alloc** (GC-intensiv): 10 Mio. kurzlebige Byte-Arrays, stresst den Garbage Collector
- **EBICS Upload** (I/O + Crypto): Reale EBICS-Banküberweisung über TravicLink-Server

---

## 2. Wie wird gemessen?

### Isolation: Ein Container pro Konfiguration

Jede Konfiguration wird in einem **eigenen** Docker-Container ausgeführt. Es läuft nie mehr als ein Container gleichzeitig. Der Ablauf pro Run:

```
docker run -d --cpus 1 --memory 768m --memory-swap 768m \
  -e JAVA_TOOL_OPTIONS="<flags>" <image>

→ Readiness-Polling (max 120s)
→ IDLE Docker-Stats (3 Snapshots)
→ First Request
→ Warmup (200 Requests, verworfen)
→ LOAD Docker-Stats starten (10 Snapshots parallel)
→ Messphase (500 Requests, aufgezeichnet)
→ POST Docker-Stats (3 Snapshots)
→ Container stoppen + entfernen
```

### Wiederholungen und Randomisierung

Bei 3 Wiederholungen (Default) werden alle 12 Konfigurationen 3× ausgeführt = **36 Container-Runs**. Pro Durchlauf wird die Reihenfolge **randomisiert** (`Collections.shuffle()`), um systematische Effekte zu eliminieren:
- CPU-Throttling nach längerer Last
- Filesystem-Cache-Aufwärmung
- Docker-Daemon-Overhead-Schwankungen

Die Konsolenausgabe zeigt nach den Einzelergebnissen eine Aggregation pro Konfiguration mit **Mittelwert ± Standardabweichung**.

### Schnelldurchlauf (--quick)

Für Entwicklungstests und schnelle Validierung steht der `--quick`-Modus zur Verfügung: 10 Warmup-Requests, 30 Mess-Requests, 1 Wiederholung. Damit dauert ein vollständiger Durchlauf mit allen 12 Konfigurationen nur wenige Minuten statt mehrerer Stunden.

Die Quick-Defaults dienen als Basis -- explizite CLI-Werte überschreiben sie (z.B. `--quick --measureRequests 100`).

Einschränkung: 10 Warmup-Requests genügen nicht für C2-Steady-State, und bei 30 Mess-Requests ist das p99 nur eingeschränkt aussagekräftig (Position 30 × 0.99 ≈ 30). Für statistisch belastbare Ergebnisse in der Thesis sollten die Standard-Defaults (200/500/3) verwendet werden.

### Flag-Injektion

JVM-Flags werden über die Umgebungsvariable `JAVA_TOOL_OPTIONS` übergeben. Die JVM wertet diese Variable automatisch beim Start aus -- unabhängig vom Startkommando. Jede Konfiguration erhält:

```
JAVA_TOOL_OPTIONS="-Xlog:gc*:stdout <konfigurationsspezifische Flags>"
```

Das GC-Logging-Flag `-Xlog:gc*:stdout` wird **immer** vorangestellt, damit GC-Events in der Container-Standardausgabe landen.

### Swap-Deaktivierung

`--memory-swap 768m` (identisch mit `--memory`) deaktiviert Swap. Das bildet Kubernetes-Verhalten nach, wo Swap standardmäßig deaktiviert ist. OOM-Kills bei Speicherüberschreitung sind damit möglich und erwünscht (realitätsnah).

---

## 3. Die 12 Konfigurationen im Detail

### 3.1 Garbage-Collector-Vergleich

#### baseline (keine Flags)
**GC:** G1GC (Default seit Java 9)
**Algorithmus:** Region-basiert, generational, concurrent marking, mixed collections. Pausenziel: ~200ms.
**Relevanz:** Referenzpunkt für alle Vergleiche. G1 ist der De-facto-Standard für Server-Workloads.

#### zgc (`-XX:+UseZGC`)
**GC:** Z Garbage Collector (seit Java 21 generational by default)
**Algorithmus:** Concurrent, region-basiert, colored pointers, load barriers. Pausenzeiten im Sub-Millisekunden-Bereich, unabhängig von Heap-Größe.
**Trade-off:** Höherer CPU-Overhead durch concurrent Arbeit, dafür extrem niedrige Tail-Latenzen.
**Cloud-Relevanz:** Relevant für latenz-kritische Microservices mit strengen SLOs (z.B. p99 < 10ms).

#### shenandoah (`-XX:+UseShenandoahGC`)
**GC:** Shenandoah (Red Hat, upstream seit Java 12)
**Algorithmus:** Concurrent compacting, Brooks forwarding pointers. Ähnliche Ziele wie ZGC (niedrige Pausen), anderer Ansatz.
**Trade-off:** Etwas höherer Speicher-Overhead durch Forwarding-Pointer. Vergleichspunkt zu ZGC unter identischen Bedingungen.

#### parallel-gc (`-XX:+UseParallelGC`)
**GC:** Parallel Collector (Throughput-Collector)
**Algorithmus:** Stop-the-World, mehrere GC-Threads parallel, generational. Maximiert Durchsatz (= minimale GC-Zeit / Gesamtzeit).
**Trade-off:** Längere einzelne Pausen, dafür höherer Gesamtdurchsatz. Ungeeignet für latenz-sensitive Workloads.
**Cloud-Relevanz:** Batch-Processing, Datenverarbeitung, wo Latenz unkritisch ist.

#### serial-gc (`-XX:+UseSerialGC`)
**GC:** Serial Collector
**Algorithmus:** Single-Thread, Stop-the-World, mark-compact. Minimaler CPU- und Speicher-Overhead.
**Trade-off:** Längste Pausen, aber geringster Footprint. Kein Thread-Management-Overhead.
**Cloud-Relevanz:** Besonders relevant bei `--cpus 1`: Multi-Thread-GCs (G1, ZGC) haben Overhead durch Thread-Koordination, der bei einer einzigen CPU nicht amortisiert wird. Serial GC könnte hier effizienter sein.

### 3.2 G1GC-Tuning

#### g1-low-pause (`-XX:+UseG1GC -XX:MaxGCPauseMillis=50`)
G1 mit aggressiverem Pausenziel (50ms statt Default 200ms). Der GC führt häufigere, kleinere Collections durch, um das Pausenziel einzuhalten. Das kann zu geringerem Durchsatz führen, verbessert aber die Latenz-Verteilung.

#### g1-heap-256m (`-Xmx256m`)
G1 mit eingeschränktem Heap (256 MB bei 768 MB Container-Limit). Erzwingt häufigere GC-Zyklen und frühere Promotions in die Old Generation. Zeigt Verhalten unter Memory-Pressure und ob die Anwendung mit weniger Heap auskommt (= kleinere Container möglich).

#### g1-heap-512m (`-Xmx512m`)
G1 mit mittlerem Heap (512 MB). Vergleichspunkt zwischen Default-Ergonomics (JVM wählt ~25% des verfügbaren Speichers), 256 MB und dem vollen Container-Speicher.

### 3.3 Cloud-relevante Konfigurationen

#### ram-percentage-75 (`-XX:MaxRAMPercentage=75`)
Container-aware Heap-Sizing. Die JVM erkennt das Container-Memory-Limit (768 MB via cgroups) und setzt den maximalen Heap auf 75% davon (~576 MB).

**Hintergrund:** Seit Java 10 ist die JVM container-aware (erkennt cgroup-Limits). `MaxRAMPercentage` steuert, welchen Anteil des erkannten Speichers der Heap maximal einnehmen darf. Der Rest wird für Metaspace, Thread-Stacks, Native Memory, JIT-Code-Cache benötigt. 75% ist ein praxisüblicher Wert in Kubernetes-Deployments.

#### tiered-stop-1 (`-XX:TieredStopAtLevel=1`)
Deaktiviert den C2-Compiler (Server-Compiler). Nur C1 (Client-Compiler) läuft.

**Hintergrund:** Die JVM kompiliert Code in Stufen:
- Stufe 0: Interpreter
- Stufe 1-3: C1-Compiler (schnell, moderate Optimierung)
- Stufe 4: C2-Compiler (langsam, aggressive Optimierungen)

`TieredStopAtLevel=1` stoppt nach C1. Ergebnis:
- **Drastisch schnellerer Startup** (C2-Kompilierung entfällt, C2 ist der Hauptgrund für langsame JVM-Aufwärmung)
- **Geringerer Peak-Durchsatz** bei lang laufenden Workloads (fehlende C2-Optimierungen wie Loop-Unrolling, Escape-Analysis, Inlining)

**Cloud-Relevanz:** Ideal für kurzlebige Container, Serverless Functions, Autoscaling-Szenarien, wo Startup-Zeit kritischer ist als Peak-Performance.

### 3.4 JVM-Interna

#### coops-off (`-XX:-UseCompressedOops`)
Deaktiviert Compressed Ordinary Object Pointers. Normalerweise komprimiert die JVM 64-Bit-Referenzen auf 32 Bit (bei Heaps < 32 GB), was den Speicherverbrauch reduziert. Ohne Compressed Oops sind alle Referenzen 8 Byte statt 4 Byte.

**Erwartung:** Höherer Speicherverbrauch, potenziell höhere Cache-Miss-Rate. Zeigt den Effekt von Compressed Oops quantitativ.

#### coh-on (`-XX:+UseCompactObjectHeaders`)
Experimentelles Feature (erfordert `-XX:+UnlockExperimentalVMOptions`). Reduziert den Object-Header von 12-16 Byte auf 8 Byte.

**Erwartung:** Signifikante Speicherersparnis bei Workloads mit vielen kleinen Objekten (wie JSON-Serialisierung mit 200.000 UserDto-Objekten). Potenziell bessere Cache-Effizienz.

---

## 4. Messwerte und deren Erhebung

### Gleiche Messwerte für alle Konfigurationen

Jede der 12 Konfigurationen durchläuft exakt den gleichen Messprozess. Es werden **keine** Messwerte selektiv weggelassen. Pro Run entstehen 24 strukturierte Messwerte:

| # | Messwert | Einheit | Erhebung |
|---|----------|---------|----------|
| 1 | `readinessMs` | ms | Zeitdifferenz: vor `docker run` bis Readiness-Probe HTTP 200 |
| 2 | `firstSeconds` | s | Erster Workload-Request nach Readiness (Cold-Path) |
| 3 | `latencyP50` | s | 50. Perzentil der 500 Mess-Requests (Median) |
| 4 | `latencyP95` | s | 95. Perzentil -- relevantester Wert für SLOs |
| 5 | `latencyP99` | s | 99. Perzentil -- Tail-Latenz, zeigt Worst-Case-Verhalten |
| 6 | `latencyMean` | s | Arithmetisches Mittel aller Mess-Request-Latenzen |
| 7 | `throughputReqPerSec` | req/s | 500 / totalMeasureTimeSeconds |
| 8 | `totalMeasureTimeSeconds` | s | Wandzeit der Messphase |
| 9 | `cpuLoadAvg` | % | Mittlere CPU% aus 10 Docker-Stats-Snapshots während LOAD |
| 10 | `memLoadAvg` | % | Mittlere Mem% während LOAD |
| 11 | `memLoadMax` | % | Maximale Mem% während LOAD |
| 12-16 | IDLE/POST Stats | % | 3 Snapshots vor/nach Last (Ruhezustand-Vergleich) |
| 17 | `readinessCheckUsed` | enum | ACTUATOR_READINESS / ACTUATOR_HEALTH / WORKLOAD_UNTIL_200 |
| 18 | `repetition` | int | 1-basierte Wiederholungsnummer |
| 19-22 | Messprofil | - | warmupRequests, measureRequests, concurrency, sleepMs |
| 23 | `effectiveJavaToolOptions` | String | Tatsächlich gesetzte JVM-Flags |
| 24 | `latenciesSeconds` | List | Alle 500 Einzellatenzen (nur in JSON + Excel-Rohdaten) |

### Perzentil-Berechnung

Die Latenzen werden sortiert, Perzentile per Index berechnet:
- p50 = Wert an Position `ceil(0.50 × n) - 1`
- p95 = Wert an Position `ceil(0.95 × n) - 1`
- p99 = Wert an Position `ceil(0.99 × n) - 1`

Bei 500 Mess-Requests: p50 = Position 250, p95 = Position 475, p99 = Position 495.

### Docker-Stats-Phasen

Docker-Stats werden in drei Phasen erhoben:
1. **IDLE** (3 Snapshots, 1s Intervall): Direkt nach Readiness, vor jeglicher Last. Zeigt Baseline-Ressourcenverbrauch.
2. **LOAD** (10 Snapshots, parallel zur Messphase): Während der 500 Mess-Requests. Zeigt Verbrauch unter Last.
3. **POST** (3 Snapshots): Direkt nach Ende der Messphase. Zeigt Erholungsverhalten.

Die Werte `cpuLoadAvg`, `memLoadAvg`, `memLoadMax` im CSV beziehen sich ausschließlich auf die LOAD-Phase.

---

## 5. Excel-Darstellung

### Sheets und Inhalte

| Sheet | Inhalt | Diagramme |
|-------|--------|-----------|
| **Übersicht** | Alle 24 Spalten, alle Runs. Section-Header gruppieren: Konfiguration, Startup, Latenzen, Durchsatz, Docker LOAD, Messprofil, Meta. AutoFilter, Freeze-Pane, Zebra-Striping. | -- |
| **Latenzen** | p50/p95/p99/Mean pro Konfiguration | Gruppiertes Balkendiagramm: p50 (grün), p95 (orange), p99 (rot) |
| **Startup & Throughput** | Readiness, First Request, Throughput | 2 Balkendiagramme: Readiness (dunkelblau), Throughput (grün) |
| **Ressourcen** | CPU%/Mem% für IDLE/LOAD/POST | Gruppiertes Balkendiagramm: CPU% LOAD (orange), Mem% LOAD (dunkelblau) |
| **Rohdaten** | Alle 500 Einzellatenzen pro Konfiguration | -- |

### Farbschema

| Element | Farbe | Hex |
|---------|-------|-----|
| Header-Zeilen | Dunkelblau, weiße Schrift | #2B579A / #FFFFFF |
| Section-Header | Hellblau | #DCE6F1 |
| Zebra-Striping | Abwechselnd weiß / hellgrau | #F2F2F2 |
| p50-Balken | Grün | #27AE60 |
| p95-Balken | Orange | #F39C12 |
| p99-Balken | Rot | #E74C3C |
| Readiness/Mem%-Balken | Dunkelblau | #2C3E50 |
| Throughput-Balken | Grün | #27AE60 |
| CPU%-Balken | Orange | #F39C12 |

### Vergleichs-Excel

Nach jedem Benchmark wird automatisch `benchmark-vergleich.xlsx` generiert. Diese fasst **alle** CSV-Dateien aus `bench-results/` zusammen (4 Sheets). Damit können verschiedene Benchmark-Durchläufe (z.B. verschiedene Szenarien oder Zeitpunkte) verglichen werden.

---

## 6. GC-Logging

Durch `-Xlog:gc*:stdout` werden detaillierte GC-Informationen in die Container-Standardausgabe geschrieben:
- GC-Typ und -Ursache (Allocation Failure, System.gc(), Humongous Allocation, ...)
- Pausendauer pro GC-Event
- Heap-Auslastung vor/nach Collection
- Region-Statistiken (bei G1)
- Concurrent-Phase-Zeiten (bei ZGC/Shenandoah)

Diese Logs werden **nicht** automatisch in die CSV/Excel-Messwerte extrahiert. Sie stehen über `docker logs <container-id>` bzw. im `startupLogSnippet` (erste 200 Zeilen) zur manuellen Analyse bereit.

---

## 7. Ausblick: Flag-Kombinationen

Die aktuellen 12 Konfigurationen testen Flags weitgehend isoliert. Für die Thesis relevante Kombinationen:

| Kombination | Flags | Hypothese |
|-------------|-------|-----------|
| ZGC + Cloud-Heap | `-XX:+UseZGC -XX:MaxRAMPercentage=75` | Niedrige Latenzen bei container-aware Heap-Sizing. Zeigt, ob ZGC mit klar definiertem Heap-Limit besser performt als mit Default-Ergonomics. |
| ZGC + C1-only | `-XX:+UseZGC -XX:TieredStopAtLevel=1` | Schnellstmöglicher Startup bei niedrigen Latenzen. Relevant für Autoscaling-Szenarien. |
| Serial + Minimaler Heap | `-XX:+UseSerialGC -Xmx256m` | Absolut minimaler Footprint. Relevant für Sidecar-Container oder serverlose Funktionen mit sehr wenig Memory. |
| G1 + Compact Headers | `-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders` | Speicherersparnis durch kleinere Object-Header im Standard-GC. |
| Parallel + MaxRAMPercentage | `-XX:+UseParallelGC -XX:MaxRAMPercentage=75` | Maximaler Durchsatz bei container-aware Heap. |
| Shenandoah + C1-only | `-XX:+UseShenandoahGC -XX:TieredStopAtLevel=1` | Vergleich: Shenandoah unter identischen Startup-Bedingungen wie ZGC+C1. |

Diese Kombinationen könnten als zusätzliche Konfigurationen im `BenchmarkPlan` ergänzt werden, ohne Änderungen am Messframework.

---

## 8. Ausblick: GraalVM Native Image

GraalVM Native Image kompiliert die gesamte Anwendung Ahead-of-Time (AOT) in ein natives Executable. Das eliminiert die JVM-Startphase (kein Classloading, kein JIT).

**Erwartete Unterschiede:**
- **Startup:** Größenordnung Millisekunden statt Sekunden
- **Peak-Durchsatz:** Typisch 10-30% geringer als JIT-kompilierter Code
- **Speicher:** Deutlich geringerer RSS-Footprint
- **GC:** Serial GC (Default) oder G1 (seit GraalVM 21)

**Voraussetzungen für Integration:**
- Eigener Build-Step (`native-image`), Kompilierung dauert mehrere Minuten
- Eigenes Dockerfile mit GraalVM-Base-Image
- Reflection-Config für den EK-Zugriff (Spring AOT kann vieles automatisch, aber manuelles `reflect-config.json` für die EK-Reflection-Aufrufe nötig)
- `JAVA_TOOL_OPTIONS` wird ignoriert -- Flags müssen zur Build-Zeit festgelegt werden

Die `isNative()`-Erkennung in `BenchmarkConfig` ist bereits vorbereitet: bei `true` wird `JAVA_TOOL_OPTIONS` nicht gesetzt.

---

## 9. Limitierungen

| Limitierung | Auswirkung | Mitigation |
|-------------|-----------|------------|
| **Synthetische Workloads** | Isolieren JVM-Effekte, bilden keine reale Business-Logik ab | EBICS-Szenario als realitätsnaher Workload |
| **1 CPU / 768 MB** | Kein Produktionsszenario, aber typisch für Cloud-Kontingente | Bewusst gewählt, um Container-Constraints abzubilden |
| **Kein Netzwerk-Jitter** | Alle Requests lokal (Host → Container) | Isoliert JVM-Effekte von Netzwerk-Effekten |
| **JIT-Varianz** | C2-Kompilierung kann zwischen Runs variieren | 3 Wiederholungen + Randomisierung, Aggregation mit Stddev |
| **Keine GC-Log-Extraktion** | GC-Events nicht als strukturierte Messwerte | GC-Logs in Container-Ausgabe für manuelle Analyse |
| **Kein Profiling** | Keine CPU-Flame-Graphs oder Heap-Dumps | Framework misst Black-Box-Metriken; Profiling wäre separater Schritt |
| **Docker-Stats Auflösung** | `docker stats` liefert ~1s Auflösung | 10 Snapshots während LOAD-Phase, Mittelwert gebildet |
