package io.cucumber.junitxmlformatter;

import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.Pickle;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.Rule;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.TestCase;
import io.cucumber.messages.types.TestCaseFinished;
import io.cucumber.messages.types.TestCaseStarted;
import io.cucumber.messages.types.TestRunFinished;
import io.cucumber.messages.types.TestStep;
import io.cucumber.messages.types.TestStepFinished;
import io.cucumber.messages.types.TestStepResult;
import io.cucumber.messages.types.TestStepResultStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.cucumber.messages.TimeConversion.timestampToJavaInstant;
import static io.cucumber.messages.types.TestStepResultStatus.PASSED;
import static java.util.Comparator.comparing;
import static java.util.Comparator.nullsFirst;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

class XmlReportData {

    private static final long MILLIS_PER_SECOND = SECONDS.toMillis(1L);
    private final Comparator<TestStepResult> testStepResultComparator = nullsFirst(
            comparing(o -> o.getStatus().ordinal()));
    private Instant testRunStarted;
    private Instant testRunFinished;
    private final Deque<String> testCaseStartedIds = new ConcurrentLinkedDeque<>();
    private final Map<String, Instant> testCaseStartedIdToStartedInstant = new ConcurrentHashMap<>();
    private final Map<String, Instant> testCaseStartedIdToFinishedInstant = new ConcurrentHashMap<>();
    private final Map<String, TestStepResult> testCaseStartedIdToResult = new ConcurrentHashMap<>();
    private final Map<String, TestStepResultStatus> testStepIdToTestStepResultStatus = new ConcurrentHashMap<>();
    private final Map<String, String> testCaseStartedIdToTestCaseId = new ConcurrentHashMap<>();
    private final Map<String, TestCase> testCaseIdToTestCase = new ConcurrentHashMap<>();
    private final Map<String, Pickle> pickleIdToPickle = new ConcurrentHashMap<>();
    private final Map<String, String> pickleIdToScenarioAstNodeId = new ConcurrentHashMap<>();
    private final Map<String, String> scenarioAstNodeIdToFeatureName = new ConcurrentHashMap<>();
    private final Map<String, String> stepAstNodeIdToStepKeyWord = new ConcurrentHashMap<>();
    private final Map<String, String> pickleAstNodeIdToLongName = new ConcurrentHashMap<>();

    void collect(Envelope envelope) {
        envelope.getTestRunStarted().ifPresent(event -> this.testRunStarted = timestampToJavaInstant(event.getTimestamp()));
        envelope.getTestRunFinished().ifPresent(this::testRunFinished);
        envelope.getTestCaseStarted().ifPresent(this::testCaseStarted);
        envelope.getTestCaseFinished().ifPresent(this::testCaseFinished);
        envelope.getTestStepFinished().ifPresent(this::testStepFinished);
        envelope.getGherkinDocument().ifPresent(this::source);
        envelope.getPickle().ifPresent(this::pickle);
        envelope.getTestCase().ifPresent(this::testCase);
    }

    private void testRunFinished(TestRunFinished event) {
        this.testRunFinished = timestampToJavaInstant(event.getTimestamp());
    }

    private void testCaseStarted(TestCaseStarted event) {
        this.testCaseStartedIds.add(event.getId());
        this.testCaseStartedIdToStartedInstant.put(event.getId(), timestampToJavaInstant(event.getTimestamp()));
        this.testCaseStartedIdToTestCaseId.put(event.getId(), event.getTestCaseId());
    }

    private void testCaseFinished(TestCaseFinished event) {
        this.testCaseStartedIdToFinishedInstant.put(event.getTestCaseStartedId(),
                timestampToJavaInstant(event.getTimestamp()));
    }

    private void testStepFinished(TestStepFinished event) {
        testStepIdToTestStepResultStatus.put(event.getTestStepId(), event.getTestStepResult().getStatus());
        testCaseStartedIdToResult.compute(event.getTestCaseStartedId(),
                (__, previousStatus) -> mostSevereResult(previousStatus, event.getTestStepResult()));
    }

    private TestStepResult mostSevereResult(TestStepResult a, TestStepResult b) {
        return testStepResultComparator.compare(a, b) >= 0 ? a : b;
    }

    private void source(GherkinDocument event) {
        event.getFeature().ifPresent(feature -> {
            feature.getChildren().forEach(featureChild -> {
                featureChild.getRule().ifPresent(rule -> {
                    rule.getChildren().forEach(ruleChild -> {
                        ruleChild.getScenario().ifPresent(scenario -> {
                            scenario(feature, rule, scenario);
                        });
                        ruleChild.getBackground().ifPresent(background -> {
                            background.getSteps().forEach(step -> {
                                stepAstNodeIdToStepKeyWord.put(step.getId(), step.getKeyword());
                            });
                        });
                    });
                });
                featureChild.getBackground().ifPresent(background -> {
                    background.getSteps().forEach(step -> {
                        stepAstNodeIdToStepKeyWord.put(step.getId(), step.getKeyword());
                    });
                });
                featureChild.getScenario().ifPresent(scenario -> {
                    scenario(feature, null, scenario);
                });
            });
        });
    }

