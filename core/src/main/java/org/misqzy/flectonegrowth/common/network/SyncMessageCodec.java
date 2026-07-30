package org.misqzy.flectonegrowth.common.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Binary (de)serialisation for {@link SyncMessage}, deliberately written
 * with plain {@link DataOutputStream}/{@link DataInputStream} instead of a
 * Bukkit {@code ByteArrayDataOutput} so both the Bukkit module and the
 * proxy module (Velocity) could decode the exact same bytes off their
 * respective plugin-messaging channel without any Bukkit dependency on the
 * proxy side, even though Velocity chooses not to (it relays the raw bytes
 * unmodified instead - see {@code docs/ARCHITECTURE.md}).
 */
public final class SyncMessageCodec {

    /** Plugin messaging channel shared by every module. */
    public static final String CHANNEL = "flectonegrowth:sync";

    private SyncMessageCodec() {}

    public static byte[] encode(SyncMessage message) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            out.writeUTF(message.type().name());
            out.writeLong(message.playerUuid().getMostSignificantBits());
            out.writeLong(message.playerUuid().getLeastSignificantBits());
            out.writeUTF(message.stringValue() != null ? message.stringValue() : "");
            out.writeDouble(message.doubleValue());
            out.writeUTF(message.sourceServerId() != null ? message.sourceServerId() : "");
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode SyncMessage", e);
        }
    }

    public static SyncMessage decode(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            SyncMessage.Type type = SyncMessage.Type.valueOf(in.readUTF());
            long msb = in.readLong();
            long lsb = in.readLong();
            String stringValue = in.readUTF();
            double doubleValue = in.readDouble();
            String sourceServerId = in.readUTF();
            return SyncMessage.raw(type, new UUID(msb, lsb),
                    stringValue.isEmpty() ? null : stringValue, doubleValue, sourceServerId);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to decode SyncMessage", e);
        }
    }
}
