package org.decps.atpsdn;

import org.onlab.packet.IPv4;
import org.onlab.packet.TCP;
import org.onosproject.net.packet.PacketContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SessionQ {

    private final Logger log = LoggerFactory.getLogger(getClass());

    public String sessionKey;
    public Integer sender;
    public Integer receiver;
    public Integer senderPort;
    public Integer receiverPort;

    // last context that was seen from either direction
    public PacketContext s2r_context;
    public PacketContext r2s_context;
    public Integer s2r_seq, s2r_ack, r2s_seq, r2s_ack;

    // total incoming packet
    public Integer totalPacketReceived = 0;
    // total packets that we tried to send to destination
    public Integer totalPacketsSent = 0;
    // total packets that were acked by the receiver
    public Integer totalPacketsAcknowledged = 0;

    private Queue<Tuple> queue = new ConcurrentLinkedQueue<>();
    private Map<String, Tuple> inflight = new HashMap<>();

    public void log(String msg) {
        log.info(String.format("[QueuedSession] %s", msg));
    }

    public long getUnsignedInt(int x) {
        return x & 0x00000000ffffffffL;
    }

    public void queuePacket(PacketContext context) {
        // now we add the information to ack tracker
        TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();
        String acknowledgementTrack = String.format("%d-%d", getUnsignedInt(tcp.getAcknowledge()), getUnsignedInt(tcp.getSequence()) + tcp.getPayload().serialize().length);
        Tuple w = new Tuple(acknowledgementTrack, context);
        queue.add(w);
        ++totalPacketReceived;
        log(String.format("queueing packet %d", totalPacketReceived));
    }

    public Boolean acknowledge(PacketContext context) {
        TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();
        String acknowledgementTrack = String.format("%d-%d", getUnsignedInt(tcp.getSequence()), getUnsignedInt(tcp.getAcknowledge()));
        //
        if (inflight.containsKey(acknowledgementTrack)) {
            inflight.remove(acknowledgementTrack);
            ++totalPacketsAcknowledged;
        }
        return false;
    }

    public Boolean acknowledge(TCP tcp) {
        String acknowledgementTrack = String.format("%d-%d", getUnsignedInt(tcp.getSequence()), getUnsignedInt(tcp.getAcknowledge()));
        //
        if (inflight.containsKey(acknowledgementTrack)) {
            inflight.remove(acknowledgementTrack);
            ++totalPacketsAcknowledged;
        }

        System.out.println(String.format("Total inflight messages: %d", inflight.size()));
        return false;
    }

    public void acknowledge(){
        // just remove the top
        if(inflight.size() > 0) {
            String key = "";
            for(Map.Entry<String, Tuple> kv:inflight.entrySet()){
                key = kv.getKey();
            }

            // now lets remove it
            inflight.remove(key);
        }
    }

    public PacketTuple getQueuedPacket() {
        // only let the packet if there is no inflight
        Tuple t = queue.poll();
        if (inflight.size() == 0 && t != null) {
            // means there is packet
            // then put it on inflight map
            inflight.put(t.key, t);
            ++totalPacketsSent;
            log(String.format("total sent packets %d, current key: %s", totalPacketsSent, t.key));
            return new PacketTuple(t.context, totalPacketsSent>10+1);
        } else {
            return null;
        }
    }

    public Boolean isDirectionSenderToReceiver(IPv4 ip, TCP tcp) {
        // check if the current ip and tcp is the part of the packet from sender to receiver
        return ip.getSourceAddress() == sender && ip.getDestinationAddress() == receiver && tcp.getSourcePort() == senderPort && tcp.getDestinationPort() == receiverPort;
    }

    private class Tuple {
        public String key;
        public PacketContext context;

        public Tuple(String key, PacketContext context) {
            this.key = key;
            this.context = context;
        }
    }

    public class PacketTuple {
        public PacketContext context;
        public Boolean flag;

        public PacketTuple(PacketContext context, Boolean flag){
            this.context = context;
            this.flag = flag;
        }
    }
}
