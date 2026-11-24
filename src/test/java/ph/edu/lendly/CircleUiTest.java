package ph.edu.lendly;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;

import javax.imageio.ImageIO;
import javax.swing.*;

class CircleUiTest {
    @Test
    void circleFormsRenderForLeaderMemberAndFormerMember() throws Exception {
        try (var context =
                new SpringApplicationBuilder(DesktopApplication.class)
                        .headless(true)
                        .run("--spring.datasource.url=jdbc:h2:mem:circle-ui;DB_CLOSE_DELAY=-1")) {
            var accounts = context.getBean(AccountService.class);
            var circles = context.getBean(CircleService.class);
            var leader =
                    accounts.register(
                            "Ana Cruz",
                            "ana-ui@example.test",
                            "ClassroomTest123",
                            "ClassroomTest123");
            var member =
                    accounts.register(
                            "Ben Reyes",
                            "ben-ui@example.test",
                            "ClassroomTest123",
                            "ClassroomTest123");
            String today = LocalDate.now(context.getBean(Clock.class)).toString();
            long id =
                    circles.create(
                            leader.id(),
                            "Project materials fund",
                            "PHP",
                            "5000",
                            "500",
                            "WEEKLY",
                            today);
            circles.addMember(leader.id(), id, member.email());
            circles.respondInvitation(
                    member.id(), circles.invitations(member.id()).getFirst().id(), true);
            circles.addEntry(
                    leader.id(),
                    id,
                    "CONTRIBUTION",
                    "500",
                    leader.id(),
                    today,
                    "Project contribution");
            circles.addEntry(
                    member.id(), id, "CONTRIBUTION", "500", member.id(), today, "For review");
            for (var actor : java.util.List.of(leader, member)) {
                SwingUtilities.invokeAndWait(
                        () -> {
                            try {
                                DesktopUi.install();
                                var panel =
                                        new DesktopCircles(
                                                actor,
                                                circles,
                                                context.getBean(Clock.class),
                                                () -> {},
                                                who -> "Not yet rated");
                                var select =
                                        DesktopCircles.class.getDeclaredMethod(
                                                "select", long.class);
                                select.setAccessible(true);
                                select.invoke(panel, id);
                                panel.setSize(1400, 850);
                                layout(panel);
                                var image =
                                        new BufferedImage(1400, 850, BufferedImage.TYPE_INT_RGB);
                                var graphics = image.createGraphics();
                                panel.printAll(graphics);
                                graphics.dispose();
                                Files.createDirectories(Path.of("tmp/ui"));
                                ImageIO.write(
                                        image,
                                        "png",
                                        Path.of(
                                                        "tmp/ui",
                                                        actor.id() == leader.id()
                                                                ? "circle-leader.png"
                                                                : "circle-member.png")
                                                .toFile());
                                assertTrue(panel.isVisible());
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        });
            }
            var pending =
                    circles.entries(leader.id(), id).stream()
                            .filter(e -> e.status().equals("PENDING_REVIEW"))
                            .findFirst()
                            .orElseThrow();
            circles.reviewEntry(leader.id(), id, pending.id(), true, "");
            circles.leave(member.id(), id);
            SwingUtilities.invokeAndWait(
                    () ->
                            new DesktopCircles(
                                            member,
                                            circles,
                                            context.getBean(Clock.class),
                                            () -> {},
                                            who -> "Not yet rated")
                                    .refresh());
        }
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) if (child instanceof Container cc) layout(cc);
    }
}
