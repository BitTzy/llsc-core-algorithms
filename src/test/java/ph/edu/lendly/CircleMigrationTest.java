package ph.edu.lendly;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CircleMigrationTest {
    @Test
    void legacyRecordsRemainApprovedAndMigrationCanRunTwice() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:legacy-circle;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update(
                "INSERT INTO app_user(name,email,password_hash) VALUES('Old"
                    + " user','legacy@example.test','test')");
        jdbc.update(
                "INSERT INTO savings_circle(name,organiser_id,currency) VALUES('Existing"
                    + " circle',1,'PHP')");
        jdbc.update("INSERT INTO circle_member(circle_id,user_id) VALUES(1,1)");
        jdbc.update(
                "INSERT INTO savings_entry(circle_id,kind,amount,member_id,actual_date,note)"
                    + " VALUES(1,'CONTRIBUTION',150,1,CURRENT_DATE,'Historic')");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        migration.execute(source);
        migration.execute(source);
        assertEquals(
                "ACTIVE", jdbc.queryForObject("SELECT status FROM circle_member", String.class));
        assertEquals(
                "APPROVED", jdbc.queryForObject("SELECT status FROM savings_entry", String.class));
        assertEquals(150, jdbc.queryForObject("SELECT amount FROM savings_entry", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM savings_entry", Integer.class));
    }
}
