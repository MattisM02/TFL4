# GraalVM Native Image — Architektur und Build-Anleitung

Diese Anleitung beschreibt die vollstaendige Architektur des GraalVM Native
Image Builds fuer die TFL4-Benchmarking-Applikation. Der Build verwendet eine
hybride Strategie: Spring Boot AOT fuer Spring-Klassen, Tracing Agent fuer die
EBICS-Kernel-JARs und eine eigene GraalVM Feature-Klasse fuer die
IAIK-JCE-Security-Integration.

## Uebersicht

```
┌─────────────────────────────────────────────────────────────────────┐
│  Native Image Build Pipeline                                        │
│                                                                     │
│  1. mvnw -Pnative spring-boot:process-aot package -DskipTests      │
│     → FAT-JAR mit Spring-AOT-generierten Klassen                    │
│                                                                     │
│  2. docker build -f Dockerfile.native.with-ek                       │
│     → Extrahiert FAT-JAR                                            │
│     → Kompiliert IaikSecurityFeature                                │
│     → native-image mit Classpath + Feature                          │
│     → Minimales Debian Runtime-Image (~153 MB)                      │
└─────────────────────────────────────────────────────────────────────┘
```

## Voraussetzungen

| Komponente | Version / Pfad |
|---|---|
| GraalVM JDK | 25.0.2+10.1 (`C:\Users\mme\.jdks\graalvm-jdk-25.0.2+10.1`) |
| Temurin JDK | 25.0.1 (`C:\Users\mme\.jdks\temurin-25.0.1`) |
| Docker Desktop | mit mindestens 6 GB RAM fuer den Build |
| Docker Base Image | `ghcr.io/graalvm/native-image-community:25` |

## Schritt 1: FAT-JAR mit AOT-Processing bauen

```powershell
$env:JAVA_HOME = "C:\Users\mme\.jdks\graalvm-jdk-25.0.2+10.1"
.\mvnw.cmd -Pnative spring-boot:process-aot package -DskipTests
```

Dies erzeugt `target/jvm-optim-demo-0.0.1-SNAPSHOT.jar` (~55 MB) mit:
- Applikationsklassen unter `BOOT-INF/classes/`
- Spring-AOT-generierte Klassen und Metadata
- Reachability-Metadata unter `META-INF/native-image/`
- Alle Dependencies unter `BOOT-INF/lib/`

## Schritt 2: Docker Native Image bauen

```powershell
# Ohne EBICS-Konfiguration (nur /json und /alloc Endpunkte):
docker build -f Dockerfile.native -t tfl4-ek-bench:native .

# Mit EBICS-Konfiguration (alle Endpunkte inkl. /ebics/upload):
docker build -f Dockerfile.native.with-ek -t tfl4-ek-bench:native-ek .
```

Build-Dauer: ca. 2,5 Minuten. Ergebnis: ~122 MB Native Binary, ~153 MB Docker Image.

## Schritt 3: Testen

```powershell
docker run --rm -p 8080:8080 tfl4-ek-bench:native-ek

# In neuem Terminal:
Invoke-WebRequest -Uri http://localhost:8080/actuator/health
Invoke-WebRequest -Uri http://localhost:8080/json
Invoke-WebRequest -Uri "http://localhost:8080/ebics/test"    # nur mit -ek Variante
```

---

## Architektur im Detail

### Spring Boot Fat-JAR Extraktion

`native-image -jar app.jar` funktioniert NICHT mit Spring Boot Fat-JARs, weil
die Main-Class auf `JarLauncher` zeigt und die echten Klassen unter
`BOOT-INF/classes/` verschachtelt sind.

**Loesung:** Das Fat-JAR wird im Docker-Build extrahiert und der Classpath
manuell zusammengebaut:

```
meta/                              ← nur META-INF/native-image/ (Metadata)
feature-classes/                   ← kompilierte IaikSecurityFeature
extracted/BOOT-INF/classes/        ← App-Klassen + Spring AOT
extracted/BOOT-INF/lib/*.jar       ← alle Dependencies
```

**Wichtig:** `extracted/` (Root) darf NICHT auf dem Classpath liegen, weil:
- `org/springframework/boot/loader/` (JarLauncher etc.) build-time-init Probleme
  verursacht: `NestedFileSystemProvider` im Image Heap, `JarUrlConnection` mit
  `nested:` Protokoll, `DefaultCleaner` Daemon-Thread
