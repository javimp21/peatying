package com.ragpeat.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PersonalDataTest {

    // Datos inventados, con la forma de una analítica pegada tal cual.
    private static final String LAB_REPORT = """
            DNI: 31415926
            Fecha Toma Muestra
            07/09/2026
            HC: 2718281
            Fecha Nac.: 01/02/1990
            Médico: GARCIA LOPEZ ANA
            Nro. Informe:1/1
            110622330100
            174 COLESTEROL TOTAL 759 PROLACTINA PLASMATICA
            Colesterol total: 215 mg/dl
            TSH: 3,2 uUI/ml""";

    @Test
    void removesIdentifiersFromPastedLabReport() {
        String redacted = PersonalData.redact(LAB_REPORT);

        assertThat(redacted).doesNotContain("31415926", "2718281", "01/02/1990", "GARCIA", "110622330100");
        assertThat(redacted).contains("DNI: [dato personal]", "Fecha Nac.: [dato personal]", "Médico: [dato personal]");
    }

    @Test
    void keepsTheLabResults() {
        String redacted = PersonalData.redact(LAB_REPORT);

        assertThat(redacted).contains("Colesterol total: 215 mg/dl", "TSH: 3,2 uUI/ml",
                "174 COLESTEROL TOTAL 759 PROLACTINA PLASMATICA", "07/09/2026");
    }

    @Test
    void splitsTwoLabelsOnTheSameLine() {
        assertThat(PersonalData.redact("HC: 2718281 Fecha Nac.: 01/02/1990"))
                .isEqualTo("HC: [dato personal] Fecha Nac.: [dato personal]");
    }

    @Test
    void removesUnlabeledIdentifiers() {
        assertThat(PersonalData.redact("mi email es ana.g@example.com y mi DNI 12345678Z, llamadme al +34 600 123 456"))
                .doesNotContain("ana.g@example.com", "12345678Z", "600 123 456");
    }

    @Test
    void leavesNormalQuestionsAlone() {
        String question = "Mi médico me dijo que tengo el LDL en 160 y la TSH en 4,5. ¿Qué pensaba Peat? Peso 52 kg y mido 157 cm.";
        assertThat(PersonalData.redact(question)).isEqualTo(question);
    }
}
