package de.mattis.resourcenoptimierung.bench;

/**
 * Beschreibt, welcher Mechanismus zur Ermittlung der Readiness
 * eines Containers während eines Benchmark-Runs verwendet wurde.
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
 * einordnen zu können.
 */
public enum ReadinessCheckUsed {

    /**
     * Readiness wurde über den dedizierten Spring-Boot-Endpunkt
     * /actuator/health/readiness ermittelt.
     *
     * Dies ist der bevorzugte und semantisch korrekteste Mechanismus,
     * da er explizit zwischen Liveness und Readiness unterscheidet.
     *
     * Voraussetzung ist eine passende Actuator-Konfiguration.
     */
    ACTUATOR_READINESS,

    /**
     * Fallback: Readiness wurde über den allgemeinen
     * /actuator/health-Endpunkt ermittelt.
     *
     * Dieser Mechanismus wird genutzt, wenn der dedizierte
     * Readiness-Endpunkt nicht verfügbar ist.
     *
     * Dabei wird nicht explizit zwischen Liveness und Readiness
     * unterschieden.
     */
    ACTUATOR_HEALTH,

    /**
     * Letzter Fallback: Der Service gilt als bereit, sobald ein
     * HTTP-GET auf den Workload-Endpunkt erstmals mit Status 200
     beantwortet wird.
     *
     * Dieser Ansatz ist technisch robust, aber semantisch ungenauer,
     * da Readiness aus der Erreichbarkeit eines Business-Endpunkts
     abgeleitet wird.
     */
    WORKLOAD_UNTIL_200
}