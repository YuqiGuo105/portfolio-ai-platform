package site.yuqi.agent.generation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.yuqi.agent.language.ResponseLanguagePolicy;

/**
 * Last-mile language alignment for user-visible answers.
 *
 * <p>The model decides the user's input language and rewrites the candidate
 * answer into that language without changing facts. This keeps hard-coded
 * fallback text, tool messages, and planner messages from leaking English when
 * the user asked in another language.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponseLanguageService {

    private final GeminiGenerationService generationService;

    private static final String SYSTEM_PROMPT = """
            You are a strict translation and localization layer for Yuqi's AI assistant.

            Task:
            - Rewrite the candidate answer in the response language selected by the contract below.
            - Preserve all facts, numbers, IDs, links, markdown structure, and safety meaning.
            - Do not add new information, remove material details, or answer a different question.
            - If the candidate answer is already in the required language, return it unchanged.
            - Return only the final user-facing answer, with no explanations or labels.
            """ + ResponseLanguagePolicy.INSTRUCTION;

    public String alignToInputLanguage(String userInput, String candidateAnswer) {
        if (candidateAnswer == null || candidateAnswer.isBlank()
                || userInput == null || userInput.isBlank()) {
            return candidateAnswer;
        }

        String prompt = """
                Current user input:
                %s

                Candidate answer:
                %s
                """.formatted(userInput, candidateAnswer);

        try {
            String aligned = generationService.generate(SYSTEM_PROMPT, prompt);
            if (aligned != null && !aligned.isBlank()) {
                return aligned.trim();
            }
        } catch (Exception e) {
            log.warn("Response language alignment failed: {}", e.toString());
        }

        return candidateAnswer;
    }

    public String alignToLanguage(String language, String candidateAnswer) {
        if (candidateAnswer == null || candidateAnswer.isBlank()
                || language == null || language.isBlank()) {
            return candidateAnswer;
        }
        String prompt = """
                Explicitly requested response language (ISO 639-1 / BCP-47):
                %s

                Candidate answer:
                %s
                """.formatted(language, candidateAnswer);
        try {
            String aligned = generationService.generate(SYSTEM_PROMPT + "\nIn this localization task, "
                    + "use the explicitly requested response language; the candidate's language is not the target.", prompt);
            return aligned == null || aligned.isBlank() ? candidateAnswer : aligned.trim();
        } catch (Exception e) {
            log.warn("Response language alignment failed: {}", e.toString());
            return candidateAnswer;
        }
    }
}
