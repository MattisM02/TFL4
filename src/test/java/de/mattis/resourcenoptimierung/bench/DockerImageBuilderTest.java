package de.mattis.resourcenoptimierung.bench;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer DockerImageBuilder: Tag-Mapping, Tag-Collection, Maven-Kommando.
 *
 * Hinweis: Die tatsaechlichen Docker- und Maven-Aufrufe (buildImage, packageIfNeeded,
 * imageExists) werden hier NICHT getestet, da sie eine Docker-Runtime erfordern.
 * Sie sind implizit durch die Docker-EndToEnd-Tests abgedeckt.
 */
class DockerImageBuilderTest {

    // ==================== IMAGE_DOCKERFILE_MAP ====================

    @Test
    void map_containsAllTenImages() {
        assertEquals(10, DockerImageBuilder.IMAGE_DOCKERFILE_MAP.size());
    }

    @Test
    void map_jvm_mapsToDockerfile() {
        assertEquals("Dockerfile", DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("tfl4-ek-bench:jvm"));
    }

    @Test
    void map_jvmEk_mapsToDockerfileWithEk() {
        assertEquals("Dockerfile.with-ek", DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("tfl4-ek-bench:jvm-ek"));
    }

    @Test
    void map_openj9_mapsToDockerfileOpenj9() {
        assertEquals("Dockerfile.openj9", DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("tfl4-ek-bench:openj9"));
    }

    @Test
    void map_openj9Ek_mapsToDockerfileOpenj9WithEk() {
        assertEquals("Dockerfile.openj9.with-ek", DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("tfl4-ek-bench:openj9-ek"));
    }

    @Test
    void map_native_mapsToDockerfileNative() {
        assertEquals("Dockerfile.native", DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("tfl4-ek-bench:native"));
    }

    @Test
    void map_nativeEk_mapsToDockerfileNativeWithEk() {
        assertEquals("Dockerfile.native.with-ek", DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("tfl4-ek-bench:native-ek"));
    }

    @Test
    void map_graalvmJit_mapsToDockerfileGraalvmJit() {
        assertEquals("Dockerfile.graalvm-jit", DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("tfl4-ek-bench:graalvm-jit"));
    }

    @Test
    void map_graalvmJitEk_mapsToDockerfileGraalvmJitWithEk() {
        assertEquals("Dockerfile.graalvm-jit.with-ek", DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("tfl4-ek-bench:graalvm-jit-ek"));
    }

    @Test
    void map_jvmCds_mapsToDockerfileCds() {
        assertEquals("Dockerfile.cds", DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("tfl4-ek-bench:jvm-cds"));
    }

    @Test
    void map_jvmCdsEk_mapsToDockerfileCdsWithEk() {
        assertEquals("Dockerfile.cds.with-ek", DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("tfl4-ek-bench:jvm-cds-ek"));
    }

    @Test
    void map_unknownTag_returnsNull() {
        assertNull(DockerImageBuilder.IMAGE_DOCKERFILE_MAP.get("unknown:tag"));
    }

    // ==================== collectUniqueImageTags ====================

    @Test
    void collectTags_defaultPlan_returnsSingleTag() {
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        Set<String> tags = DockerImageBuilder.collectUniqueImageTags(plan);
        assertEquals(1, tags.size());
        assertTrue(tags.contains("tfl4-ek-bench:jvm"));
    }

