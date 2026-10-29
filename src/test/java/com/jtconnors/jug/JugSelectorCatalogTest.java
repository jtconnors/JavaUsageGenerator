package com.jtconnors.jug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JugSelectorCatalogTest {
    @TempDir Path temporaryDirectory;

    @Test
    void includesDeliveredSecurityAlerts() {
        var current = new JugSelector.Catalog(
            "2026-10-20",
            List.of(
                new JugSelector.Entry("8", "8u511", "8u511", "1.8.0_511", "update"),
                new JugSelector.Entry("27", "27.0.1", "27.0.1", "27.0.1", "update")
            ),
            List.of("8", "27")
        );
        var feed = """
            {
              "generated": "2026-10-24",
              "data": {
                "releases": [
                  {"status":"DELIVERED","family":27,"version":"27.0.1.0.3","type":"SA"},
                  {"status":"DELIVERED","family":8,"version":"8u511b33","type":"SA"},
                  {"status":"PLANNED","family":27,"version":"27.0.2.0.1","type":"SA"},
                  {"status":"DELIVERED","family":27,"version":"27.0.1.0.3.1","type":"SA"}
                ]
              }
            }
            """;

        var merged = JugSelector.mergeOracleReleases(current, feed);

        assertEquals("2026-10-24", merged.date());
        assertEquals(4, merged.entries().size());
        var modernAlert = merged.entries().stream()
            .filter(entry -> entry.version().equals("27.0.1.0.3"))
            .findFirst().orElseThrow();
        assertEquals("27.0.1.0.3", modernAlert.javaVersion());
        assertEquals("27.0.1.0.3 (Security Alert)", modernAlert.label());
        var java8Alert = merged.entries().stream()
            .filter(entry -> entry.version().equals("8u511b33"))
            .findFirst().orElseThrow();
        assertEquals("1.8.0_511", java8Alert.javaVersion());
        assertEquals("8u511b33 (Security Alert)", java8Alert.label());
        assertFalse(merged.entries().stream().anyMatch(entry -> entry.version().equals("27.0.2.0.1")));
        assertFalse(merged.entries().stream().anyMatch(entry -> entry.version().equals("27.0.1.0.3.1")));
        assertTrue(merged.entries().stream().allMatch(entry -> entry.kind().equals("update")));
    }

    @Test
    void writesAndReadsCustomCatalogs() throws Exception {
        var application = new JugSelector.ApplicationEntry(
            "APP-900001", "Custom Service", "Real", "Service", List.of("21"),
            List.of("/opt/custom/service.jar"), List.of("com.example.Main"), List.of("VM start")
        );
        var host = new JugSelector.HostEntry(
            "HOST-900001", "custom-host/192.0.2.42", "custom-host", "192.0.2.42", "Linux"
        );
        var osVersion = new JugSelector.OsVersionEntry(
            "OSV-900001", "test-1.0", "Linux", List.of("Test Linux"), List.of("amd64")
        );

        Path applications = temporaryDirectory.resolve("custom-applications.json");
        Path hosts = temporaryDirectory.resolve("custom-hosts.json");
        Path osVersions = temporaryDirectory.resolve("custom-os-versions.json");
        JugSelector.appendCustomApplication(applications, application);
        JugSelector.appendCustomHost(hosts, host);
        JugSelector.appendCustomOsVersion(osVersions, osVersion);

        assertEquals(application, JugSelector.readApplicationCatalog(Files.readString(applications)).entries().getFirst());
        assertEquals(host, JugSelector.readHostCatalog(Files.readString(hosts)).entries().getFirst());
        assertEquals(osVersion, JugSelector.readOsVersionCatalog(Files.readString(osVersions)).entries().getFirst());
    }

    @Test
    void mergesCustomEntriesAndAllocatesStableIds() {
        var base = new JugSelector.HostCatalog("2026-10-20", List.of(
            new JugSelector.HostEntry("HOST-001", "base/192.0.2.1", "base", "192.0.2.1", "Linux")
        ));
        var custom = new JugSelector.HostCatalog("2026-10-29", List.of(
            new JugSelector.HostEntry("HOST-900001", "custom/192.0.2.2", "custom", "192.0.2.2", "Linux")
        ));

        var merged = JugSelector.mergeHostCatalogs(base, custom);

        assertEquals(2, merged.entries().size());
        assertEquals("2026-10-29", merged.date());
        assertEquals("HOST-900002", JugSelector.nextCustomId("HOST-", merged.entries().stream().map(JugSelector.HostEntry::id).toList()));
        assertTrue(JugSelector.validIpAddress("203.0.113.255"));
        assertFalse(JugSelector.validIpAddress("203.0.113.256"));
    }
}
