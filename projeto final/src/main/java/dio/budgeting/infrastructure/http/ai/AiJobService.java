package dio.budgeting.infrastructure.http.ai;

import dio.budgeting.application.AiActionRecorder;
import dio.budgeting.application.ListAllTransactionsUseCase;
import dio.budgeting.application.ListTransactionsByCategoryUseCase;
import dio.budgeting.application.PersistTransactionUseCase;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

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
    private final AiActionRecorder aiActionRecorder;
    private final Map<String, AiJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    // Uma chamada de IA por vez: com CPU limitada, chamadas paralelas se
    // atropelam (todas ficam lentas e o modelo pode ser morto por falta de RAM).
    private final Semaphore llmLock = new Semaphore(1);

    public AiJobService(@Value("classpath:prompts/system-message.st") Resource systemPrompt,
                        ChatClient.Builder chatClientBuilder,
                        PersistTransactionUseCase persistTransactionUseCase,
                        ListTransactionsByCategoryUseCase listTransactionsByCategoryUseCase,
                        ListAllTransactionsUseCase listAllTransactionsUseCase,
                        AiActionRecorder aiActionRecorder) throws IOException {
        this.chatClient = chatClientBuilder
                .defaultSystem(systemPrompt.getContentAsString(Charset.defaultCharset()))
                .defaultTools(persistTransactionUseCase, listTransactionsByCategoryUseCase, listAllTransactionsUseCase)
                .build();
        this.aiActionRecorder = aiActionRecorder;
    }

    /** Cria um job, dispara o processamento da IA em segundo plano e retorna o jobId. */
    public String submit(String message) {
        var jobId = UUID.randomUUID().toString();
        jobs.put(jobId, new AiJob(Status.PROCESSING, null));

        executor.submit(() -> {
            try {
                llmLock.acquire();
                try {
                    var parts = splitExpenses(message);
                    String reply;

                    if (parts.size() == 1) {
                        var outcome = runOnce(message);
                        reply = outcome.action() != null ? outcome.action() : friendlyFallback(outcome.reply());
                    } else {
                        // Vários gastos na mesma frase: um por chamada, o modelo pequeno acerta mais.
                        var lines = new ArrayList<String>();
                        var notUnderstood = new ArrayList<String>();
                        for (var part : parts) {
                            // Trecho sem verbo ("R$5 no café"): o modelo pequeno acerta bem a forma "gastei ...".
                            var command = part.matches("(?i)^(R\\$\\s*)?\\d.*") ? "gastei " + part : part;
                            var outcome = runOnce(command);
                            if (outcome.action() != null) {
                                lines.add(outcome.action());
                            } else {
                                notUnderstood.add("⚠️ Não entendi: \"" + part + "\"");
                            }
                        }
                        if (lines.isEmpty()) {
                            reply = friendlyFallback(null);
                        } else {
                            lines.addAll(notUnderstood);
                            reply = String.join("\n", lines);
                        }
                    }

                    jobs.put(jobId, new AiJob(Status.DONE, reply));
                } finally {
                    llmLock.release();
                }
            } catch (Exception e) {
                jobs.put(jobId, new AiJob(Status.ERROR, "Falha ao processar o comando: " + e.getMessage()));
            }
        });

        return jobId;
    }

    private record Outcome(String action, String reply) {}

    /** Uma chamada ao modelo; action é o que a ferramenta REALMENTE executou (null se nada). */
    private Outcome runOnce(String message) {
        aiActionRecorder.consume(); // limpa restos de execuções anteriores

        var reply = chatClient.prompt().user(message).call().content();
        var action = aiActionRecorder.consume();

        // Modelos pequenos (~1B) às vezes despejam o JSON da ferramenta como
        // TEXTO em vez de executá-la: tenta de novo com instrução explícita.
        if (action == null && isRawToolJson(reply)) {
            reply = chatClient.prompt().user(message
                    + "\n\nIMPORTANTE: NÃO escreva JSON nem código. Use a ferramenta disponível e responda APENAS com uma frase amigável em português."
            ).call().content();
            action = aiActionRecorder.consume();
        }
        // AUDITORIA: a resposta final vem do que a ferramenta executou, não do texto do modelo.
        return new Outcome(action, reply);
    }

    private String friendlyFallback(String reply) {
        reply = sanitize(reply);
        if (reply == null || isRawToolJson(reply) || reply.startsWith("ERRO:")) {
            return "😅 Não consegui identificar os dados do comando. Tente algo direto, por exemplo: \"gastei 50 reais no mercado\".";
        }
        return reply;
    }

    // ";", ", R$" ou " e " seguido de valor separam gastos; vírgula decimal (30,50) não separa.
    private static final Pattern EXPENSE_SPLIT = Pattern.compile("(?i)\\s*;\\s*|\\s*,\\s*(?=R\\$)|\\s+e\\s+(?=R\\$|\\d)");

    /** Divide "gastei R$30 no posto e R$5 no café" em um gasto por item; só divide se todos tiverem valor. */
    private List<String> splitExpenses(String message) {
        var parts = Arrays.stream(EXPENSE_SPLIT.split(message))
                .map(p -> p.replaceAll("[.\\s]+$", ""))
                .filter(p -> !p.isBlank())
                .toList();
        if (parts.size() < 2 || parts.stream().anyMatch(p -> p.chars().noneMatch(Character::isDigit))) {
            return List.of(message);
        }
        return parts;
    }

    /** Remove aspas extras que o modelo às vezes coloca em volta da resposta. */
    private String sanitize(String text) {
        if (text == null) {
            return null;
        }
        var t = text.trim();
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    /** Detecta resposta que é JSON/código de chamada de ferramenta em vez de texto amigável. */
    private boolean isRawToolJson(String text) {
        if (text == null) {
            return false;
        }
        // cobre JSON puro ou embutido em bloco de markdown (```json ... ```)
        return text.contains("\"function\"")
                || text.contains("\"parameters\"")
                || text.contains("persist-transaction")
                || text.contains("list-all-transactions")
                || text.contains("list-transactions-by-category");
    }

    /** Retorna o job ou null se o jobId não existir. */
    public AiJob find(String jobId) {
        return jobs.get(jobId);
    }
}
