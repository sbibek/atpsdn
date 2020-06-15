package org.decps.atpsdn.Kafka;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class KafkaUtils {
    public static Integer decodeLength(byte[] array) {
        ByteBuffer buffer = ByteBuffer.wrap(array).order(ByteOrder.BIG_ENDIAN);
        return buffer.getInt();
    }

}
