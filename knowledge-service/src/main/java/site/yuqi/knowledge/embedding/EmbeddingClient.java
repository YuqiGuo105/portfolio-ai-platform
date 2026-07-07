package site.yuqi.knowledge.embedding;

/**
 * Embedding client interface — generates vectors via Gemini text-embedding-004.
 */
public interface EmbeddingClient {
    float[] embed(String text);
    int dimension();
}