    private void scenario(Feature feature, Rule rule, Scenario scenario) {
        scenarioAstNodeIdToFeatureName.put(scenario.getId(), feature.getName());
        scenario.getSteps().forEach(step -> {
            stepAstNodeIdToStepKeyWord.put(step.getId(), step.getKeyword());
        });

        String rulePrefix = rule == null ? "" : rule.getName() + " - ";
        pickleAstNodeIdToLongName.put(scenario.getId(), rulePrefix + scenario.getName());

        List<Examples> examples = scenario.getExamples();
        for (int examplesIndex = 0; examplesIndex < examples.size(); examplesIndex++) {
            Examples currentExamples = examples.get(examplesIndex);
            List<TableRow> tableRows = currentExamples.getTableBody();
            for (int exampleIndex = 0; exampleIndex < tableRows.size(); exampleIndex++) {
                TableRow currentExample = tableRows.get(exampleIndex);
                StringBuilder suffix = new StringBuilder(" - ");
                if (!currentExamples.getName().isEmpty()) {
                    suffix.append(currentExamples.getName()).append(" - ");
                }
                suffix.append("Example #").append(examplesIndex + 1).append(".").append(exampleIndex + 1);
                pickleAstNodeIdToLongName.put(currentExample.getId(), rulePrefix + scenario.getName() + suffix);
            }
        }
    }

    private void pickle(Pickle event) {
        pickleIdToPickle.put(event.getId(), event);
        // @formatter:off
        event.getAstNodeIds().stream()
                .findFirst()
                .ifPresent(id -> pickleIdToScenarioAstNodeId.put(event.getId(), id));
        // @formatter:on
    }

    private void testCase(TestCase testCase) {
        testCaseIdToTestCase.put(testCase.getId(), testCase);
    }


    double getSuiteDurationInSeconds() {
        if (testRunStarted == null || testRunFinished == null) {
            return 0;
        }
        return durationInSeconds(testRunStarted, testRunFinished);
    }

    double getDurationInSeconds(String testCaseStartedId) {
        return durationInSeconds(testCaseStartedIdToStartedInstant.get(testCaseStartedId),
                testCaseStartedIdToFinishedInstant.get(testCaseStartedId));
    }

    private static double durationInSeconds(Instant testRunStarted, Instant testRunFinished) {
        return Duration.between(testRunStarted, testRunFinished).toMillis() / (double) MILLIS_PER_SECOND;
    }

    Map<TestStepResultStatus, Long> getTestCaseStatusCounts() {
        // @formatter:off
        return testCaseStartedIdToResult.values().stream()
                .map(TestStepResult::getStatus)
                .collect(groupingBy(identity(), counting()));
        // @formatter:on
    }

    int getTestCaseCount() {
        return testCaseStartedIdToStartedInstant.size();
    }

    String getPickleName(String testCaseStartedId) {
        String testCaseId = testCaseStartedIdToTestCaseId.get(testCaseStartedId);
        String pickleId = testCaseIdToTestCase.get(testCaseId).getPickleId();
        Pickle pickle = pickleIdToPickle.get(pickleId);
        List<String> astNodeIds = pickle.getAstNodeIds();
        String pickleAstNodeId = astNodeIds.get(astNodeIds.size() - 1);
        return pickleAstNodeIdToLongName.getOrDefault(pickleAstNodeId, pickle.getName());
    }

    public String getFeatureName(String testCaseStartedId) {
        String testCaseId = testCaseStartedIdToTestCaseId.get(testCaseStartedId);
        String pickleId = testCaseIdToTestCase.get(testCaseId).getPickleId();
        String astNodeId = pickleIdToScenarioAstNodeId.get(pickleId);
        return scenarioAstNodeIdToFeatureName.get(astNodeId);
    }

    List<Map.Entry<String, String>> getStepsAndResult(String testCaseStartedId) {
        String testCaseId = testCaseStartedIdToTestCaseId.get(testCaseStartedId);
        TestCase testCase = testCaseIdToTestCase.get(testCaseId);
        Pickle pickle = pickleIdToPickle.get(testCase.getPickleId());

        return testCase.getTestSteps().stream()
                .filter(testStep -> testStep.getPickleStepId().isPresent())
                .map(testStep -> {
                    String key = renderTestStepText(pickle).apply(testStep);
                    String value = renderTestStepResult(testStep);
                    return new AbstractMap.SimpleEntry<>(key, value);
                })
                .collect(Collectors.toList());
    }

    private String renderTestStepResult(TestStep testStep) {
        return testStepIdToTestStepResultStatus.get(testStep.getId()).value().toLowerCase(Locale.ROOT);
    }

    private Function<TestStep, String> renderTestStepText(Pickle pickle) {
        return testStep -> {
            String pickleId = testStep.getPickleStepId().orElse(null);

            Optional<PickleStep> pickleStep = pickle.getSteps().stream()
                    .filter(s -> s.getId().equals(pickleId))
                    .findFirst();

            String stepKeyWord = pickleStep
                    .map(s -> s.getAstNodeIds().get(0))
                    .map(stepAstNodeIdToStepKeyWord::get)
                    .orElse("");

            String stepText = pickleStep
                    .map(PickleStep::getText)
                    .orElse("");

            return stepKeyWord + stepText;
        };
    }

    Deque<String> testCaseStartedIds() {
        return testCaseStartedIds;
    }

    private static final io.cucumber.messages.types.Duration ZERO_DURATION =
            new io.cucumber.messages.types.Duration(0L, 0L);
    // By definition, but see https://github.com/cucumber/gherkin/issues/11
    private static final TestStepResult SCENARIO_WITH_NO_STEPS = new TestStepResult(ZERO_DURATION, null, PASSED, null);

    TestStepResult getTestCaseStatus(String testCaseStartedId) {
        return testCaseStartedIdToResult.getOrDefault(testCaseStartedId, SCENARIO_WITH_NO_STEPS);
    }

}