    @Test
    void collectTags_profilePlan_returnsFiveTags() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan();
        Set<String> tags = DockerImageBuilder.collectUniqueImageTags(plan);
        assertEquals(5, tags.size());
        assertTrue(tags.contains("tfl4-ek-bench:jvm"));
        assertTrue(tags.contains("tfl4-ek-bench:openj9"));
        assertTrue(tags.contains("tfl4-ek-bench:native"));
        assertTrue(tags.contains("tfl4-ek-bench:jvm-cds"));
        assertTrue(tags.contains("tfl4-ek-bench:graalvm-jit"));
    }

    @Test
    void collectTags_profilePlan_ebics_returnsFiveEkTags() {
        BenchmarkPlan plan = BenchmarkPlan.profilePlan().withEbicsImages();
        Set<String> tags = DockerImageBuilder.collectUniqueImageTags(plan);
        assertEquals(5, tags.size());
        assertTrue(tags.contains("tfl4-ek-bench:jvm-ek"));
        assertTrue(tags.contains("tfl4-ek-bench:openj9-ek"));
        assertTrue(tags.contains("tfl4-ek-bench:native-ek"));
        assertTrue(tags.contains("tfl4-ek-bench:jvm-cds-ek"));
        assertTrue(tags.contains("tfl4-ek-bench:graalvm-jit-ek"));
    }

    @Test
    void collectTags_emptyPlan_returnsEmptySet() {
        BenchmarkPlan plan = new BenchmarkPlan(List.of());
        Set<String> tags = DockerImageBuilder.collectUniqueImageTags(plan);
        assertTrue(tags.isEmpty());
    }

    @Test
    void collectTags_duplicateImages_deduplicates() {
        // Default plan: all 20 configs use the same image
        BenchmarkPlan plan = BenchmarkPlan.defaultPlan();
        assertEquals(20, plan.configs.size(), "defaultPlan should have 20 configs");
        Set<String> tags = DockerImageBuilder.collectUniqueImageTags(plan);
        assertEquals(1, tags.size(), "20 configs with same image should yield 1 unique tag");
    }

    @Test
    void collectTags_singleCustomConfig() {
        BenchmarkConfig cfg = new BenchmarkConfig("test", "my-custom:image", List.of(), RuntimeType.HOTSPOT);
        BenchmarkPlan plan = new BenchmarkPlan(List.of(cfg));
        Set<String> tags = DockerImageBuilder.collectUniqueImageTags(plan);
        assertEquals(1, tags.size());
        assertTrue(tags.contains("my-custom:image"));
    }

    // ==================== resolveMavenCommand ====================

    @Test
    void resolveMavenCommand_returnsNonNull() {
        String cmd = DockerImageBuilder.resolveMavenCommand();
        assertNotNull(cmd);
        assertFalse(cmd.isBlank());
    }

    @Test
    void resolveMavenCommand_isEitherWindowsOrUnix() {
        String cmd = DockerImageBuilder.resolveMavenCommand();
        // Muss entweder "mvnw.cmd" (Windows) oder "./mvnw" (Unix) sein
        assertTrue(cmd.equals("mvnw.cmd") || cmd.equals("./mvnw"),
                "Maven command should be 'mvnw.cmd' or './mvnw', got: " + cmd);
    }

    // ==================== allKnownTags are covered by profilePlan ====================

    @Test
    void allKnownTags_coveredByProfilePlanAndEbicsVariant() {
        // profilePlan() + withEbicsImages() deckt alle 6 bekannten Tags ab
        BenchmarkPlan base = BenchmarkPlan.profilePlan();
        BenchmarkPlan ebics = base.withEbicsImages();

        Set<String> baseTags = DockerImageBuilder.collectUniqueImageTags(base);
        Set<String> ebicsTags = DockerImageBuilder.collectUniqueImageTags(ebics);

        // Zusammen muessen alle 10 Tags aus der Map abgedeckt sein
        Set<String> allTags = new java.util.HashSet<>(baseTags);
        allTags.addAll(ebicsTags);

        for (String knownTag : DockerImageBuilder.IMAGE_DOCKERFILE_MAP.keySet()) {
            assertTrue(allTags.contains(knownTag),
                    "Known tag '" + knownTag + "' should be covered by profilePlan + withEbicsImages");
        }
    }

    // ==================== JAR_PATH ====================

    @Test
    void jarPath_isConsistentWithDockerfileCopy() {
        // Die Dockerfiles verwenden alle "target/jvm-optim-demo-0.0.1-SNAPSHOT.jar"
        assertEquals("target/jvm-optim-demo-0.0.1-SNAPSHOT.jar", DockerImageBuilder.JAR_PATH);
    }
}
