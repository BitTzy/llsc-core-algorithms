package ph.edu.lendly;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;

public final class Models {
    private Models() {}

    public record Account(long id, String name, String email) {}

    public record Terms(
            int version,
            String kind,
            BigDecimal amount,
            String currency,
            String itemDescription,
            LocalDate startDate,
            LocalDate dueDate,
            long proposedBy) {}

    public record Loan(
            long id,
            long lenderId,
            long borrowerId,
            long initiatorId,
            String status,
            int currentVersion,
            String lenderName,
            String borrowerName,
            Terms terms,
            BigDecimal balance,
            LocalDate completedOn) {}

    public record Activity(
            long id,
            long loanId,
            int version,
            String kind,
            long submittedBy,
            String submitterName,
            LocalDate actualDate,
            BigDecimal amount,
            String note,
            String status,
            String reviewReason,
            Long receiptId,
            OffsetDateTime createdAt) {}

    public record Confirmation(long userId, String name, int version, OffsetDateTime confirmedAt) {}

    public record Notice(
            long id, Long loanId, String message, OffsetDateTime createdAt, boolean read) {}

    public record Circle(
            long id,
            String name,
            long organiserId,
            String organiserName,
            String currency,
            BigDecimal balance,
            BigDecimal targetAmount,
            BigDecimal contributionAmount,
            String frequency,
            LocalDate startDate,
            String status) {

        public static final java.util.List<String> FREQUENCIES =
                java.util.List.of("WEEKLY", "BIWEEKLY", "MONTHLY");

        /** Share of the savings target reached by the recorded balance, from 0 upwards. */
        public double progressPercentage() {
            if (targetAmount == null || targetAmount.signum() <= 0) return 0;
            return balance.multiply(BigDecimal.valueOf(100))
                    .divide(targetAmount, 1, java.math.RoundingMode.DOWN)
                    .doubleValue();
        }

        public boolean goalReached() {
            return targetAmount != null && balance.compareTo(targetAmount) >= 0;
        }

        public boolean closed() {
            return "CLOSED".equals(status);
        }

        /** The n-th scheduled contribution date (n = 0 is the start date). */
        public static LocalDate scheduledDate(LocalDate start, String frequency, int n) {
            return switch (frequency) {
                case "WEEKLY" -> start.plusWeeks(n);
                case "BIWEEKLY" -> start.plusWeeks(2L * n);
                default -> start.plusMonths(n);
            };
        }

        /** Index of the first scheduled date that is today or later. */
        public static int nextIndex(LocalDate start, String frequency, LocalDate today) {
            int n = 0;
            while (scheduledDate(start, frequency, n).isBefore(today)) n++;
            return n;
        }

        public LocalDate nextContributionDue(LocalDate today) {
            if (startDate == null || frequency == null) return null;
            return scheduledDate(startDate, frequency, nextIndex(startDate, frequency, today));
        }

        public String scheduleLabel() {
            if (contributionAmount == null || frequency == null) return "No schedule set";
            return currency
                    + " "
                    + contributionAmount.toPlainString()
                    + " "
                    + frequency.toLowerCase(Locale.ROOT).replace("biweekly", "every two weeks");
        }
    }

    public record Member(long id, String name, String email, LocalDate joinedOn, String status) {}

    public record Invitation(long id, long circleId, String circleName, String organiserName) {}

    public record Entry(
            long id,
            String kind,
            BigDecimal amount,
            String memberName,
            LocalDate actualDate,
            String note,
            OffsetDateTime createdAt,
            String status,
            Long submittedBy,
            String reviewReason) {}

    public record Evidence(String contentType, byte[] bytes) {}
}
