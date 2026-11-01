package dio.budgeting.infrastructure.http.ai;

import dio.budgeting.application.ListAllTransactionsUseCase;
import dio.budgeting.application.ListTransactionsByCategoryUseCase;
import dio.budgeting.application.PersistTransactionUseCase;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gerencia a execução assíncrona dos comandos de IA.
 *
 * Por que assíncrono? O Ollama roda em CPU (sem GPU), então um comando de IA
 * pode levar minutos. Se o endpoint ficasse esperando a resposta, proxies
 * intermediários (ex.: o do GitHub Codespaces) cortariam a conexão com 504.
 * Aqui o POST cria um "job" e retorna 202 imediatamente; o cliente consulta
 * o resultado depois por GET /transactions/ai/{jobId}.
 */
@Service
public class AiJobService {

    public enum Status { PROCESSING, DONE, ERROR }

    public record AiJob(Status status, String reply) {}

    private final ChatClient chatClient;
    private final Map<String, AiJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AiJobService(@Value("classpath:prompts/system-message.st") Resource systemPrompt,
                        ChatClient.Builder chatClientBuilder,
                        PersistTransactionUseCase persistTransactionUseCase,
                        ListTransactionsByCategoryUseCase listTransactionsByCategoryUseCase,
                        ListAllTransactionsUseCase listAllTransactionsUseCase) throws IOException {
        this.chatClient = chatClientBuilder
                .defaultSystem(systemPrompt.getContentAsString(Charset.defaultCharset()))
                .defaultTools(persistTransactionUseCase, listTransactionsByCategoryUseCase, listAllTransactionsUseCase)
                .build();
    }

    /** Cria um job, dispara o processamento da IA em segundo plano e retorna o jobId. */
    public String submit(String message) {
        var jobId = UUID.randomUUID().toString();
        jobs.put(jobId, new AiJob(Status.PROCESSING, null));

        executor.submit(() -> {
            try {
                var reply = chatClient.prompt().user(message).call().content();
                jobs.put(jobId, new AiJob(Status.DONE, reply));
            } catch (Exception e) {
                jobs.put(jobId, new AiJob(Status.ERROR, "Falha ao processar o comando: " + e.getMessage()));
            }
        });

        return jobId;
    }

    /** Retorna o job ou null se o jobId não existir. */
    public AiJob find(String jobId) {
        return jobs.get(jobId);
    }
}
