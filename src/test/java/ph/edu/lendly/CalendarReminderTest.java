package ph.edu.lendly;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ph.edu.lendly.Models.Loan;
import ph.edu.lendly.Models.Terms;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

class CalendarReminderTest {
    @TempDir Path folder;

    @Test
    void selectsOnlyCurrentAndFutureActiveLoansVisibleToTheAccount() {
        var today = LocalDate.of(2026, 12, 2);
        var dueToday = loan(1, 1, "ACTIVE", today);
        var future = loan(2, 1, "ACTIVE", today.plusDays(7));
        var completed = loan(3, 1, "COMPLETED", today);
        var overdue = loan(4, 1, "ACTIVE", today.minusDays(1));
        var otherAccount = loan(5, 9, "ACTIVE", today);
        assertEquals(List.of(dueToday, future), CalendarReminder.upcoming(
                List.of(future, completed, overdue, otherAccount, dueToday), 1, today));
    }

    @Test
    void writesPrivateAllDayEventAndRequiresExplicitReplacement() throws Exception {
        var deadline = LocalDate.of(2026, 12, 2);
        var original = loan(1, 1, "ACTIVE", deadline);
        var timestamp = Instant.parse("2026-11-29T23:00:00Z");
        var destination = folder.resolve("reminders.ics");

        CalendarReminder.save(destination, List.of(original), timestamp, false);
        String file = Files.readString(destination);
        assertTrue(file.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"));
        assertTrue(file.contains("DTSTART;VALUE=DATE:20261202\r\n"));
        assertTrue(file.contains("DTSTAMP:20261129T230000Z\r\n"));
        assertTrue(file.contains("ACTION:DISPLAY\r\nTRIGGER:-P1D\r\n"));
        assertTrue(file.endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n"));
        assertFalse(file.contains("Test Lender"));
        assertFalse(file.contains("Test Borrower"));
        assertFalse(file.contains("Private book"));

        var updated = loan(1, 1, "ACTIVE", deadline.plusDays(1));
        assertThrows(FileAlreadyExistsException.class, () ->
                CalendarReminder.save(destination, List.of(updated), timestamp, false));
        assertEquals(file, Files.readString(destination));
        CalendarReminder.save(destination, List.of(updated), timestamp, true);
        String replaced = Files.readString(destination);
        assertTrue(replaced.contains("DTSTART;VALUE=DATE:20261203\r\n"));
        assertEquals(file.lines().filter(line -> line.startsWith("UID:")).toList(),
                replaced.lines().filter(line -> line.startsWith("UID:")).toList());
    }

    private Loan loan(long id, long lender, String status, LocalDate due) {
        var terms = new Terms(1, "ITEM", null, null, "Private book", due.minusDays(1), due, lender);
        return new Loan(id, lender, 2, lender, status, 1, "Test Lender", "Test Borrower", terms, null, null);
    }
}
