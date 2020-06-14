package org.decps.atpsdn.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    Map<String, AtpSession> sessions = new ConcurrentHashMap<String, AtpSession>();


    public AtpSession createSessionIfNotExists(PacketInfo packetInfo) {
        if(!sessions.containsKey(packetInfo.key)) {
            AtpSession session = new AtpSession(packetInfo.key, packetInfo.srcAddr, packetInfo.dstAddr, packetInfo.srcPort, packetInfo.dstPort);
            sessions.put(packetInfo.key, session);
            return session;
        } else
            return sessions.get(packetInfo.key);
    }

    public Boolean doesSessionExist(String key) {
        return sessions.containsKey(key);
    }
}
