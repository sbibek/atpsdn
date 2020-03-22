package org.decps.atpsdn;

import org.onlab.packet.IPv4;
import org.onlab.packet.TCP;
import org.onosproject.net.packet.PacketContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

public class SessionTracker {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private HashMap<String, Session> tracker = new HashMap<>();

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

    private String createKey(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort) {
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

    public void createSession(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort) {
        String key =  createKey(sender, receiver, senderPort, receiverPort);
        // now lets check if there is already this session as key
        if(!tracker.containsKey(key)) {
            // means there is no such key, so lets add the session
            Session session = new Session();
            session.initSession(sender, receiver, senderPort, receiverPort);
            tracker.put(key, session);
            log(String.format("added session key %s",key));
        } else {
            log(String.format("duplicate key %s",key));
        }
    }

    public Boolean verdict(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort, IPv4 ip, TCP tcp) {
        String key =  createKey(sender, receiver, senderPort, receiverPort);
        Session session = tracker.get(key);
        // throw error if there is no such key
        if(session == null) {
            throw new RuntimeException(String.format("key %s not found",key ));
        }

        // first thing is we are only interested in packet coming in from sender to receiver for the calculations
        // so we need to fist check if the current packet is coming from sender to receiver
        if(session.isPacketDirectionDesiredOne(ip, tcp) && tcp.getFlags() == 24) {
            // this means we have desired paceket

            // TODO
            // for now update the count of the packets received
            session.setDataCount(session.getDataCount()+1);
            log(String.format("desired packet PA %d -> %d dc=%d", tcp.getSourcePort(), tcp.getDestinationPort(), session.getDataCount()));

            // if the data count is greater than 10 then it means tear down so send true verdict in that case
            return session.getDataCount() > 10;
        } else {
            // else will mean that either packet is not of desired direction or the tcp packet is not PA or both
            return false;
        }

        // now if we have key, then we update the session count of PA, but for that we need to
    }
}
