package ph.edu.lendly;

import java.util.List;
import ph.edu.lendly.Models.Loan;

public final class LoanReliability {
    private LoanReliability() {}

    public static String trust(List<Loan> records, long borrowerId) {
        long completed = 0;
        long onTime = 0;
        for (var record : records) {
            if (record.borrowerId() != borrowerId || !"COMPLETED".equals(record.status())) continue;
            completed++;
            if (!record.completedOn().isAfter(record.terms().dueDate())) onTime++;
        }
        return completed == 0 ? "Not yet rated" : Math.round(100.0 * onTime / completed) + "%";
    }
}
