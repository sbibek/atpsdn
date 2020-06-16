package org.decps.atpsdn.session;

import org.decps.atpsdn.Utils;
import org.decps.atpsdn.atp.ContextTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AtpSession {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private PayloadManager payloadManager = new PayloadManager();
    public ContextTracker contextTracker = new ContextTracker();

    /**
     * we need to skip all the retransmissions
     */
    Long nextExpectedSequence = null;

    // **** parameters defining this session *********
    public Integer srcAddr, dstAddr, srcPort, dstPort;
    public String key;
    // ***********************************************

    // this flag is set when we finally detect that the session is active data session
    public Boolean isAtpActiveSession = false;
    public Boolean possiblyHandshakeDone = false;


    /**
     * We will queue the incoming packet for each session so that we can implement our own logic on
     * how we can handle the outgoing rate from this point on towards the broker
     *
     * Also we track how many message are represented by the queued packets so far
     *
     * queueFull is set to true when the session has reached its MLR and no messages will be queued thereafter
     * and such messages will have to be acked to the source
     */
    public Queue<PacketInfo> packetQueue = new ConcurrentLinkedQueue<>();
    public Integer totalMessagesQueued = 0;
    public Boolean queueFull = false;


    /**
     * MLR related stuffs
     *
     * totalInboundMessages (constant for now) is total message that is expected of this session
     * MLR (Maximum Loss Rate) for this session
     * totalOutboundMessages is the total number of message that needs to be sent without breaching MLR
     */
    public Integer totalInboundMessages = 2000;
    public Double MLR = 0.6;
    public Integer totalOutboundMessages = 0;


    public AtpSession(String key, Integer srcAddr, Integer dstAddr, Integer srcPort, Integer dstPort) {
        this.key = key;
        this.srcAddr = srcAddr;
        this.dstAddr = dstAddr;
        this.srcPort = srcPort;
        this.dstPort = dstPort;

        /**
         * We calculate the totalOutboundMessages right here for now
         * and will change if we need to
         */
        totalOutboundMessages = Utils.calculateTotalOutboundMessagesFor(totalInboundMessages, MLR);
    }

    public void noPayloadPush(PacketInfo info) {
        // this will just push the packet to the queue
        // this wont change anything
        packetQueue.add(info);
    }

    public Boolean push(PacketInfo info) {
        /**
         * DONOT do anything here if the queue is already full
         */
        if(queueFull) return false;
        payloadManager.process(info.getPayload());

        /**
         * We perform atp related checks
         */
//        Integer diff = (totalMessagesQueued + payloadManager.totalMessagesInLastPacket - totalOutboundMessages);
//        if(diff >= 0) {
//            // this means that we dont want any more messages as the MLR is filled,
//            // if remaining is 0, them we have perfectly reached MLR, else we have
//            // excess messages that we need to purge before adding
//
//            // this is amount of message we need in the current payload
//            Integer requiredMessagesInTheCurrentPacket = payloadManager.totalMessagesInLastPacket - diff;
//            if(requiredMessagesInTheCurrentPacket > 0) {
//                // we now have everything to make the modification, so lets rollback the manager to the previous state
//                log.info(String.format("**processing reduced payload (diff %d) %d of %d", requiredMessagesInTheCurrentPacket, diff, payloadManager.totalMessagesInLastPacket));
//                payloadManager.rollbackState();
//                payloadManager.processWithReducedPacketPayload(info, requiredMessagesInTheCurrentPacket);
//                packetQueue.add(info);
//            }
//            totalMessagesQueued = payloadManager.totalMessages;
//            queueFull = true;
//            return true;
//        }

        nextExpectedSequence = info.seq + info.payloadLength;

        // now that we have processed the packet, we now add it to the queue and then
        // update the totalMessageQueued according to the total message that we have found
        packetQueue.add(info);
        totalMessagesQueued = payloadManager.totalMessages;

        if(totalMessagesQueued >= totalOutboundMessages){
            queueFull = true;
        }

        log.info(String.format("%d->%d added %d total", srcPort, dstPort, payloadManager.totalMessagesInLastPacket, payloadManager.totalMessages));

        if(payloadManager.totalMessagesInLastPacket > 0)
            return true;

        return false;
    }

    public Boolean isThisExpected(PacketInfo packetInfo) {
        return (nextExpectedSequence == null || (nextExpectedSequence != null && packetInfo.seq.equals(nextExpectedSequence)));
    }

    public Boolean doWeHaveQueuedPackets() {
        return packetQueue.peek() != null;
    }

    public PacketInfo getQueuedPacket() {
        return packetQueue.poll();
    }

}
