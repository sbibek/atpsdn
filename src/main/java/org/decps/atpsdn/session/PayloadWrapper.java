package org.decps.atpsdn.session;

import org.decps.atpsdn.Kafka.KafkaHeader;
import org.decps.atpsdn.Kafka.KafkaUtils;
import org.decps.atpsdn.Utils;
import org.onlab.packet.IPv4;
import org.onlab.packet.TCP;
import org.onosproject.net.packet.PacketContext;

public class PayloadWrapper {

    // packet payload types
    public static Integer PAYLOAD_TYPE_METADATA = 0;
    public static Integer PAYLOAD_TYPE_PRODUCE = 1;
    public static Integer PAYLOAD_TYPE_UNDEF = -1;

    public Integer totalMessageCount = 0;

    // suppose there is a merge of packets
    // then there might be  bytes that are excess in current context
    // and will be full when we see unseen number of bytes in the next packet
    public byte[] excessBytes;
    public Integer totalUnseenBytes = 0;


    public Integer payloadType = PAYLOAD_TYPE_UNDEF;


    //    public void process(Boolean isDataConnectionResolved, byte[] payload){}
    public void process(Boolean isDataConnectionResolved, byte[] payload) {
        //         IPv4 ip = (IPv4) context.inPacket().parsed().getPayload();
        //        TCP tcp = (TCP) ip.getPayload();
        //        byte[] payload = tcp.getPayload().serialize();


        if (payload.length < 4) {
            throw new RuntimeException("Packet with less than 4 bytes!!!");
        }

        while (true) {
            // first thing to check is if we have enough data to unpack just length
            if (payload.length < 4) {
                // this means that we have small payload remaining and cant even unpack the length field
                excessBytes = payload;
                totalUnseenBytes = -1; // because in this case we dont know how much data is there for this message
                //System.out.println("partial data found, also couldn't determine the total length");
                break;
            }

            // first lets get the length of the first message
            Integer length = KafkaUtils.decodeLength(payload);
            // + 4 because the 4 bytes is what we decoded as header length
            if (payload.length >= length + 4) {
                // this means we have payload for this message complete
                // so lets just extract the header for the information on if whether this is data connection
                KafkaHeader header = new KafkaHeader();
                header.decodeHeaderOnly(payload);
                //header.log();

                if(!isDataConnectionResolved) {
                    if (header.request_api_key == 0) {
                        // key 0 is the produce request
                        payloadType = PAYLOAD_TYPE_PRODUCE;
                    } else if (header.request_api_key == 3) {
                        // key 3 is the metadata request
                        payloadType = PAYLOAD_TYPE_METADATA;
                    } else {
                        // we are not sure its type
                        payloadType = PAYLOAD_TYPE_UNDEF;
                    }
                }


                payload = trim(payload, length + 4);
                //System.out.println("trimmed payload length "+payload.length);
                if(header.request_api_key == 0)
                    ++totalMessageCount;

                // break if all the payload has been processed
                if (payload.length == 0) {
                    excessBytes = null;
                    totalUnseenBytes = 0;
                    break;
                }
                else continue;
            } else {
                // this means we dont have complete payload for this message
                // and in this case we know the amount of total unseen bytes
                excessBytes = payload;
                totalUnseenBytes = length - (payload.length - 4);
                //System.out.println(String.format(String.format("partial data found, length:%d total unseen bytes:%d ", length, totalUnseenBytes)));
                break;
            }

        }

        // now we have decoded
    }


    public byte[] getBytesRepresentingSomeMessages(byte[] payload, Integer totalMessages) {
        byte[] temp = Utils.createCopy(payload);
        // one thing we are sure about here is that the payload is guaranteed to have the totalMessages in it
        // so we can unpack without caring to run out of buffers until we hit the totalMessages count
        Integer index = 0;
        Integer totalMessagesProcessed = 0;

        while(totalMessagesProcessed < totalMessages) {
            Integer length = KafkaUtils.decodeLength(payload);
            index += length + 4;
            totalMessagesProcessed++;
            payload = trim(payload, length + 4);
        }

        return Utils.getSpecifiedBytes(temp, index);
    }

    public byte[] trim(byte[] data, Integer totalBytes) {
        byte[] rem = new byte[data.length - totalBytes];
        Integer t = 0;
        for (int i = totalBytes; i < data.length; i++) {
            rem[i - totalBytes] = data[i];
            t++;
        }
        return rem;
    }

    public void log() {
        //System.out.println(String.format("totalMessages=%d excessBytes=%d totalUnseenBytes=%d", totalMessageCount, excessBytes == null ? 0 : excessBytes.length, totalUnseenBytes));
    }
}
