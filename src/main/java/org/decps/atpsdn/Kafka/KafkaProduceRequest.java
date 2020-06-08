package org.decps.atpsdn.Kafka;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class KafkaProduceRequest implements KafkaMessage {
    public String transactional_id;
    public Integer acks;
    public Integer timeout;
    public List<TopicData> topic_data;

    @Override
    public void encode(ByteBuffer buffer) {

    }

    @Override
    public void decode(ByteBuffer buffer) {
        Integer transactional_id_length = (int)buffer.getShort();
        if(transactional_id_length != -1) {
            byte[] tid_buffer = new byte[transactional_id_length];
            buffer.get(tid_buffer);
            transactional_id = new String(tid_buffer);
        }
        acks = (int)buffer.getShort();
        timeout = buffer.getInt();

        Integer topic_data_length = buffer.getInt();
        if(topic_data_length > 0) topic_data = new ArrayList<>();
        for(int i=0;i<topic_data_length;i++) {
            TopicData topicData = new TopicData();
            topicData.decode(buffer);
            topic_data.add(topicData);
        }
    }

    @Override
    public void log() {
        System.out.println(String.format("transactional_id = %s, acks = %d, timeout = %d",transactional_id, acks, timeout));
        topic_data.forEach(d -> d.log());
    }

    public class Record implements  KafkaMessage{
        public Integer length;
        public Integer attributes;
        public Integer timestampDelta;
        public Integer offsetDelta;
        public Integer keyLength;
        public String key_s;
        public byte[] key;
        public Integer valueLength;
        public byte[] value;
        public String value_s;

        public Integer headersLength;

        @Override
        public void encode(ByteBuffer buffer) {

        }

        @Override
        public void decode(ByteBuffer buffer) {
            length = ByteUtils.readVarint(buffer);
            attributes = (int)buffer.get();
            timestampDelta = ByteUtils.readVarint(buffer);
            offsetDelta = ByteUtils.readVarint(buffer);
            keyLength = ByteUtils.readVarint(buffer);
            key = new byte[keyLength];
            buffer.get(key);
            key_s = new String(key);
            valueLength = ByteUtils.readVarint(buffer);
            value = new byte[valueLength];
            buffer.get(value);
            value_s = new String(value);
            headersLength = ByteUtils.readVarint(buffer);
        }

        @Override
        public void log() {
            System.out.println(String.format("length = %d, attributes = %d, timestampDelta = %d, offsetDelta = %d, " +
                            "key = %s, value = %s",
                    length, attributes, timestampDelta, offsetDelta, key_s, value_s  ));
        }
    }

    public class RecordBatch implements KafkaMessage{
        public Long baseOffset;
        public Integer batchLength;
        public Integer partitionLeaderEpoch;
        public Integer magic;
        public Integer crc;
        public Integer attributes;
        public Integer lastOffsetDelta;
        public Long firstTimestamp;
        public Long maxTimestamp;
        public Long producerId;
        public Integer producerEpoch;
        public Integer baseSequence;
        public List<Record> records;

        @Override
        public void encode(ByteBuffer buffer) {

        }

        @Override
        public void decode(ByteBuffer buffer) {
            baseOffset = buffer.getLong();
            batchLength = buffer.getInt();
            partitionLeaderEpoch = buffer.getInt();
            magic = (int)buffer.get();
            crc = buffer.getInt();
            attributes = (int)buffer.getShort();
            lastOffsetDelta = buffer.getInt();
            firstTimestamp = buffer.getLong();
            maxTimestamp = buffer.getLong();
            producerId = buffer.getLong();
            producerEpoch = (int)buffer.getShort();
            baseSequence = buffer.getInt();

            Integer total_records = buffer.getInt();
            if(total_records > 0) records = new ArrayList<>();
            for(int i =0; i<total_records;i++){
                Record r = new Record();
                r.decode(buffer);
                records.add(r);
            }
        }

        @Override
        public void log() {
            System.out.println(String.format("baseOffset = %d, batchLength = %d, partitionLeaderEpoch = %d, magic = %d, " +
                    "crc = %d, attributes = %d, lastOffsetDelta = %d, firstTimestamp = %d, maxTimestamp = %d, " +
                    "producerId = %d, producerEpoch = %d, baseSequence = %d",baseOffset, batchLength, partitionLeaderEpoch,magic,
                    crc, attributes, lastOffsetDelta, firstTimestamp, maxTimestamp, producerId, producerEpoch, baseSequence));

            records.forEach(r -> r.log());
        }
    }

    public class Data implements KafkaMessage{
        public Integer partition;
        public Integer batch_size;
        public RecordBatch record_set;

        @Override
        public void encode(ByteBuffer buffer) {

        }

        @Override
        public void decode(ByteBuffer buffer) {
            partition = buffer.getInt();
            batch_size = buffer.getInt();
            record_set = new RecordBatch();
            record_set.decode(buffer);
        }

        @Override
        public void log() {
            System.out.println(String.format("partition = %d, batch_size = %d", partition, batch_size));
            record_set.log();
        }
    }

    public class TopicData implements KafkaMessage{
        public String topic;
        List<Data> data;

        @Override
        public void encode(ByteBuffer buffer) {

        }

        @Override
        public void decode(ByteBuffer buffer) {
            Integer topic_len = (int)buffer.getShort();
            byte[] topic_bytes = new byte[topic_len];
            buffer.get(topic_bytes);
            topic = new String(topic_bytes);

            Integer total_data = buffer.getInt();
            if(total_data > 0) data = new ArrayList<>();
            for(int i=0;i<total_data;i++) {
                Data _data = new Data();
                _data.decode(buffer);
                data.add(_data);
            }
        }

        @Override
        public void log() {
            System.out.println(String.format("topic = %s",topic ));
            data.forEach(d -> d.log());
        }
    }

}
