package ph.edu.lendly;

import ph.edu.lendly.Models.Loan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;

final class CalendarReminder {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Path INSTALLATION = Path.of("data", "lendly").toAbsolutePath().normalize();

    private CalendarReminder() {}

    static List<Loan> upcoming(List<Loan> records, long actor, LocalDate today) {
        return records.stream()
                .filter(loan -> loan.lenderId() == actor || loan.borrowerId() == actor)
                .filter(loan -> "ACTIVE".equals(loan.status()) && !loan.terms().dueDate().isBefore(today))
                .sorted(Comparator.comparing(loan -> loan.terms().dueDate())).toList();
    }

    static void save(Path file, List<Loan> loans, Instant exportedAt, boolean replace) throws IOException {
        var content = new StringJoiner("\r\n", "", "\r\n");
        content.add("BEGIN:VCALENDAR").add("VERSION:2.0").add("PRODID:-//Lendly//Loan reminders//EN");
        for (var loan : loans) {
            var key = INSTALLATION + ":" + loan.id();
            var uid = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            content.add("BEGIN:VEVENT")
                    .add("UID:" + uid + "@lendly.local")
                    .add("DTSTAMP:" + TIMESTAMP.format(exportedAt))
                    .add("DTSTART;VALUE=DATE:" + DateTimeFormatter.BASIC_ISO_DATE.format(loan.terms().dueDate()))
                    .add("SUMMARY:Lendly loan due")
                    .add("CLASS:PRIVATE")
                    .add("TRANSP:TRANSPARENT")
                    .add("BEGIN:VALARM")
                    .add("ACTION:DISPLAY")
                    .add("TRIGGER:-P1D")
                    .add("DESCRIPTION:Check Lendly for the return or repayment deadline.")
                    .add("END:VALARM")
                    .add("END:VEVENT");
        }
        content.add("END:VCALENDAR");

        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && (!replace || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
            throw new FileAlreadyExistsException(file.toString());
        }
        var temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "lendly-", ".tmp");
        try {
            Files.writeString(temporary, content.toString());
            if (replace) Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
