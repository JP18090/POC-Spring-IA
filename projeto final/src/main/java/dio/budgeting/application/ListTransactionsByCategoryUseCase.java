package dio.budgeting.application;

import dio.budgeting.application.output.TransactionOutput;
import dio.budgeting.domain.Category;
import dio.budgeting.domain.TransactionRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ListTransactionsByCategoryUseCase {
    private final TransactionRepository transactionRepository;
    private final AiActionRecorder aiActionRecorder;

    public ListTransactionsByCategoryUseCase(TransactionRepository transactionRepository,
                                             AiActionRecorder aiActionRecorder) {
        this.transactionRepository = transactionRepository;
        this.aiActionRecorder = aiActionRecorder;
    }

    /**
     * Versão exposta à IA: texto amigável com valores em REAIS
     * (mesmo padrão do ListAllTransactionsUseCase).
     */
    @Tool(name = "list-transactions-by-category", description = "Lista transações financeiras por categoria")
    public List<String> execute(@ToolParam(description = "Categoria de uma transação") Category category) {
        var transactions = transactionRepository.findAllByCategory(category);
        if (transactions.isEmpty()) {
            aiActionRecorder.record("📭 Nenhuma transação na categoria " + category + ".");
            return List.of("Nenhuma transação na categoria " + category + ".");
        }
        var items = transactions.stream().map(ListAllTransactionsUseCase::toFriendlyText).toList();
        aiActionRecorder.record("📋 Transações em " + category + " (" + items.size() + "):\n" + String.join("\n", items));
        return items;
    }

    /** Versão estruturada (JSON), usada pelo fluxo REST. */
    public List<TransactionOutput> executeStructured(Category category) {
        return transactionRepository.findAllByCategory(category).stream().map(TransactionOutput::from).toList();
    }
}
