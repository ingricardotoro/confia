package com.confia.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Scenario [I185] of specs/identity (session-endpoints-cookie-and-csrf, DA-15): the risk of the window
 * of the first enrollment is declared in the documents that own the decision. Until the first
 * verification whoever knows the password can replace the secret; the risk is accepted, to be reviewed
 * in change 8, and each re-enrollment is audited. A documentary test: it reads the two files.
 */
class SecondFactorEnrollmentWindowDocumentedTest {

    @Test
    void securityDocumentSection43AndTheAdrDeclareTheRiskOfTheFirstEnrollmentWindow() throws IOException {
        Path root = repositoryRoot();
        String security = Files.readString(root.resolve("docs/03-seguridad.md"), StandardCharsets.UTF_8);
        String adr = Files.readString(root.resolve("docs/adr/ADR-0005-autenticacion-y-gestion-de-sesiones.md"),
                StandardCharsets.UTF_8);
        int start = security.indexOf("### 4.3 MFA con TOTP");
        String section = security.substring(start, security.indexOf("### 4.4", start));

        for (String raw : new String[] {section, adr}) {
            String text = raw.replaceAll("\\s*\\R>?\\s*", " ").toLowerCase(java.util.Locale.ROOT);
            assertThat(text).contains("hasta la primera verificación")
                    .contains("quien conozca la contraseña puede reemplazar el secreto")
                    .contains("da-15").contains("cambio 8").contains("audita");
        }
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("docs/03-seguridad.md"))) {
            directory = directory.getParent();
        }
        assertThat(directory).as("the repository root, found from the working directory").isNotNull();
        return directory;
    }
}
