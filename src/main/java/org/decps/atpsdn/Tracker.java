package org.decps.atpsdn;

import org.onlab.packet.IPv4;
import org.onlab.packet.TCP;
import org.onosproject.net.packet.PacketContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public class Tracker {
    private final Logger log = LoggerFactory.getLogger(getClass());
    public ConcurrentHashMap<String, SessionQ> tracker = new ConcurrentHashMap<String, SessionQ>();

    public static long getUnsignedInt(int x) {
        return x & 0x00000000ffffffffL;
    }

    public void log(String msg) {
        log.info(String.format("[SessionTracker] %s",msg));
    }


    public Boolean sessionExists(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort){
        String key =  createKey(sender, receiver, senderPort, receiverPort);
        return tracker.containsKey(key);
    }

    public void createSession(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort, PacketContext context) {
        String key =  createKey(sender, receiver, senderPort, receiverPort);
        // now lets check if there is already this session as key
        if(!tracker.containsKey(key)) {
            // means there is no such key, so lets add the session
            SessionQ session = new SessionQ();
            // the session creation is done using the first packet that was seen (SYNACK), so we dont need to queue that packet as we wont rate limit it
            // session.queuePacket(context);
            session.sessionKey = key;
            session.sender = sender;
            session.receiver = receiver;
            session.senderPort = senderPort;
            session.receiverPort = receiverPort;
            session.s2r_context = context;

            tracker.put(key, session);

            log(String.format("added session key %s",key));
        } else {
            log(String.format("duplicate key %s",key));
        }
    }

    public void addPacket(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort, PacketContext context) {
        TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();
        String key =  createKey(sender, receiver, senderPort, receiverPort);
        SessionQ session = tracker.get(key);
        // throw error if there is no such key
        if(session == null) {
            throw new RuntimeException(String.format("key %s not found",key ));
        }

        // the packet is always Push Ack from the source -> destination
        session.s2r_context = context;
        session.s2r_ack = tcp.getAcknowledge();
        session.s2r_seq = tcp.getSequence();

        // this will just add the packet to the specific queued session
        // but this is not always the solution
        session.queuePacket(context);
    }

    public SessionQ getSession(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort) {
        String key = createKey(sender, receiver, senderPort, receiverPort);
        return tracker.get(key);
    }

    public void removeSession(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort) {
        String key = createKey(sender, receiver, senderPort, receiverPort);
        tracker.remove(key);
    }

    public void removeSession(String key){
        tracker.remove(key);
        log(String.format("removed session with key: %s", key));
    }

    public static String createKey(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort) {
        // we would like to define key for the map, so we will always make the key in sorted order of host and port
        Integer s1, s2, p1, p2;
        if(sender < receiver) {
            s1 = sender;
            s2 = receiver;
        } else {
            s1 = receiver;
            s2 = sender;
        }

        if( senderPort < receiverPort ) {
            p1 = senderPort;
            p2 = receiverPort;
        } else {
            p1 = receiverPort;
            p2 = senderPort;
        }

        String key = String.format("%d:%d:%d:%d",getUnsignedInt(s1), getUnsignedInt(s2),p1, p2);
        return key;
    }

    public void runAcks() {
        tracker.forEach((k,tq) -> {
            tq.acknowledge();
        });
    }
}
