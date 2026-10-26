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
        assertThat(geo.locate(null)).isEqualTo(GeoLocator.Location.UNKNOWN);
        assertThat(geo.locate("example.com")).isEqualTo(GeoLocator.Location.UNKNOWN);   // nada de consultas DNS
        assertThat(geo.locate("127.0.0.1").country()).isNull();                          // IP local: sin país
    }

    /** Solo si está descargada la base de datos (data/geo, ver deploy.sh). */
    @Test
    void locatesAPublicIp() {
        assumeTrue(GeoLocator.DATABASE.isFile(), "sin data/geo/dbip-city-lite.mmdb");
        GeoLocator geo = new GeoLocator();
        assertThat(geo.locate("8.8.8.8").country()).isEqualTo("Estados Unidos");
        assertThat(GeoLocator.country(Map.of("iso_code", "ES"))).isEqualTo("España");
    }
}
