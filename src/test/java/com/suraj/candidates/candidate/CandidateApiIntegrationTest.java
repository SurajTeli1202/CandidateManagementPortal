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
import static org.hamcrest.Matchers.hasSize;
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
    void searchFiltersSkillsAndReturnsStablePagesWithoutDeletedCandidates() throws Exception {
        createSearchCandidate("Alpha", "[\"Java\", \"Spring\"]");
        createSearchCandidate("Beta", "[\"Java\"]");
        createSearchCandidate("Gamma", "[\"Kotlin\"]");
        String deletedLocation = createSearchCandidate("Deleted", "[\"Java\", \"Spring\"]");
        mockMvc.perform(delete(deletedLocation)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/candidates")
                        .param("q", "searchfixture engineer")
                        .param("status", "NEW,SCREENING")
                        .param("location", "PUNE")
                        .param("skills", " JAVA , Spring ")
                        .param("minExperience", "4.5")
                        .param("maxExperience", "4.5")
                        .param("maxNoticePeriod", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].lastName").value("Alpha"))
                .andExpect(jsonPath("$.content[0].skills").value(contains("java", "spring")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(get("/api/v1/candidates")
                        .param("q", "searchfixture")
                        .param("skills", "java,spring")
                        .param("skillMatch", "ANY")
                        .param("size", "1")
                        .param("sort", "lastName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].lastName").value("Alpha"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/v1/candidates")
                        .param("q", "searchfixture")
                        .param("skills", "java,spring")
                        .param("skillMatch", "ANY")
                        .param("page", "1")
                        .param("size", "1")
                        .param("sort", "lastName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].lastName").value("Beta"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
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

    private String createSearchCandidate(String lastName, String skillsJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "SearchFixture",
                                  "lastName": "%s",
                                  "email": "search-%s@example.test",
                                  "currentTitle": "Engineer",
                                  "location": "Pune",
                                  "status": "SCREENING",
                                  "experienceYears": 4.5,
                                  "noticePeriodDays": 30,
                                  "skills": %s
                                }
                                """.formatted(lastName, lastName, skillsJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getHeader("Location");
    }
}