- `META-INF/services/java.nio.file.spi.FileSystemProvider` registriert
  `NestedFileSystemProvider` via SPI, was den Build crasht

Daher wird NUR `META-INF/native-image/` in ein separates `meta/`-Verzeichnis
kopiert.

### IaikSecurityFeature (GraalVM Feature)

Datei: `native-feature/IaikSecurityFeature.java` (~300 Zeilen)

Die IAIK-JAR (`iaik-jce-full-unlimited-5.63.jar`) ist NICHT mit einem
JCE-trusted CA-Zertifikat signiert. Ausserdem entfernt GraalVMs interne
`SecurityServicesFeature` alle Provider-Services, die zur Build-Zeit nicht als
"benutzt" erkannt werden. Die Feature-Klasse loest beide Probleme:

#### 1. JCE-Verifikations-Patch

```
beforeAnalysis() {
    // IAIK-Provider registrieren
    Security.addProvider(new IAIK())

    // JceSecurity.verificationResults Cache patchen:
    // WeakIdentityWrapper-keyed ConcurrentHashMap,
    // Wert = PROVIDER_VERIFIED sentinel (null Object)
    // → wird spaeter von FieldValueTransformer ins Image Heap geschrieben
}
```

#### 2. Service-Instanziierung (gegen SecurityServicesFeature-Stripping)

```
beforeAnalysis() {
    // ALLE 408 IAIK-Services via JCA-API instanziieren:
    // Cipher.getInstance("PBES2", iaikProvider)
    // Mac.getInstance(...)
    // etc.
    // → 407 OK, 1 fehlgeschlagen (SecureRandom wird uebersprungen)
    //
    // Einfaches Class.forName() genuegt NICHT — die JCA-API muss
    // tatsaechlich aufgerufen werden, damit SecurityServicesFeature
    // den Service als "used" erkennt.
}
```

#### 3. Reflection-Registrierung

```
beforeAnalysis() {
    // Alle 407 IAIK-Service-Klassen fuer Reflection registrieren
    // (noetig fuer JCA Provider-Lookup zur Laufzeit)
}
```

#### 4. NativeSchemaFactory-Registration

```
beforeAnalysis() {
    System.setProperty(
        "javax.xml.validation.SchemaFactory:http://www.w3.org/2001/XMLSchema",
        "de.mattis.jvmoptimdemo.NativeSchemaFactory"
    );
}
```

### NativeSchemaFactory (XSD-Schema-Aufloesung)

Datei: `src/main/java/.../jvmoptimdemo/NativeSchemaFactory.java`

Xerces kann im Native Image keine `resource:`-Protokoll-URLs lesen. Die
`NativeSchemaFactory` ist eine `SchemaFactory`-Subklasse, die:

1. Die System-Default-SchemaFactory via `SchemaFactory.newDefaultInstance()`
   erstellt (oeffentliche JAXP-API, keine `com.sun.*`-Interna)
2. Automatisch einen `LSResourceResolver` setzt, der `resource:`-URLs via
   `ClassLoader.getResourceAsStream()` auflöst
3. Einen `ChainedResolver` bereitstellt, falls Aufrufer ihren eigenen Resolver
   setzen (unserer dient als Fallback)

Registrierung:
- `META-INF/services/javax.xml.validation.SchemaFactory` (JAXP ServiceLoader)
- `System.setProperty()` in `main()` und `IaikSecurityFeature.beforeAnalysis()`

### Build-Zeit-Initialisierung

| Flag | Grund |
|---|---|
| `--initialize-at-build-time=ch.qos.logback` | Netty erzeugt Logback-Logger bei Class-Init |
| `--initialize-at-build-time=org.slf4j` | SLF4J-Binding bei Class-Init |
| `--initialize-at-build-time=iaik` | IAIK-Provider-Instanz im Image Heap behalten |
| `--initialize-at-run-time=iaik.security.random` | Klassen mit `Random`/`SecureRandom` static Fields (GraalVM verbietet Random im Image Heap wegen gecachter Seed-Werte) |

### Weitere native-image Flags

