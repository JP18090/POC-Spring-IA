package dio.budgeting.infrastructure.http;

import dio.budgeting.application.ListAllTransactionsUseCase;
import dio.budgeting.application.ListTransactionsByCategoryUseCase;
import dio.budgeting.application.PersistTransactionUseCase;
import dio.budgeting.domain.Category;
import dio.budgeting.infrastructure.ai.WhisperTranscriptionService;
import dio.budgeting.infrastructure.http.ai.AiJobService;
import dio.budgeting.infrastructure.http.request.AiRequest;
import dio.budgeting.infrastructure.http.request.TransactionRequest;
import dio.budgeting.infrastructure.http.response.AiJobResponse;
import dio.budgeting.infrastructure.http.response.TransactionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/transactions")
public class TransactionController {
    private final PersistTransactionUseCase persistTransactionUseCase;
    private final ListTransactionsByCategoryUseCase listTransactionsByCategoryUseCase;
    private final ListAllTransactionsUseCase listAllTransactionsUseCase;

    private final AiJobService aiJobService;
    private final WhisperTranscriptionService whisperTranscriptionService;

    public TransactionController(PersistTransactionUseCase persistTransactionUseCase,
                                 ListTransactionsByCategoryUseCase listTransactionsByCategoryUseCase,
                                 ListAllTransactionsUseCase listAllTransactionsUseCase,
                                 AiJobService aiJobService,
                                 WhisperTranscriptionService whisperTranscriptionService) {
        this.persistTransactionUseCase = persistTransactionUseCase;
        this.listTransactionsByCategoryUseCase = listTransactionsByCategoryUseCase;
        this.listAllTransactionsUseCase = listAllTransactionsUseCase;
        this.aiJobService = aiJobService;
        this.whisperTranscriptionService = whisperTranscriptionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransaction(@RequestBody TransactionRequest request) {
        var transaction = persistTransactionUseCase.execute(request.toInput());
        return TransactionResponse.from(transaction);
    }

    @GetMapping
    public List<TransactionResponse> readAllTransactions() {
        return listAllTransactionsUseCase.execute().stream().map(TransactionResponse::from).toList();
    }

    @GetMapping("/{category}")
    public List<TransactionResponse> readTransactionsByCategory(@PathVariable Category category) {
        return listTransactionsByCategoryUseCase.execute(category).stream().map(TransactionResponse::from).toList();
    }

    /**
     * Endpoint de IA ASSÍNCRONO: recebe um comando em texto (ex.: "gastei 80 reais
     * no mercado"), cria um job e retorna 202 + jobId imediatamente. O modelo local
     * (Ollama) processa em segundo plano: entende a intenção e chama as ferramentas
     * (casos de uso) para registrar ou consultar transações de verdade.
     * O resultado é consultado em GET /transactions/ai/{jobId}.
     */
    @PostMapping("/ai")
    public ResponseEntity<AiJobResponse> aiCommand(@RequestBody AiRequest request) {
        var jobId = aiJobService.submit(request.message());
        return ResponseEntity.accepted().body(new AiJobResponse(jobId, "PROCESSING", null));
    }

    /** Consulta o andamento/resultado de um comando de IA. */
    @GetMapping("/ai/{jobId}")
    public ResponseEntity<AiJobResponse> aiCommandStatus(@PathVariable String jobId) {
        var job = aiJobService.find(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new AiJobResponse(jobId, job.status().name(), job.reply()));
    }

    /**
     * Endpoint de IA com ÁUDIO (o fluxo completo do desafio!): recebe um arquivo
     * de áudio (multipart), transcreve com o Whisper local e envia o texto como
     * comando de IA assíncrono. Retorna 202 + jobId; o campo reply traz a
     * transcrição para o usuário conferir o que foi entendido.
     */
    @PostMapping(value = "/ai/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AiJobResponse> aiAudioCommand(@RequestParam("file") MultipartFile file) {
        try {
            var transcription = whisperTranscriptionService.transcribe(file.getResource());
            var jobId = aiJobService.submit(transcription);
            return ResponseEntity.accepted().body(new AiJobResponse(jobId, "PROCESSING", transcription));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new AiJobResponse(null, "ERROR", "Falha na transcrição do áudio: " + e.getMessage()));
        }
    }
}
