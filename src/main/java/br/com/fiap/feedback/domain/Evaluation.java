package br.com.fiap.feedback.domain;

import java.time.Instant;

public record Evaluation(String id, String descricao, int nota, Urgency urgencia, Instant createdAt) {
    public enum Urgency { CRITICA, MEDIA, BAIXA }
    public static Urgency classify(int score) {
        if (score < 0 || score > 10) throw new IllegalArgumentException("Nota fora do intervalo");
        return score <= 3 ? Urgency.CRITICA : score <= 6 ? Urgency.MEDIA : Urgency.BAIXA;
    }
}
