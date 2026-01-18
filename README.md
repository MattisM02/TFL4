## README

# Ressourcen-Optimierung von Spring-Boot-basierten Container-Anwendungen

**Benchmark-Demo für JVM-Flags (Compressed OOPs, Compact Object Headers) & GraalVM Native Image**

Dieses Repository enthält eine kleine Spring-Boot-Anwendung **plus** ein CLI-Benchmark-Tool, um unterschiedliche Laufzeit-Varianten reproduzierbar zu vergleichen:

* **JVM (Baseline)**
* **JVM mit Flags** (z. B. `-XX:-UseCompressedOops`, `-XX:+UseCompactObjectHeaders`)
* **GraalVM Native Image** (als separater Docker-Image-Target)

Ziel ist es, Effekte auf **Ressourcenbedarf** (Memory/CPU), **Startup/Readiness** und **Latenzen** messbar zu machen und die Ergebnisse im Rahmen einer wissenschaftlichen Arbeit sauber einzuordnen.

---

## 1) Motivation & Kontext

In Container-/Cloud-Umgebungen sind Ressourcen direkt an Kosten gekoppelt (Requests/Limits, Node-Dichte, Autoscaling). Gerade Java/Spring-Boot-Services sind oft:

* speicherintensiv (Heap + Metaspace + Native Memory)
* startup-lastig (Skalierung, Rollouts)
* abhängig von GC-/Heap-Layout-Effekten

Dieses Projekt untersucht, welche **Laufzeit-/VM-Optionen** den Ressourcenbedarf reduzieren können und welche **Trade-offs** entstehen.

---

## 2) Was ist im Repo enthalten?

### A) Spring Boot Demo-App

Eine minimale REST-Anwendung mit zwei Workload-Endpoints:

* `GET /json?n=200000`
  Erzeugt `n` DTOs und gibt sie als JSON zurück.
  **Charakter:** payload-/serialisierungs-lastig, relativ heap-lastig durch Objektgraph.

* `GET /alloc?n=10000000`
  Erzeugt in Schleifen viele kleine `byte[]`-Allokationen in Chunks.
  **Charakter:** allocation-/GC-lastig, stark synthetisch (Stress-Test).

> Hinweis: Beide Workloads sind **synthetisch gewählt**, um JVM-Effekte isolierter sichtbar zu machen. Ergebnisse sind **nicht 1:1** auf Business-Workloads übertragbar, dienen aber gut zur Demonstration und zur Diskussion von Vor-/Nachteilen.

### B) Benchmark CLI

Ein Java-CLI (Main-Klasse: `BenchCli`) steuert mehrere Benchmark-Runs:

* startet jeweils einen Docker-Container (JVM/Native) mit definierten Limits
* wartet auf Readiness (Actuator oder Fallback)
* misst:

    * **Readiness-Zeit** (ms)
    * **First Request** (s) als Cold-Path-Indikator
    * **Warm Latencies** (p50/p95/p99) über mehrere Requests
    * **Docker Stats** (CPU%, Mem%) in Phasen: IDLE / LOAD / POST
* schreibt Resultate als **CSV** und **JSON** (`bench-results/`)

---

## 3) Architektur/Komponenten (Überblick)

### BenchmarkPlan / BenchmarkConfig

* `BenchmarkPlan.defaultPlan()` definiert die zu testenden Konfigurationen
* `BenchmarkConfig` enthält:

    * `name` (z. B. baseline, coops-off, coh-on, native)
    * `dockerImage` (Tag: `...:jvm` oder `...:native`)
    * `jvmArgs` (JVM Flags für `JAVA_TOOL_OPTIONS`)

### SingleRun (Herzstück)

Ein Run macht u. a.:

1. **docker run** mit:

    * Port Mapping `host:8080 -> container:8080`
    * CPU & Memory Limits (`--cpus 1`, `--memory 768m`)
    * (JVM) `JAVA_TOOL_OPTIONS` aus den Flags
