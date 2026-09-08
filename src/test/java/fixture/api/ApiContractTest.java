package fixture.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bovina.platform.api.ApiExceptionHandler;
import com.bovina.platform.api.ApiProblems;
import com.bovina.platform.api.RequestCorrelationFilter;
import com.bovina.platform.application.ApplicationFailure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

class ApiContractTest {
  private MockMvc mvc;
  private LocalValidatorFactoryBean validator;

  @BeforeEach
  void setup() {
    validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(
                new ApiExceptionHandler(new ApiProblems(JsonMapper.builder().build())))
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .setValidator(validator)
            .addFilters(new RequestCorrelationFilter())
            .build();
  }

  @AfterEach
  void closeValidator() {
    validator.close();
  }

  @Test
  void validationDoesNotExposeRejectedValues() throws Exception {
    mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("name"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
  }

  @Test
  void malformedJsonIsSafeAndCorrelated() throws Exception {
    mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{secret:"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("HTTP_400"))
        .andExpect(jsonPath("$.detail").value("Bad Request"))
        .andExpect(jsonPath("$.traceId").isString());
  }

  @Test
  void domainFailureIsMappedOnlyAtHttpBoundary() throws Exception {
    mvc.perform(get("/probe/conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TEST_CONFLICT"));
  }

  @Test
  void unexpectedFailureDoesNotLeakSensitiveDetailAndClearsMdc() throws Exception {
    var id = "01992678-9600-7000-8000-000000000001";
    mvc.perform(get("/probe/failure").header("X-Correlation-ID", id))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("Internal server error"))
        .andExpect(jsonPath("$.traceId").value(id))
        .andExpect(header().string("X-Correlation-ID", id))
        .andExpect(jsonPath("$.stackTrace").doesNotExist());
    assertThat(MDC.get("traceId")).isNull();
  }

  @Test
  void invalidCorrelationHeaderIsNotEchoed() throws Exception {
    var result =
        mvc.perform(get("/probe/conflict").header("X-Correlation-ID", "sensitive-header"))
            .andReturn();
    assertThat(result.getResponse().getHeader("X-Correlation-ID")).isNotEqualTo("sensitive-header");
  }

  @RestController
  static class ProbeController {
    @PostMapping("/probe")
    Name echo(@Valid @RequestBody Name input) {
      return input;
    }

    @GetMapping("/probe/conflict")
    void conflict() {
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT, "TEST_CONFLICT", "Conflicting command");
    }

    @GetMapping("/probe/failure")
    void failure() {
      throw new IllegalStateException("password=must-not-leak");
    }
  }

  record Name(@NotBlank String name) {}
}
