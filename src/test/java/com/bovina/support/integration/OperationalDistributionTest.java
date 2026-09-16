package com.bovina.support.integration;

import org.springframework.boot.test.context.SpringBootTest;

/** Exercises recording physical facts, never regulatory authorization of a dispatch. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "bovina.distribution.regulated-dispatch=false")
public abstract class OperationalDistributionTest extends AuthenticatedIntegrationTest {}