2. **Readiness-Probe** via `ReadinessProber`:

    * `/actuator/health/readiness`
    * `/actuator/health`
    * Fallback: Workload Endpoint bis HTTP 200
3. **Messung**

    * `firstSeconds`: erster GET nach Ready
    * Warmup (20 Requests)
    * Messreihe (100 Requests)
4. **Docker Stats Sampling**

    * IDLE direkt nach readiness
    * LOAD parallel zur Lastphase
    * POST nach Lastphase

### ConsoleSummaryPrinter / ResultExporters

* Konsolen-Zusammenfassung (Readiness, First, Latenz-Perzentile)
* Export nach CSV/JSON zur späteren Auswertung (z. B. in Excel)

---

## 4) Voraussetzungen

### Lokal

* Java (für das Benchmark-CLI)
* Docker (zum Ausführen der Container und `docker stats`)
* Optional: Build-Tooling für Images (je nach Projekt-Setup, z. B. Maven/Gradle + Dockerfile)

### Container Images

Der Default-Plan erwartet Images:

* `jvm-optim-demo:jvm`
* `jvm-optim-demo:native`

> Falls Images anders heißen: `BenchmarkPlan.defaultPlan()` anpassen.

---

## 5) Anwendung bauen & starten (typischer Ablauf)

### 1) Docker Images bauen

Es gibt zwei Targets:

* **JVM Image**: Spring Boot läuft auf normaler JVM
* **Native Image**: Spring Boot als GraalVM Native Binary

Wie genau gebaut wird, hängt von eurem Build-Setup ab (Dockerfile/Maven Plugin/Gradle Plugin).
Wichtig ist nur: Am Ende existieren zwei Docker Images mit den erwarteten Tags.

### 2) Benchmarks ausführen

CLI starten:

* Interaktiv (Scenario wählen):

    * `BenchCli` ohne Parameter starten
* Nicht-interaktiv:

    * `--scenario json --n 200000`
    * `--scenario alloc --n 10000000`

Outputs:

* Konsole: Summary
* Dateien unter `bench-results/`:

    * `results-<timestamp>.csv`
    * `results-<timestamp>.json`

---

## 6) Was bedeuten die Metriken?

* **Readiness (ms)**: Zeit bis Service „bereit“ ist
  Relevant für Rollouts, Autoscaling, Kaltstarts.

* **First (s)**: erster Request nach Readiness
  Zeigt Cold-Path-Effekte (JIT, Lazy Init, Caches).

* **Latency p50/p95/p99 (s)**: typische und tail latencies
  p95/p99 sind besonders relevant für User Experience/SLOs.

* **Docker Mem % / CPU %** (IDLE/LOAD/POST)
  Indikator, ob Optimierungen unter Last wirklich Ressourcen sparen und ob Memory „zurückkommt“.

---

## 7) Interpretation & Grenzen

* Die Workloads sind **synthetisch**.
  Ziel ist: JVM-/Runtime-Effekte sichtbar machen, nicht Business-Logik abbilden.

* Ein einzelner Container mit `--cpus 1` und festem Memory-Limit bildet **nicht** das komplette Produktionsbild ab (Concurrency, horizontale Skalierung, Traffic-Muster).

* Ergebnisse müssen mit Vorsicht interpretiert werden:

    * Unterschiede können durch JIT, GC, Heap-Layout, Container-Limits entstehen
    * Für belastbare Aussagen sind mehrere Wiederholungen und ggf. statistische Betrachtungen sinnvoll

---

## 8) Erweiterungsideen (wenn wir weitergehen wollen)

* Mehr Szenarien:

    * Concurrency (parallel requests)
    * Datenbank-/I/O-lastige Endpoints
    * realistischere Payloads (DTO-Komplexität, JSON Libraries)
* Mehr Messungen:

    * RSS / PSS, Heap/GC Logs, JFR
    * Throughput (req/s) statt nur Single-Request-Latenz
* Methodik:

    * mehrere Runs pro Config, Konfidenzintervalle
    * Störfaktoren kontrollieren (CPU scaling, background noise)
