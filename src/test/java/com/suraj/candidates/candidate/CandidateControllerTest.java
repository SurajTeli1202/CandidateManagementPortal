package com.suraj.candidates.candidate;

import com.suraj.candidates.candidate.dto.CandidateRequest;
import com.suraj.candidates.candidate.dto.CandidateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Also covers GlobalExceptionHandler, which @WebMvcTest picks up as a @RestControllerAdvice. */
@WebMvcTest(CandidateController.class)
class CandidateControllerTest {

    private static final String BASE = "/api/v1/candidates";

    private static final String VALID_BODY = """
            {
              "firstName": "Jane",
              "lastName": "Doe",
              "email": "jane@example.com",
              "experienceYears": 5.5,
              "noticePeriodDays": 30,
              "skills": ["Java", "Spring"]
            }
            """;

    private final MockMvc mockMvc;

    @MockitoBean
    private CandidateService candidateService;

    @Autowired
    CandidateControllerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        when(candidateService.create(any())).thenReturn(response(1L, 0L));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/candidates/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.skills[0]").value("java"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createWithInvalidFieldsReturns400WithFieldErrors() throws Exception {
        String body = """
                {
                  "firstName": " ",
                  "lastName": "%s",
                  "email": "not-an-email",
                  "experienceYears": -1.25,
                  "noticePeriodDays": -1,
                  "skills": ["%s"]
                }
                """.formatted("x".repeat(101), "s".repeat(51));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems(
                        "firstName", "lastName", "email", "experienceYears", "noticePeriodDays", "skills[]")))
                .andExpect(content().string(not(containsString("not-an-email"))));
        verifyNoInteractions(candidateService);
    }

    @Test
    void createWithMoreThan50SkillsReturns400() throws Exception {
        String skills = IntStream.rangeClosed(1, 51).mapToObj(i -> "\"skill" + i + "\"")
                .collect(Collectors.joining(","));
        String body = """
                {"firstName": "Jane", "lastName": "Doe", "email": "jane@example.com", "skills": [%s]}
                """.formatted(skills);

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skills"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"firstName\": "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Malformed JSON request"));
    }

    @Test
    void unknownStatusReturns400ListingAllowedValues() throws Exception {
        String body = """
                {"firstName": "Jane", "lastName": "Doe", "email": "jane@example.com", "status": "SLEEPING"}
                """;

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("'status'")))
                .andExpect(jsonPath("$.detail").value(containsString("INTERVIEWING")));
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        when(candidateService.create(any())).thenThrow(new DuplicateEmailException());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate email"));
    }

    @Test
    void dataIntegrityViolationReturns409WithoutLeakingDetails() throws Exception {
        when(candidateService.create(any()))
                .thenThrow(new DataIntegrityViolationException("uq_candidate_email: jane@example.com"));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The request conflicts with existing data."));
    }

    @Test
    void getReturnsCandidate() throws Exception {
        when(candidateService.getById(1L)).thenReturn(response(1L, 2L));

        mockMvc.perform(get(BASE + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("jane@example.com"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void getMissingCandidateReturns404() throws Exception {
        when(candidateService.getById(99L)).thenThrow(new CandidateNotFoundException(99L));

        mockMvc.perform(get(BASE + "/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Candidate not found"))
                .andExpect(jsonPath("$.instance").value(BASE + "/99"));
    }

    @Test
    void nonNumericIdReturns400() throws Exception {
        mockMvc.perform(get(BASE + "/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void updateReturnsUpdatedCandidate() throws Exception {
        when(candidateService.update(eq(1L), any(CandidateRequest.class))).thenReturn(response(1L, 4L));

        mockMvc.perform(put(BASE + "/1").contentType(MediaType.APPLICATION_JSON).content(withVersion(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void updateWithoutVersionReturns400() throws Exception {
        mockMvc.perform(put(BASE + "/1").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        verifyNoInteractions(candidateService);
    }

    @Test
    void updateWithStaleVersionReturns409() throws Exception {
        when(candidateService.update(eq(1L), any(CandidateRequest.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Candidate.class, 1L));

        mockMvc.perform(put(BASE + "/1").contentType(MediaType.APPLICATION_JSON).content(withVersion(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Concurrent modification"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete(BASE + "/1"))
                .andExpect(status().isNoContent());
        verify(candidateService).delete(1L);
    }

    @Test
    void deleteMissingCandidateReturns404() throws Exception {
        doThrow(new CandidateNotFoundException(5L)).when(candidateService).delete(5L);

        mockMvc.perform(delete(BASE + "/5"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unexpectedErrorReturns500WithGenericMessage() throws Exception {
        when(candidateService.getById(1L)).thenThrow(new IllegalStateException("secret internal detail"));

        mockMvc.perform(get(BASE + "/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    private static String withVersion(long version) {
        return VALID_BODY.replace("\"firstName\"", "\"version\": " + version + ",\n  \"firstName\"");
    }

    private static CandidateResponse response(Long id, Long version) {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        return new CandidateResponse(id, "Jane", "Doe", "jane@example.com", null, null, null, null,
                new BigDecimal("5.5"), 30, CandidateStatus.NEW, null, List.of("java", "spring"), now, now, version);
    }
}
