package org.decps.atpsdn;

import org.onlab.packet.IPv4;
import org.onlab.packet.TCP;
import org.onosproject.net.packet.PacketContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class QueuedSession {
    // Target Loss Rate
    private static Float TLR = 0.0f;
    private static Float RMAX = 100.0f;
    // corresponding to m in the ATP paper
    private static Float incrementRate = 0.5f;

    private final Logger log = LoggerFactory.getLogger(getClass());

    public String sessionKey;
    public Integer sender;
    public Integer receiver;
    public Integer senderPort;
    public Integer receiverPort;

    // last context that was seen from either direction
    public PacketContext s2r_context;
    public PacketContext r2s_context;

    private Queue<PacketContext> queue = new ConcurrentLinkedQueue<>();

    private HashMap<String, Integer> packetAcknowledgementTracker = new HashMap<>();
    public Long totalSentPackets = 0L;
    public Long totalAcknowledgedPackets = 0L;
    public Long totalRetransmittedPackets = 0L;
    public Long _lastTotalSentPackets = 0L;
    public Long _lastTotalAcknowledgedPackets = 0L;
    public Long _lastTotalRetransmittedPackets = 0L;
    public Float currentSendRate = 0f, currentAckRate = 0f, messageLossRate = 0f;

    // send rate to maintain per second. This is set by how much it takes to receive ack for packets sent
    // default  max send rate is 100 packets/second
    public float  maxSendRate = RMAX;

    public void log(String msg) {
        log.info(String.format("[QueuedSession] %s", msg));
    }

    public long getUnsignedInt(int x) {
        return x & 0x00000000ffffffffL;
    }

    public void queuePacket(PacketContext context) {
        // now we add the information to ack tracker
        TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();


        // format seq-ack of the ack packet
        String acknowledgementTrack = String.format("%d-%d", getUnsignedInt(tcp.getAcknowledge()), getUnsignedInt(tcp.getSequence()) + tcp.getPayload().serialize().length );
        // before queueing, we try to check if this is a retransmission. Retransmission packets will have its entry in ack tracker
        if(packetAcknowledgementTracker.containsKey(acknowledgementTrack)){
            // we will set the count of retransmitted packets
            totalRetransmittedPackets++;
        } else {
            // only put to tracker if its not retransmission as retransmitted packets will already have an entry there
            packetAcknowledgementTracker.put(acknowledgementTrack, 1);
        }
        queue.add(context);
    }

    public void acknowledge(PacketContext context) {
        TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();
        String acknowledgementTrack = String.format("%d-%d", getUnsignedInt(tcp.getSequence()), getUnsignedInt(tcp.getAcknowledge()));
        if(packetAcknowledgementTracker.containsKey(acknowledgementTrack)) {
            packetAcknowledgementTracker.remove(acknowledgementTrack);
            totalAcknowledgedPackets++;
        }
    }

    public Boolean isRetransmission(TCP tcp) {
        // format seq-ack of the ack packet
        String acknowledgementTrack = String.format("%d-%d", getUnsignedInt(tcp.getAcknowledge()), getUnsignedInt(tcp.getSequence()) + tcp.getPayload().serialize().length );
        // for this packet to be retransmission, the key should already exist
        return packetAcknowledgementTracker.containsKey(acknowledgementTrack);
    }

    public PacketContext getQueuedPacket(){
        PacketContext c = queue.poll();
        if(c != null) {
            totalSentPackets++;
        }
        return c;
    }

    public void adaptSendingRate(Integer period){
        currentSendRate = (totalSentPackets - _lastTotalSentPackets)/(float)period;
        currentAckRate = (totalAcknowledgedPackets - _lastTotalAcknowledgedPackets)/(float)period;
        messageLossRate = (totalRetransmittedPackets - _lastTotalRetransmittedPackets)/(float)(totalSentPackets - _lastTotalSentPackets);

        // we perform the send rate adaptation only if there is messageLoss
        if(messageLossRate > 0) {
            // now we check our loss against the message Loss Rate
            if (messageLossRate <= TLR) {
                // then we aggresively increase our sending speed
                maxSendRate = (1 - incrementRate) * maxSendRate + RMAX;
            } else {
                // means we need to decrease the sending rate
                // so according to the paper, we decrease it by factor of 2
                maxSendRate = maxSendRate * (1 - messageLossRate / 2);
            }
        }


        // now update last values
        _lastTotalSentPackets = totalSentPackets;
        _lastTotalAcknowledgedPackets = totalAcknowledgedPackets;
        _lastTotalRetransmittedPackets = totalRetransmittedPackets;
    }

    public void log(){
       log(String.format("currentSendRate: %f currentAckRate %f, loss rate: %f, sending rate: %f", currentSendRate, currentAckRate, messageLossRate, maxSendRate));
    }
}
