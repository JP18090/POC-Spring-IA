package dio.budgeting.application;

import dio.budgeting.domain.Category;
import dio.budgeting.domain.Transaction;
import dio.budgeting.domain.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersistTransactionUseCaseTest {

    static class InMemoryRepository implements TransactionRepository {
        final List<Transaction> saved = new ArrayList<>();

        public Transaction save(Transaction transaction) {
            saved.add(transaction);
            return transaction;
        }

        public List<Transaction> findAll() {
            return saved;
        }

        public List<Transaction> findAllByCategory(Category category) {
            return saved.stream().filter(t -> t.getCategory() == category).toList();
        }
    }

    private final InMemoryRepository repository = new InMemoryRepository();
    private final AiActionRecorder recorder = new AiActionRecorder();
    private final PersistTransactionUseCase useCase = new PersistTransactionUseCase(repository, recorder);

    @Test
    void should_convertReaisToCents_when_toolIsCalled() {
        useCase.execute("café", 12.5, Category.GROCERIES);

        assertThat(repository.saved).singleElement().satisfies(t -> assertThat(t.getAmount()).isEqualTo(1250));
    }

    @Test
    void should_recordAction_when_transactionIsSaved() {
        useCase.execute("mercado", 80.0, Category.GROCERIES);

        assertThat(recorder.consume()).contains("mercado").contains("80.00");
    }

    @Test
    void should_returnErrorAndSaveNothing_when_amountIsMissing() {
        var result = useCase.execute("mercado", null, Category.GROCERIES);

        assertThat(result).startsWith("ERRO:");
        assertThat(repository.saved).isEmpty();
        assertThat(recorder.consume()).isNull();
    }

    @Test
    void should_stripVerbAndAmount_when_descriptionIsFullSentence() {
        assertThat(PersistTransactionUseCase.cleanDescription("gastei 12,50 reais na farmácia")).isEqualTo("farmácia");
        assertThat(PersistTransactionUseCase.cleanDescription("paguei 45 reais de remédio na farmácia")).isEqualTo("remédio na farmácia");
        assertThat(PersistTransactionUseCase.cleanDescription("Gostei R$30 no posto de gasolina")).isEqualTo("posto de gasolina");
    }

    @Test
    void should_keepDescription_when_itHasNoVerbOrAmountPrefix() {
        assertThat(PersistTransactionUseCase.cleanDescription("café dentro do mercado")).isEqualTo("café dentro do mercado");
    }
}
