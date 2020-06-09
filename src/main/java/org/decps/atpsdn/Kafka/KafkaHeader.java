package org.decps.atpsdn.Kafka;

import java.nio.ByteBuffer;

public class KafkaHeader implements  KafkaMessage {
    public Integer length;
    public Integer request_api_key;
    public Integer request_api_version;
    public Integer correlation_id;
    public String client_id;

    public KafkaProduceRequest produceRequest;

    @Override
    public void encode(ByteBuffer buffer) {
        buffer.putInt(length);
        buffer.putShort(request_api_key.shortValue());
        buffer.putShort(request_api_version.shortValue());
        buffer.putInt(correlation_id);
        buffer.putShort((short)client_id.getBytes().length);
        buffer.put(client_id.getBytes());

        if(produceRequest != null) {
            produceRequest.encode(buffer);
        }
    }

    @Override
    public void decode(ByteBuffer buffer) {
        length = buffer.getInt();
        request_api_key =  (int)buffer.getShort();
        request_api_version =  (int)buffer.getShort();
        correlation_id = buffer.getInt();
        Integer client_id_length = (int)buffer.getShort();
        byte[] client_id_bytes = new byte[client_id_length];
        buffer.get(client_id_bytes);
        client_id = new String(client_id_bytes);

        produceRequest = new KafkaProduceRequest();
        produceRequest.decode(buffer);
    }

    public void log() {
        System.out.println(String.format("length = %d, request_api_key = %d, request_api_version = %d," +
                " correlation_id = %d, client_id = %s", length, request_api_key, request_api_version,correlation_id, client_id));
        produceRequest.log();
    }
}
