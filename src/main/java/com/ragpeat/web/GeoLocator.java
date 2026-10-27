package com.ragpeat.web;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.maxmind.db.Reader;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * País de una IP, con la base de datos gratuita DB-IP Lite (licencia CC BY 4.0:
 * hay que enlazar a db-ip.com, está en el pie de la web). El archivo está en nuestro servidor:
 * la IP no se manda a ningún servicio de fuera. deploy.sh lo descarga y lo renueva cada mes.
 *
 * Si el archivo no está (p. ej. en local sin descargarlo), todo sigue funcionando: la ubicación queda vacía.
 *
 * La ciudad no se usa: con datos móviles (y muchas fibras) la IP es la de la salida de la operadora,
 * así que alguien de Madrid salía en Barcelona. El país sí es fiable.
 */
@Component
public class GeoLocator {

    private static final Logger log = LoggerFactory.getLogger(GeoLocator.class);
    static final File DATABASE = new File("data/geo/dbip-city-lite.mmdb");
    private static final Locale SPANISH = Locale.forLanguageTag("es");

    private final Reader reader;

    public GeoLocator() {
        Reader opened = null;
        if (DATABASE.isFile()) {
            try {
                // Por defecto el archivo se "mapea" en memoria: lo gestiona el sistema operativo, no ocupa memoria de Java.
                opened = new Reader(DATABASE);
            } catch (IOException e) {
                log.warn("No se pudo abrir {}: sin ubicación de las visitas", DATABASE, e);
            }
        } else {
            log.info("No está {}: las visitas se guardan sin ubicación", DATABASE);
        }
        this.reader = opened;
    }

    /** El país en español, o null si no se sabe. */
    public String country(String ip) {
        // Solo IPs escritas en números: con un nombre, getByName haría una consulta DNS.
        if (reader == null || ip == null || !ip.matches("[0-9a-fA-F:.]{2,45}")) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> record = reader.get(InetAddress.getByName(ip), Map.class);
            if (record == null) {
                return null;    // IP privada o sin datos
            }
            return countryName(record.get("country"));
        } catch (IOException | IllegalArgumentException e) {
            return null;        // IP mal formada o archivo dañado: no merece romper la visita
        }
    }

    /**
     * {"iso_code": "US", ...} -> "Estados Unidos". El nombre en español de DB-IP es el oficial de la ISO
     * ("Estados Unidos de América (los)"); el de Java es el de uso normal.
     */
    static String countryName(Object place) {
        if (place instanceof Map<?, ?> map && map.get("iso_code") instanceof String code && code.length() == 2) {
            return Locale.of("", code).getDisplayCountry(SPANISH);
        }
        return name(place);
    }

    /** {"names": {"es": "Londres", "en": "London"}} -> "Londres" (o el inglés si no hay español). */
    static String name(Object place) {
        if (!(place instanceof Map<?, ?> map) || !(map.get("names") instanceof Map<?, ?> names)) {
            return null;
        }
        for (String language : List.of("es", "en")) {
            if (names.get(language) instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @PreDestroy
    void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }
}
