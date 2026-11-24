package ph.edu.lendly;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Clock;
import java.time.ZoneId;

import javax.swing.SwingUtilities;

@SpringBootApplication
@EnableScheduling
public class DesktopApplication {
    public static void main(String[] args) {
        var context =
                new SpringApplicationBuilder(DesktopApplication.class)
                        .headless(false)
                        .web(WebApplicationType.NONE)
                        .run(args);
        SwingUtilities.invokeLater(
                () -> {
                    try {
                        DesktopUi.install();
                        var window =
                                new DesktopWindow(
                                        context.getBean(AccountService.class),
                                                context.getBean(LoanService.class),
                                        context.getBean(ReceiptService.class),
                                                context.getBean(CircleService.class),
                                        context.getBean(NotificationService.class),
                                                context.getBean(Clock.class));
                        window.addWindowListener(
                                new WindowAdapter() {
                                    @Override
                                    public void windowClosed(WindowEvent event) {
                                        context.close();
                                    }
                                });
                        window.setVisible(true);
                    } catch (RuntimeException ex) {
                        context.close();
                        throw ex;
                    }
                });
    }

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Manila"));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
