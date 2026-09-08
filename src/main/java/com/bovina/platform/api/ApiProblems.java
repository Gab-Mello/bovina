package com.bovina.platform.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ApiProblems {
  private final JsonMapper mapper;

  public ApiProblems(JsonMapper mapper) {
    this.mapper = mapper;
  }

  public ProblemDetail create(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    var problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code);
    problem.setProperty("traceId", request.getAttribute("traceId"));
    return problem;
  }

  public void write(
      HttpStatus status, String code, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(
        response.getOutputStream(), create(status, code, status.getReasonPhrase(), request));
  }
}
