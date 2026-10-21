package com.confia.shared.web.csrf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs/web-edge, scenario WE46 and the requirement "{@code Origin} es obligatorio en todo método no
 * seguro" (session-endpoints-cookie-and-csrf design.md, decision 4): the list is validated at start,
 * an invalid entry stops the process with a message that names the property and the position and
 * never repeats the entry, and the comparison is exact.
 */
class AllowedOriginsTest {

    private static final String PROPERTY = "confia.web.allowed-origins";
    private static final String ALLOWED = "https://admin.colegio.edu.hn";

    @Test
    void anEmptyListAllowsNothing() {
        AllowedOrigins origins = AllowedOrigins.parse(List.of());

        assertThat(origins.contains(ALLOWED)).isFalse();
        assertThat(origins.contains("null")).isFalse();
        assertThat(origins.contains("")).isFalse();
    }

    @Test
    void aListedOriginIsAllowedAndNothingSimilarIs() {
        AllowedOrigins origins = AllowedOrigins.parse(List.of(ALLOWED));

        assertThat(origins.contains(ALLOWED)).isTrue();
        assertThat(origins.contains("http://admin.colegio.edu.hn")).isFalse();
        assertThat(origins.contains(ALLOWED + ".evil.example")).isFalse();
        assertThat(origins.contains("https://evil.example")).isFalse();
        assertThat(origins.contains("null")).isFalse();
        assertThat(origins.contains(null)).isFalse();
        assertThat(origins.contains(ALLOWED + "/")).isFalse();
        assertThat(origins.contains(ALLOWED.toUpperCase())).isFalse();
    }

    @Test
    void severalOriginsAreAllAllowed() {
        AllowedOrigins origins = AllowedOrigins.parse(
                List.of(ALLOWED, "https://otro.colegio.edu.hn:8443"));

        assertThat(origins.contains(ALLOWED)).isTrue();
        assertThat(origins.contains("https://otro.colegio.edu.hn:8443")).isTrue();
        assertThat(origins.contains("https://otro.colegio.edu.hn")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://localhost", "http://127.0.0.1:5173",
            "https://localhost:5173", "https://admin.colegio.edu.hn:8443"})
    void validOriginsAreAccepted(String entry) {
        assertThat(AllowedOrigins.parse(List.of(entry)).contains(entry)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://admin.colegio.edu.hn/ruta", "https://admin.colegio.edu.hn/", "*",
            "HTTPS://Admin.Colegio.edu.hn", "https://Admin.colegio.edu.hn",
            "http://admin.colegio.edu.hn", "https://usuario@admin.colegio.edu.hn",
            "https://*.colegio.edu.hn", "admin.colegio.edu.hn", "", "   ", "null",
            "https://admin.colegio.edu.hn?x=1", "https://admin.colegio.edu.hn#frag",
            "https://admin.colegio.edu.hn:0", "https://admin.colegio.edu.hn:99999",
            "https://admin.colegio.edu.hn:abc", "ftp://admin.colegio.edu.hn",
            "http://localhost.evil.example", "https://", "https:///"})
    void anInvalidEntryStopsTheStartNamingThePropertyAndThePositionAndNotTheValue(String entry) {
        assertThatThrownBy(() -> AllowedOrigins.parse(List.of(ALLOWED, entry)))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).contains(PROPERTY + "[1]")
                            .doesNotContain(PROPERTY + "[0]");
                    if (entry.length() > 3) {
                        assertThat(failure.getMessage()).doesNotContain(entry);
                    }
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void aRepeatedEntryStopsTheStartNamingTheSecondPosition() {
        assertThatThrownBy(() -> AllowedOrigins.parse(List.of(ALLOWED, ALLOWED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROPERTY + "[1]")
                .hasMessageNotContaining(ALLOWED);
    }

    @Test
    void theFirstInvalidEntryIsTheOneNamed() {
        assertThatThrownBy(() -> AllowedOrigins.parse(List.of("*", "also-bad")))
                .hasMessageContaining(PROPERTY + "[0]");
    }

    @Test
    void theListIsCopiedSoALaterChangeDoesNotWidenIt() {
        java.util.ArrayList<String> mutable = new java.util.ArrayList<>(List.of(ALLOWED));
        AllowedOrigins origins = AllowedOrigins.parse(mutable);

        mutable.add("https://evil.example");

        assertThat(origins.contains("https://evil.example")).isFalse();
    }
}
