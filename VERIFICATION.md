# Verification for the circle update

19 automated tests passed, including legacy-database migration, repeated migration, consent before membership, decline/reinvite, approval/rejection, no balance change for pending/rejected/withdrawn entries, date preservation, actor restrictions, withdrawal, leaving and retained history. Existing loan, validation and calendar tests also passed.

A headless Swing render test constructed the organiser, member and former-member circle panels. The organiser and member views were rendered and visually checked.

The existing full display-dependent DesktopTabsTest was skipped because no display server was available. The invitation/review dialogs were not exercised with real mouse/keyboard interaction here.

Sources were compiled for Java 21 with Eclipse ECJ using the packaged application's dependency JARs, then tested with JUnit Platform Console. The executable Spring Boot JAR was rebuilt with those compiled classes and updated resources. The project's normal Maven commands remain unchanged for a Java 21 JDK environment:

```sh
mvn test
mvn package
java -jar target/lending-ledger-desktop.jar
```

The first source build requires Maven Central. Running the packaged JAR only needs Java 21. No production/personal database was modified; tests used isolated H2 databases and demonstration accounts.
