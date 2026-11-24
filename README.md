# Lending Ledger and Saving Circle

Core Java algorithms from the updated desktop classroom project. This source extract is for review in the appendix. It requires the original application configuration to run as an app.

| Area | Start here |
| --- | --- |
| Loan terms, handovers, repayments and returns | [LoanService.java](src/main/java/ph/edu/lendly/LoanService.java) |
| Saving circle invitations, contribution review and balances | [CircleService.java](src/main/java/ph/edu/lendly/CircleService.java) |
| Return reliability calculation | [LoanReliability.java](src/main/java/ph/edu/lendly/LoanReliability.java) |
| Loan and contribution reminders | [NotificationService.java](src/main/java/ph/edu/lendly/NotificationService.java) |
| Account rules and password verification | [AccountService.java](src/main/java/ph/edu/lendly/AccountService.java) |
| Receipt validation and participant access | [ReceiptService.java](src/main/java/ph/edu/lendly/ReceiptService.java) |
| Storage and shared rules | [schema.sql](src/main/resources/schema.sql), [Database.java](src/main/java/ph/edu/lendly/Database.java), [Models.java](src/main/java/ph/edu/lendly/Models.java), [Rules.java](src/main/java/ph/edu/lendly/Rules.java) |

The desktop app keeps a record of exchanges that happen outside the system. The reliability calculation originally lived in a desktop UI helper. Its calculation is shown here in a separate Java class so it can be read alongside the service code.
