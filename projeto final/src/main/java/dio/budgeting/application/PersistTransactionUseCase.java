package dio.budgeting.application;

import dio.budgeting.application.input.PersistTransactionInput;
import dio.budgeting.application.output.TransactionOutput;
import dio.budgeting.domain.Category;
import dio.budgeting.domain.Transaction;
import dio.budgeting.domain.TransactionRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class PersistTransactionUseCase {
    private final TransactionRepository transactionRepository;

    public PersistTransactionUseCase(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Versão exposta à IA. Decisões pensadas para modelos locais pequenos:
     * - Parâmetros simples (em vez de objeto aninhado): schemas "achatados" têm
     *   muito mais acerto;
     * - Validação com mensagem em texto: em vez de estourar exceção, o erro volta
     *   para o modelo, que pode corrigir os parâmetros e chamar de novo;
     * - Retorno em linguagem natural (valor em reais): facilita a resposta final.
     */
    @Tool(name = "persist-transaction", description = "Persiste uma nova transação financeira")
    public String execute(@ToolParam(description = "Descrição do gasto") String description,
                          @ToolParam(description = "Valor do gasto (em centavos)") Long amount,
                          @ToolParam(description = "Categoria de uma transação") Category category) {
        if (description == null || description.isBlank()) {
            return "ERRO: o parâmetro 'description' é obrigatório. Chame a ferramenta novamente informando a descrição do gasto.";
        }
        if (amount == null || amount <= 0) {
            return "ERRO: o parâmetro 'amount' (valor em centavos) é obrigatório e maior que zero. Chame a ferramenta novamente informando-o (ex.: 80 reais = 8000).";
        }
        if (category == null) {
            return "ERRO: o parâmetro 'category' é obrigatório (GROCERIES, PHARMA ou AUTO). Chame a ferramenta novamente informando-o.";
        }

        execute(new PersistTransactionInput(description, amount, category));
        return "Transação registrada com sucesso: " + description
                + " | categoria " + category
                + " | valor " + String.format("%.2f", amount / 100.0) + " reais";
    }

    /** Versão usada pelo fluxo REST. */
    public TransactionOutput execute(PersistTransactionInput input) {
        var transaction = transactionRepository.save(
                new Transaction(input.description(), input.amount(), input.category()));

        return TransactionOutput.from(transaction);
    }
}
