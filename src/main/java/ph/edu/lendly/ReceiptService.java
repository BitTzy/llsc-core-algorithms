package ph.edu.lendly;

import static ph.edu.lendly.Models.*;
import static ph.edu.lendly.Rules.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

@Service
public class ReceiptService {
    private final JdbcTemplate jdbc;

    public ReceiptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Evidence validate(Path file) {
        if (file == null) return null;
        byte[] bytes;
        int limit = 2 * 1024 * 1024;
        try (var source = Files.newInputStream(file)) {
            bytes = source.readNBytes(limit + 1);
        } catch (IOException ex) {
            throw new InvalidInput("The attachment could not be read.");
        }
        require(bytes.length > 0, "Choose a PNG or JPEG image with content.");
        require(bytes.length <= limit, "Receipt images must be 2 MB or smaller.");
        try (var input =
                new javax.imageio.stream.MemoryCacheImageInputStream(
                        new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            require(readers.hasNext(), "Upload a valid PNG or JPEG image.");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                require(
                        format.equals("png") || format.equals("jpeg"),
                        "Upload a PNG or JPEG receipt.");
                reader.setInput(input, true, true);
                require(
                        (long) reader.getWidth(0) * reader.getHeight(0) <= 12_000_000,
                        "Receipt images must be no larger than 12 megapixels.");
                var output = new ByteArrayOutputStream();
                ImageIO.write(reader.read(0), format, output);
                require(
                        output.size() <= limit,
                        "The processed image is too large. Resize it and try again.");
                return new Evidence("image/" + format, output.toByteArray());
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new InvalidInput("The image could not be read. Upload a PNG or JPEG.");
        }
    }

    public void attach(long activity, Evidence evidence) {
        if (evidence != null)
            jdbc.update(
                    "INSERT INTO receipt(activity_id,content_type,content) VALUES(?,?,?)",
                    activity,
                    evidence.contentType(),
                    evidence.bytes());
    }

    public Evidence get(long actor, long id) {
        var rows =
                jdbc.query(
                        """
SELECT r.content_type,r.content FROM receipt r JOIN loan_activity a ON a.id=r.activity_id
JOIN loan l ON l.id=a.loan_id WHERE r.id=? AND (l.lender_id=? OR l.borrower_id=?)
""",
                        (r, n) -> new Evidence(r.getString(1), r.getBytes(2)),
                        id,
                        actor,
                        actor);
        if (rows.isEmpty()) throw new NotFound();
        return rows.getFirst();
    }
}
