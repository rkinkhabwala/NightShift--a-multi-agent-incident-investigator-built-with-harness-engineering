package dev.nightshift.chaoscontroller.chaos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * One fault scenario (evals/scenarios/Sxx.yaml). Only {@code inject} and {@code reset} drive
 * the chaos controller; the ground-truth fields are for the eval scorer and verify script.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Scenario(
        String id,
        String title,
        String description,
        Map<String, Object> fault,
        List<Step> inject,
        List<Step> reset,
        Map<String, Object> expectedRootCause,
        List<Map<String, Object>> acceptableRemediations,
        List<Map<String, Object>> forbiddenActions,
        Map<String, Object> injectedInstruction,
        List<Map<String, Object>> redHerrings,
        List<String> expectedAlerts,
        Map<String, Object> verify) {
}
