package com.liskovsoft.youtubeapi.videoinfo;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class FormatTransformDiagnosticsTest {
    @Test
    public void completeOutputsCountOnlyRequestedPositions() {
        assertEquals("2/2,unchanged=0,size=match", FormatTransformDiagnostics.summarize(
                Arrays.asList("input-a", null, "input-b"),
                Arrays.asList("output-a", null, "output-b")));
    }

    @Test
    public void missingResultIsNotReportedAsSuccessful() {
        assertEquals("0/2,unchanged=0,size=absent", FormatTransformDiagnostics.summarize(
                Arrays.asList("input-a", "input-b"), null));
    }

    @Test
    public void nullAndEmptyOutputsAreMissing() {
        assertEquals("0/2,unchanged=0,size=match", FormatTransformDiagnostics.summarize(
                Arrays.asList("input-a", "input-b"), Arrays.asList(null, "")));
    }

    @Test
    public void shortResultIsCountedByPositionWithoutBroadcasting() {
        assertEquals("1/2,unchanged=0,size=mismatch", FormatTransformDiagnostics.summarize(
                Arrays.asList("input-a", "input-b"), Collections.singletonList("output-a")));
    }

    @Test
    public void extraOutputsCannotInflateCompleteness() {
        assertEquals("1/1,unchanged=0,size=mismatch", FormatTransformDiagnostics.summarize(
                Collections.singletonList("input-a"), Arrays.asList("output-a", "extra")));
    }

    @Test
    public void unchangedValuesAreCountedWithoutPrintingThem() {
        assertEquals("1/1,unchanged=1,size=match", FormatTransformDiagnostics.summarize(
                Collections.singletonList("private-test-value"),
                Collections.singletonList("private-test-value")));
    }

    @Test
    public void noWorkIsDistinctFromMissingRequestedOutputs() {
        assertEquals("0/0,unchanged=0,size=match", FormatTransformDiagnostics.summarize(
                Collections.emptyList(), Collections.emptyList()));
        assertEquals("0/0,unchanged=0,size=absent", FormatTransformDiagnostics.summarize(
                Collections.singletonList(null), null));
    }

    @Test
    public void inspectionDoesNotMutateLists() {
        java.util.List<String> inputs = Collections.unmodifiableList(Arrays.asList("a", "b"));
        java.util.List<String> outputs = Collections.unmodifiableList(Arrays.asList("c", "d"));
        assertEquals("2/2,unchanged=0,size=match", FormatTransformDiagnostics.summarize(inputs, outputs));
        assertEquals(Arrays.asList("a", "b"), inputs);
        assertEquals(Arrays.asList("c", "d"), outputs);
    }
}
