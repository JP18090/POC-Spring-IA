package dio.budgeting.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Registra a ação real executada pelas ferramentas durante um comando de IA.
 *
 * Como as chamadas de IA são serializadas (uma por vez, ver AiJobService),
 * um único slot é suficiente. A resposta final ao usuário é montada a partir
 * do que REALMENTE aconteceu no sistema — não do texto que um modelo pequeno
 * improvisou. É a camada de auditoria do fluxo de IA.
 */
@Component
public class AiActionRecorder {

    private final AtomicReference<String> lastAction = new AtomicReference<>();

    public void record(String action) {
        lastAction.set(action);
    }

    /** Lê e limpa a última ação registrada (null se nenhuma ferramenta executou). */
    public String consume() {
        return lastAction.getAndSet(null);
    }
}
