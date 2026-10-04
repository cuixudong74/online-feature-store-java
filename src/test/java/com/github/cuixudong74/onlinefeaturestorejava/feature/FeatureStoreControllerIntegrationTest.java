package com.github.cuixudong74.onlinefeaturestorejava.feature;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "feature-store.dir=target/controller-test-store")
class FeatureStoreControllerIntegrationTest {

    static {
        deleteDirectory(Path.of("target/controller-test-store"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registersCommitsReadsAndPollsOverHttp() throws Exception {
        String registration = """
                {
                  "featureName":"httpFeature",
                  "version":"v1",
                  "effectiveFromEpochMillis":1000,
                  "ttlMillis":60000,
                  "freshnessStrategy":"MARK_STALE",
                  "lateWriteStrategy":"ACCEPT",
                  "allowedLatenessMillis":10000,
                  "retentionMillis":100000
                }
                """;
        mockMvc.perform(post("/features/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"));

        String commit = """
                {
                  "commitId":"http-commit-1",
                  "commitTimeEpochMillis":5000,
                  "writes":[
                    {
                      "featureName":"httpFeature",
                      "version":"v1",
                      "eventTimeEpochMillis":2000,
                      "valueJson":"42",
                      "revision":0
                    }
                  ]
                }
                """;
        mockMvc.perform(post("/materialization/commits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.sequence").value(0));

        mockMvc.perform(get("/features/httpFeature/value")
                        .param("version", "v1")
                        .param("asOfEpochMillis", "2000")
                        .param("consumerId", "http-reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.valueJson").value("42"))
                .andExpect(jsonPath("$.ageMillis").value(0));

        mockMvc.perform(get("/features/httpFeature/value")
                        .param("version", "v1")
                        .param("asOfEpochMillis", "1999")
                        .param("consumerId", "http-reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_DATA"));

        mockMvc.perform(get("/streams/http-consumer/poll")
                        .param("offset", "0")
                        .param("maxEvents", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.highWatermark").value(1))
                .andExpect(jsonPath("$.events[0].offset").value(0))
                .andExpect(jsonPath("$.events[0].sequence").value(0))
                .andExpect(jsonPath("$.events[0].write.valueJson").value("42"));
    }


    private static void deleteDirectory(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ignored) {
            // Directory does not exist on a clean workspace.
        }
    }
}
