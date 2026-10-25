package com.confia.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Specs/build-integrity BI59, BI61 and BI100 (background-jobs-with-db-scheduler): the db-scheduler
 * version has one source, the {@code dependencyManagement} of the parent POM, with no range and no
 * repetition in a module; and the other scheduling libraries stay banned by the enforcer with
 * db-scheduler present (ADR-0016).
 */
class SchedulerDependencySingleSourceTest {

    private static final String GROUP = "com.github.kagkarlsson";
    private static final String STARTER = "db-scheduler-spring-boot-4-starter";
    private static final String VERSION_PROPERTY = "db-scheduler.version";

    @Test
    void theVersionIsDeclaredOnceInTheParentDependencyManagement() {
        Document parent = pom("pom.xml");

        List<Element> ofTheLibrary = dependencies(parent, true).stream()
                .filter(d -> GROUP.equals(text(d, "groupId"))).toList();
        assertThat(ofTheLibrary).as("the parent manages exactly the starter").hasSize(1);
        assertThat(text(ofTheLibrary.getFirst(), "artifactId")).isEqualTo(STARTER);
        assertThat(text(ofTheLibrary.getFirst(), "version"))
                .isEqualTo("${" + VERSION_PROPERTY + "}");

        List<String> values = propertyValues(parent, VERSION_PROPERTY);
        assertThat(values).as("one property, declared once").hasSize(1);
        assertThat(values.getFirst()).as("an exact version, never a range")
                .matches("\\d+\\.\\d+\\.\\d+");
    }

    @Test
    void noModuleRepeatsTheVersionOrUsesARange() {
        for (String module : List.of("app/pom.xml", "kernel/pom.xml")) {
            Document document = pom(module);
            assertThat(propertyValues(document, VERSION_PROPERTY))
                    .as("%s must not redeclare the version property", module).isEmpty();
            for (Element dependency : dependencies(document, false)) {
                if (GROUP.equals(text(dependency, "groupId"))) {
                    assertThat(text(dependency, "version"))
                            .as("%s declares %s without a version", module,
                                    text(dependency, "artifactId"))
                            .isNull();
                }
            }
        }
        assertThat(dependencies(pom("app/pom.xml"), false))
                .as("non-vacuous: the app module must depend on the starter")
                .anyMatch(d -> GROUP.equals(text(d, "groupId"))
                        && STARTER.equals(text(d, "artifactId")));
    }

    @Test
    void quartzAndJobrunrStayBannedWhileDbSchedulerIsAdmitted() {
        Document parent = pom("pom.xml");
        List<String> excludes = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagName("exclude");
        for (int i = 0; i < nodes.getLength(); i++) {
            excludes.add(nodes.item(i).getTextContent().strip());
        }

        assertThat(excludes).contains("org.quartz-scheduler:*", "org.jobrunr:*");
        assertThat(excludes).as("db-scheduler is the only admitted scheduling library")
                .noneMatch(exclude -> exclude.startsWith(GROUP));
        assertThat(propertyValues(parent, VERSION_PROPERTY))
                .as("non-vacuous: db-scheduler is present while the bans stand").hasSize(1);
    }

    private static Document pom(String relative) {
        Path file = findApiRoot().resolve(relative);
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(in);
        } catch (IOException | SAXException | ParserConfigurationException e) {
            throw new IllegalStateException("could not parse " + relative, e);
        }
    }

    /** Dependency elements of the project, either the managed ones or the direct ones. */
    private static List<Element> dependencies(Document document, boolean managed) {
        List<Element> result = new ArrayList<>();
        NodeList all = document.getElementsByTagName("dependency");
        for (int i = 0; i < all.getLength(); i++) {
            Element dependency = (Element) all.item(i);
            Node container = dependency.getParentNode().getParentNode();
            if (managed && "dependencyManagement".equals(container.getNodeName())) {
                result.add(dependency);
            } else if (!managed && "project".equals(container.getNodeName())) {
                result.add(dependency);
            }
        }
        return result;
    }

    private static List<String> propertyValues(Document document, String name) {
        List<String> values = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName(name);
        for (int i = 0; i < nodes.getLength(); i++) {
            values.add(nodes.item(i).getTextContent().strip());
        }
        return values;
    }

    private static String text(Element parent, String child) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(child)) {
                return element.getTextContent().strip();
            }
        }
        return null;
    }

    /** The {@code apps/api} directory: the nearest ancestor holding the parent POM and its modules. */
    private static Path findApiRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("app"))
                    && Files.isDirectory(candidate.resolve("kernel"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "could not find apps/api from " + Paths.get("").toAbsolutePath());
    }
}
