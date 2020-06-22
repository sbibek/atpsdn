package org.decps.atpsdn.session;

import org.decps.atpsdn.Utils;
import org.decps.atpsdn.atp.AckSimulator;
import org.decps.atpsdn.atp.ContextTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
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
    public Integer totalInboundMessages = 1000;
    public Double MLR = 0.5;
    public Integer totalOutboundMessages = 0;


    /**
     * Information below will track the inflight packets/messages
     * RMAX is the maximum sending rate / line rate (PPS)
     * for now, we put the receive rate as ack rate from the broker
     * The rates are packetsPerSec
     */
    public Map<String, PacketInfo> inflight = new ConcurrentHashMap<>();
    public static final Long periodMs = 5000L;
    public static final Float RMAX = 1000f;
    public static final Float TLR = 0.5f; // Target Loss Rate
    public Float currentSendRate = RMAX; // set the current send rate to line rate ie RMAX
    public final Float convergenceRate = 0.1f; // rate of convergence to the RMAX
    public Integer totalPacketsSent = 0;
    public Integer totalPacketsReceivedByBroker = 0;
    public Long lastUpdatedOn = 0L;
    public Integer totalSentWhenLastUpdated = 0;
    public Integer totalReceivedWhenLastUpdated = 0;
    public Float sendRate = 0f;
    public Float receiveRate = 0f; // for now, ack rate from the broker
    public Float lossRate = 0f;

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

       // log.info(String.format("%d->%d added %d total", srcPort, dstPort, payloadManager.totalMessagesInLastPacket, payloadManager.totalMessages));

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

    synchronized public PacketInfo getQueuedPacket() {
        /**
         * Here is the thing,
         * we need to first check if sending the packet would
         * in anyway breach the current send rate, if so then we will have to defer
         * the sending for the next pass
         */
        PacketInfo pkt = packetQueue.peek();
        if(pkt != null && pkt.payloadLength > 0) {
            // means we have a valid packet
            // lets check if sending this packet would breach the sending rate
            if(checkIfSendingPacketsWouldBreachSendRate(1, AtpSession.periodMs)) {
                // means its breached, so lets return null
                // and try again in next pass
                //log.info(String.format("[%s]packet breached the sending rate, so skipping sending on this pass", key));
                return null;
            }
        }

        // else normal flow

        PacketInfo packetInfo = packetQueue.poll();
        // set in flight mode for just the payload packets
        if(packetInfo != null && packetInfo.payloadLength > 0) {
            packetInfo.setAcknowledgementParams();
            inflight.put(String.format("%d-%d", packetInfo.expectedAcknowledgementSeq, packetInfo.expectedAcknowledgementAck), packetInfo);
           // log.info(String.format("[popped] %s total inflight : %d",String.format("%d-%d", packetInfo.expectedAcknowledgementSeq, packetInfo.expectedAcknowledgementAck), inflight.size()));
            /**
             * Update the total packets that were sent
             */
            totalPacketsSent++;
        }
        return packetInfo;
    }

    public void acknowledge(PacketInfo packetInfo) {
        String ackKey = String.format("%d-%d", packetInfo.seq, packetInfo.ack);
        log.info(String.format("acking %s", ackKey));
        if(inflight.containsKey(ackKey)){
            inflight.remove(ackKey);
            /**
             * TODO
             * This is just for now, this will have to be updated later on
             */
            totalPacketsReceivedByBroker++;
          //  log.info(String.format("[acked] total inflight : %d", inflight.size()));
        }
    }

    public void acknowledge(Long seq, Long ack) {
        String ackKey = String.format("%d-%d", seq, ack);
        //log.info(String.format("acking %s", ackKey));
        if(inflight.containsKey(ackKey)){
            inflight.remove(ackKey);
            /**
             * TODO
             * This is just for now, this will have to be updated later on
             */
            totalPacketsReceivedByBroker++;
         //   log.info(String.format("[acked] total inflight : %d", inflight.size()));
        }
    }

    private Boolean checkIfSendingPacketsWouldBreachSendRate(Integer N, Long period) {
        /**
         * First get the snapshot of the current stats
         * and then perform the calculation of N packets increased in this period
         */
        Integer currentTotalSent = totalPacketsSent;
        Integer currentTotalReceived = totalPacketsReceivedByBroker;

        Integer totalSentOnThisPeriod = currentTotalSent - totalSentWhenLastUpdated + N;
        Float _sendRate = (float)totalSentOnThisPeriod/period*1000.0f;
        return _sendRate > currentSendRate;
    }

    /**
     * This will be called by a thread as this needs to be updated periodically
     */
    public void updateRateStats() {
        Integer currentTotalSent = totalPacketsSent;
        Integer currentTotalReceived = totalPacketsReceivedByBroker;
        Long currentTimestamp = System.currentTimeMillis();
        Long period = currentTimestamp - lastUpdatedOn;

        Integer totalSentOnThisPeriod = currentTotalSent - totalSentWhenLastUpdated;
        Integer totalReceivedOnThisPeriod = currentTotalReceived - totalReceivedWhenLastUpdated;
        Float _sendRate = (float)totalSentOnThisPeriod/period*1000.0f;
        Float _receiveRate = (float)totalReceivedOnThisPeriod/period*1000.0f;
        Float _lossRate = 0f;
        Float _currentSendRate = currentSendRate;
        if(totalSentOnThisPeriod != totalReceivedOnThisPeriod && totalReceivedOnThisPeriod > 0) {
            if(totalSentOnThisPeriod > 0)
                _lossRate = (float)(totalSentOnThisPeriod-totalReceivedOnThisPeriod)/totalSentOnThisPeriod;
            else _lossRate = 0f;

            if(_lossRate < 0) _lossRate = 0f;

            // now lets check the loss rate with the TLR
            if(_lossRate <= TLR && currentSendRate < RMAX) {
                // means we can increase the current sending rate
                _currentSendRate = (1-convergenceRate)*_currentSendRate+convergenceRate*RMAX;
            } else if(_lossRate >  TLR) {
                // we need to decrease the current sending rate
                _currentSendRate = _currentSendRate * ( 1.0f - lossRate/2f);
            }

            if(_currentSendRate <= 0) _currentSendRate = 10.0f;
        }


        log.info(String.format("[stats %s] total sent %d/%d, total rcv %d/%d, loss rate %f, send rate %f, rcv rate %f, opt. send rate %f",key, totalSentOnThisPeriod, currentTotalSent, totalReceivedOnThisPeriod, currentTotalReceived, _lossRate, _sendRate, _receiveRate, _currentSendRate));
        // now update this parameter
        synchronized (this) {
            lastUpdatedOn = currentTimestamp;
            totalSentWhenLastUpdated  = currentTotalSent;
            totalReceivedWhenLastUpdated = currentTotalReceived;
            sendRate = _sendRate;
            receiveRate = _receiveRate;
            lossRate = _lossRate;
            currentSendRate = _currentSendRate;
        }
    }

}
