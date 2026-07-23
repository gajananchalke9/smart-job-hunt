package com.smartjobhunt;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test that verifies the Spring application context loads without errors.
 *
 * <p>This test uses the "test" profile which should provide mock/placeholder
 * configuration to avoid requiring live GCP credentials.
 */
@SpringBootTest
@ActiveProfiles("test")
class SmartJobHuntApplicationTests {

    @Test
    void contextLoads() {
        // If the context starts without exceptions, the test passes.
    }
}
