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
    private static Float MLR = 0.5f;
    private static Integer TotalPacketsToBeSent = 20;

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
    public Integer s2r_seq, s2r_ack, r2s_seq, r2s_ack;

    // used for teardown
    public Boolean initiateTeardown = false;
    public Boolean teardownStarted = false;
    // this is used to safely remove the session of closed session after 2 encounters by the thread ie when its value=1
    public Integer teardownEncounteredByClearingThread = 0;

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
    // queue id is assigned to the flow to a queue according to the sending rate
    // the one with high sending rate will go through the low priority sending queue
    // and the one with low sending rate will go through the high priority sending queue

    // here is the parameters that determines queueId
    /**
     *  Priority on decreasing order
     *  1 (0-16% of the Line rate)
     *  2 (16-32% ... )
     *  3 (32-48% ... )
     *  4 (48-64% ... )
     *  5 (64-80% ... )
     *  6 (80-any high ... )
     *  7 (backup flows)
     */
    public Integer assignedQueueId = null;

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
        System.out.println("put the packet");
    }

    public Boolean acknowledge(PacketContext context) {
        TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();
        String acknowledgementTrack = String.format("%d-%d", getUnsignedInt(tcp.getSequence()), getUnsignedInt(tcp.getAcknowledge()));
        if (packetAcknowledgementTracker.containsKey(acknowledgementTrack)) {
            packetAcknowledgementTracker.remove(acknowledgementTrack);
            totalAcknowledgedPackets++;

            // whenever there is an acknowledgement, then we are sure that the packet is delivered and we can be sure that this is the time
            // we can check if we can actually start the teardown
            // for that to happen, the initiateTeardown flag should be set and the ack packets should equal the sent packets count
            teardownStarted=  initiateTeardown && (totalSentPackets <= totalAcknowledgedPackets);
            return teardownStarted;
        }
        return false;
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
            System.out.println("get the packet");
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

        // ************* queue selection ******************* //
            Float lineRateOcc = tcurrentSendRate / RMAX*100 ;
            if(lineRateOcc < 16) {
                assignedQueueId = 1;
            } else if(lineRateOcc < 32) {
                assignedQueueId = 2;
            } else if(lineRateOcc < 48) {
                assignedQueueId = 3;
            } else if(lineRateOcc < 64) {
                assignedQueueId = 4;
            } else if(lineRateOcc < 80) {
                assignedQueueId = 5;
            } else {
                assignedQueueId = 6;
            }
        // ************************************************* //

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
        log(String.format("(%d->%d) totalSent: %d, totalAcked: %d, currentSendRate: %f currentAckRate %f, loss rate: %f, sending rate: %f, assigned queue id: %d",senderPort,receiverPort, totalSentPackets, totalAcknowledgedPackets, currentSendRate, currentAckRate, messageLossRate, maxSendRate, assignedQueueId));
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
