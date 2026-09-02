package com.development.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
// Provide the production-required secrets (no defaults exist) so the context can
// start. These are TEST-ONLY values and must never be used in production.
@TestPropertySource(properties = {
        "app.jwt.secret=ltuIwGGZAWQ0d3Jzwuj6asUDVgfzt9Ua08SjvciClHmS7kW4exs2VSduahgLN+UK",
        "app.initial-admin.username=testadmin",
        "app.initial-admin.password=testAdminPassword123!",
        "app.initial-admin.email=testadmin@localhost"
})
class AiAgentApplicationTests {

	@Test
	void contextLoads() {
	}

}