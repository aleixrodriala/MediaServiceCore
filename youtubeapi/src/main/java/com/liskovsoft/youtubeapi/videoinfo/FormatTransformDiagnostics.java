package com.liskovsoft.youtubeapi.videoinfo;

import java.util.List;

/** Structural diagnostics only: never expose input/output values or judge server validity. */
final class FormatTransformDiagnostics {
    private FormatTransformDiagnostics() {}

    static String summarize(List<String> inputs, List<String> outputs) {
        int expected = 0;
        int present = 0;
        int unchanged = 0;
        for (int i = 0; i < inputs.size(); i++) {
            String input = inputs.get(i);
            if (input == null) {
                continue;
            }
            expected++;
            String output = outputs != null && i < outputs.size() ? outputs.get(i) : null;
            if (output != null && !output.isEmpty()) {
                present++;
                if (input.equals(output)) {
                    unchanged++;
                }
            }
        }
        return present + "/" + expected + ",unchanged=" + unchanged
                + ",size=" + (outputs == null ? "absent"
                : outputs.size() == inputs.size() ? "match" : "mismatch");
    }
}
