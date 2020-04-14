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
    private static Float MLR = 0.2f;
    private static Integer TotalPacketsToBeSent = 10;

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

    // used for teardown
    public Boolean initiateTeardown = false;
    public Boolean senderAcked = false;
    public Boolean receiverAcked = false;

    // Push Ack Data queue
    private Queue<Wrapper> queue = new ConcurrentLinkedQueue<>();

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
    public Float maxSendRate = RMAX;

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
        String acknowledgementTrack = String.format("%d-%d", getUnsignedInt(tcp.getAcknowledge()), getUnsignedInt(tcp.getSequence()) + tcp.getPayload().serialize().length);
        // before queueing, we try to check if this is a retransmission. Retransmission packets will have its entry in ack tracker
        // and the packet should have left the queue, means the integer should have to be set to 0
        // if we have a packet retransmitted from the source only, but it is still in our queue than it should be ignored
        if (packetAcknowledgementTracker.containsKey(acknowledgementTrack)) {
            if (packetAcknowledgementTracker.get(acknowledgementTrack) == 0) {
                // means the packet is retransmitted while it is on the queue
                // this means we will do nothing for this packet
                return;
            }
            // we will set the count of retransmitted packets
            totalRetransmittedPackets++;
        } else {
            // only put to tracker if its not retransmission as retransmitted packets will already have an entry there
            packetAcknowledgementTracker.put(acknowledgementTrack, 1);
        }
        Wrapper w = new Wrapper(acknowledgementTrack, context);
        queue.add(w);
    }

    public void acknowledge(PacketContext context) {
        TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();
        String acknowledgementTrack = String.format("%d-%d", getUnsignedInt(tcp.getSequence()), getUnsignedInt(tcp.getAcknowledge()));
        if (packetAcknowledgementTracker.containsKey(acknowledgementTrack)) {
            packetAcknowledgementTracker.remove(acknowledgementTrack);
            totalAcknowledgedPackets++;
        }
    }

    public Boolean isRetransmission(TCP tcp) {
        // format seq-ack of the ack packet
        String acknowledgementTrack = String.format("%d-%d", getUnsignedInt(tcp.getAcknowledge()), getUnsignedInt(tcp.getSequence()) + tcp.getPayload().serialize().length);
        // for this packet to be retransmission, the key should already exist
        return packetAcknowledgementTracker.containsKey(acknowledgementTrack);
    }

    public PacketContext getQueuedPacket() {
        Wrapper w = queue.poll();
        if (w != null) {
            // now the dequeued packet will have its integer set to 0 in the tracker
            packetAcknowledgementTracker.put(w.key, 0);
            totalSentPackets++;

            // now since the packet will be sent after this, so we will need to check the MLR thingy here
            initiateTeardown =  totalSentPackets/(1.0-MLR) > TotalPacketsToBeSent;

            return w.context;
        } else
            return null;
    }

    public Boolean hasPackets() {
        Wrapper w = queue.peek();
        return w != null;
    }

    public void adaptSendingRate(Integer period) {
        // temporary vars
        float tcurrentSendRate, tcurrentAckRate, tmessageLossRate, tmaxSendRate = maxSendRate;

        tcurrentSendRate = (totalSentPackets - _lastTotalSentPackets) / (float) period;
        tcurrentAckRate = (totalAcknowledgedPackets - _lastTotalAcknowledgedPackets) / (float) period;

        if (tcurrentSendRate > 0)
            tmessageLossRate = (totalRetransmittedPackets - _lastTotalRetransmittedPackets) / (float) (totalSentPackets - _lastTotalSentPackets);
        else tmessageLossRate = 0f;

        // now we check our loss against the message Loss Rate
        // if we have sending rate of over max and still have loss below TLR then increase max send rate
        if (tmessageLossRate <= TLR && tcurrentSendRate >= maxSendRate) {
            // then we aggresively increase our sending speed
            tmaxSendRate = (1 - incrementRate) * maxSendRate + RMAX;
        } else if (tmessageLossRate > TLR) {
            // if we have message loss rate greater than TLR than it means we need to decrease sendRate
            // means we need to decrease the sending rate
            // so according to the paper, we decrease it by factor of 2
            tmaxSendRate = maxSendRate * (1 - messageLossRate / 2);
        }

        // now lets commit the temporary vars
        currentSendRate = tcurrentSendRate;
        currentAckRate = tcurrentAckRate;
        messageLossRate = tmessageLossRate;
        maxSendRate = tmaxSendRate;

        // now update last values
        _lastTotalSentPackets = totalSentPackets;
        _lastTotalAcknowledgedPackets = totalAcknowledgedPackets;
        _lastTotalRetransmittedPackets = totalRetransmittedPackets;
    }

    public void log() {
        log(String.format("currentSendRate: %f currentAckRate %f, loss rate: %f, sending rate: %f", currentSendRate, currentAckRate, messageLossRate, maxSendRate));
    }

    public Boolean isDirectionSenderToReceiver(IPv4 ip, TCP tcp) {
        // check if the current ip and tcp is the part of the packet from sender to receiver
        return ip.getSourceAddress() == sender && ip.getDestinationAddress() == receiver && tcp.getSourcePort() == senderPort && tcp.getDestinationPort() == receiverPort;
    }

    private class Wrapper {
        public String key;
        public PacketContext context;

        public Wrapper(String key, PacketContext context) {
            this.key = key;
            this.context = context;
        }
    }
}