| Flag | Grund |
|---|---|
| `--no-fallback` | Kein JVM-Fallback, reines Native Binary |
| `-H:+ReportExceptionStackTraces` | Bessere Fehlerausgabe bei Build-Fehlern |
| `-march=compatibility` | Breite CPU-Kompatibilitaet (kein AVX512 etc.) |
| `-H:AdditionalSecurityProviders=iaik.security.provider.IAIK` | Verhindert, dass der IAIK-Provider beim Filtern als "unused" entfernt wird |
| `--features=de.mattis.jvmoptimdemo.IaikSecurityFeature` | Registriert die Feature-Klasse |
| `-J--add-opens=java.base/javax.crypto=ALL-UNNAMED` | JPMS-Zugriff fuer Reflection in `javax.crypto.JceSecurity` |

---

## Reachability-Metadata erneuern (Tracing Agent)

Falls sich Dependencies aendern oder neue Reflection-Pfade hinzukommen,
muss die Metadata neu erzeugt werden.

### Via Docker (empfohlen)

```powershell
# 1. FAT-JAR bauen (siehe Schritt 1)

# 2. Tracing-Agent-Container starten
docker build -f Dockerfile.tracing -t tfl4-tracing .
docker run --rm -p 8080:8080 -v "${PWD}/tracing-output:/output" tfl4-tracing

# 3. Alle Endpunkte mehrfach aufrufen (in neuem Terminal)
Invoke-WebRequest -Uri http://localhost:8080/actuator/health
Invoke-WebRequest -Uri http://localhost:8080/json
Invoke-WebRequest -Uri http://localhost:8080/alloc
Invoke-WebRequest -Uri "http://localhost:8080/ebics/upload?n=3"
# Jeden Endpunkt 2-3 Mal aufrufen fuer Lazy-Initialization-Pfade

# 4. Container stoppen (Ctrl+C) — Agent schreibt Metadata beim Shutdown

# 5. Metadata kopieren
Copy-Item tracing-output/* src/main/resources/META-INF/native-image/ -Force
```

### GraalVM 25 Metadata-Format

GraalVM 25 verwendet das neue unifizierte Format: eine einzelne Datei
`reachability-metadata.json` statt der alten separaten Dateien
(`reflect-config.json`, `resource-config.json`, etc.).

Die Datei liegt unter:
```
src/main/resources/META-INF/native-image/reachability-metadata.json
```

Sie enthaelt aktuell: 105 IAIK-Crypto-Klassen, 207 EBICS-Kernel-Klassen,
5 PKCS12-Klassen, 273 XML/JAXB-Referenzen, sowie Glob-Patterns fuer
XSD/DTD-Ressourcen.

Spring AOT generiert zusaetzlich eigene Metadata unter:
```
META-INF/native-image/de.mattis/jvm-optim-demo/
```

---

## Build-Ergebnis

| Metrik | Wert |
|---|---|
| Typen (reachable) | ~25.574 |
| Reflection-Registrierungen | ~9.935 |
| Image-Groesse | ~122 MB (Binary), ~153 MB (Docker Image) |
| Build-Dauer | ~2,5 Minuten |
| IAIK-Services | 407 OK, 1 uebersprungen (SecureRandom) |
| Runtime-Base | `debian:bookworm-slim` (keine JVM noetig) |

## Dateiuebersicht

| Datei | Zweck |
|---|---|
| `Dockerfile.native` | Native Image ohne EBICS-Config |
| `Dockerfile.native.with-ek` | Native Image mit EBICS-Config (Benchmark-Variante) |
| `Dockerfile.tracing` | Tracing-Agent-Container fuer Metadata-Erzeugung |
| `native-feature/IaikSecurityFeature.java` | GraalVM Feature (JCE-Patch + Service-Instanziierung) |
| `src/.../NativeSchemaFactory.java` | SchemaFactory fuer `resource:`-Protokoll |
| `src/.../ResourcenOptimierungTfl4Application.java` | Setzt NativeSchemaFactory System Property |
| `src/.../resources/META-INF/native-image/reachability-metadata.json` | Tracing-Agent-Metadata |
| `src/.../resources/META-INF/services/javax.xml.validation.SchemaFactory` | JAXP ServiceLoader fuer NativeSchemaFactory |
| `pom.xml` (Profil `native`) | Maven-Profil mit `native-maven-plugin` + AOT |
