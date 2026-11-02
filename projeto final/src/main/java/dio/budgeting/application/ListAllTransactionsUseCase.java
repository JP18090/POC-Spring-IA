package dio.budgeting.application;

import dio.budgeting.application.output.TransactionOutput;
import dio.budgeting.domain.Transaction;
import dio.budgeting.domain.TransactionRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class ListAllTransactionsUseCase {
    private final TransactionRepository transactionRepository;
    private final AiActionRecorder aiActionRecorder;

    public ListAllTransactionsUseCase(TransactionRepository transactionRepository,
                                      AiActionRecorder aiActionRecorder) {
        this.transactionRepository = transactionRepository;
        this.aiActionRecorder = aiActionRecorder;
    }

    /**
     * Versão exposta à IA: devolve texto amigável com valores em REAIS,
     * para o modelo não precisar converter centavos (erro comum em LLMs pequenos).
     */
    @Tool(name = "list-all-transactions", description = "Lista todas as transações financeiras registradas")
    public List<String> execute() {
        var transactions = transactionRepository.findAll();
        if (transactions.isEmpty()) {
            aiActionRecorder.record("📭 Nenhuma transação registrada até o momento.");
            return List.of("Nenhuma transação registrada até o momento.");
        }
        var items = transactions.stream().map(ListAllTransactionsUseCase::toFriendlyText).toList();
        aiActionRecorder.record("📋 Suas transações (" + items.size() + "):\n" + String.join("\n", items));
        return items;
    }

    /** Versão estruturada (JSON), usada pelo fluxo REST. */
    public List<TransactionOutput> executeStructured() {
        return transactionRepository.findAll().stream().map(TransactionOutput::from).toList();
    }

    static String toFriendlyText(Transaction transaction) {
        var reais = BigDecimal.valueOf(transaction.getAmount())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return transaction.getDescription() + " | categoria " + transaction.getCategory() + " | R$ " + reais;
    }
}
