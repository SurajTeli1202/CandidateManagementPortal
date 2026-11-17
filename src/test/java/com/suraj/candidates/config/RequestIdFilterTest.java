package com.suraj.candidates.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
    private final ListAppender<ILoggingEvent> accessLog = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void captureAccessLog() {
        accessLog.start();
        logger.addAppender(accessLog);
    }

    @AfterEach
    void cleanUp() {
        logger.detachAppender(accessLog);
        accessLog.stop();
        MDC.remove("requestId");
        MDC.remove("otherContext");
    }

    @ParameterizedTest
    @ValueSource(strings = {"request-123_ID", "ABCxyz_09"})
    void echoesValidRequestIdAndMakesItAvailableInMdc(String id) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("X-Request-Id", id);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get("requestId")).isEqualTo(id));

        assertThat(response.getHeader("X-Request-Id")).isEqualTo(id);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(accessLog.list.getFirst().getMDCPropertyMap()).containsEntry("requestId", id);
    }

    @Test
    void acceptsRequestIdAtThe64CharacterLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        String id = "a".repeat(64);
        request.addHeader("X-Request-Id", id);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader("X-Request-Id")).isEqualTo(id);
    }

    @Test
    void generatesUuidWhenRequestIdIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-Id")));

        String id = response.getHeader("X-Request-Id");
        assertThat(id).isNotNull();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(MDC.get("requestId")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "contains space", "bad.id", "bad/id", "bad\nid", "\u00e9"})
    void replacesInvalidRequestIdWithUuid(String suppliedId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("X-Request-Id", suppliedId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String id = response.getHeader("X-Request-Id");
        assertThat(id).isNotNull().isNotEqualTo(suppliedId);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void replacesRequestIdLongerThan64Characters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        String suppliedId = "a".repeat(65);
        request.addHeader("X-Request-Id", suppliedId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String id = response.getHeader("X-Request-Id");
        assertThat(id).isNotNull().isNotEqualTo(suppliedId);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    }

    @Test
    void clearsMdcAndLogsFailureWhenTheFilterChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("requestId")).isNotNull();
            throw new ServletException("Expected test failure");
        })).isInstanceOf(ServletException.class);

        assertThat(MDC.get("requestId")).isNull();
        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.getFirst().getFormattedMessage()).contains("status=500");
    }

    @Test
    void clearingRequestIdPreservesOtherMdcEntries() throws Exception {
        MDC.put("otherContext", "keep");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("otherContext")).isEqualTo("keep");
    }

    @Test
    void logsOneInfoAccessLineWithoutQueryBodyOrOtherHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/candidates");
        request.setQueryString("q=private-query");
        request.setContent("private-body".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "private-header");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                ((HttpServletResponse) res).setStatus(HttpServletResponse.SC_CREATED));

        assertThat(accessLog.list).hasSize(1);
        ILoggingEvent event = accessLog.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .matches("HTTP POST /api/v1/candidates status=201 durationMs=\\d+")
                .doesNotContain("private-query", "private-body", "private-header");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator", "/actuator/health", "/swagger-ui.html", "/swagger-ui/index.html",
            "/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml",
            "/webjars/swagger-ui/swagger-ui.js"
    })
    void skipsAccessLoggingButKeepsRequestIdsForInfrastructurePaths(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app" + path);
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get("requestId")).isNotNull());

        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        assertThat(MDC.get("requestId")).isNull();
        assertThat(accessLog.list).isEmpty();
    }
}
