package dev.opencollab.paletter.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data workflows keep their constants in one place each and their logic in the tools: the data paths in
 * .github/data-paths.txt ({@link DataPaths#ALL}), the add-on inputs in {@link DataPaths#ADDON_INPUTS}, and no jq outside the
 * publish jobs (which run no repository code and so check artifact values in the shell). The build number regex is
 * {@code BuildNumberTest}'s.
 */
class WorkflowConsistencyTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    /** The workflows that publish data. */
    static final List<String> DATA_WORKFLOWS = List.of("bds-data.yml", "recapture.yml");
    /** The workflows whose logic lives in the tools. */
    static final List<String> TOOL_LOGIC = DATA_WORKFLOWS;
    private static final Pattern JOB = Pattern.compile("(?m)^  ([A-Za-z0-9_-]+):\\s*$");

    private static String workflow(String name) throws IOException {
        return Files.readString(ROOT.resolve(".github/workflows").resolve(name));
    }

    /** The jobs of a workflow by name, each with its text. */
    static Map<String, String> jobs(String workflow) {
        String jobs = workflow.substring(workflow.indexOf("\njobs:\n"));
        Map<String, String> byName = new LinkedHashMap<>();
        Matcher matcher = JOB.matcher(jobs);
        String name = null;
        int start = 0;
        while (matcher.find()) {
            if (name != null) {
                byName.put(name, jobs.substring(start, matcher.start()));
            }
            name = matcher.group(1);
            start = matcher.start();
        }
        if (name != null) {
            byName.put(name, jobs.substring(start));
        }
        return byName;
    }

    @Test
    void theDataPathsAreTheToolsOnes() throws IOException {
        List<String> lines = Files.readAllLines(ROOT.resolve(DataPaths.FILE)).stream().filter(line -> !line.isBlank()).toList();
        assertEquals(DataPaths.ALL, lines);
        for (String name : DATA_WORKFLOWS) {
            String text = workflow(name);
            for (String path : DataPaths.ALL.subList(1, DataPaths.ALL.size())) {
                assertFalse(text.contains(path), name + " spells the data path " + path + " instead of reading " + DataPaths.FILE);
            }
        }
    }

    @Test
    void noJqOutsideThePublishJobs() throws IOException {
        for (String name : TOOL_LOGIC) {
            Map<String, String> jobs = jobs(workflow(name));
            assertTrue(jobs.containsKey("publish"), name + " has a publish job");
            jobs.forEach((job, text) -> {
                if (!job.equals("publish")) {
                    assertFalse(Pattern.compile("\\bjq\\b").matcher(text).find(), name + ", job " + job + ": jq outside the publish job; that logic belongs in the tools");
                }
            });
        }
    }

    @Test
    void theAddonInputsAreNamedInTheToolsOnly() throws IOException {
        for (String name : TOOL_LOGIC) {
            String text = workflow(name);
            for (String input : DataPaths.ADDON_INPUTS) {
                String file = input.substring(input.lastIndexOf('/') + 1);
                assertFalse(text.contains(file) || text.contains(file.replace(".json", "|")), name + " names the add-on input " + file);
            }
        }
    }

    @Test
    void decideAndPublishComputeTheDataTreeAlike() throws IOException {
        String line = "DATA_TREE=$(git ls-tree HEAD -- $(cat .github/data-paths.txt) | sha256sum | cut -c1-64)";
        Map<String, String> jobs = jobs(workflow("bds-data.yml"));
        assertTrue(jobs.get("decide").contains(line), "decide computes the data tree");
        assertTrue(jobs.get("publish").contains(line), "publish computes the data tree");
    }

    @Test
    void theJobSplitFindsEveryJob() {
        String text = "on: push\njobs:\n  decide:\n    steps: []\n  capture:\n    run: jq\n  publish:\n    run: x\n";
        assertEquals(List.of("decide", "capture", "publish"), List.copyOf(jobs(text).keySet()));
        assertTrue(jobs(text).get("capture").contains("jq"));
    }
}
