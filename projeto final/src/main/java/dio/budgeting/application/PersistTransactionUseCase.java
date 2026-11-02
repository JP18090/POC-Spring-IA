package dio.budgeting.application;

import dio.budgeting.application.input.PersistTransactionInput;
import dio.budgeting.application.output.TransactionOutput;
import dio.budgeting.domain.Category;
import dio.budgeting.domain.Transaction;
import dio.budgeting.domain.TransactionRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

@Service
public class PersistTransactionUseCase {
    private final TransactionRepository transactionRepository;
    private final AiActionRecorder aiActionRecorder;

    public PersistTransactionUseCase(TransactionRepository transactionRepository,
                                     AiActionRecorder aiActionRecorder) {
        this.transactionRepository = transactionRepository;
        this.aiActionRecorder = aiActionRecorder;
    }

    /**
     * Versão exposta à IA. Decisões pensadas para modelos locais pequenos:
     * - Parâmetros simples (em vez de objeto aninhado): schemas "achatados" têm
     *   muito mais acerto;
     * - Valor em REAIS: a conversão para centavos é feita aqui, não pelo modelo;
     * - Validação com mensagem em texto: em vez de estourar exceção, o erro volta
     *   para o modelo, que pode corrigir os parâmetros e chamar de novo.
     */
    @Tool(name = "persist-transaction", description = "Persiste uma nova transação financeira")
    public String execute(@ToolParam(description = "Descrição do gasto") String description,
                          @ToolParam(description = "Valor do gasto em REAIS (ex.: 30 ou 12.50), NÃO em centavos") Double amount,
                          @ToolParam(description = "Categoria de uma transação") Category category) {
        if (description == null || description.isBlank()) {
            return "ERRO: o parâmetro 'description' é obrigatório. Chame a ferramenta novamente informando a descrição do gasto.";
        }
        if (amount == null || amount <= 0) {
            return "ERRO: o parâmetro 'amount' (valor em reais) é obrigatório e maior que zero. Chame a ferramenta novamente informando-o (ex.: 80 reais = 80).";
        }
        if (category == null) {
            return "ERRO: o parâmetro 'category' é obrigatório (GROCERIES, PHARMA ou AUTO). Chame a ferramenta novamente informando-o.";
        }

        var cents = BigDecimal.valueOf(amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        var cleanDescription = cleanDescription(description);
        execute(new PersistTransactionInput(cleanDescription, cents, category));
        var message = "Transação registrada com sucesso: " + cleanDescription
                + " | categoria " + category
                + " | valor " + String.format("%.2f", cents / 100.0) + " reais";
        aiActionRecorder.record("✅ " + message);
        return message;
    }

    // Modelos de 1B costumam devolver a frase inteira ("gastei 12,50 reais na farmácia") como descrição.
    private static final Pattern LEADING_VERB_AND_AMOUNT = Pattern.compile(
            "(?i)^\\s*(?:(?:gastei|gostei|paguei|comprei)\\s+)?(?:r\\$\\s*)?\\d+(?:[.,]\\d+)?\\s*(?:reais|real)?\\s*(?:de|do|da|no|na|em|com)?\\s+");

    static String cleanDescription(String description) {
        var cleaned = LEADING_VERB_AND_AMOUNT.matcher(description).replaceFirst("").trim();
        return cleaned.isBlank() ? description.trim() : cleaned;
    }

    /** Versão usada pelo fluxo REST. */
    public TransactionOutput execute(PersistTransactionInput input) {
        var transaction = transactionRepository.save(
                new Transaction(input.description(), input.amount(), input.category()));

        return TransactionOutput.from(transaction);
    }
}
