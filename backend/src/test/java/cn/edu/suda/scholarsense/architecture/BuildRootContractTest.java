package cn.edu.suda.scholarsense.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class BuildRootContractTest {

    @Test
    void wrapperPinsMavenAndItsDistributionChecksum() throws IOException {
        Path wrapperProperties = Path.of(".mvn/wrapper/maven-wrapper.properties");
        assertTrue(Files.isRegularFile(Path.of("mvnw")));
        assertTrue(Files.isRegularFile(Path.of("mvnw.cmd")));
        assertTrue(Files.isRegularFile(wrapperProperties));

        Properties properties = new Properties();
        try (var input = Files.newInputStream(wrapperProperties)) {
            properties.load(input);
        }

        assertTrue(properties.getProperty("distributionUrl").contains("apache-maven-3.9.16-bin.zip"));
        assertTrue(properties.getProperty("distributionSha256Sum").matches("[0-9a-f]{64}"));
        assertEquals("false", properties.getProperty("alwaysDownload"));
        assertEquals("false", properties.getProperty("alwaysUnpack"));
    }

    @Test
    void buildPinsTheApprovedJavaAndSpringBootBaselines() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<version>4.1.0</version>"));
        assertTrue(pom.contains("<maven.compiler.release>25</maven.compiler.release>"));
        assertTrue(pom.contains("<artifactId>maven-wrapper-plugin</artifactId>"));
        assertTrue(pom.contains("<version>3.3.4</version>"));
    }
}
