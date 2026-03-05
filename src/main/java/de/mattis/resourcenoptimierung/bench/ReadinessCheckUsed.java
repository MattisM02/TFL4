package de.mattis.resourcenoptimierung.bench;

/**
 * Beschreibt, welcher Mechanismus zur Ermittlung der Readiness
 * eines Containers waehrend eines Benchmark-Runs verwendet wurde.
 *
 * Die Readiness-Zeit ist die Dauer vom Start des Containers bis zu dem
 * Zeitpunkt, an dem der Service als bereit gilt und Requests
 * erfolgreich beantworten kann.
 *
 * Da nicht jede Anwendung oder Konfiguration denselben Readiness-Endpoint
 * bereitstellt, wird eine Fallback-Strategie verwendet.
 * Dieses Enum dokumentiert, welcher Check letztlich erfolgreich war.
 *
 * Der verwendete Mechanismus wird im RunResult gespeichert und
 * in Konsole sowie Exporten ausgegeben, um Messergebnisse korrekt
 * einordnen zu koennen.
 *
 * WICHTIG fuer die Interpretation:
 * - ACTUATOR_READINESS: Readiness = Spring-Boot-Kontext vollstaendig initialisiert.
 *   Das ist der sauberste Messwert.
 * - ACTUATOR_HEALTH: Readiness = Health-Check OK. Aehnlich wie ACTUATOR_READINESS,
 *   aber weniger praezise (unterscheidet nicht Liveness vs. Readiness).
 * - WORKLOAD_UNTIL_200: Readiness = erster erfolgreicher Business-Request.
 *   Bei EBICS-Szenarien bedeutet das: EK-Initialisierung (inkl. HPB-Abruf
 *   der Bank-Keys) ist in der Readiness-Zeit enthalten!
 *   Readiness = JVM-Start + Spring-Init + EK-Init + HPB-Roundtrip.
 *   Das ist beabsichtigt: Der Service ist erst "ready", wenn er EBICS-
 *   Operationen tatsaechlich ausfuehren kann.
 */
public enum ReadinessCheckUsed {

    /**
     * Readiness wurde ueber den dedizierten Spring-Boot-Endpunkt
     * /actuator/health/readiness ermittelt.
     *
     * Dies ist der bevorzugte und semantisch korrekteste Mechanismus,
     * da er explizit zwischen Liveness und Readiness unterscheidet.
     *
     * Voraussetzung ist eine passende Actuator-Konfiguration.
     *
     * Was enthalten ist: JVM-Start + Spring-Context-Initialisierung.
     * Was NICHT enthalten ist: Lazy-Init von EK (erst beim ersten Upload/Download).
     */
    ACTUATOR_READINESS,

    /**
     * Fallback: Readiness wurde ueber den allgemeinen
     * /actuator/health-Endpunkt ermittelt.
     *
     * Dieser Mechanismus wird genutzt, wenn der dedizierte
     * Readiness-Endpunkt nicht verfuegbar ist.
     *
     * Dabei wird nicht explizit zwischen Liveness und Readiness
     * unterschieden.
     *
     * Was enthalten ist: JVM-Start + Spring-Context-Initialisierung.
     * Was NICHT enthalten ist: Lazy-Init von EK.
     */
    ACTUATOR_HEALTH,

    /**
     * Letzter Fallback: Der Service gilt als bereit, sobald ein
     * HTTP-GET auf den Workload-Endpunkt erstmals mit Status 200
     * beantwortet wird.
     *
     * Dieser Ansatz ist technisch robust, aber semantisch ungenauer,
     * da Readiness aus der Erreichbarkeit eines Business-Endpunkts
     * abgeleitet wird.
     *
     * ACHTUNG bei EBICS-Szenarien:
     * Wenn dieser Fallback greift, enthaelt die Readiness-Zeit auch:
     * - EK-Initialisierung (Lizenzsetzung, Key-Loading)
     * - HPB-Abruf der Bank-Keys (Netzwerk-Roundtrip zum Bankserver)
     * Die gemessene Readiness ist dann: JVM-Start + Spring-Init + EK-Init + HPB.
     *
     * Bei nicht-EBICS-Szenarien (/json, /alloc) enthaelt die Readiness-Zeit
     * nur den ersten Business-Request, was typischerweise JIT-/Classloading-Kosten zeigt.
     */
    WORKLOAD_UNTIL_200
}
