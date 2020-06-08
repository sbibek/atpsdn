package org.decps.atpsdn.Kafka;

import java.nio.ByteBuffer;

public interface KafkaMessage {
    public void encode(ByteBuffer buffer);
    public void decode(ByteBuffer buffer);
    public void log();
}
