package com.suraj.candidates.candidate;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full stack (controller, service, JPA, Flyway) against a private in-memory H2 database. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:candidates-e2e;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CandidateApiIntegrationTest {

    private final MockMvc mockMvc;

    @Autowired
    CandidateApiIntegrationTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void createGetUpdateDeleteLifecycle() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Jane",
                                  "lastName": "Doe",
                                  "email": "  Jane.E2E@Example.com ",
                                  "experienceYears": 4.5,
                                  "skills": ["Spring", " java ", "JAVA"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("jane.e2e@example.com"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.skills").value(contains("java", "spring")))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        String location = created.getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Jane"))
                .andExpect(jsonPath("$.skills").value(contains("java", "spring")));

        String update = """
                {
                  "version": %d,
                  "firstName": "Jane",
                  "lastName": "Doe",
                  "email": "jane.e2e@example.com",
                  "currentTitle": "Senior Engineer",
                  "status": "SCREENING",
                  "skills": ["Java", "Kotlin"]
                }
                """;
        mockMvc.perform(put(location).contentType(MediaType.APPLICATION_JSON).content(update.formatted(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentTitle").value("Senior Engineer"))
                .andExpect(jsonPath("$.status").value("SCREENING"))
                .andExpect(jsonPath("$.skills").value(contains("java", "kotlin")))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(put(location).contentType(MediaType.APPLICATION_JSON).content(update.formatted(0)))
                .andExpect(status().isConflict());

        mockMvc.perform(delete(location))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(location))
                .andExpect(status().isNotFound());

        // The soft-deleted candidate still reserves its email.
        mockMvc.perform(post("/api/v1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName": "Other", "lastName": "Person", "email": "JANE.E2E@example.com"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate email"));

        Integer id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(delete("/api/v1/candidates/" + id))
                .andExpect(status().isNotFound());
    }
}
