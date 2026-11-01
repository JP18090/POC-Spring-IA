package dio.budgeting.application;

import dio.budgeting.application.output.TransactionOutput;
import dio.budgeting.domain.TransactionRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ListAllTransactionsUseCase {
    private final TransactionRepository transactionRepository;

    public ListAllTransactionsUseCase(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Tool(name = "list-all-transactions", description = "Lista todas as transações financeiras registradas")
    public List<TransactionOutput> execute() {
        return transactionRepository.findAll().stream().map(TransactionOutput::from).toList();
    }
}
