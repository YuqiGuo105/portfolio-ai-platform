package site.yuqi.knowledge.embedding;

/**
 * Embedding client interface — generates vectors via Gemini text-embedding-004.
 */
public interface EmbeddingClient {
    float[] embed(String text);
    default float[] embedQuery(String text, int dimensions) {
        float[] result = embed(text);
        if (result.length != dimensions) throw new IllegalStateException("Embedding dimensions do not match the target index");
        return result;
    }
    int dimension();
}
