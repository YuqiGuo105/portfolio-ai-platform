package site.yuqi.agent.generation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepAnswerLoopTest {

    private static final DeepAnswerLoop.Progress SILENT = (label, detail, completed) -> { };

    @Test
    void reviewReplyIsReadLenientlyAndBounded() {
        DeepAnswerLoop.Review review = DeepAnswerLoop.parseReview("""
                VERDICT: REVISE
                GAPS:
                - No dates for the Goldman role
                - Does not say which stack
                SEARCH:
                - Goldman Sachs start date
                - portfolio platform tech stack
                - a third query is dropped
                """);
        assertThat(review.complete()).isFalse();
        assertThat(review.gaps()).containsExactly("No dates for the Goldman role", "Does not say which stack");
        assertThat(review.queries()).containsExactly("Goldman Sachs start date", "portfolio platform tech stack");

        DeepAnswerLoop.Review mixed = DeepAnswerLoop.parseReview(
                "Verdict: revise\nGaps:\n1. Missing the university names\n* Search results are thin\nSearch: Syracuse degree");
        assertThat(mixed.gaps()).containsExactly("Missing the university names", "Search results are thin");
        assertThat(mixed.queries()).containsExactly("Syracuse degree");

        assertThat(DeepAnswerLoop.parseReview("VERDICT: REVISE\nGAPS:\n- a\n- b\n- c\n- d\n- e").gaps()).hasSize(4);
    }

    @Test
    void anythingThatIsNotARequestForChangesKeepsTheDraft() {
        assertThat(DeepAnswerLoop.parseReview(null).asksForNothing()).isTrue();
        assertThat(DeepAnswerLoop.parseReview("  ").asksForNothing()).isTrue();
        assertThat(DeepAnswerLoop.parseReview("I think it is fine.").asksForNothing()).isTrue();
        assertThat(DeepAnswerLoop.parseReview("VERDICT: COMPLETE\nGAPS:\nSEARCH:").asksForNothing()).isTrue();
        // A complete verdict wins over stray notes; an echoed template with "none" asks for nothing.
        assertThat(DeepAnswerLoop.parseReview("VERDICT: COMPLETE\nGAPS:\n- something anyway").complete()).isTrue();
        assertThat(DeepAnswerLoop.parseReview("**VERDICT:** COMPLETE or REVISE\n**GAPS:** none\n**SEARCH:** N/A")
                .asksForNothing()).isTrue();
    }

    @Test
    void draftsReviewsLooksUpNewEvidenceAndRefinesOnce() {
        List<String> steps = new ArrayList<>(), prompts = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        DeepAnswerLoop loop = new DeepAnswerLoop(
                (system, prompt) -> {
                    prompts.add(prompt);
                    now.addAndGet(8_000);
                    return prompts.size() == 1 ? " First draft. " : "Final answer with dates.";
                },
                (system, prompt) -> {
                    assertThat(system).isEqualTo(DeepAnswerLoop.REVIEW_SYSTEM);
                    assertThat(prompt).contains("When did he start?", "known chunk", "First draft.");
                    now.addAndGet(3_000);
                    return "VERDICT: REVISE\nGAPS:\n- No dates\nSEARCH:\n- role dates";
                },
                query -> List.of(new DeepAnswerLoop.Evidence("c1", "known chunk"),
                        new DeepAnswerLoop.Evidence("c9", "## Role\nSince Jan 2025")),
                (label, detail, completed) -> steps.add(label + "|" + completed),
                now::get, 1, 45_000);

        DeepAnswerLoop.Result result = loop.run("SYS", "PROMPT", "When did he start?", "known chunk", Set.of("c1"));

        assertThat(result.answer()).isEqualTo("Final answer with dates.");
        assertThat(result.refined()).isTrue();
        assertThat(result.modelCalls()).isEqualTo(3);
        assertThat(result.reviews()).isEqualTo(1);
        assertThat(result.lookups()).isEqualTo(1);
        assertThat(result.addedChunks()).isEqualTo(1);
        // Evidence grows only by chunks that were not already in the prompt.
        assertThat(result.evidence()).isEqualTo("known chunk\n---\n## Role\nSince Jan 2025");
        assertThat(prompts.get(0)).startsWith("PROMPT").contains("Deep answer instructions");
        assertThat(prompts.get(1)).startsWith("PROMPT")
                .contains("Since Jan 2025", "First draft.", "- No dates", "do not mention the draft");
        assertThat(steps).containsExactly(
                "Draft the answer|false", "Draft the answer|true",
                "Review the draft|false", "Review the draft|true",
                "Look up what is missing|false", "Look up what is missing|true",
                "Refine the answer|false", "Refine the answer|true");
    }

    @Test
    void aCompleteDraftIsNotRewrittenAndNothingIsLookedUp() {
        AtomicInteger writes = new AtomicInteger();
        DeepAnswerLoop.Result result = new DeepAnswerLoop(
                (system, prompt) -> { writes.incrementAndGet(); return "Good."; },
                (system, prompt) -> "VERDICT: COMPLETE",
                query -> { throw new IllegalStateException("no look-up expected"); },
                SILENT, () -> 0L, 2, 45_000).run("S", "P", "Q", "", Set.of());
        assertThat(result.answer()).isEqualTo("Good.");
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(writes).hasValue(1);
        assertThat(result.refined()).isFalse();
    }

    @Test
    void onlyAFailedFirstDraftIsAnError() {
        // The review is down: the draft stands.
        DeepAnswerLoop.Result noReview = new DeepAnswerLoop(
                (system, prompt) -> "Draft.",
                (system, prompt) -> { throw new IllegalStateException("down"); },
                query -> List.of(), SILENT, () -> 0L, 2, 45_000).run("S", "P", "Q", "E", Set.of());
        assertThat(noReview.answer()).isEqualTo("Draft.");
        assertThat(noReview.reviews()).isZero();

        // The rewrite is down, and so is the look-up: the draft stands and the loop stops.
        AtomicInteger writes = new AtomicInteger();
        DeepAnswerLoop.Result noRewrite = new DeepAnswerLoop(
                (system, prompt) -> {
                    if (writes.getAndIncrement() > 0) throw new IllegalStateException("down");
                    return "Draft.";
                },
                (system, prompt) -> "VERDICT: REVISE\nGAPS:\n- x\nSEARCH:\n- y",
                query -> { throw new IllegalStateException("down"); },
                SILENT, () -> 0L, 2, 45_000).run("S", "P", "Q", "E", Set.of());
        assertThat(noRewrite.answer()).isEqualTo("Draft.");
        assertThat(noRewrite.refined()).isFalse();
        assertThat(noRewrite.reviews()).isEqualTo(1);
        assertThat(noRewrite.evidence()).isEqualTo("E");

        assertThatThrownBy(() -> new DeepAnswerLoop((system, prompt) -> "  ", (system, prompt) -> "",
                query -> List.of(), SILENT, () -> 0L, 1, 45_000).run("S", "P", "Q", "E", Set.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Generation returned no answer");
    }

    @Test
    void theLoopIsBoundedInTimeAndInRounds() {
        // A slow first draft leaves no time for a rewrite, so no review is started.
        AtomicLong now = new AtomicLong();
        AtomicInteger reviews = new AtomicInteger();
        DeepAnswerLoop.Result slow = new DeepAnswerLoop(
                (system, prompt) -> { now.addAndGet(40_000); return "Slow draft."; },
                (system, prompt) -> { reviews.incrementAndGet(); return "VERDICT: REVISE\nGAPS:\n- x"; },
                query -> List.of(), SILENT, now::get, 2, 45_000).run("S", "P", "Q", "E", Set.of());
        assertThat(slow.answer()).isEqualTo("Slow draft.");
        assertThat(reviews).hasValue(0);
        assertThat(slow.modelCalls()).isEqualTo(1);

        // A reviewer that always wants more is cut off: never more than three rounds.
        AtomicInteger versions = new AtomicInteger();
        DeepAnswerLoop.Result bounded = new DeepAnswerLoop(
                (system, prompt) -> "v" + versions.incrementAndGet(),
                (system, prompt) -> "VERDICT: REVISE\nGAPS:\n- more",
                query -> List.of(), SILENT, () -> 0L, 99, 600_000).run("S", "P", "Q", "E", Set.of());
        assertThat(bounded.reviews()).isEqualTo(3);
        assertThat(bounded.modelCalls()).isEqualTo(7);
        assertThat(bounded.answer()).isEqualTo("v4");

        // Zero rounds is a single careful draft.
        DeepAnswerLoop.Result single = new DeepAnswerLoop(
                (system, prompt) -> "only",
                (system, prompt) -> { throw new AssertionError("no review expected"); },
                query -> List.of(), SILENT, () -> 0L, 0, 45_000).run("S", "P", "Q", "E", Set.of());
        assertThat(single.modelCalls()).isEqualTo(1);
    }
}
