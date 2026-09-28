package com.cavisson.jenkins.analysefailure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies parsing against the exact JUnit report shape provided by Cavisson: testsuite-level
 * properties (started_by, workspace) plus per-testcase properties (status, tr_number), deriving
 * each failing testcase's analysis inputs with no extra task input required.
 */
class JunitFailureParserTest {

    private static final String SAMPLE_REPORT =
            "<?xml version='1.0' encoding='utf-8'?>\n"
                    + "<testsuite name=\"AI/demo/BoutiqueDiscountCouponRegression\" tests=\"10\" failures=\"2\" errors=\"0\" skipped=\"0\" time=\"315.000\" timestamp=\"2026-07-06T07:19:24\">\n"
                    + "  <properties>\n"
                    + "    <property name=\"tsr_no\" value=\"1061\" />\n"
                    + "    <property name=\"run_name\" value=\"TSR_1061\" />\n"
                    + "    <property name=\"workspace\" value=\"admin/system\" />\n"
                    + "    <property name=\"status\" value=\"failed\" />\n"
                    + "    <property name=\"started_by\" value=\"cavisson\" />\n"
                    + "    <property name=\"passed\" value=\"8\" />\n"
                    + "    <property name=\"failed\" value=\"2\" />\n"
                    + "  </properties>\n"
                    + "  <testcase name=\"AI/demo/twoproducts_EUR_SAVE10DiscountCoupon\" classname=\"AI/demo/BoutiqueDiscountCouponRegression\" time=\"61.000\">\n"
                    + "    <properties>\n"
                    + "      <property name=\"tr_number\" value=\"1753\" />\n"
                    + "      <property name=\"status\" value=\"failed\" />\n"
                    + "      <property name=\"tags\" value=\"TestSuiteRun=1061\" />\n"
                    + "      <property name=\"continue_on_failure\" value=\"true\" />\n"
                    + "    </properties>\n"
                    + "  </testcase>\n"
                    + "  <testcase name=\"AI/demo/anotherFailingCase\" classname=\"AI/demo/BoutiqueDiscountCouponRegression\" time=\"45.000\">\n"
                    + "    <properties>\n"
                    + "      <property name=\"tr_number\" value=\"1754\" />\n"
                    + "      <property name=\"status\" value=\"failed\" />\n"
                    + "    </properties>\n"
                    + "  </testcase>\n"
                    + "  <testcase name=\"AI/demo/passingCase\" classname=\"AI/demo/BoutiqueDiscountCouponRegression\" time=\"30.000\">\n"
                    + "    <properties>\n"
                    + "      <property name=\"tr_number\" value=\"1755\" />\n"
                    + "      <property name=\"status\" value=\"passed\" />\n"
                    + "    </properties>\n"
                    + "  </testcase>\n"
                    + "</testsuite>\n";

    @Test
    void onlyFailingTestcasesAreReturned() throws Exception {
        List<AnalysisTarget> targets = JunitFailureParser.parseFailingTestcases(SAMPLE_REPORT);

        assertEquals(2, targets.size());
    }

    @Test
    void failingTestcaseFieldsAreDerivedCorrectly() throws Exception {
        List<AnalysisTarget> targets = JunitFailureParser.parseFailingTestcases(SAMPLE_REPORT);

        AnalysisTarget first = targets.get(0);
        assertEquals("1061", first.tsrNumber);
        assertEquals("1753", first.trNumber);
        assertEquals("twoproducts_EUR_SAVE10DiscountCoupon", first.scenario);
        assertEquals("AI", first.project);
        assertEquals("demo", first.subProject);
        assertEquals("cavisson", first.userName);
        assertEquals("system", first.workProfileName);
    }

    @Test
    void secondFailingTestcaseIsAlsoDerived() throws Exception {
        List<AnalysisTarget> targets = JunitFailureParser.parseFailingTestcases(SAMPLE_REPORT);

        AnalysisTarget second = targets.get(1);
        assertEquals("1061", second.tsrNumber);
        assertEquals("1754", second.trNumber);
        assertEquals("anotherFailingCase", second.scenario);
    }

    @Test
    void noFailingTestcasesReturnsEmptyList() throws Exception {
        String allPassingReport =
                "<?xml version='1.0' encoding='utf-8'?>\n"
                        + "<testsuite name=\"AI/demo/BoutiqueDiscountCouponRegression\" tests=\"1\" failures=\"0\">\n"
                        + "  <properties>\n"
                        + "    <property name=\"started_by\" value=\"cavisson\" />\n"
                        + "    <property name=\"workspace\" value=\"admin/system\" />\n"
                        + "  </properties>\n"
                        + "  <testcase name=\"AI/demo/passingCase\" classname=\"AI/demo/BoutiqueDiscountCouponRegression\" time=\"30.000\">\n"
                        + "    <properties>\n"
                        + "      <property name=\"tr_number\" value=\"1755\" />\n"
                        + "      <property name=\"status\" value=\"passed\" />\n"
                        + "    </properties>\n"
                        + "  </testcase>\n"
                        + "</testsuite>\n";

        List<AnalysisTarget> targets = JunitFailureParser.parseFailingTestcases(allPassingReport);

        assertEquals(0, targets.size());
    }
}
