package com.github.cuixudong74.onlinefeaturestorejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "feature-store.dir=target/application-test-store")
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
