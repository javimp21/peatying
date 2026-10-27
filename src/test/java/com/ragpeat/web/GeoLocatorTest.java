package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class GeoLocatorTest {

    @Test
    void prefersTheSpanishName() {
        assertThat(GeoLocator.name(Map.of("names", Map.of("en", "Spain", "es", "España")))).isEqualTo("España");
        assertThat(GeoLocator.name(Map.of("names", Map.of("en", "Madrid")))).isEqualTo("Madrid");
        assertThat(GeoLocator.name(null)).isNull();
    }

    @Test
    void unknownWithoutDatabaseOrWithBadInput() {
        GeoLocator geo = new GeoLocator();
        assertThat(geo.country(null)).isNull();
        assertThat(geo.country("example.com")).isNull();   // nada de consultas DNS
        assertThat(geo.country("127.0.0.1")).isNull();     // IP local: sin país
    }

    /** Solo si está descargada la base de datos (data/geo, ver deploy.sh). */
    @Test
    void locatesAPublicIp() {
        assumeTrue(GeoLocator.DATABASE.isFile(), "sin data/geo/dbip-city-lite.mmdb");
        GeoLocator geo = new GeoLocator();
        assertThat(geo.country("8.8.8.8")).isEqualTo("Estados Unidos");
        assertThat(GeoLocator.countryName(Map.of("iso_code", "ES"))).isEqualTo("España");
    }
}
