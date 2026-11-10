package com.codewalnut.bd.contacts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * A connection's photo (ADR-0014). Whatever is uploaded is decoded and re-encoded as a square JPEG of at most
 * {@value #SIDE} pixels, so only pixels are kept: no metadata, no other content.
 */
@Service
class ConnectionPhotos {

    static final int SIDE = 256;
    static final long MAX_UPLOAD = 2L * 1024 * 1024;

    record Photo(byte[] bytes, String contentType, long version) {}

    private final JdbcClient jdbc;
    private final AuditLog audit;

    ConnectionPhotos(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    Optional<Photo> get(long connectionId) {
        return jdbc.sql("SELECT bytes, content_type, updated_at FROM connection_photo WHERE connection_id = :id")
            .param("id", connectionId)
            .query((rs, n) -> new Photo(rs.getBytes(1), rs.getString(2), rs.getTimestamp(3).getTime()))
            .optional();
    }

    @Transactional
    void set(long connectionId, byte[] upload, CurrentUser user) {
        exists(connectionId);
        byte[] jpeg = normalise(upload);
        jdbc.sql("""
                INSERT INTO connection_photo (connection_id, content_type, bytes, updated_by, updated_at)
                VALUES (:id, 'image/jpeg', :bytes, :user, CURRENT_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE bytes = VALUES(bytes), content_type = VALUES(content_type),
                    updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)
                """).param("id", connectionId).param("bytes", jpeg).param("user", user.id()).update();
        audit.record("connection", connectionId, AuditLog.Action.UPDATE, Map.of("photo", "set"), user.id());
    }

    @Transactional
    void remove(long connectionId, CurrentUser user) {
        exists(connectionId);
        int removed = jdbc.sql("DELETE FROM connection_photo WHERE connection_id = :id").param("id", connectionId).update();
        if (removed > 0) {
            audit.record("connection", connectionId, AuditLog.Action.UPDATE, Map.of("photo", "removed"), user.id());
        }
    }

    /** Decodes the image, crops the centre square, scales it to at most SIDE and writes a JPEG. */
    static byte[] normalise(byte[] upload) {
        if (upload == null || upload.length == 0) {
            throw new RuleViolationException(List.of("Choose a photo."));
        }
        if (upload.length > MAX_UPLOAD) {
            throw new RuleViolationException(List.of("The photo must be 2 MB or less."));
        }
        BufferedImage in;
        try {
            in = ImageIO.read(new ByteArrayInputStream(upload));
        } catch (IOException | RuntimeException e) {
            in = null;
        }
        if (in == null || in.getWidth() < 16 || in.getHeight() < 16) {
            throw new RuleViolationException(List.of("Use a JPEG or PNG photo of at least 16 by 16 pixels."));
        }
        int side = Math.min(in.getWidth(), in.getHeight());
        int out = Math.min(side, SIDE);
        BufferedImage square = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = square.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, out, out);
            int x = (in.getWidth() - side) / 2;
            int y = (in.getHeight() - side) / 2;
            g.drawImage(in, 0, 0, out, out, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.88f);
            writer.write(null, new IIOImage(square, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode the photo", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private void exists(long id) {
        if (jdbc.sql("SELECT COUNT(*) FROM connection WHERE id = :id").param("id", id).query(Integer.class).single() == 0) {
            throw new NotFoundException("Connection", id);
        }
    }
}
