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

    }

    @Override
    public void decode(ByteBuffer buffer) {
        length = buffer.getInt();
        request_api_key =  new Short(buffer.getShort()).intValue();
        request_api_version =  new Short(buffer.getShort()).intValue();
        correlation_id = buffer.getInt();
        Integer client_id_length = new Short(buffer.getShort()).intValue();
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
